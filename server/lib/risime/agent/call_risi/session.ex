defmodule RisiMe.Agent.CallRisi.Session do
  @moduledoc """
  One Call Risi call (decision 090): the **Voice agent's** turn-taking for one room. It owns the media
  worker (`RisiMe.Agent.CallRisi.Media`), runs each turn in a `RisiMe.Agent.CallRisi.Brain` task,
  forwards the brain's pieces to the worker to be spoken, handles barge-in (the worker stops the audio
  at once; the session kills the turn), keeps the call's transcript in memory, posts it to the Risi
  chat when the call ends (`risi_call_transcript`, proposal §41.5), and records one `risi_agent_runs` row
  per turn (agent `voice_call`: timings only, never text or audio).

  Ends on: the caller leaving, Risi not joining in 10 s or the caller not arriving in 45 s, 30 min,
  the switch turning off for the caller (checked every 10 s), or the worker exiting.
  """
  use GenServer, restart: :temporary

  require Logger

  alias RisiMe.Agent.CallRisi
  alias RisiMe.Agent.CallRisi.{Brain, Media}

  @join_ms 10_000
  @caller_ms 45_000
  @flag_every_ms 10_000
  @budget_ms 1_500
  @greeting "Hi, it's Risi. How can I help?"

  def start_link(args), do: GenServer.start_link(__MODULE__, args, name: via(args.call_id))

  def via(call_id), do: {:via, Registry, {CallRisi.Registry, call_id}}

  @doc "Ends the call (e.g. the switch went off)."
  def stop(call_id, reason \\ :stopped), do: GenServer.cast(via(call_id), {:end, reason})

  @impl true
  def init(args) do
    Process.flag(:trap_exit, true)

    case Media.impl().open(self(), args) do
      {:ok, media} ->
        cfg = CallRisi.config()

        Media.impl().command(media, %{
          "cmd" => "join",
          "url" => args.worker_url,
          "token" => args.risi_token,
          "caller" => args.user_id <> "/",
          "tts_url" => Keyword.get(cfg, :tts_url, "http://127.0.0.1:8230"),
          "tts_key" => Keyword.get(cfg, :tts_key),
          "eot_ms" => Keyword.get(cfg, :eot_ms, 350),
          "barge_ms" => Keyword.get(cfg, :barge_ms, 300)
        })

        Process.send_after(self(), :join_timeout, @join_ms)
        Process.send_after(self(), :caller_timeout, @caller_ms)
        # v1.48 §41.2: the limit the start reply announced (`max_s`), fixed for the call.
        Process.send_after(
          self(),
          :max_length,
          (Map.get(args, :max_s) || CallRisi.max_s()) * 1000
        )

        Process.send_after(self(), :flag_check, @flag_every_ms)

        {:ok,
         %{
           args: args,
           media: media,
           joined?: false,
           caller?: false,
           started_at: DateTime.utc_now(),
           voice: CallRisi.voice("en"),
           seq: 0,
           pieces: %{},
           lines: [],
           history: [],
           turn: nil,
           filler_n: 0,
           # P0 2026-10-15: the call's last tool topic (a follow-up inherits it), the
           # once-per-call lines said, and the last user utterance (fragments merge).
           topic: nil,
           # Harsha's 3rd call: the call's last write turn `%{request_id, route, last?}` (its
           # card's truth is read from its row; `last?` while no other tool turn came after).
           write: nil,
           once: MapSet.new(),
           last_user: nil,
           # v1.52 §41.8: the caller's phone can send its meeting titles for a voice answer;
           # `cal_wait` is the one open request `{id, brain pid}` (titles never stored here).
           cal?: Map.get_lazy(args, :cal?, fn -> cal_capable?(args) end),
           cal_wait: nil,
           barge_ins: 0,
           # Device test 15 Oct: the caller started speaking before Risi was audible: Risi's
           # pieces wait (`held`, in order) until the words arrive (or `@listen_ms`).
           listen: nil,
           ended?: false
         }}

      {:error, reason} ->
        {:stop, {:shutdown, reason}}
    end
  end

  ## Media events

  @impl true
  def handle_info({port, {:data, data}}, %{media: port} = st) when is_port(port) do
    case Jason.decode(data) do
      {:ok, ev} -> media(ev, st)
      _ -> {:noreply, st}
    end
  end

  def handle_info({port, {:exit_status, _}}, %{media: port} = st) when is_port(port),
    do: finish(st, :error)

  def handle_info({:call_risi_media, ev}, st), do: media(ev, st)

  def handle_info({:brain, utt, ev}, %{turn: %{utt: utt}} = st), do: brain(ev, st)
  def handle_info({:brain, _old, _ev}, st), do: {:noreply, st}

  def handle_info(:listen_timeout, st), do: {:noreply, release(st, true)}
  def handle_info(:join_timeout, %{joined?: false} = st), do: finish(st, :timeout)
  def handle_info(:caller_timeout, %{caller?: false} = st), do: finish(st, :timeout)
  def handle_info(:max_length, st), do: finish(st, :max_length)

  def handle_info(:flag_check, st) do
    if CallRisi.enabled_for?(st.args.user_id) do
      Process.send_after(self(), :flag_check, @flag_every_ms)
      {:noreply, st}
    else
      finish(st, :switch_off)
    end
  end

  def handle_info({:side_stt, held, utt, r}, st) do
    text =
      case r do
        {:ok, %{text: t} = x} when is_binary(t) ->
          if x[:speech] == false, do: "", else: String.trim(t)

        _ ->
          ""
      end

    cond do
      text == "" -> {:noreply, st}
      # The question being checked, or its restart with merged words (device test 15 Oct: a
      # second fragment after a restart must merge too, never replace the question).
      match?(%{turn: %{root: ^held, brain_done?: false}}, st) -> held_fragment(st, utt, text)
      # The answer came meanwhile: the words are a new question.
      true -> {:noreply, start_text_turn(cancel_turn(st, :replaced), utt, text, true)}
    end
  end

  def handle_info({:EXIT, _pid, _reason}, st), do: {:noreply, st}
  def handle_info(_msg, st), do: {:noreply, st}

  @impl true
  def handle_cast({:end, reason}, st), do: finish(st, reason)

  defp media(%{"ev" => "joined"}, st), do: {:noreply, %{st | joined?: true}}

  defp media(%{"ev" => "caller_joined"}, %{caller?: false} = st) do
    st = %{st | caller?: true}
    # Like answering a phone: Risi speaks first (utterance 0, no user turn to time).
    st = %{st | turn: new_turn(0, nil, st)}
    {:noreply, speak(st, @greeting, :greeting) |> put_in([:turn, :brain_done?], true)}
  end

  # Device test 15 Oct ("Do you have anything" … 1 s … "on the 18th?"): Risi had begun to answer
  # the first words and the worker lost the rest under Risi's voice. When the caller starts
  # speaking while Risi is not audible yet, Risi stops what is queued and waits for the words.
  defp media(%{"ev" => "speech_start", "speaking" => false}, %{turn: %{} = t, ended?: false} = st)
       when st.listen == nil do
    held =
      st.pieces
      |> Enum.filter(fn {_seq, {utt, _, _, _}} -> utt == t.utt end)
      |> Enum.sort_by(&elem(&1, 0))
      |> Enum.map(&elem(&1, 1))

    if held != [], do: Media.impl().command(st.media, %{"cmd" => "stop_speaking"})
    ref = Process.send_after(self(), :listen_timeout, listen_ms())
    {:noreply, %{st | pieces: %{}, listen: %{held: held, ref: ref}}}
  end

  defp media(%{"ev" => "caller_left"}, st), do: finish(st, :hangup)
  defp media(%{"ev" => "left", "reason" => r}, st), do: finish(st, left_reason(r))

  # P0 2026-10-15 (Harsha's 2nd call): while Risi is checking (a tool turn, no answer yet) the
  # user's speech never cancels the question: barge-in only stops the holding line.
  defp media(%{"ev" => "barge_in"}, st) do
    st = %{st | barge_ins: st.barge_ins + 1}

    if holding?(st) do
      Media.impl().command(st.media, %{"cmd" => "stop_speaking"})
      {:noreply, st}
    else
      {:noreply, cancel_turn(st, :barge_in)}
    end
  end

  # The hook for the app's Talk/Interrupt button (root adds the wire/worker event): stops
  # whatever Risi is doing, also while holding.
  defp media(%{"ev" => "interrupt"}, st), do: {:noreply, cancel_turn(st, :barge_in)}

  # While holding, an utterance is transcribed on the side and merged into the question
  # (`{:side_stt, …}`): no new turn, no cancel (unless it changes the request or says "stop").
  defp media(%{"ev" => "utterance", "utt" => utt, "pcm16k" => b64}, st)
       when is_map(st.turn) and st.turn.brain_done? == false and
              st.turn.route not in [nil, "chat", "capabilities", "write_status"] do
    st = release(st, true)
    me = self()
    held = st.turn.root
    wav = b64 |> Base.decode64!() |> wav16k()

    Task.start(fn ->
      r =
        try do
          RisiMe.Agent.Speech.transcribe(wav, language: "en")
        catch
          _, _ -> {:error, :failed}
        end

      send(me, {:side_stt, held, utt, r})
    end)

    {:noreply, st}
  end

  defp media(%{"ev" => "utterance", "utt" => utt, "pcm16k" => b64} = ev, st) do
    st = release(st, false)
    now = System.monotonic_time(:millisecond)
    end_ms = now - (ev["eot_wait_ms"] || 0)
    start_ms = end_ms - (ev["speech_ms"] || 0)

    pcm = Base.decode64!(b64)

    {prefix, pcm, st} =
      case stt_pending(st) do
        # Device test 15 Oct: the next words came before the last utterance was transcribed:
        # both are transcribed together (the first part never vanishes).
        %{} = t ->
          {t.prefix, t.pcm <> :binary.copy(<<0, 0>>, 4_000) <> pcm, st}

        nil ->
          case take_unanswered(st) do
            {"", st} ->
              {p, st} = take_fragment(st, start_ms)
              {p, pcm, st}

            {p, st} ->
              {p, pcm, %{st | last_user: nil}}
          end
      end

    st = cancel_turn(st, :replaced)
    wav = wav16k(pcm)
    ctx = brain_ctx(st, utt)
    {:ok, pid} = Task.start_link(fn -> Brain.run(ctx, wav, prefix) end)

    turn =
      new_turn(utt, pid, st)
      |> Map.merge(%{eot_wait_ms: ev["eot_wait_ms"], end_ms: end_ms, pcm: pcm, prefix: prefix})

    {:noreply, %{st | turn: turn}}
  end

  defp media(%{"ev" => "first_audio", "utt" => utt} = ev, %{turn: %{utt: utt} = t} = st) do
    t = %{t | after_end_ms: ev["after_end_ms"], tts_first_ms: ev["tts_first_ms"]}
    {:noreply, %{st | turn: t}}
  end

  defp media(%{"ev" => "said", "seq" => seq, "interrupted" => false}, st) do
    case Map.pop(st.pieces, seq) do
      {{utt, text, kind, store}, pieces} ->
        st = answered(st, utt, kind)

        st =
          case store do
            :skip -> st
            s when is_binary(s) -> add_line(st, :risi, s, kind, utt)
            %{text: s} -> titled_line(add_line(st, :risi, s, kind, utt))
            _ -> add_line(st, :risi, text, kind, utt)
          end

        {:noreply, %{st | pieces: pieces}}

      {nil, _} ->
        {:noreply, st}
    end
  end

  defp media(%{"ev" => "said", "seq" => seq}, st),
    do: {:noreply, %{st | pieces: Map.delete(st.pieces, seq)}}

  defp media(%{"ev" => "idle"}, %{listen: %{held: [_ | _]}} = st), do: {:noreply, st}

  defp media(%{"ev" => "idle"}, %{turn: %{brain_done?: true} = t} = st) do
    record(t, st, :ok)
    {:noreply, %{st | turn: nil}}
  end

  # §41.8: the phone's reply goes only to the brain that asked (matching id); never logged.
  defp media(%{"ev" => "cal", "id" => id} = ev, %{cal_wait: {id, pid}} = st) do
    if is_pid(pid), do: send(pid, {:call_risi_cal, ev})
    {:noreply, %{st | cal_wait: nil}}
  end

  defp media(%{"ev" => "cal"}, st), do: {:noreply, st}

  defp media(%{"ev" => "error", "code" => code}, st) do
    Logger.warning("call risi: worker error #{inspect(code)} call=#{st.args.call_id}")
    {:noreply, st}
  end

  defp media(_ev, st), do: {:noreply, st}

  ## Brain events

  defp brain({:stt, %{} = r}, st) do
    # A text turn's words may have grown meanwhile (a fragment merged before this event).
    text =
      if is_binary(st.turn.text) and String.starts_with?(st.turn.text, r.text),
        do: st.turn.text,
        else: r.text

    # The audio leaves memory with the transcription.
    t = %{st.turn | stt_ms: r.ms, lang: r.lang, text: text, stt_model: r.model, pcm: nil}
    r = %{r | text: text}

    st =
      if r.text != "",
        do: %{
          add_line(%{st | turn: t}, :user, r.text, nil, t.utt)
          | last_user: %{utt: t.utt, text: r.text, end_ms: t.end_ms, answered?: false}
        },
        else: %{st | turn: t}

    {:noreply, st}
  end

  defp brain({:route, route}, st), do: {:noreply, put_in(st.turn.route, route)}
  defp brain({:decided, d}, st), do: {:noreply, %{st | turn: Map.put(st.turn, :decided, d)}}
  defp brain({:first_token, ms}, st), do: {:noreply, put_in(st.turn.first_token_ms, ms)}
  defp brain({:topic, topic}, st), do: {:noreply, %{st | topic: topic}}
  defp brain({:once, key}, st), do: {:noreply, %{st | once: MapSet.put(st.once, key)}}

  defp brain({:write, %{} = w}, st), do: {:noreply, %{st | write: w}}

  defp brain({:write, nil}, %{write: %{} = w} = st),
    do: {:noreply, %{st | write: %{w | last?: false}}}

  defp brain({:write, nil}, st), do: {:noreply, st}

  # §41.8: the brain's titles request goes to the worker (published to the caller only).
  defp brain({:cal_req, %{"cmd" => "cal_req", "id" => id} = cmd}, st) do
    Media.impl().command(st.media, cmd)
    {:noreply, %{st | cal_wait: {id, st.turn.pid}}}
  end

  defp brain({:piece, text, kind}, st), do: brain({:piece, text, kind, nil}, st)

  defp brain({:piece, text, kind, store}, st) do
    st = if kind == :filler, do: %{st | filler_n: st.filler_n + 1}, else: st
    t = st.turn
    t = if t.first_piece_ms == nil, do: %{t | first_piece_ms: since(t.t0)}, else: t
    {:noreply, speak(%{st | turn: t}, text, kind, store)}
  end

  defp brain({:done, %{status: status} = d}, st) do
    t = %{st.turn | brain_done?: true, status: status, request_id: d.request_id}
    # The history keeps what the transcript keeps (never a title from the phone).
    answer = t.stored |> Enum.reverse() |> Enum.join(" ")

    history =
      if t.text not in [nil, ""],
        do:
          st.history ++
            [%{"role" => "user", "content" => t.text}] ++
            if(answer != "", do: [%{"role" => "assistant", "content" => answer}], else: []),
        else: st.history

    st = %{st | turn: t, history: Enum.take(history, -12)}

    # Nothing to say (no speech): the turn ends here.
    if t.spoken == [] do
      record(t, st, status)
      {:noreply, %{st | turn: nil}}
    else
      {:noreply, st}
    end
  end

  ## Helpers

  defp new_turn(utt, pid, _st) do
    %{
      utt: utt,
      # The utterance of the question this turn answers (a restart with merged words keeps it).
      root: utt,
      pid: pid,
      pcm: nil,
      prefix: "",
      t0: System.monotonic_time(:millisecond),
      eot_wait_ms: nil,
      stt_ms: nil,
      stt_model: nil,
      first_token_ms: nil,
      first_piece_ms: nil,
      after_end_ms: nil,
      tts_first_ms: nil,
      route: nil,
      lang: "en",
      text: nil,
      spoken: [],
      stored: [],
      end_ms: nil,
      brain_done?: false,
      status: nil,
      request_id: nil
    }
  end

  defp speak(st, text, kind, store \\ nil) do
    st =
      case st.listen do
        %{held: held} = l ->
          %{st | listen: %{l | held: held ++ [{st.turn.utt, text, kind, store}]}}

        nil ->
          send_say(st, st.turn.utt, text, kind, store)
      end

    stored =
      case store do
        :skip -> st.turn.stored
        s when is_binary(s) -> [s | st.turn.stored]
        %{text: s} -> [s | st.turn.stored]
        _ -> [text | st.turn.stored]
      end

    %{st | turn: %{st.turn | spoken: [text | st.turn.spoken], stored: stored}}
  end

  defp send_say(st, utt, text, kind, store) do
    seq = st.seq + 1

    Media.impl().command(st.media, %{
      "cmd" => "say",
      "seq" => seq,
      "utt" => utt,
      "text" => text,
      "voice" => st.voice
    })

    %{st | seq: seq, pieces: Map.put(st.pieces, seq, {utt, text, kind, store})}
  end

  defp listen_ms, do: Application.get_env(:risime, :risi_call_listen_ms, 6_000)

  # The caller's words came (or `@listen_ms` passed): the held pieces are said now (`resend?`)
  # or dropped (a new question replaces the turn).
  defp release(%{listen: nil} = st, _resend?), do: st

  defp release(%{listen: %{held: held, ref: ref}} = st, resend?) do
    Process.cancel_timer(ref)
    st = %{st | listen: nil}

    if resend? and is_map(st.turn) do
      Enum.reduce(held, st, fn {utt, text, kind, store}, acc ->
        if utt == acc.turn.utt, do: send_say(acc, utt, text, kind, store), else: acc
      end)
    else
      st
    end
  end

  # Risi is checking (a tool turn without its answer yet): the user's speech joins the question.
  defp holding?(%{turn: %{brain_done?: false, route: r, pid: pid}})
       when is_pid(pid) and r not in [nil, "chat", "capabilities", "write_status"],
       do: true

  defp holding?(_st), do: false

  # "and tell me the important things": a fragment that goes on with the question.
  @and_then ~r/^\W*(?:and|also|plus|then|so|with)\b/iu

  @stop ~r/^\W*(?:risi\W+)?(?:stop|cancel(?:\s+that)?|never\s*mind|forget\s+it)\b/iu

  # A fragment said while holding: "Risi, stop" stops; one that changes the request (another
  # day, a calendar follow-up) restarts it with the merged words (no second holding line);
  # anything else is merged into the question's line and the wait goes on.
  defp held_fragment(st, utt, text) do
    t = st.turn
    merged = String.trim((t.text || "") <> " " <> text)
    decided = Map.get(t, :decided)
    frag = Brain.decide(text, decide_ctx(st, t))

    # "Give me the entire week" … "and tell me the important things": a fragment that is no
    # calendar read of its own, said while one is checked, whose words together still read as
    # one, continues the question: it joins it (the range stays the one being checked).
    joins? =
      changed?(frag, decided) and elem(frag, 0) != "calendar_read" and
        match?({"calendar_read", _}, decided) and
        (Regex.match?(@and_then, text) or
           match?({"calendar_read", _}, Brain.decide(merged, decide_ctx(st, t))))

    # Harsha's 3rd call ("… from 10 to" / "discuss about RISD"): a detail said while a write is
    # being made changes the request: it restarts with the merged words.
    write_detail? =
      match?({r, _} when r in ~w(calendar_write reminder), decided) and
        CallRisi.WriteTruth.correction?(text)

    cond do
      Regex.match?(@stop, text) ->
        {:noreply, add_line(cancel_turn(st, :barge_in), :user, text, nil, utt)}

      write_detail? ->
        lines = drop_first(st.lines, &(&1["who"] == "user" and &1["text"] == t.text))
        st = cancel_turn(%{st | lines: lines}, :replaced)
        {:noreply, restart(st, t, utt, merged, decided)}

      changed?(frag, decided) and not joins? ->
        lines = drop_first(st.lines, &(&1["who"] == "user" and &1["text"] == t.text))
        st = cancel_turn(%{st | lines: lines}, :replaced)
        {:noreply, restart(st, t, utt, merged, frag)}

      true ->
        lines =
          Enum.map(st.lines, fn
            %{"who" => "user", "text" => x} = l when x == t.text -> %{l | "text" => merged}
            l -> l
          end)

        {:noreply, %{st | lines: lines, turn: %{t | text: merged}}}
    end
  end

  # The fragment alone, read as a follow-up of the question being checked.
  defp decide_ctx(st, t) do
    topic =
      case Map.get(t, :decided) do
        {"calendar_read", %{} = r} -> %{route: "calendar_read", range: r}
        _ -> st.topic
      end

    %{user_id: st.args.user_id, topic: topic, write: st.write}
  end

  # A fragment changes the request when it is a tool request of its own that differs.
  defp changed?({route, _} = d, decided),
    do: route not in ~w(chat capabilities) and d != decided

  defp brain_ctx(st, utt) do
    %{
      pid: self(),
      utt: utt,
      conv: st.args.conv,
      call_id: st.args.call_id,
      user_id: st.args.user_id,
      device_id: st.args.device_id,
      history: st.history,
      filler_n: st.filler_n,
      tools?: Map.get(st.args, :tools?, true),
      topic: st.topic,
      write: st.write,
      once: st.once,
      cal?: st.cal?,
      caps: Map.get(st.args, :caps)
    }
  end

  # A turn from words already transcribed (a merged or late fragment).
  defp start_text_turn(st, utt, text, filler?, decision \\ nil) do
    ctx = brain_ctx(st, utt) |> Map.merge(%{filler?: filler?, decision: decision})
    {:ok, pid} = Task.start_link(fn -> Brain.run_text(ctx, text) end)
    %{st | turn: %{new_turn(utt, pid, st) | text: text}}
  end

  # The held question restarted with the merged words: it keeps its root (later fragments merge).
  defp restart(st, held, utt, merged, decision) do
    st = start_text_turn(st, utt, merged, false, decision)
    {route, _} = decision

    turn =
      Map.merge(st.turn, %{root: held.root, route: route, decided: decision})

    %{st | turn: turn}
  end

  # The current turn's utterance is still being transcribed (no words, nothing said yet).
  defp stt_pending(%{turn: %{utt: u, text: nil, spoken: [], pcm: pcm, pid: pid} = t})
       when u > 0 and is_binary(pcm) and is_pid(pid),
       do: if(Process.alive?(pid), do: t)

  defp stt_pending(_st), do: nil

  # A failed device lookup (even an exit from the pool) means "don't ask": times only.
  defp cal_capable?(args) do
    CallRisi.PhoneCalendar.capable?(args.user_id, args.device_id)
  catch
    _, _ -> false
  end

  # §41.8: the stored line of an answer that had the phone's titles: times only, marked.
  defp titled_line(%{lines: [line | rest]} = st),
    do: %{st | lines: [Map.put(line, "titles_spoken", true) | rest]}

  # An answer piece of the last user utterance was heard in full: no merging into it any more.
  defp answered(%{last_user: %{utt: utt} = l} = st, utt, kind) when kind != :filler,
    do: %{st | last_user: %{l | answered?: true}}

  defp answered(st, _utt, _kind), do: st

  # P0 2026-10-15: the user paused mid-question ("Do you have anything …" / "on the 18th?"). When
  # the new utterance starts within the merge window of the last one's end and no answer to it
  # was heard yet (a filler may have been), it continues that one: its text is the prefix and
  # that turn is cancelled.
  defp take_fragment(%{last_user: %{answered?: false, text: text, end_ms: e}} = st, start_ms)
       when is_integer(e) and is_binary(text) and text != "" do
    if start_ms - e <= merge_ms() do
      lines = drop_first(st.lines, &(&1["who"] == "user" and &1["text"] == text))

      history =
        case Enum.reverse(st.history) do
          [%{"role" => "assistant"}, %{"role" => "user", "content" => ^text} | rest] ->
            Enum.reverse(rest)

          [%{"role" => "user", "content" => ^text} | rest] ->
            Enum.reverse(rest)

          _ ->
            st.history
        end

      {text, %{st | lines: lines, history: history, last_user: nil}}
    else
      {"", st}
    end
  end

  defp take_fragment(st, _start_ms), do: {"", st}

  defp merge_ms, do: Application.get_env(:risime, :risi_call_merge_ms, 1_500)

  defp drop_first(list, f) do
    case Enum.split_while(list, &(not f.(&1))) do
      {before, [_ | rest]} -> before ++ rest
      {before, []} -> before
    end
  end

  # The user paused and went on before Risi said anything: the next utterance continues this one.
  defp take_unanswered(%{turn: %{spoken: [], text: text}} = st)
       when is_binary(text) and text != "" do
    lines =
      case st.lines do
        [%{"who" => "user", "text" => ^text} | rest] -> rest
        other -> other
      end

    history = st.history
    {text, %{st | lines: lines, history: history}}
  end

  defp take_unanswered(st), do: {"", st}

  defp cancel_turn(%{turn: nil} = st, _why), do: st

  defp cancel_turn(%{turn: t} = st, why) do
    st = release(st, false)
    if is_pid(t.pid) and Process.alive?(t.pid), do: Process.exit(t.pid, :kill)

    if why == :barge_in or t.spoken != [],
      do: Media.impl().command(st.media, %{"cmd" => "stop_speaking"})

    if t.utt > 0 and t.spoken != [],
      do: record(t, st, if(why == :barge_in, do: :barged, else: :replaced))

    %{st | turn: nil, pieces: %{}}
  end

  defp add_line(st, who, text, kind, utt) do
    line =
      %{
        "who" => Atom.to_string(who),
        "text" => String.slice(text, 0, 2000),
        "lang" => lang(st),
        "at" => now()
      }
      |> then(fn l -> if kind == :filler, do: Map.put(l, "filler", true), else: l end)
      |> then(fn l ->
        rid = st.turn && st.turn.utt == utt && st.turn.request_id
        if is_binary(rid) and who == :risi, do: Map.put(l, "request_id", rid), else: l
      end)

    %{st | lines: [line | st.lines]}
  end

  defp lang(%{turn: %{lang: l}}) when l in ["en", "si", "ta"], do: l
  defp lang(_), do: "en"

  defp record(%{utt: 0}, _st, _status), do: :ok

  defp record(t, st, status) do
    after_end = t.after_end_ms

    status_s =
      cond do
        status in [:ok, :barged, :replaced] and is_integer(after_end) and
            after_end > 2 * @budget_ms ->
          "over_budget"

        status in [:ok, :barged, :replaced, :no_speech, :ignored] ->
          "ok"

        true ->
          "error"
      end

    timings =
      "eot=#{t.eot_wait_ms};stt=#{t.stt_ms};tok=#{t.first_token_ms};piece=#{t.first_piece_ms};" <>
        "tts=#{t.tts_first_ms};first=#{after_end}"

    Logger.info("call risi turn: #{timings} route=#{t.route} status=#{status}")

    CallRisi.runs().record(%{
      agent: "voice_call",
      mode: CallRisi.mode_label(),
      decision: timings,
      by: t.route,
      lang: t.lang,
      status: status_s,
      reason: Atom.to_string(status),
      latency_ms: after_end,
      first_ms: t.stt_ms,
      budget_ms: @budget_ms,
      user: st.args.user_id
    })

    send_metric(st, t, status)
  end

  # The harness (and tests) may ask for each turn's timings.
  defp send_metric(%{args: %{metrics_to: pid}}, t, status) when is_pid(pid) do
    send(
      pid,
      {:call_risi_turn,
       Map.take(
         t,
         ~w(utt eot_wait_ms stt_ms first_token_ms first_piece_ms after_end_ms tts_first_ms route lang stt_model)a
       )
       |> Map.put(:status, status)}
    )
  end

  defp send_metric(_st, _t, _status), do: :ok

  defp finish(%{ended?: true} = st, _reason), do: {:stop, :normal, st}

  defp finish(st, reason) do
    st = cancel_turn(st, :ended)
    Media.impl().command(st.media, %{"cmd" => "leave", "reason" => Atom.to_string(reason)})
    media = st.media
    Task.start(fn -> Process.sleep(1_500) && Media.impl().close(media) end)
    CallRisi.post_transcript(st.args, Enum.reverse(st.lines), st.started_at, reason)
    {:stop, :normal, %{st | ended?: true}}
  end

  defp left_reason("port_closed"), do: :error
  defp left_reason("disconnected"), do: :hangup
  defp left_reason(_), do: :hangup

  defp since(t0), do: System.monotonic_time(:millisecond) - t0
  defp now, do: DateTime.utc_now() |> DateTime.truncate(:millisecond) |> DateTime.to_iso8601()

  @doc false
  # 16 kHz mono s16 WAV header for the speech service (audio stays in memory).
  def wav16k(pcm) do
    size = byte_size(pcm)

    <<"RIFF", 36 + size::little-32, "WAVEfmt ", 16::little-32, 1::little-16, 1::little-16,
      16_000::little-32, 32_000::little-32, 2::little-16, 16::little-16, "data", size::little-32,
      pcm::binary>>
  end
end
