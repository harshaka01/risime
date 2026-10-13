defmodule RisiMe.Agent.CallRisi.Brain do
  @moduledoc """
  One spoken turn of the Voice agent (decision 090), run in a task the call's session owns (killed
  on barge-in). It reports to the session `pid` as `{:brain, utt, event}`:

    * `{:stt, %{text, lang, model, ms}}` — the routed transcription (`RisiMe.Agent.Speech`: the admin
      console's per-language choice through `SpeechRoutes.pick/2`, else today's route);
    * `{:route, route}` — `RisiMe.Agents.Router.rules/2` (µs, no model call);
    * `{:piece, text, :filler | :answer}` — a speakable piece, in order;
    * `{:first_token, ms}` — the streamed answer's first token;
    * `{:done, %{status, request_id}}` — the turn ended (`:ok`, `:no_speech`, `:stt_failed`,
      `:model_failed`, `:tool_timeout`).

  **Honesty:** a filler is said only on a tool route, before the tool turn runs, and never implies a
  result; a tool route's answer is the §25 turn's own answer (its card lands in the Risi chat with
  `call_id`); when that turn fails or takes over 25 s, Risi says so and never makes a result up.
  """

  alias RisiMe.Agent.CallRisi.{Fillers, LLMStream, Sentences}

  @tool_wait_ms 25_000

  @system """
  You are Risi, the user's assistant, speaking on a live phone call. Speak naturally, like a person on \
  the phone: one to three short sentences, plain words, no lists, no markdown, no emoji, no headings. \
  Answer in English (the call voice speaks English only for now). Chat freely and answer general \
  questions from what you know. Never invent the user's own names, amounts, dates or results, and never \
  say you checked, saved, sent or scheduled anything: in this reply you have no tools. Only if the user \
  wants something from their own calendar, notes, reminders or messages, ask them to say it directly, \
  for example "what's on my calendar tomorrow". If you didn't understand, ask them to say it again.
  """

  @doc "The system prompt of the streamed chat route (its hash is what the learning log keeps)."
  def system, do: @system

  @doc """
  Runs the turn. `ctx`: `%{pid, utt, conv, call_id, user_id, device_id, history, filler_n, tools?}`;
  `wav` is the utterance (16 kHz mono WAV, in memory, dropped after STT); `prefix` is the text of an
  earlier utterance of the same turn that got no answer yet (the user paused and went on).
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

        if text == "" or r[:speech] == false,
          do: report(ctx, {:done, %{status: :no_speech, request_id: nil}}),
          else: answer(ctx, text, r.language)

      {:error, _} ->
        report(ctx, {:stt, %{text: prefix, lang: "en", model: nil, ms: nil}})
        say(ctx, "Sorry, I didn't catch that. Could you say it again?", :answer)
        report(ctx, {:done, %{status: :stt_failed, request_id: nil}})
    end
  end

  defp answer(ctx, text, lang) do
    # The rules' fallback (`planner` below 0.6) means "no sign of a tool": on a call that is a
    # spoken chat answer, not a filler and a tool turn.
    route =
      case RisiMe.Agents.Router.rules(text, Date.utc_today()) do
        %{route: "planner", confidence: c} when c < 0.6 -> "chat"
        %{route: r} -> r
      end

    report(ctx, {:route, route})

    if Fillers.tool_route?(route) and Map.get(ctx, :tools?, true),
      do: tool_turn(ctx, text, lang, route),
      else: chat_turn(ctx, text)
  end

  # The chat route: risi-l1 streamed, cut into pieces as it comes.
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

  # A tool route: the filler at once, then the Risi chat's own turn (§25); its answer is spoken.
  defp tool_turn(ctx, text, lang, route) do
    say(ctx, Fillers.pick(route, lang, ctx.filler_n), :filler)
    request_id = Ecto.UUID.generate()
    me = self()

    env = %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => request_id,
      "action" => "ask",
      "text" => String.slice(text, 0, 1000),
      "__sender" => ctx.user_id,
      "__device" => ctx.device_id,
      "__ts" => DateTime.utc_now() |> DateTime.to_iso8601()
    }

    {:ok, task} =
      Task.start(fn ->
        Process.put(:risi_voice_sink, {me, ctx.call_id})
        result = turn_impl().run(ctx.conv, env, request_id, ctx.user_id)
        send(me, {:risi_voice_turn_end, request_id, result})
      end)

    ref = Process.monitor(task)
    wait_answer(ctx, request_id, ref)
  end

  defp wait_answer(ctx, request_id, ref) do
    receive do
      {:risi_voice_answer, ^request_id, answer} when is_binary(answer) ->
        {pieces, rest} = Sentences.feed("", Sentences.clean(answer) <> " ", true)
        Enum.each(pieces ++ Sentences.flush(rest), &say(ctx, &1, :answer))
        report(ctx, {:done, %{status: :ok, request_id: request_id}})

      {:risi_voice_turn_end, ^request_id, {:snooze, _}} ->
        say(
          ctx,
          "I'm still busy with your last request. Give me a moment and ask again.",
          :answer
        )

        report(ctx, {:done, %{status: :tool_busy, request_id: request_id}})

      {:risi_voice_turn_end, ^request_id, _} ->
        # The turn ended without an answer text (a card or a question went to the chat).
        say(ctx, "I've put that in your Risi chat.", :answer)
        report(ctx, {:done, %{status: :ok, request_id: request_id}})

      {:DOWN, ^ref, :process, _, reason} when reason != :normal ->
        say(ctx, "Sorry, I couldn't get that done just now.", :answer)
        report(ctx, {:done, %{status: :tool_failed, request_id: request_id}})
    after
      @tool_wait_ms ->
        say(
          ctx,
          "This is taking longer than it should. I'll put the answer in your Risi chat.",
          :answer
        )

        report(ctx, {:done, %{status: :tool_timeout, request_id: request_id}})
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
