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

  alias RisiMe.Agent.CallRisi.{
    Fillers,
    Hearing,
    LLMStream,
    PhoneCalendar,
    Sentences,
    Spoken,
    VoiceConfirm,
    WriteTruth
  }

  alias RisiMe.Agent.{Clock, ScheduleIntent}

  # The 25 s tool budget (tests shorten it: `config :risime, :risi_call_tool_wait_ms`).
  defp tool_wait_ms, do: Application.get_env(:risime, :risi_call_tool_wait_ms, 25_000)
  defp still_ms, do: Application.get_env(:risime, :risi_call_still_ms, 4_000)
  @retry_ms 700
  @write_routes ~w(calendar_write reminder)

  @system """
  You are Risi, the user's assistant, speaking on a live phone call. Speak naturally, like a person on \
  the phone: one to three short sentences, plain words, no lists, no markdown, no emoji, no headings. \
  Answer in English (the call voice speaks English only for now). Chat freely and answer general \
  questions from what you know. Never invent the user's own names, amounts, dates or results, and never \
  say you checked, added, saved, sent, booked, set or scheduled anything, or that you will or can do it \
  now: in this reply you have no tools and nothing gets added. If the user wants something from their \
  own calendar, reminders or promises, ask them to say it directly, for example "what's on my calendar \
  tomorrow": you can read those when asked. If they want something added, ask them to say it with the \
  day and time, for example "add a meeting tomorrow from 9 to 10": you then put a card in their Risi \
  chat for them to confirm. Never say you can't see or access \
  their calendar, promises, reminders or notes: they are yours to keep. If you didn't understand, \
  ask them to say it again.
  """

  @doc "The system prompt of the streamed chat route (its hash is what the learning log keeps)."
  def system, do: @system

  @doc """
  Runs the turn. `ctx`: `%{pid, utt, conv, call_id, user_id, device_id, history, filler_n, tools?}`
  and optionally `topic` (the call's last tool topic), `once` (the once-per-call lines already
  said), `cal?` (the device may send its titles, §41.8) and `caps` (the tool names
  for "what can you do?"; default: the registry's for this user). `wav` is the utterance (16 kHz
  mono WAV, in memory, dropped after STT); `prefix` is the text of an earlier utterance of the same
  turn that got no answer yet (the user paused and went on).
  """
  def run(ctx, wav, prefix \\ "") do
    t0 = System.monotonic_time(:millisecond)

    # v1 calls are English-first (the start reply's `lang`): naming the language skips the service's
    # detection pass (~250 ms measured). An admin's manual model for it still applies (SpeechRoutes).
    case RisiMe.Agent.Speech.transcribe(wav, language: Map.get(ctx, :lang, "en")) do
      {:ok, %{text: _} = r} ->
        ms = System.monotonic_time(:millisecond) - t0
        prefix = String.trim(prefix)

        # Harsha 2026-10-16: words nobody said (Whisper on noise, silence or echo) are never
        # answered, and never replace the question they arrived after (`prefix`).
        {heard, outcome} =
          case Hearing.judge(r, hearing_audio(ctx, wav)) do
            {:ok, h} -> {h, :ok}
            {why, code} -> {"", {why, code}}
          end

        report(ctx, {:heard, outcome})
        text = String.trim(prefix <> " " <> heard)
        report(ctx, {:stt, %{text: text, lang: r.language, model: r.model, ms: ms}})

        cond do
          text != "" ->
            lang_note(ctx, r.language)
            answer(ctx, text, r.language)

          # Unsure what was said: one "Sorry, I didn't catch that." in a row, then silence.
          match?({:unclear, _}, outcome) and Map.get(ctx, :unclear_last?) != true ->
            say(ctx, Hearing.unclear_line(), :answer)
            report(ctx, {:done, %{status: :ok, request_id: nil}})

          true ->
            report(ctx, {:done, %{status: :no_speech, request_id: nil}})
        end

      {:error, _} ->
        report(ctx, {:stt, %{text: prefix, lang: "en", model: nil, ms: nil}})
        say(ctx, "Sorry, I didn't catch that. Could you say it again?", :answer)
        report(ctx, {:done, %{status: :stt_failed, request_id: nil}})
    end
  end

  # The utterance's audio facts for the hearing gate (the PCM after the WAV header).
  defp hearing_audio(ctx, wav) do
    pcm =
      case wav do
        <<_hdr::binary-size(44), pcm::binary>> -> pcm
        _ -> nil
      end

    %{pcm: pcm, speech_ms: Map.get(ctx, :speech_ms), during_tts?: Map.get(ctx, :during_tts?)}
  end

  # Harsha's rule: Sinhala or Tamil speech gets one line (once a call), then the answer in English.
  defp lang_note(ctx, lang) when lang in ["si", "ta"] do
    once(ctx, :lang_note, fn ->
      say(ctx, "I can only speak English on calls for now, so I'll answer in English.", :answer)
    end)
  end

  defp lang_note(_ctx, _lang), do: :ok

  @doc """
  A turn from words already transcribed (the session merged a fragment said while Risi was
  checking, or the words came after the answer): no STT; `ctx.filler?` false skips the holding
  line (it was said once for this question).
  """
  def run_text(ctx, text) do
    report(ctx, {:stt, %{text: text, lang: "en", model: nil, ms: 0}})
    answer(ctx, text, "en")
  end

  defp answer(ctx, text, lang) do
    {route, range} = Map.get(ctx, :decision) || decide(text, ctx)
    report(ctx, {:route, route})
    report(ctx, {:decided, {route, range}})

    cond do
      # Harsha's 3rd call: "That's correct" / "I can't see that you added it" after a write turn:
      # the truth from the card's own row, never the model.
      route == "write_status" ->
        write_status(ctx, range || :confirm)
        report(ctx, {:done, %{status: :ok, request_id: nil}})

      # Root 15 Oct: a write on a call that names no day asks "Which day?" (never the day of an
      # earlier read of the call).
      route == "calendar_write" and
          (range == :ask_day_more or
             (range == nil and not ScheduleIntent.day_named?(text, today(ctx)))) ->
        pending = if range == :ask_day_more, do: ctx.write_ask <> " " <> text, else: text
        report(ctx, {:write_ask, pending})
        say(ctx, "Which day should I put it on?", :answer)
        report(ctx, {:done, %{status: :ok, request_id: nil}})

      # Thinking aloud ("Let me see. I need to listen to it.") is not a request, whatever the words.
      route in ~w(chat planner message_write summary_chat) and addressed(text, ctx) == :ignore ->
        report(ctx, {:done, %{status: :ignored, request_id: nil}})

      route == "capabilities" ->
        say_text(ctx, Spoken.capabilities(caps(ctx)))
        report(ctx, {:done, %{status: :ok, request_id: nil}})

      Fillers.tool_route?(route) and Map.get(ctx, :tools?, true) ->
        tool_turn(ctx, write_text(text, route, range), lang, route, range)

      true ->
        case addressed(text, ctx) do
          :ignore ->
            report(ctx, {:done, %{status: :ignored, request_id: nil}})

          :ask ->
            say(ctx, "Was that for me?", :answer)
            report(ctx, {:done, %{status: :ok, request_id: nil}})

          :yes ->
            chat_turn(ctx, text)
        end
    end
  end

  ## Thinking aloud (Harsha's 2nd call: "Let me see. I need to listen to it." got an answer)

  @aloud ~r/^\W*(?:let\s+me|let'?s\s+see|hmm+|um+|uh+|erm|i\s+need\s+to|i\s+have\s+to|i\s+think|i\s+guess|wait|okay\s+so|so\b|where\s+was\s+i|one\s+sec)/iu
  @request ~r/\?|\brisi\b|\byou\b|\byour\b|^\W*(?:what|when|where|who|whom|whose|which|why|how|can|could|would|will|do|does|did|is|are|am|was|were|should|shall|may|tell|give|show|check|remind|add|send|set|remember|take|book|cancel|find|read|call|play|say|repeat|help|please|explain|translate|summari[sz]e|note|schedule|make|create|put)\b/iu
  @smalltalk ~r/^\W*(?:hi|hello|hey|thanks|thank\s+you|thank|bye|goodbye|ok|okay|yes|yeah|yep|no|nope|sure|good|great|fine|cool|nice|right|alright|got\s+it|stop|cancel|never\s*mind|go\s+on|continue|again|sorry|pardon|good\s+(?:morning|afternoon|evening|night))\b/iu

  @doc """
  Is a chat-route utterance meant for Risi? `:yes`; `:ignore` for thinking aloud that is no
  request ("Let me see. I need to listen to it."); `:ask` ("Was that for me?") for a short
  statement with no sign of a request.
  """
  def addressed(text) do
    words = length(String.split(text))

    cond do
      Regex.match?(@request, text) or Regex.match?(@smalltalk, text) -> :yes
      Regex.match?(@aloud, text) -> :ignore
      words <= 8 -> :ask
      true -> :yes
    end
  end

  # "That's correct", "not in the menu", "yeah, that's for you": a reply to Risi.
  @reply ~r/^\W*(?:not|no|that'?s|that\s+is|it'?s|it\s+is|correct|exactly|right)\b|\bfor\s+you\b/iu

  # Risi's last line asked something or offered something.
  @offered ~r/\?\s*$|\b(?:tap\s+(?:add|send|schedule)|or\s+cancel|would\s+you\s+like|do\s+you\s+want|shall\s+i|should\s+i|let\s+me\s+know|is\s+that\s+(?:right|correct|ok(?:ay)?))\b/iu

  @doc """
  `addressed/1` in the call's context (Harsha's 3rd call: "That's correct" after Risi's offer got
  "Was that for me?"): "Was that for me?" only for a short statement that is no reply, when Risi's
  last line was no question or offer.
  """
  def addressed(text, ctx) do
    case addressed(text) do
      :ask ->
        if Regex.match?(@reply, text) or WriteTruth.confirmation?(text) or
             Regex.match?(@offered, last_risi(ctx)),
           do: :yes,
           else: :ask

      other ->
        other
    end
  end

  defp last_risi(ctx) do
    (Map.get(ctx, :history) || [])
    |> Enum.reverse()
    |> Enum.find_value("", fn
      %{"role" => "assistant", "content" => c} when is_binary(c) -> c
      _ -> nil
    end)
  end

  # §41.9: a clear yes confirms the caller's own pending card like tapping Add (flag
  # RISI_CALL_VOICE_CONFIRM); Risi says "Added" only from the write's own done row.
  defp write_status(ctx, :confirm) do
    write = Map.get(ctx, :write)

    with %{request_id: rid} <- write,
         true <- VoiceConfirm.enabled?(ctx.user_id),
         %{state: "pending"} <- WriteTruth.state(rid) do
      say(ctx, Fillers.adding(), :filler)

      case VoiceConfirm.confirm(rid, ctx) do
        {:done, _wid} ->
          if RisiMe.Agent.ClaimGuard.done?(rid),
            do: say_text(ctx, WriteTruth.added_line(write[:summary], today(ctx))),
            else: say_text(ctx, WriteTruth.status_line(write, :complaint))

        {:failed, _} ->
          say_text(
            ctx,
            "That didn't go through, so it isn't added. Tap Add on the card in your Risi chat to try again."
          )

        {:not_allowed, _} ->
          say_text(ctx, WriteTruth.status_line(write, :confirm))
      end
    else
      _ -> say_text(ctx, WriteTruth.status_line(write, :confirm))
    end
  end

  # "I can't see it": re-checks the write's row and says what it finds; never insists.
  defp write_status(ctx, why),
    do: say_text(ctx, WriteTruth.status_line(Map.get(ctx, :write), why))

  # A correction of the card being made goes to the write turn as a change of it (the turn's
  # action draft patches the pending draft; the old card is superseded).
  defp write_text(text, route, :correction) when route in ~w(calendar_write reminder) do
    # The words name a write ("add"), so the turn never reads them as a schedule question.
    noun = if route == "reminder", do: "reminder", else: "event"
    "Change the #{noun} I just asked you to add: " <> text
  end

  defp write_text(text, "calendar_write", {:day_for, pending}), do: pending <> " " <> text
  defp write_text(text, _route, _range), do: text

  ## Routing (with the call's topic)

  @caps_re ~r/\bwhat\s+(?:else\s+)?(?:can|could|do)\s+you\s+(?:do|help)\b|\bwhat\s+are\s+you\s+able\b|\bhow\s+can\s+you\s+help\b|\bwhat\s+(?:are\s+)?your\s+(?:features|capabilities|skills|abilities)\b|\bwhat\s+can\s+i\s+ask\b|\bwhat\s+do\s+you\s+do\b|\bwhat\s+are\s+you\s+capable\b/iu

  @complaint_re ~r/\b(?:why|how\s+come)\b.{0,40}\b(?:can'?t|cannot|couldn'?t|didn'?t|don'?t|won'?t|not|no)\b|\b(?:try|check|look)\s+again\b|\b(?:what|how)\s+about\b|\banything\s+else\b|\bwhat\s+else\b|\bshould\s+(?:have|be\s+able)\b|\bhave\s+access\b|\byou\s+(?:can|could|do)\s+(?:see|access|read)\b/iu

  # Risi's OWN data (Harsha's 2nd call: "Did I promise anything today or tomorrow?" got "I don't
  # have access to your personal commitments"): always a tool turn, never the chat route.
  @write_words ~r/\b(?:add\w*|entry|event|appointment|meeting|card|reminder|booked|saved|scheduled|put\s+it)\b/iu

  @own_items ~r/\b(?:promis\w*|commitments?|committed|reminders?|follow[- ]?ups?|owe|pending|to-?dos?|tasks?)\b/iu

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

    own? = Regex.match?(@own_items, text) and route in ~w(chat planner calendar_read ledger_items)
    window = Spoken.window(text)
    write = Map.get(ctx, :write)
    write_last? = match?(%{last?: true}, write)
    reads? = route == "calendar_read" and ScheduleIntent.schedule_words?(text)

    ask = Map.get(ctx, :write_ask)

    cond do
      Regex.match?(@caps_re, text) and route in ~w(chat planner) ->
        {"capabilities", nil}

      # The answer to "Which day should I put it on?".
      is_binary(ask) and ScheduleIntent.day_named?(text, today) and not reads? ->
        {"calendar_write", {:day_for, ask}}

      # Still no day, more details: asked again with all the words.
      is_binary(ask) and WriteTruth.correction?(text) and not reads? ->
        {"calendar_write", :ask_day_more}

      # Harsha's 3rd call (15 Oct 14:16Z): "Rishi, I can't see that you have added something."
      WriteTruth.complaint?(text) and not reads? and
          (write_last? or Regex.match?(@write_words, text)) ->
        {"write_status", :complaint}

      # "That's correct" / "Yeah, that's for you, just add that entry" after the write turn.
      write_last? and WriteTruth.confirmation?(text) and not WriteTruth.correction?(text) and
          route in ~w(chat planner calendar_write reminder) ->
        {"write_status", :confirm}

      # "I don't know. From 9 to 10. discuss about racing": changes the card being made.
      write_last? and WriteTruth.correction?(text) and not reads? and
          route in ~w(chat planner calendar_write reminder calendar_read) ->
        # (a full write request of its own goes as said; the card's draft keeps its day)
        if route == write.route,
          do: {route, :as_said},
          else: {write.route, :correction}

      # "Did I promise anything today or tomorrow?": Risi's items in that range (the read lists
      # them with the phone's times; the voice speaks only Risi's items).
      own? and ScheduleIntent.day_named?(text, today) ->
        {"calendar_read", Map.put(ScheduleIntent.range(text, today), :items_only, true)}

      own? and route in ~w(chat planner) ->
        {"ledger_items", nil}

      route == "calendar_read" ->
        r = ScheduleIntent.range(text, today)

        if r.now? and cal_topic? and (Regex.match?(@complaint_re, text) or window != nil),
          do: {"calendar_read", with_window(topic.range, window)},
          else: {"calendar_read", with_window(r, window)}

      # "You should have access, you add the notes and calendar" (a complaint, not a write).
      cal_topic? and route == "calendar_write" and Regex.match?(@complaint_re, text) and
          not ScheduleIntent.day_named?(text, today) ->
        {"calendar_read", topic.range}

      # "Do I have anything from 2 am until 10 pm?" after a calendar answer: that window of the
      # topic's day (Harsha's 3rd call; was "just ask me directly").
      cal_topic? and route in ~w(chat planner message_write) and
          (follow_up?(text, today) or window != nil) ->
        if ScheduleIntent.day_named?(text, today),
          do: {"calendar_read", with_window(ScheduleIntent.range(text, today), window)},
          else: {"calendar_read", with_window(topic.range, window)}

      true ->
        {route, nil}
    end
  end

  defp with_window(range, nil), do: Map.delete(range, :window)
  defp with_window(range, w), do: Map.put(range, :window, w)

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
        catch
          _, _ -> DateTime.to_date(now)
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
        catch
          _, _ -> []
        end
    end
  end

  @no_access ~r/\b(?:don'?t|do\s+not|doesn'?t)\s+have\s+access\b|\b(?:can'?t|cannot|unable\s+to)\s+(?:access|see|view|check|read)\b|\bno\s+access\b|\bdon'?t\s+have\s+(?:your|any)\s+(?:personal|calendar|commitments|promises)/iu

  @doc false
  # A chat piece that claims Risi has no access to the user's own things is false: replaced.
  def no_access_guard(piece) do
    if Regex.match?(@no_access, piece),
      do:
        "I can check that for you: just ask me directly, for example, what did I promise today, " <>
          "or what's on my calendar tomorrow.",
      else: piece
  end

  @doc false
  # P0 (Harsha's 3rd call): the chat route has no tools, so a piece that claims or promises a
  # write is false: the first is replaced by the truth (the call's last card, from its row) and
  # the rest of that answer is dropped.
  def guard(ctx, piece) do
    claimed = {__MODULE__, :claimed}

    cond do
      Process.get(claimed) ->
        nil

      WriteTruth.claim?(piece) ->
        Process.put(claimed, true)
        RisiMe.Agent.ClaimGuard.violation("call_chat")
        WriteTruth.status_line(Map.get(ctx, :write), :claim)

      true ->
        no_access_guard(piece)
    end
  end

  ## The chat route: risi-l1 streamed, cut into pieces as it comes.

  defp chat_turn(ctx, text) do
    messages =
      [%{"role" => "system", "content" => @system <> " " <> now_line(ctx)}] ++
        Enum.take(ctx.history, -12) ++ [%{"role" => "user", "content" => text}]

    key = {__MODULE__, :buf}
    Process.put(key, {"", true})
    Process.delete({__MODULE__, :claimed})

    on_delta = fn delta ->
      {buf, first?} = Process.get(key)
      {pieces, rest} = Sentences.feed(buf, delta, first?)
      Enum.each(pieces, &say(ctx, guard(ctx, &1), :answer))
      Process.put(key, {rest, first? and pieces == []})
    end

    opts = [conversation_id: ctx.conv, call_id: ctx.call_id]
    t0 = System.monotonic_time(:millisecond)

    case LLMStream.run(messages, opts, on_delta) do
      {:ok, r} ->
        report(ctx, {:first_token, r.first_token_ms || System.monotonic_time(:millisecond) - t0})
        {buf, _} = Process.get(key)
        Enum.each(Sentences.flush(buf), &say(ctx, guard(ctx, &1), :answer))
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
    if Map.get(ctx, :filler?, true),
      do: say(ctx, Fillers.pick(route, lang, ctx.filler_n), :filler)

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
      |> then(fn e ->
        if is_map(range), do: Map.put(e, "__schedule", Map.delete(range, :window)), else: e
      end)
      |> then(fn e ->
        if route == "ledger_items", do: Map.put(e, "__intent", "risi_items"), else: e
      end)

    if route == "calendar_read" and is_map(range),
      do: report(ctx, {:topic, %{route: route, range: Map.delete(range, :window)}})

    # Harsha's 3rd call: the call's write (its card's truth is read by this request id).
    write = if route in @write_routes, do: %{request_id: request_id, route: route, last?: true}
    report(ctx, {:write, write})

    # §41.8: the phone's titles for this one answer, asked at once (in parallel with the turn).
    cal = if route == "calendar_read" and is_map(range), do: cal_request(ctx, range)

    # Linked: when the session kills this task (barge-in, a new question) the §25 turn dies too
    # and its per-user lock (§25.6) is released. Trapping exits keeps a crash of the turn honest.
    Process.flag(:trap_exit, true)
    deadline = System.monotonic_time(:millisecond) + tool_wait_ms()
    inner = start_inner(ctx, env, request_id)

    ctx =
      Map.merge(ctx, %{
        cal: cal,
        items_only: is_map(range) and range[:items_only] == true,
        window: if(is_map(range), do: range[:window]),
        this_write: write
      })

    # One short "Still checking…" when the tool takes over 4 s.
    Process.send_after(self(), {:still_checking, request_id}, still_ms())

    wait_answer(
      %{ctx: ctx, rid: request_id, env: env, inner: inner, deadline: deadline},
      route,
      text,
      nil
    )
  end

  # Contract v1.52 §41.8: only for a device with `call_risi_cal` (the session's `cal?`). The
  # session sends the Port command; the phone's reply comes back as `{:call_risi_cal, ev}`.
  defp cal_request(ctx, range) do
    if Map.get(ctx, :cal?) == true do
      now = Clock.now()
      tz = Clock.user_tz(ctx.user_id)

      from =
        if range[:now?],
          do: now,
          else: Clock.to_utc(NaiveDateTime.new!(range.from, ~T[00:00:00]), tz)

      to = Clock.to_utc(NaiveDateTime.new!(range.to, ~T[00:00:00]), tz)
      max = DateTime.add(from, 8 * 86_400, :second)
      to = if DateTime.compare(to, max) == :gt, do: max, else: to
      off = PhoneCalendar.offset_s(now, Clock.local(now, tz))

      case PhoneCalendar.request(from, to, offset_s: off) do
        {:ok, cmd} ->
          report(ctx, {:cal_req, cmd})
          %{cmd: cmd, t0: System.monotonic_time(:millisecond)}

        _ ->
          nil
      end
    end
  catch
    _, _ -> nil
  end

  # The phone's events for this answer (at most the 2 s since the request), or an error.
  defp cal_events(%{cmd: cmd, t0: t0}) do
    left = max(PhoneCalendar.timeout_ms() - (System.monotonic_time(:millisecond) - t0), 0)
    PhoneCalendar.await(cmd, left)
  end

  defp cal_events(_), do: {:error, "off"}

  # `(start_utc, end_utc) -> title | nil` over the phone's events (same start and end; else the
  # same start).
  defp titles_fn({:ok, events}) when events != [] do
    unix = fn s ->
      case DateTime.from_iso8601(to_string(s)) do
        {:ok, d, _} -> DateTime.to_unix(d)
        _ -> nil
      end
    end

    exact = Map.new(events, &{{DateTime.to_unix(&1.start), DateTime.to_unix(&1.end)}, &1.title})
    starts = Map.new(events, &{DateTime.to_unix(&1.start), &1.title})
    fn s, e -> exact[{unix.(s), unix.(e)}] || starts[unix.(s)] end
  end

  defp titles_fn(_), do: nil

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
      {:still_checking, ^rid} ->
        say(ctx, Fillers.still(), :filler)
        wait_answer(w, route, question, facts)

      {:risi_voice_facts, ^rid, f} ->
        wait_answer(w, route, question, f)

      # Harsha's 3rd call: the confirm card this turn made (its summary is spoken, never "added").
      {:risi_voice_card, ^rid, summary} ->
        if this = Map.get(ctx, :this_write),
          do: report(ctx, {:write, Map.put(this, :summary, summary)})

        wait_answer(w, route, question, {:card, summary})

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
        case facts do
          {:card, summary} -> say_text(ctx, card_line(ctx, summary))
          _ -> say(ctx, "I've put that in your Risi chat.", :answer)
        end

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

  defp speak_answer(ctx, _answer, {:card, summary}, _question) do
    say_text(ctx, card_line(ctx, summary))
  end

  defp speak_answer(ctx, _answer, %{ok?: _} = facts, question) do
    # "Did I promise anything today or tomorrow?": only Risi's own items of the read.
    facts =
      if Map.get(ctx, :items_only) and facts.ok?, do: Spoken.items_only(facts), else: facts

    # "Anything from 2 am until 10 pm?": only that window of the day.
    facts =
      if facts.ok? and Map.get(ctx, :window), do: Map.put(facts, :window, ctx.window), else: facts

    # §41.8: the phone's titles live only in this process, for this one answer.
    cal = if facts.ok?, do: cal_events(Map.get(ctx, :cal)), else: {:error, "off"}
    titles = titles_fn(cal)
    {rendered, missing?} = Spoken.render(facts, titles: titles)
    log = if Map.get(ctx, :cal), do: PhoneCalendar.log_fields(cal), else: %{}

    text =
      if facts.ok?,
        do:
          phrase(
            ctx,
            rendered,
            question,
            Spoken.titles(facts, titles),
            log,
            Map.get(facts, :items_only) == true
          ),
        else: rendered

    note =
      if missing? and once(ctx, :titles_note, fn -> :ok end),
        do: " " <> PhoneCalendar.times_only_note(),
        else: ""

    # What the transcript keeps: never a title from the phone (the times-only rendering, then
    # PhoneCalendar's redaction as a second guard; `titles_spoken` marks the line).
    stored =
      if Spoken.titled?(facts, titles) do
        {:ok, events} = cal
        times = elem(Spoken.render(facts, titles: nil), 0) <> note
        {safe, _} = PhoneCalendar.redact(times, events)
        %{text: safe, titles_spoken: true}
      end

    say_text(ctx, text <> note, stored)
  end

  defp speak_answer(ctx, answer, _facts, _question) do
    case Spoken.speakable(answer) do
      "" ->
        say(ctx, "I've put that in your Risi chat.", :answer)

      text ->
        # The claim guard (the turn guarded its answer already; the call checks again with the
        # write's own status line).
        case Map.get(ctx, :this_write) do
          %{request_id: rid} = this ->
            honest = WriteTruth.status_line(this, :claim)
            {safe, _} = RisiMe.Agent.ClaimGuard.guard(text, rid, "call_answer", honest: honest)
            say_text(ctx, short(safe))

          _ ->
            say_text(ctx, short(text))
        end
    end
  end

  # "Shall I add it?" only for a card a voice yes can confirm (§41.9: Risi Calendar, reminders).
  defp card_line(ctx, summary) do
    voice? =
      VoiceConfirm.enabled?(ctx.user_id) and
        String.starts_with?(summary, ["Add to your Risi Calendar:", "Remind you "])

    WriteTruth.card_line(summary, today(ctx), voice?)
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
  defp phrase(ctx, rendered, question, titles, log_meta, items_only?) do
    buf = {__MODULE__, :phrase}
    Process.put(buf, "")

    opts = [
      conversation_id: ctx.conv,
      call_id: ctx.call_id,
      max_tokens: 160,
      timeout: 8_000,
      log_meta: log_meta
    ]

    said =
      case LLMStream.run(Spoken.compose_messages(rendered, question, items_only?), opts, fn d ->
             Process.put(buf, Process.get(buf) <> d)
           end) do
        {:ok, _} -> Process.get(buf)
        _ -> nil
      end

    Spoken.checked(said, rendered, titles) || rendered
  catch
    _, _ -> rendered
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
      catch
        _, _ -> {now, "UTC"}
      end

    "It is now #{Calendar.strftime(local, "%A %d %B %Y, %H:%M")} (#{tz}). Use this for the time and date; " <>
      "you know nothing else about the user's day unless they tell you."
  end

  defp turn_impl, do: Application.get_env(:risime, :risi_call_turn, RisiMe.Agent.Turn)

  defp say(_ctx, nil, _kind), do: :ok
  defp say(ctx, text, kind), do: report(ctx, {:piece, text, kind})

  defp report(ctx, event), do: send(ctx.pid, {:brain, ctx.utt, event})
end
