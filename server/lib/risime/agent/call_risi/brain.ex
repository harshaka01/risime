defmodule RisiMe.Agent.CallRisi.Brain do
  @moduledoc """
  One spoken turn of the Voice agent (decision 090), run in a task the call's session owns (killed
  on barge-in). It reports to the session `pid` as `{:brain, utt, event}`:

    * `{:stt, %{text, lang, model, ms}}` — the routed transcription (`RisiMe.Agent.Speech`: the admin
      console's per-language choice through `SpeechRoutes.pick/2`, else today's route);
    * `{:route, route}` — `RisiMe.Agents.Router.rules/2` (µs, no model call), with the call's topic
      carried over (below);
    * `{:piece, text, :filler | :answer}` or `{:piece, text, :answer, store}` — a speakable piece,
      in order; `store` is what the call's transcript keeps instead (`:skip`: nothing), so a title
      spoken from the phone's data never lands in the stored transcript;
    * `{:topic, %{route, range}}` — the last tool topic of the call (a follow-up inherits it);
    * `{:once, key}` — a once-per-call line was said (the titles line, a timeout line, the
      language line);
    * `{:first_token, ms}` — the streamed answer's first token;
    * `{:done, %{status, request_id}}` — the turn ended (`:ok`, `:no_speech`, `:stt_failed`,
      `:model_failed`, `:tool_timeout`, `:tool_busy`, `:tool_failed`).

  **P0 2026-10-15 (Harsha's call):**
    * a calendar answer is **spoken from the read's facts** (`RisiMe.Agent.CallRisi.Spoken`), never
      the card's text (a v2 card with local events is only its head line and footer);
    * "what can you do?" gets a true list of this user's call tools;
    * a follow-up ("on the 18th?", "why can't you get the calendar?", "the entire week") inherits
      the call's calendar topic and is a calendar read, never a chat answer that claims Risi can't
      see the calendar;
    * the §25 turn runs **linked** to this task: a barge-in or a new question kills both, so the
      user's one-turn lock (§25.6) is released; a busy lock is retried inside the 25 s budget, and
      Risi never says it is "still busy".

  **Honesty:** a filler is said only on a tool route, before the tool turn runs, and never implies a
  result; when the turn fails or takes over 25 s, Risi says so (once per call) and never makes a
  result up.
  """

  alias RisiMe.Agent.CallRisi.{Fillers, LLMStream, Sentences, Spoken}
  alias RisiMe.Agent.{Clock, ScheduleIntent}

  # The 25 s tool budget (tests shorten it: `config :risime, :risi_call_tool_wait_ms`).
  defp tool_wait_ms, do: Application.get_env(:risime, :risi_call_tool_wait_ms, 25_000)
  @retry_ms 700

  @system """
  You are Risi, the user's assistant, speaking on a live phone call. Speak naturally, like a person on \
  the phone: one to three short sentences, plain words, no lists, no markdown, no emoji, no headings. \
  Answer in English (the call voice speaks English only for now). Chat freely and answer general \
  questions from what you know. Never invent the user's own names, amounts, dates or results, and never \
  say you checked, saved, sent or scheduled anything: in this reply you have no tools. If the user wants \
  something from their own calendar, reminders or promises, ask them to say it directly, for example \
  "what's on my calendar tomorrow": you can read those when asked. Never say you can't see or access \
  their calendar. If you didn't understand, ask them to say it again.
  """

  @doc "The system prompt of the streamed chat route (its hash is what the learning log keeps)."
  def system, do: @system

  @doc """
  Runs the turn. `ctx`: `%{pid, utt, conv, call_id, user_id, device_id, history, filler_n, tools?}`
  and optionally `topic` (the call's last tool topic), `once` (the once-per-call lines already
  said), `titles` (`(start_utc, end_utc) -> title | nil`, speech only) and `caps` (the tool names
  for "what can you do?"; default: the registry's for this user). `wav` is the utterance (16 kHz
  mono WAV, in memory, dropped after STT); `prefix` is the text of an earlier utterance of the same
  turn that got no answer yet (the user paused and went on).
  """
  def run(ctx, wav, prefix \\ "") do
    t0 = System.monotonic_time(:millisecond)

    # v1 calls are English-first (the start reply's `lang`): naming the language skips the service's
    # detection pass (~250 ms measured). An admin's manual model for it still applies (SpeechRoutes).
    case RisiMe.Agent.Speech.transcribe(wav, language: Map.get(ctx, :lang, "en")) do
      {:ok, %{text: text} = r} ->
        ms = System.monotonic_time(:millisecond) - t0
        text = String.trim(prefix <> " " <> text)
        report(ctx, {:stt, %{text: text, lang: r.language, model: r.model, ms: ms}})

        cond do
          text == "" or r[:speech] == false ->
            report(ctx, {:done, %{status: :no_speech, request_id: nil}})

          true ->
            lang_note(ctx, r.language)
            answer(ctx, text, r.language)
        end

      {:error, _} ->
        report(ctx, {:stt, %{text: prefix, lang: "en", model: nil, ms: nil}})
        say(ctx, "Sorry, I didn't catch that. Could you say it again?", :answer)
        report(ctx, {:done, %{status: :stt_failed, request_id: nil}})
    end
  end

  # Harsha's rule: Sinhala or Tamil speech gets one line (once a call), then the answer in English.
  defp lang_note(ctx, lang) when lang in ["si", "ta"] do
    once(ctx, :lang_note, fn ->
      say(ctx, "I can only speak English on calls for now, so I'll answer in English.", :answer)
    end)
  end

  defp lang_note(_ctx, _lang), do: :ok

  defp answer(ctx, text, lang) do
    {route, range} = decide(text, ctx)
    report(ctx, {:route, route})

    cond do
      route == "capabilities" ->
        say_text(ctx, Spoken.capabilities(caps(ctx)))
        report(ctx, {:done, %{status: :ok, request_id: nil}})

      Fillers.tool_route?(route) and Map.get(ctx, :tools?, true) ->
        tool_turn(ctx, text, lang, route, range)

      true ->
        chat_turn(ctx, text)
    end
  end

  ## Routing (with the call's topic)

  @caps_re ~r/\bwhat\s+(?:else\s+)?(?:can|could|do)\s+you\s+(?:do|help)\b|\bwhat\s+are\s+you\s+able\b|\bhow\s+can\s+you\s+help\b|\bwhat\s+(?:are\s+)?your\s+(?:features|capabilities|skills|abilities)\b|\bwhat\s+can\s+i\s+ask\b|\bwhat\s+do\s+you\s+do\b|\bwhat\s+are\s+you\s+capable\b/iu

  @complaint_re ~r/\b(?:why|how\s+come)\b.{0,40}\b(?:can'?t|cannot|couldn'?t|didn'?t|don'?t|won'?t|not|no)\b|\b(?:try|check|look)\s+again\b|\b(?:what|how)\s+about\b|\banything\s+else\b|\bwhat\s+else\b/iu

  @doc """
  The route of `text` on a call and, for a calendar read, its range (`ScheduleIntent.range/2`):
  `{"capabilities", nil}`, `{"calendar_read", %{from, to, now?}}` or `{route, nil}`. A
  follow-up of the call's calendar topic (a date-only fragment, "the entire week", "why can't you
  get it?", "what about Friday?") is a calendar read: with its own day when it names one, else
  the topic's range.
  """
  def decide(text, ctx) do
    today = today(ctx)
    topic = Map.get(ctx, :topic)
    cal_topic? = match?(%{route: "calendar_read", range: %{}}, topic)

    route =
      case RisiMe.Agents.Router.rules(text, today) do
        %{route: "planner", confidence: c} when c < 0.6 -> "chat"
        %{route: r} -> r
      end

    cond do
      Regex.match?(@caps_re, text) and route in ~w(chat planner) ->
        {"capabilities", nil}

      route == "calendar_read" ->
        r = ScheduleIntent.range(text, today)

        if r.now? and cal_topic? and Regex.match?(@complaint_re, text),
          do: {"calendar_read", topic.range},
          else: {"calendar_read", r}

      cal_topic? and route in ~w(chat planner message_write) and follow_up?(text, today) ->
        if ScheduleIntent.day_named?(text, today),
          do: {"calendar_read", ScheduleIntent.range(text, today)},
          else: {"calendar_read", topic.range}

      true ->
        {route, nil}
    end
  end

  defp follow_up?(text, today) do
    words = length(String.split(text))

    (ScheduleIntent.day_named?(text, today) and words <= 14) or
      Regex.match?(@complaint_re, text)
  end

  # The user's local date (their zone; UTC if unknown).
  defp today(ctx) do
    case Map.get(ctx, :today) do
      %Date{} = d ->
        d

      _ ->
        now = Clock.now()

        try do
          now |> Clock.local(Clock.user_tz(ctx.user_id)) |> NaiveDateTime.to_date()
        rescue
          _ -> DateTime.to_date(now)
        end
    end
  end

  defp caps(ctx) do
    case Map.get(ctx, :caps) do
      names when is_list(names) ->
        names

      _ ->
        try do
          %{asker: ctx.user_id, device_id: ctx.device_id, conv: ctx.conv}
          |> RisiMe.Agent.Tools.allowed()
          |> Enum.map(& &1.name)
        rescue
          _ -> []
        end
    end
  end

  ## The chat route: risi-l1 streamed, cut into pieces as it comes.

  defp chat_turn(ctx, text) do
    messages =
      [%{"role" => "system", "content" => @system <> " " <> now_line(ctx)}] ++
        Enum.take(ctx.history, -12) ++ [%{"role" => "user", "content" => text}]

    key = {__MODULE__, :buf}
    Process.put(key, {"", true})

    on_delta = fn delta ->
      {buf, first?} = Process.get(key)
      {pieces, rest} = Sentences.feed(buf, delta, first?)
      Enum.each(pieces, &say(ctx, &1, :answer))
      Process.put(key, {rest, first? and pieces == []})
    end

    opts = [conversation_id: ctx.conv, call_id: ctx.call_id]
    t0 = System.monotonic_time(:millisecond)

    case LLMStream.run(messages, opts, on_delta) do
      {:ok, r} ->
        report(ctx, {:first_token, r.first_token_ms || System.monotonic_time(:millisecond) - t0})
        {buf, _} = Process.get(key)
        Enum.each(Sentences.flush(buf), &say(ctx, &1, :answer))
        report(ctx, {:done, %{status: :ok, request_id: nil}})

      {:error, _} ->
        say(
          ctx,
          "Sorry, I'm having trouble thinking right now. Please try again in a moment.",
          :answer
        )

        report(ctx, {:done, %{status: :model_failed, request_id: nil}})
    end
  end

  ## A tool route: the filler at once, then the Risi chat's own turn (§25), spoken from its results.

  defp tool_turn(ctx, text, lang, route, range) do
    say(ctx, Fillers.pick(route, lang, ctx.filler_n), :filler)
    request_id = Ecto.UUID.generate()

    env =
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => request_id,
        "action" => "ask",
        "text" => String.slice(text, 0, 1000),
        "__sender" => ctx.user_id,
        "__device" => ctx.device_id,
        "__ts" => DateTime.utc_now() |> DateTime.to_iso8601()
      }
      |> then(fn e -> if range, do: Map.put(e, "__schedule", range), else: e end)

    if route == "calendar_read" and range,
      do: report(ctx, {:topic, %{route: route, range: range}})

    # Linked: when the session kills this task (barge-in, a new question) the §25 turn dies too
    # and its per-user lock (§25.6) is released. Trapping exits keeps a crash of the turn honest.
    Process.flag(:trap_exit, true)
    deadline = System.monotonic_time(:millisecond) + tool_wait_ms()
    inner = start_inner(ctx, env, request_id)

    wait_answer(
      %{ctx: ctx, rid: request_id, env: env, inner: inner, deadline: deadline},
      route,
      text,
      nil
    )
  end

  defp start_inner(ctx, env, request_id) do
    me = self()

    {:ok, pid} =
      Task.start_link(fn ->
        Process.put(:risi_voice_sink, {me, ctx.call_id})
        result = turn_impl().run(ctx.conv, env, request_id, ctx.user_id)
        send(me, {:risi_voice_turn_end, request_id, result})
      end)

    pid
  end

  defp wait_answer(w, route, question, facts) do
    %{ctx: ctx, rid: rid} = w
    left = max(w.deadline - System.monotonic_time(:millisecond), 0)
    session = ctx.pid

    receive do
      {:risi_voice_facts, ^rid, f} ->
        wait_answer(w, route, question, f)

      {:risi_voice_answer, ^rid, answer} when is_binary(answer) ->
        speak_answer(ctx, answer, facts, question)
        report(ctx, {:done, %{status: :ok, request_id: rid}})

      {:risi_voice_turn_end, ^rid, {:snooze, _}} ->
        # Another turn of this user holds the lock (§25.6): wait and retry inside the budget.
        if left > @retry_ms * 2 do
          Process.sleep(@retry_ms)
          wait_answer(%{w | inner: start_inner(ctx, w.env, rid)}, route, question, facts)
        else
          say(ctx, "Sorry, I couldn't get to that just now. Please ask me again.", :answer)
          report(ctx, {:done, %{status: :tool_busy, request_id: rid}})
        end

      {:risi_voice_turn_end, ^rid, _} ->
        # The turn ended without an answer text (a card or a question went to the chat).
        say(ctx, "I've put that in your Risi chat.", :answer)
        report(ctx, {:done, %{status: :ok, request_id: rid}})

      {:EXIT, ^session, reason} ->
        Process.exit(w.inner, :kill)
        exit(reason)

      {:EXIT, pid, reason} when pid == w.inner and reason != :normal ->
        say(ctx, "Sorry, I couldn't get that done just now.", :answer)
        report(ctx, {:done, %{status: :tool_failed, request_id: rid}})
    after
      left ->
        timeout_line(ctx, route)
        report(ctx, {:done, %{status: :tool_timeout, request_id: rid}})
    end
  end

  # The timeout line says what is slow, once a call; then a short one.
  defp timeout_line(ctx, route) do
    first =
      if route == "calendar_read",
        do:
          "Your phone's calendar didn't answer in time, so I'll put the answer in your Risi chat.",
        else: "That's taking too long, so I'll put the answer in your Risi chat."

    said? = once(ctx, :timeout, fn -> say(ctx, first, :answer) end)
    unless said?, do: say(ctx, "That one will be in your Risi chat too.", :answer)
  end

  ## The spoken answer

  defp speak_answer(ctx, _answer, %{ok?: _} = facts, question) do
    titles = Map.get(ctx, :titles)
    {rendered, missing?} = Spoken.render(facts, titles: titles)

    text =
      if facts.ok?,
        do: phrase(ctx, rendered, question, Spoken.titles(facts, titles)),
        else: rendered

    # What the transcript keeps: never a title from the phone (times only).
    stored =
      if Spoken.titled?(facts, titles),
        do: elem(Spoken.render(facts, titles: nil), 0),
        else: nil

    note =
      if missing? and
           once(ctx, :titles_note, fn -> :ok end),
         do: " I can see the times; the titles stay on your phone.",
         else: ""

    say_text(ctx, text <> note, stored && stored <> note)
  end

  defp speak_answer(ctx, answer, _facts, _question) do
    case Spoken.speakable(answer) do
      "" -> say(ctx, "I've put that in your Risi chat.", :answer)
      text -> say_text(ctx, short(text))
    end
  end

  # A long card answer: the first three sentences, then where the rest is.
  defp short(text) do
    {pieces, rest} = Sentences.feed("", text <> " ")
    pieces = pieces ++ Sentences.flush(rest)

    if length(pieces) > 3,
      do: Enum.join(Enum.take(pieces, 3), " ") <> " The rest is in your Risi chat.",
      else: Enum.join(pieces, " ")
  end

  # risi-l1 phrases the rendering; its text is used only when `Spoken.checked/3` passes.
  defp phrase(ctx, rendered, question, titles) do
    buf = {__MODULE__, :phrase}
    Process.put(buf, "")
    opts = [conversation_id: ctx.conv, call_id: ctx.call_id, max_tokens: 160, timeout: 8_000]

    said =
      case LLMStream.run(Spoken.compose_messages(rendered, question), opts, fn d ->
             Process.put(buf, Process.get(buf) <> d)
           end) do
        {:ok, _} -> Process.get(buf)
        _ -> nil
      end

    Spoken.checked(said, rendered, titles) || rendered
  rescue
    _ -> rendered
  end

  # Speaks `text` piece by piece; with `stored`, the transcript keeps `stored` instead (once).
  defp say_text(ctx, text, stored \\ nil) do
    {pieces, rest} = Sentences.feed("", Sentences.clean(text) <> " ", true)
    pieces = pieces ++ Sentences.flush(rest)

    pieces
    |> Enum.with_index()
    |> Enum.each(fn
      {p, _i} when stored == nil -> say(ctx, p, :answer)
      {p, 0} -> report(ctx, {:piece, p, :answer, stored})
      {p, _} -> report(ctx, {:piece, p, :answer, :skip})
    end)
  end

  # Runs `f` and reports `key` unless the call said it already. True when it ran.
  defp once(ctx, key, f) do
    if MapSet.member?(Map.get(ctx, :once, MapSet.new()), key) or Process.get({:once, key}) do
      false
    else
      Process.put({:once, key}, true)
      f.()
      report(ctx, {:once, key})
      true
    end
  end

  # The harness caught the model inventing the time ("It is currently 10:15 AM"): it gets the real
  # local time (the user's zone; UTC if unknown) and is told it knows nothing else about now.
  @doc false
  def now_line(ctx) do
    now = RisiMe.Agent.Clock.now()

    {local, tz} =
      try do
        tz = RisiMe.Agent.Clock.user_tz(ctx.user_id)
        {RisiMe.Agent.Clock.local(now, tz), tz}
      rescue
        _ -> {now, "UTC"}
      end

    "It is now #{Calendar.strftime(local, "%A %d %B %Y, %H:%M")} (#{tz}). Use this for the time and date; " <>
      "you know nothing else about the user's day unless they tell you."
  end

  defp turn_impl, do: Application.get_env(:risime, :risi_call_turn, RisiMe.Agent.Turn)

  defp say(_ctx, nil, _kind), do: :ok
  defp say(ctx, text, kind), do: report(ctx, {:piece, text, kind})

  defp report(ctx, event), do: send(ctx.pid, {:brain, ctx.utt, event})
end
