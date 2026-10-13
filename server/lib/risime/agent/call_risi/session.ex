defmodule RisiMe.Agent.CallRisi.Session do
  @moduledoc """
  One Call Risi call (decision 090): the **Voice agent's** turn-taking for one room. It owns the media
  worker (`RisiMe.Agent.CallRisi.Media`), runs each turn in a `RisiMe.Agent.CallRisi.Brain` task,
  forwards the brain's pieces to the worker to be spoken, handles barge-in (the worker stops the audio
  at once; the session kills the turn), keeps the call's transcript in memory, posts it to the Risi
  chat when the call ends (`call_transcript`, proposal §41.5), and records one `risi_agent_runs` row
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
          "eot_ms" => Keyword.get(cfg, :eot_ms, 450),
          "barge_ms" => Keyword.get(cfg, :barge_ms, 300)
        })

        Process.send_after(self(), :join_timeout, @join_ms)
        Process.send_after(self(), :caller_timeout, @caller_ms)
        Process.send_after(self(), :max_length, CallRisi.max_s() * 1000)
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
           barge_ins: 0,
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

  defp media(%{"ev" => "caller_left"}, st), do: finish(st, :hangup)
  defp media(%{"ev" => "left", "reason" => r}, st), do: finish(st, left_reason(r))

  defp media(%{"ev" => "barge_in"}, st) do
    st = %{st | barge_ins: st.barge_ins + 1}
    {:noreply, cancel_turn(st, :barge_in)}
  end

  defp media(%{"ev" => "utterance", "utt" => utt, "pcm16k" => b64} = ev, st) do
    {prefix, st} = take_unanswered(st)
    st = cancel_turn(st, :replaced)
    wav = b64 |> Base.decode64!() |> wav16k()

    ctx = %{
      pid: self(),
      utt: utt,
      conv: st.args.conv,
      call_id: st.args.call_id,
      user_id: st.args.user_id,
      device_id: st.args.device_id,
      history: st.history,
      filler_n: st.filler_n,
      tools?: Map.get(st.args, :tools?, true)
    }

    {:ok, pid} = Task.start_link(fn -> Brain.run(ctx, wav, prefix) end)
    turn = new_turn(utt, pid, st) |> Map.put(:eot_wait_ms, ev["eot_wait_ms"])
    {:noreply, %{st | turn: turn}}
  end

  defp media(%{"ev" => "first_audio", "utt" => utt} = ev, %{turn: %{utt: utt} = t} = st) do
    t = %{t | after_end_ms: ev["after_end_ms"], tts_first_ms: ev["tts_first_ms"]}
    {:noreply, %{st | turn: t}}
  end

  defp media(%{"ev" => "said", "seq" => seq, "interrupted" => false}, st) do
    case Map.pop(st.pieces, seq) do
      {{utt, text, kind}, pieces} ->
        {:noreply, %{add_line(st, :risi, text, kind, utt) | pieces: pieces}}

      {nil, _} ->
        {:noreply, st}
    end
  end

  defp media(%{"ev" => "said", "seq" => seq}, st),
    do: {:noreply, %{st | pieces: Map.delete(st.pieces, seq)}}

  defp media(%{"ev" => "idle"}, %{turn: %{brain_done?: true} = t} = st) do
    record(t, st, :ok)
    {:noreply, %{st | turn: nil}}
  end

  defp media(%{"ev" => "error", "code" => code}, st) do
    Logger.warning("call risi: worker error #{inspect(code)} call=#{st.args.call_id}")
    {:noreply, st}
  end

  defp media(_ev, st), do: {:noreply, st}

  ## Brain events

  defp brain({:stt, %{} = r}, st) do
    t = %{st.turn | stt_ms: r.ms, lang: r.lang, text: r.text, stt_model: r.model}

    st =
      if r.text != "",
        do: add_line(%{st | turn: t}, :user, r.text, nil, t.utt),
        else: %{st | turn: t}

    {:noreply, st}
  end

  defp brain({:route, route}, st), do: {:noreply, put_in(st.turn.route, route)}
  defp brain({:first_token, ms}, st), do: {:noreply, put_in(st.turn.first_token_ms, ms)}

  defp brain({:piece, text, kind}, st) do
    st = if kind == :filler, do: %{st | filler_n: st.filler_n + 1}, else: st
    t = st.turn
    t = if t.first_piece_ms == nil, do: %{t | first_piece_ms: since(t.t0)}, else: t
    {:noreply, speak(%{st | turn: t}, text, kind)}
  end

  defp brain({:done, %{status: status} = d}, st) do
    t = %{st.turn | brain_done?: true, status: status, request_id: d.request_id}
    answer = t.spoken |> Enum.reverse() |> Enum.join(" ")

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
      pid: pid,
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
      brain_done?: false,
      status: nil,
      request_id: nil
    }
  end

  defp speak(st, text, kind) do
    seq = st.seq + 1

    Media.impl().command(st.media, %{
      "cmd" => "say",
      "seq" => seq,
      "utt" => st.turn.utt,
      "text" => text,
      "voice" => st.voice
    })

    turn = %{st.turn | spoken: [text | st.turn.spoken]}
    %{st | seq: seq, pieces: Map.put(st.pieces, seq, {st.turn.utt, text, kind}), turn: turn}
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

        status in [:ok, :barged, :replaced, :no_speech] ->
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
