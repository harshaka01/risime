defmodule RisiMe.Agent.CallRisi.BrainV3 do
  @moduledoc """
  **Call Risi brain v3** (architect P0 2026-10-16 09:40 SLT, after Harsha's call 75ec8683):
  understanding by the model, not keyword rules. Behind `RISI_CALL_BRAIN` (`off | canary | on`,
  plus `v3` = on and `legacy` = off; default `canary` = `RISI_DEV_USERS`); the legacy brain
  (`RisiMe.Agent.CallRisi.Brain.legacy_answer/3`) is unchanged and is also the fallback when the
  decision model fails.

  Per turn (`answer/3`, inside the Brain task the session owns):

    1. **Fragments (C):** words that don't end a thought ("Do you have anything", "… and") are
       held up to 1.2 s (`:risi_call_hold_ms`): words that follow merge into them (the session
       kills this turn and starts one with both), and a fragment is never acted on alone.
    2. **One model decision (A)** (`RisiMe.Agent.CallRisi.Decider`): the last turns, the open state
       (`RisiMe.Agent.CallRisi.Dialogue`: the open card, what Risi waits for), the date and zone.
       While it thinks, a neutral "One moment…" after 700 ms (D); the calendar filler only once a
       calendar tool was chosen.
    3. **The decision runs**, checked only for safety: a message's text must be the caller's own
       words (else Risi asks "What should I say to <name>?"; B); "cancel that" cancels the open
       card for real (`RisiMe.Agent.Writes.cancel_request/2`), and a new request in the same
       words still runs; a voice yes never sends a message (§41.9); every spoken reply passes
       the claim guard (F).

  Regex keeps only: "Risi, stop" (the session), the filler choice and the safety checks.
  """

  alias RisiMe.Agent.CallRisi.{Brain, Decider, Dialogue, Reads, Sentences, Spoken, WriteTruth}
  alias RisiMe.Agent.{Clock, FindContact, ScheduleIntent}

  @flag "RISI_CALL_BRAIN"

  @doc "The flag's name."
  def flag, do: @flag

  @doc "True when the v3 brain answers `user`'s calls."
  def on_for?(user) do
    case RisiMe.Agents.Flags.feature(@flag) do
      m when m in [:on, :v3] -> true
      :canary -> RisiMe.Agents.Flags.dev_user?(user)
      _ -> false
    end
  rescue
    _ -> false
  end

  defp hold_ms, do: Application.get_env(:risime, :risi_call_hold_ms, 1_200)
  defp neutral_ms, do: Application.get_env(:risime, :risi_call_neutral_ms, 700)
  defp decide_ms, do: Application.get_env(:risime, :risi_call_decide_ms, 9_000)

  @doc "One turn (see the moduledoc)."
  def answer(ctx, text, lang) do
    d = ctx |> Map.get(:dialogue) |> Dialogue.get() |> fresh_card()

    case Map.get(ctx, :dictated) do
      %{} = dict ->
        dictated(Map.put(ctx, :heard, text), d, dict, lang)

      nil ->
        hold(ctx, text)
        ctx = Map.put(ctx, :heard, text)

        # Item 7 (Harsha's 04:0x call): "No", "That's not true", "I mean…", a spelled name: what Risi
        # waited for is dropped and the model decides again from the whole utterance (a person
        # picked from the offered names still counts; a message's text stays).
        d =
          if Dialogue.correction?(text) and
               not match?(
                 %{slot: s} when s in ~w(person_pick send_confirm fix_confirm message_confirm),
                 d.awaiting
               ),
             do: %{d | awaiting: nil, dictation: nil},
             else: d

        case answer_pending_or_here(ctx, d, text, lang) do
          :model -> answer_model(ctx, d, text, lang)
          :done -> :ok
        end
    end
  end

  ## Dictation (Harsha's call 22e016e0): the session took the message down; this is its end

  # The session's dictation ended (an end signal, or silence): the composed text is the task's
  # text (the caller's words; checked against what they said, never invented), then the task's
  # next step. Misheard words: Risi offers the fix first ("Shall I fix them?").
  defp dictated(ctx, d, dict, lang) do
    report(ctx, {:decided, {:v3, "dictate", "end"}})
    report(ctx, {:route, "message_write"})
    raw = dict.raw |> String.trim()

    text =
      cond do
        dict.text != "" and Dialogue.grounded?(dict.text, raw) -> dict.text
        true -> Dialogue.strip_lead(raw)
      end
      |> Dialogue.strip_end()

    fixed =
      if dict.fixed != "" and norm_q(dict.fixed) != norm_q(text), do: dict.fixed

    # Item 2: the caller's sending instructions are never message text; a leading "tell her
    # that" is no message word either (replay 0605z).
    {text, _} = Dialogue.split_instructions(text)
    text = if text != "", do: Dialogue.strip_lead(text), else: text

    p = d.pending || Dialogue.message_task(%{to: dict[:to], tab: dict[:tab]})
    tab = if dict[:tab] == "official", do: "official", else: p.tab
    p = %{p | text: text, fixed: fixed, tab: tab, send_now: dict[:send_now] == true or p.send_now}
    d = %{d | pending: p, dictation: nil}

    cond do
      text == "" ->
        ask(ctx, d, Dialogue.ask_text(p.to), %{slot: "message_text", to: p.to})

      is_binary(fixed) ->
        ask(ctx, d, Dialogue.fix_question(fixed), %{slot: "fix_confirm"})

      true ->
        run_message(ctx, d, p, false, lang)
    end
  end

  defp answer_pending_or_here(ctx, %{awaiting: nil} = _d, text, _lang) do
    if Dialogue.non_answer?(text) do
      report(ctx, {:decided, {:v3, "none", "reply"}})
      report(ctx, {:route, "chat"})
      Brain.say_text(ctx, "I'm here. How can I help?")
      done(ctx)
      :done
    else
      :model
    end
  end

  defp answer_pending_or_here(ctx, d, text, lang), do: answer_pending(ctx, d, text, lang)

  # The open question about the pending task, answered without the model when the answer is
  # plain (architect 11:45): a non-answer ("Hello?") keeps the task and says what Risi heard; a
  # yes (or the name) confirms the read-back; one of the names offered picks it.
  defp answer_pending(
         ctx,
         %{awaiting: %{slot: "person_pick", options: os, tool: t}} = d,
         text,
         lang
       ) do
    case Dialogue.pick_option(text, os) do
      %{name: _} = person ->
        report(ctx, {:decided, {:v3, t, "tool"}})
        report(ctx, {:route, "messages_read"})
        d = %{d | awaiting: nil}
        dec = %{Decider.normalize(%{"tool" => t, "action" => "tool"}) | to: person.name}
        tool(ctx, dec, d, person.name, lang, nil)
        :done

      nil ->
        answer_pending(ctx, %{d | awaiting: nil}, text, lang)
    end
  end

  defp answer_pending(ctx, %{awaiting: %{} = a} = d, text, lang) do
    cond do
      Dialogue.non_answer?(text) ->
        report(ctx, {:decided, {:v3, "none", "ask"}})
        report(ctx, {:route, "chat"})
        Brain.say_text(ctx, Dialogue.heard_line(text, d))
        done(ctx)
        :done

      a[:slot] == "message_confirm" and is_map(d.pending) and
          (Dialogue.yes?(text) or names_to?(text, d.pending.to)) ->
        report(ctx, {:decided, {:v3, "message_draft", "tool"}})
        report(ctx, {:route, "message_write"})
        draft(ctx, d, d.pending, lang)
        :done

      a[:slot] == "message_confirm" and is_map(d.pending) and Dialogue.no?(text) ->
        report(ctx, {:decided, {:v3, "none", "ask"}})
        report(ctx, {:route, "chat"})

        ask(ctx, d, "Okay. What should I change: who it's for, or what it says?", %{
          slot: "answer"
        })

        :done

      # v1.55 §41.11: "Send it?" after the read-back of an Official message: a clear yes sends.
      a[:slot] == "send_confirm" and Dialogue.yes?(text) ->
        report(ctx, {:decided, {:v3, "confirm_open", "tool"}})
        report(ctx, {:route, "write_status"})
        voice_send(ctx, d)
        :done

      a[:slot] == "send_confirm" and Dialogue.no?(text) ->
        report(ctx, {:decided, {:v3, "none", "reply"}})
        report(ctx, {:route, "chat"})
        report(ctx, {:dialogue, %{d | awaiting: nil}})
        send_record(ctx, "declined")

        Brain.say_text(
          ctx,
          "Okay, I won't send it. It stays on the card in your Risi chat, or tell me what to change."
        )

        done(ctx)
        :done

      # "Shall I fix the misheard words?"
      a[:slot] == "fix_confirm" and is_map(d.pending) and Dialogue.fix_yes?(text) ->
        report(ctx, {:decided, {:v3, "message_draft", "tool"}})
        report(ctx, {:route, "message_write"})
        p = %{d.pending | text: d.pending.fixed || d.pending.text, fixed: nil}
        run_message(ctx, %{d | pending: p, awaiting: nil}, p, false, lang)
        :done

      a[:slot] == "fix_confirm" and is_map(d.pending) and Dialogue.no?(text) ->
        report(ctx, {:decided, {:v3, "message_draft", "tool"}})
        report(ctx, {:route, "message_write"})
        p = %{d.pending | fixed: nil}
        run_message(ctx, %{d | pending: p, awaiting: nil}, p, false, lang)
        :done

      a[:slot] == "message_pick" and is_map(d.pending) and
          pick(d.pending, d.pending.to_candidates, text).to != nil ->
        report(ctx, {:decided, {:v3, "message_draft", "tool"}})
        report(ctx, {:route, "message_write"})
        p = pick(d.pending, d.pending.to_candidates, text)
        run_message(ctx, %{d | pending: p}, p, false, lang)
        :done

      true ->
        :model
    end
  end

  defp answer_pending(_ctx, _d, _text, _lang), do: :model

  # B: while Risi waits for a message's recipient or text, the caller's words answer it. A model
  # that talked back instead ("Got it. What should the message say?") continues the task: the
  # words are the name (Risi asked who) or the text (Risi asked what). Words not meant for Risi
  # (no reply) and a tool or a cancel are the model's call.
  @doc false
  # A message decision written as a question is still the message tool (Risi asks for the slot).
  @cancel_words ~r/\b(?:cancel|never\s*mind|forget\s+(?:it|that|the|this)|scratch\s+that|start\s+(?:again|over)|stop|drop\s+it|don'?t\s+send)\b/iu

  # The model may set cancel_open on a new request without a cancel word ("Yeah, I have sent a
  # draft to …" dropped the task with its text): a cancel needs the caller's cancel words.
  def in_task(%{cancel_open: true} = dec, d, text)
      when is_binary(text) do
    if Regex.match?(@cancel_words, text),
      do: in_task_(dec, d, text),
      else: in_task_(%{dec | cancel_open: false}, d, text)
  end

  def in_task(dec, d, text), do: in_task_(dec, d, text)

  @doc false
  def in_task_(%{tool: t, action: a} = dec, d, text)
      when a != "tool" and
             t in ~w(message_draft dictate read_messages replied_since promises_by my_promises
                     unsupported undo_send sources),
      do: in_task_(%{dec | action: "tool", say: ""}, d, text)

  # The model named the tool of the open state but filled a new message ("cancel that and start
  # a new message … just say …" came back as cancel_open with the text; the name answering "who"
  # as confirm_open): the fields it filled are the request.
  def in_task_(%{tool: "cancel_open", text: t} = dec, d, text) when t != "",
    do: in_task_(%{dec | tool: "message_draft", action: "tool", cancel_open: true}, d, text)

  # "Cancel that" written as a reply: the cancel runs (the model's words never claim it).
  # … but while Risi waits for who it is for or what it says, a "reply" is the answer (22e016e0:
  # "Yeah, I have sent a draft to Senamali" came back as a cancel written as a reply).
  def in_task_(%{tool: "cancel_open", action: a} = dec, %{awaiting: %{slot: s}} = d, text)
      when a != "tool" and s in ~w(message_to message_pick message_text),
      do: in_task2(%{dec | tool: "none", cancel_open: false}, d, text)

  def in_task_(%{tool: "cancel_open", action: a} = dec, d, text) when a != "tool",
    do: in_task_(%{dec | action: "tool", cancel_open: true, say: ""}, d, text)

  # "Send it to work" about a Private message card, written as a status or yes: the same message
  # again for the Official chat.
  def in_task_(%{tool: t, tab: "official"} = dec, %{card: %{kind: "message"} = c} = d, text)
      when t in ~w(card_status confirm_open) do
    if c[:tab] == "official",
      do: in_task2(dec, d, text),
      else: official_again(dec, c, d, text)
  end

  # "Forget it. Tell Nimali … instead": a new request after a cancel word cancels the open card
  # (the cancel the session already knows as "Risi, stop"/"cancel that"; architect 09:40 B).
  def in_task_(%{tool: t, cancel_open: false} = dec, %{card: %{}} = d, text)
      when t in ~w(message_draft calendar_add reminder_add) do
    if Regex.match?(
         ~r/^\W*(?:no,?\s+)?(?:cancel(?:\s+(?:that|it|this))?|forget\s+(?:it|that)|never\s*mind|scratch\s+that)\b/iu,
         text
       ),
       do: in_task_(%{dec | cancel_open: true}, d, text),
       else: in_task2(dec, d, text)
  end

  def in_task_(dec, d, text), do: in_task2(dec, d, text)

  @doc false
  def in_task2(%{tool: "confirm_open", text: t} = dec, %{card: %{kind: "message"} = c} = d, text)
      when t != "" do
    if norm_q(t) != norm_q(c[:text] || ""),
      do: in_task2(%{dec | tool: "message_draft", action: "tool"}, d, text),
      else: in_task2(%{dec | text: ""}, d, text)
  end

  def in_task2(%{tool: "confirm_open", to: to} = dec, %{awaiting: %{slot: s}} = d, text)
      when to != "" and s in ~w(message_to message_pick),
      do: in_task2(%{dec | tool: "message_draft", action: "tool"}, d, text)

  # Safety: a bare "yeah" with nothing open (no card, no task, no question) repeats no write.
  def in_task2(%{tool: t} = dec, %{card: nil, pending: nil, awaiting: nil}, text)
      when t in ~w(calendar_add reminder_add message_draft) do
    if Dialogue.yes?(text) and length(String.split(text)) <= 2,
      do: %{dec | action: "reply", tool: "none", say: "Okay.", cancel_open: false},
      else: dec
  end

  def in_task2(
        %{action: a, tool: "none", cancel_open: false} = dec,
        %{awaiting: %{slot: slot}},
        text
      )
      when slot in ~w(message_to message_pick) and
             (a == "ask" or (a == "reply" and dec.say != "")) do
    %{dec | action: "tool", tool: "message_draft", say: "", to: name_words(text), text: ""}
  end

  def in_task2(dec, _d, _text), do: dec

  defp official_again(dec, c, d, text) do
    to = if dec.to != "", do: dec.to, else: c[:to] || ""
    body = if dec.text != "", do: dec.text, else: c[:text] || ""
    in_task(%{dec | tool: "message_draft", action: "tool", to: to, text: body}, d, text)
  end

  defp name_words(text) do
    t =
      text
      |> String.trim()
      |> String.replace(
        ~r/^(?:(?:it'?s|it is|send it|the message is|this is)\s+)?(?:to|for)\s+/iu,
        ""
      )
      |> String.trim(" .,!?")

    if length(String.split(t)) <= 4, do: t, else: ""
  end

  defp names_to?(text, to) when is_binary(to) do
    t = RisiMe.Agent.NameCheck.norm(text)
    first = to |> RisiMe.Agent.NameCheck.norm() |> String.split() |> List.first()
    first != nil and length(String.split(t)) <= 4 and String.contains?(t, first)
  end

  defp names_to?(_, _), do: false

  # The model fills "tab" on every decision; it counts only when the caller named the chat
  # ("send it to work", "on the office chat", "privately"); else the open task's tab stays.
  @official_words ~r/\b(?:work|office|official|officially)\b/iu
  @private_words ~r/\b(?:private|privately|personal|personally)\b/iu

  @doc false
  def said_tab(%{tab: "official"} = dec, text),
    do: if(Regex.match?(@official_words, text), do: dec, else: %{dec | tab: ""})

  def said_tab(%{tab: "private"} = dec, text),
    do: if(Regex.match?(@private_words, text), do: dec, else: %{dec | tab: ""})

  def said_tab(dec, _text), do: dec

  defp answer_model(ctx, d, text, lang) do
    input = %{
      now: now_text(ctx),
      tz: tz(ctx),
      dialogue: d,
      history: Map.get(ctx, :history) || [],
      text: text
    }

    case decide(ctx, input) do
      {{:ok, dec}, neutral?} ->
        dec = dec |> insisted(ctx, input, text) |> said_tab(text) |> in_task(d, text)
        report(ctx, {:decided, {:v3, dec.tool, dec.action}})
        act(Map.put(ctx, :neutral?, neutral?), dec, d, text, lang)

      {{:error, _}, neutral?} ->
        # The model failed: the legacy brain answers this turn (no second holding line).
        Brain.legacy_answer(Map.put(ctx, :filler?, not neutral?), text, lang)
    end
  end

  # Golden call-2 t3: "You should have access, you add the notes and calendar" right after a read
  # is the caller insisting, not asking what Risi can do: the read they asked before runs again.
  @insist ~r/\byou\s+(?:should|do|must|already|definitely)?\s*(?:have|got)\s+(?:the\s+)?access\b|\byou\s+can\s+(?:see|read|access)\b|\byou\s+add(?:ed)?\s+(?:the|my|all)\b/iu
  @reads ~w(calendar_read my_promises promises_by read_messages replied_since)

  @doc false
  def insisted(%{tool: t} = dec, ctx, input, text) when t in ~w(capabilities sources none) do
    prev =
      for(%{"role" => "user", "content" => c} <- input.history, is_binary(c), c != text, do: c)
      |> List.last()

    with true <- Regex.match?(@insist, text),
         true <- is_binary(prev),
         {{:ok, %{tool: r} = again}, _} when r in @reads <- decide(ctx, %{input | text: prev}) do
      again
    else
      _ -> dec
    end
  end

  def insisted(dec, _ctx, _input, _text), do: dec

  # C: a cut-off utterance waits for the words that follow (the session restarts the turn with
  # both). A turn that already merged a prefix isn't held again.
  defp hold(ctx, text) do
    punctuated? = Map.get(ctx, :stt_model) != "phone"

    if Map.get(ctx, :held?) != true and Dialogue.unfinished?(text, punctuated?),
      do: Process.sleep(hold_ms())
  end

  # The open card only while its row is still pending (a newer request supersedes it, a tap
  # sends or cancels it).
  defp fresh_card(%{card: %{request_id: rid}} = d) do
    case WriteTruth.state(rid) do
      %{state: "pending"} -> d
      _ -> %{d | card: nil}
    end
  end

  defp fresh_card(d), do: d

  defp decide(ctx, input) do
    task = Task.async(fn -> Decider.run(input, ctx) end)

    case Task.yield(task, neutral_ms()) do
      {:ok, r} ->
        {r, false}

      nil ->
        # Architect 11:45: no "One moment…" while the model decides (a short question or a
        # confirmation follows at once); a tool says its own holding line when it starts.
        case Task.yield(task, decide_ms()) || Task.shutdown(task, :brutal_kill) do
          {:ok, r} -> {r, false}
          _ -> {{:error, :timeout}, false}
        end
    end
  end

  ## Acting on the decision

  @doc false
  # The route name the session and the turn rows know (chat = no tool, not "holding").
  def route(%{tool: "capabilities"}), do: "capabilities"
  def route(%{tool: "list_contacts"}), do: "contacts"
  def route(%{action: a}) when a in ~w(reply ask), do: "chat"
  def route(%{tool: "unsupported"}), do: "chat"
  def route(%{tool: "calendar_read"}), do: "calendar_read"
  def route(%{tool: "calendar_add"}), do: "calendar_write"
  def route(%{tool: "reminder_add"}), do: "reminder"
  def route(%{tool: t}) when t in ~w(my_promises promises_by), do: "ledger_items"
  def route(%{tool: t}) when t in ~w(read_messages replied_since), do: "messages_read"
  def route(%{tool: "sources"}), do: "chat"
  def route(%{tool: "message_draft"}), do: "message_write"
  def route(%{tool: "dictate"}), do: "dictation"

  def route(%{tool: t}) when t in ~w(cancel_open confirm_open card_status undo_send),
    do: "write_status"

  def route(_), do: "chat"

  defp act(ctx, %{tool: "capabilities", action: "tool"}, _d, _text, _lang) do
    report(ctx, {:route, "capabilities"})
    Brain.say_text(ctx, Spoken.capabilities(Brain.caps(ctx)))
    done(ctx)
  end

  # The model said to cancel but wrote a reply ("Okay, I've cancelled that."): the cancel runs for
  # real and Risi says the honest line (never the model's claim).
  defp act(ctx, %{cancel_open: true, action: "reply", tool: t} = dec, d, text, lang)
       when t in ~w(none cancel_open),
       do: act(ctx, %{dec | action: "tool", tool: "cancel_open"}, d, text, lang)

  # … or cancelled and asked a question ("No, no, no … you should ask what it should say"): the
  # cancel runs, then the question (its claiming sentences dropped).
  defp act(ctx, %{cancel_open: true, action: "ask", say: q} = dec, d, text, lang) when q != "" do
    {d, _} = cancel(ctx, d)
    act(ctx, %{dec | cancel_open: false}, d, text, lang)
  end

  defp act(ctx, %{action: "tool", tool: t} = dec, d, text, lang) when t != "none" do
    report(ctx, {:route, route(dec)})
    {d, cancelled} = if dec.cancel_open or t == "cancel_open", do: cancel(ctx, d), else: {d, nil}
    d = close_task(ctx, d, t)
    tool(ctx, dec, d, text, lang, cancelled)
  end

  defp act(ctx, %{action: "ask", say: q}, d, _text, _lang) when q != "" do
    report(ctx, {:route, "chat"})

    if repeat?(d, q) do
      Brain.say_text(ctx, Dialogue.heard_line(ctx[:heard] || "", d))
      done(ctx)
    else
      spoken = speak_guarded(ctx, q, d)
      report(ctx, {:dialogue, %{d | awaiting: %{slot: "answer", question: spoken}}})
      done(ctx)
    end
  end

  defp act(ctx, %{say: say}, d, text, _lang) do
    report(ctx, {:route, "chat"})

    # Thinking aloud ("Let me see. I need to listen to it.", Harsha's 2nd call) gets no answer
    # even when the model wrote one ("Take your time."): the existing thinking-aloud rule.
    # (A statement that only starts with "So …" is still said to Risi: 15:2x "So it is being
    # connected …".)
    ack? =
      length(String.split(say)) <= 6 and not String.contains?(say, "?") and
        not Regex.match?(~r/^\W*so\b/iu, text)

    cond do
      # "… Hello?" after a cut-off fragment: the caller checks Risi is there (never silence).
      say == "" and
          Regex.match?(~r/\b(?:hello|hi|are you there|can you hear me|risi|rishi)\W*$/iu, text) ->
        Brain.say_text(ctx, "I'm here. How can I help?")
        done(ctx)

      true ->
        act_reply(ctx, say, ack?, d, text)
    end
  end

  defp act_reply(ctx, say, ack?, d, text) do
    if say == "" or (ack? and Brain.addressed(text) == :ignore) do
      # Words not meant for Risi: silence (never "Was that for me?" from a guess).
      report(ctx, {:done, %{status: :ignored, request_id: nil}})
    else
      spoken = speak_guarded(ctx, say, d)

      awaiting =
        if String.ends_with?(spoken, "?"), do: %{slot: "answer", question: spoken}

      report(ctx, {:dialogue, %{d | awaiting: awaiting}})
      done(ctx)
    end
  end

  # Architect 16:05 item 2: a new request finishes the open task (a finished task never captures
  # the next words). The open card stays (it is in the Risi chat; "cancel that" still reaches it),
  # but nothing waits for an answer any more.
  @task_tools ~w(message_draft dictate confirm_open card_status cancel_open)

  # The day for a write Risi asked about ("Which day should I put it on?") is that write's answer.
  defp close_task(_ctx, %{awaiting: %{slot: "day"}} = d, t)
       when t in ~w(calendar_add reminder_add),
       do: d

  defp close_task(ctx, d, t) when t not in @task_tools do
    if d.pending != nil or d.awaiting != nil or d.dictation != nil do
      d = %{d | pending: nil, awaiting: nil, dictation: nil}
      report(ctx, {:dialogue, d})
      d
    else
      d
    end
  end

  defp close_task(_ctx, d, _t), do: d

  # "Cancel that": the open card is cancelled for real (its row), and what Risi waited for goes.
  defp cancel(ctx, %{card: %{request_id: rid, kind: kind}} = d) do
    correction(ctx, "cancel")
    result = cancel_impl().(ctx.user_id, rid)
    d = %{d | card: nil, awaiting: nil, pending: nil}
    report(ctx, {:dialogue, d})
    if kind != "message", do: report(ctx, {:write, nil})
    {d, if(result == :ok, do: kind, else: :gone)}
  end

  defp cancel(ctx, %{pending: %{}} = d) do
    d = %{d | awaiting: nil, pending: nil}
    report(ctx, {:dialogue, d})
    {d, "message"}
  end

  defp cancel(ctx, %{awaiting: %{}} = d) do
    d = %{d | awaiting: nil}
    report(ctx, {:dialogue, d})
    {d, :awaiting}
  end

  defp cancel(_ctx, d), do: {d, nil}

  defp cancel_impl do
    case Application.get_env(:risime, :risi_call_cancel) do
      f when is_function(f, 2) -> f
      _ -> &RisiMe.Agent.Writes.cancel_request/2
    end
  end

  defp tool(ctx, %{tool: "cancel_open"}, d, text, lang, cancelled) do
    line =
      case cancelled do
        k when k in ~w(message calendar reminder) -> Dialogue.cancelled_line(k)
        :gone -> "That card is already closed, so nothing will be sent or added."
        :awaiting -> "Okay, cancelled."
        nil -> "There's nothing open to cancel."
      end

    case after_cancel(text) do
      rest when is_binary(rest) and cancelled != nil ->
        Brain.say_text(ctx, if(cancelled == :awaiting, do: "Okay.", else: line))
        answer_model(Map.put(ctx, :heard, rest), d, rest, lang)

      _ ->
        Brain.say_text(ctx, line)
        done(ctx)
    end
  end

  defp tool(ctx, %{tool: "message_draft"} = dec, d, text, lang, _cancelled),
    do: message(ctx, dec, d, text, lang)

  defp tool(ctx, %{tool: "calendar_read"} = dec, d, text, lang, _c),
    do: calendar_read(ctx, dec, d, text, lang, false)

  defp tool(ctx, %{tool: "my_promises"} = dec, d, text, lang, _c) do
    today = Brain.today(ctx)

    # "Did I promise anything today or tomorrow?" (or a follow-up of that read): Risi's items of
    # that range (the read), under the promises line (never the calendar's: 15:35 bug 3).
    if ScheduleIntent.day_named?(text, today) or
         (dec.request != "" and ScheduleIntent.day_named?(dec.request, today)) or
         match?(%{route: "calendar_read", range: %{}}, Map.get(ctx, :topic)) do
      report(ctx, {:route, "calendar_read"})
      calendar_read(Map.put(ctx, :filler_route, "ledger_items"), dec, d, text, lang, true)
    else
      clear_awaiting(ctx, d)
      Brain.say_text(ctx, Reads.my_promises(ctx.user_id, tz: tz(ctx)))
      done(ctx)
    end
  end

  # Architect 15:35 B: promises other people made to the caller, from the ledger by owner.
  defp tool(ctx, %{tool: "promises_by"} = dec, d, text, _lang, _c) do
    with_person(ctx, d, dec.to, text, "promises_by", fn person ->
      Reads.promises_by(ctx.user_id, person, tz: tz(ctx))
    end)
  end

  # Architect 15:35 B: "Did Nimali reply?", "any new messages?": Official only, never Private.
  defp tool(ctx, %{tool: "read_messages"} = dec, d, text, _lang, _c) do
    private? = dec.tab == "private"
    opts = [tz: tz(ctx), now: Clock.now(), private?: private?]

    if dec.to == "" or (no_name?(dec.to) and Dialogue.focus(d) == nil) do
      Brain.say_text(ctx, Reads.read_messages(ctx.user_id, nil, opts))
      done(ctx)
    else
      with_person(ctx, d, dec.to, text, "read_messages", fn person ->
        Reads.read_messages(ctx.user_id, person, opts)
      end)
    end
  end

  # "Did Shenika reply?": her Official texts after his own last one in their 1:1.
  defp tool(ctx, %{tool: "replied_since"} = dec, d, text, _lang, _c) do
    if dec.to == "" and Dialogue.focus(d) == nil do
      Brain.say_text(ctx, Reads.read_messages(ctx.user_id, nil, tz: tz(ctx), now: Clock.now()))
      done(ctx)
    else
      with_person(ctx, d, dec.to, text, "replied_since", fn person ->
        Reads.replied_since(ctx.user_id, person, tz: tz(ctx), now: Clock.now())
      end)
    end
  end

  # Architect 04:20 item 8: "To which account are you reading?"
  defp tool(ctx, %{tool: "sources"}, _d, _text, _lang, _c) do
    Brain.say_text(ctx, Dialogue.sources_line())
    done(ctx)
  end

  # A long message: Risi listens until the caller is done (the session takes it down).
  defp tool(ctx, %{tool: "dictate"} = dec, d, text, _lang, _c) do
    p = d.pending || Dialogue.message_task()
    p = recipient(ctx, p, dec.to, d.card || %{}, text, d)
    p = %{p | tab: tab(dec.tab, p.tab || Dialogue.tab_for(d, p.to) || (d.card || %{})[:tab])}
    first = if dec.text != "" and Dialogue.grounded?(dec.text, text), do: [dec.text], else: []
    dict = %{to: p.to, tab: p.tab, asked?: false}
    d = %{d | pending: p, awaiting: nil, dictation: dict}
    report(ctx, {:dialogue, d})
    report(ctx, {:dictation, Map.put(dict, :parts, first)})

    line =
      if MapSet.member?(Map.get(ctx, :once, MapSet.new()), :dictation_hint),
        do: "Go ahead.",
        else: "Go ahead, I'm listening. Say \"that's it\" when you're done."

    report(ctx, {:once, :dictation_hint})
    Brain.say_text(ctx, line)
    done(ctx)
  end

  defp tool(ctx, %{tool: "unsupported"}, d, _text, _lang, _c) do
    line =
      if repeat?(d, Dialogue.unsupported_line()) or last_said?(ctx, "I can't do that yet"),
        do: "That one I can't do yet. Ask me about your calendar, messages or promises.",
        else: Dialogue.unsupported_line()

    Brain.say_text(ctx, line)
    done(ctx)
  end

  # v1.55 §41.11: "undo that" takes back the message Risi just sent (within 2 minutes).
  defp tool(ctx, %{tool: "undo_send"}, _d, _text, _lang, _c) do
    line =
      case send_impl().undo(ctx.user_id, :last) do
        {:ok, %{name: n}} -> "Undone: the message to #{first_name(n)} was deleted."
        {:error, :expired} -> "It's too late to undo that one: the 2 minutes have passed."
        _ -> "There's no message of mine to undo."
      end

    Brain.say_text(ctx, line)
    done(ctx)
  catch
    _, _ ->
      Brain.say_text(ctx, "Sorry, I couldn't undo that just now.")
      done(ctx)
  end

  defp tool(ctx, %{tool: t} = dec, d, text, lang, cancelled)
       when t in ~w(calendar_add reminder_add),
       do: write(ctx, dec, d, text, lang, cancelled)

  defp tool(
         ctx,
         %{tool: "confirm_open"},
         %{awaiting: %{slot: "message_confirm"}, pending: %{} = p} = d,
         _text,
         lang,
         _c
       ),
       do: draft(ctx, d, p, lang)

  defp tool(ctx, %{tool: "list_contacts"}, _d, _text, _lang, _c) do
    line =
      case FindContact.list(ctx.user_id, 10) do
        [] ->
          "I don't see anyone in your RisiMe contacts yet."

        names ->
          "Here are #{length(names)} of your contacts: #{Dialogue.or_names(names) |> String.replace(" or ", " and ")}."
      end

    Brain.say_text(ctx, line)
    done(ctx)
  end

  defp tool(ctx, %{tool: "confirm_open"} = dec, d, _text, _lang, _c) do
    send? = match?(%{slot: "send_confirm"}, d.awaiting)

    case d.card do
      # v1.55 §41.11: the yes to Risi's "Send it?" sends the Official message.
      %{kind: "message"} when send? ->
        voice_send(ctx, d)

      # 22e016e0 bug C: only when the caller asks Risi to send it now, and said honestly once.
      %{kind: "message"} = c ->
        line = Dialogue.private_tap_line(first_name(c[:to]))

        line =
          if send_on?(ctx),
            do: line <> " Or say \"send it to work\", and I'll send it in Official myself.",
            else: line

        if last_said?(ctx, "I can only draft it"),
          do: Brain.say_text(ctx, "It's on the card in your Risi chat: tap Send there."),
          else: Brain.say_text(ctx, line)

        done(ctx)

      %{} ->
        Brain.write_status(ctx, :confirm)
        done(ctx)

      nil ->
        # Nothing waits for a yes: the model's own words, if any.
        if dec.say != "", do: speak_guarded(ctx, dec.say, d), else: Brain.say_text(ctx, "Okay.")
        done(ctx)
    end
  end

  defp tool(ctx, %{tool: "card_status"}, d, _text, _lang, _c) do
    case d.card do
      %{kind: "message", request_id: rid} ->
        Brain.say_text(
          ctx,
          WriteTruth.status_line(%{request_id: rid, route: "message_write"}, :complaint)
        )

      _ ->
        Brain.write_status(ctx, :complaint)
    end

    done(ctx)
  end

  # A read about one person: the name looked up among the caller's people with the call's
  # context (find_contact); "her" = the person in focus; 2–3 near ones are asked once.
  defp with_person(ctx, d, said, text, tool, f) do
    said = String.trim(said || "", " .,!?")
    # Item 12: a name spelled in the words ("S-H-A-K-Y-A") is the name, by its letters.
    said = Dialogue.spelled_name(text) || said
    people = FindContact.people(ctx.user_id)

    said =
      if said == "" or no_name?(said), do: Dialogue.focus(d) || "", else: said

    case said == "" || contact(said, people, d, FindContact.recent(ctx.user_id)) do
      true ->
        q = "Who do you mean?"
        report(ctx, {:dialogue, %{d | awaiting: %{slot: "answer", question: q}}})
        Brain.say_text(ctx, q)

      {:one, %{user_id: id, name: n}} ->
        report(ctx, {:dialogue, Dialogue.add_person(d, n)})
        Brain.say_text(ctx, f.(%{user_id: id, name: n}))

      {:pick, xs} ->
        q = Dialogue.ask_pick(Enum.map(xs, & &1.name))

        if repeat?(d, q) do
          Brain.say_text(ctx, Dialogue.heard_line(ctx[:heard] || "", d))
        else
          a = %{slot: "person_pick", question: q, options: xs, tool: tool}
          report(ctx, {:dialogue, %{d | awaiting: a}})
          Brain.say_text(ctx, q)
        end

      :none ->
        Brain.say_text(ctx, "I couldn't find #{said} in your RisiMe contacts.")
    end

    done(ctx)
  end

  @doc false
  # find_contact with the call's context (architect 16:05 item 4): the pinned recipient (the
  # caller corrected to them), then the people of this call, win a near tie — and a near match
  # of them wins over another person's, unless the name heard is exactly that other person's.
  def contact(said, people, d, recent \\ []) do
    found = FindContact.find(said, people)
    asked = (d[:pending] || %{})[:to]
    call = Enum.uniq(Enum.reject([d[:pinned] | d[:people] || []] ++ [asked], &is_nil/1) ++ recent)

    in_call =
      Enum.find_value(call, fn name ->
        Enum.find(found, &(&1.name == name and &1.score >= 0.5))
      end)

    exact_other? =
      Enum.any?(found, &(&1.score == 1.0 and (in_call == nil or &1.name != in_call.name)))

    cond do
      in_call != nil and not exact_other? ->
        {:one, in_call}

      found != [] ->
        FindContact.decide(found) |> nearest(said, people, d, recent)

      true ->
        nearest(:none, said, people, d, recent)
    end
  end

  # Shared case N-04: "Sineka" matches nobody: "Did you mean <the person in focus>?", else the
  # closest of the caller's people; only when they have nobody at all, :none.
  defp nearest(:none, said, people, d, recent) do
    focus = Dialogue.focus(d) || (d[:pending] || %{})[:to] || List.first(recent)

    pick =
      if is_binary(focus),
        do: Enum.find(people, fn {_id, n} -> n == focus end),
        else: FindContact.closest(said, people)

    case pick do
      {id, name} -> {:pick, [%{user_id: id, name: name, score: 0.0}]}
      _ -> :none
    end
  end

  defp nearest(decided, _said, _people, _d, _recent), do: decided

  ## Messages: a pending task {to, to_candidates, text}, never a made-up text (B, architect 11:45)

  defp message(ctx, dec, d, text, lang) do
    p0 = d.pending || Dialogue.message_task()
    card = d.card || %{}
    slot = (d.awaiting || %{})[:slot]

    p = recipient(ctx, p0, dec.to, card, text, d)
    # "Send it to work" (22e016e0 bug A): the Official chat with the person in focus.
    p = %{p | tab: tab(dec.tab, p.tab || Dialogue.tab_for(d, p.to) || card[:tab])}

    # A correction of the recipient ("Nimali, not Kamal") pins the new one for the call.
    old = p0.to || card[:to]
    corrected? = is_binary(old) and is_binary(p.to) and p.to != old
    if corrected?, do: correction(ctx, "to")
    d = if corrected?, do: Dialogue.add_person(d, p.to, true), else: d

    {body, from_model?} =
      message_body(Map.put(ctx, :pending?, d.pending != nil), dec, p, card, slot, text)

    # Soft grounding against the whole call so far (the caller's words, the recipient, the open
    # card's and the task's text).
    source =
      Enum.join(
        [text, p.to || "", card[:text] || "", p.text || ""] ++ user_words(ctx),
        " "
      )

    grounded? = body != nil and Dialogue.grounded?(body, source)

    {body, confirm?} =
      cond do
        body == nil -> {nil, false}
        grounded? -> {body, false}
        # Risi waited for the text: the caller's words are the text; a reworded one that doesn't
        # match them is read back and confirmed (never asked again).
        slot == "message_text" -> {body, true}
        from_model? -> {nil, false}
        true -> {body, true}
      end

    p = %{p | text: body || p.text}

    # Item 2/3 (04:0x call): "… send it to her work address, not the private one, you can send it
    # straight away" is no message text: it says where (Official) and when (now).
    {clean, flags} = Dialogue.split_instructions(p.text || "")

    p =
      if is_binary(p.text),
        do: %{p | text: if(clean == "", do: nil, else: Dialogue.strip_lead(clean))},
        else: p

    p = if flags.official? and p.tab == nil, do: %{p | tab: "official"}, else: p
    p = %{p | send_now: p.send_now or flags.send_now? or Dialogue.send_now?(text)}
    d = %{d | pending: p}
    run_message(ctx, d, p, confirm?, lang)
  end

  # The task's next step: who → which → what → confirm → the card.
  defp run_message(ctx, d, p, confirm?, lang) do
    cond do
      p.to == nil and p.to_candidates != [] ->
        ask(ctx, d, Dialogue.ask_pick(p.to_candidates), %{
          slot: "message_pick",
          options: p.to_candidates
        })

      p.to == nil ->
        ask(ctx, d, Dialogue.ask_to(), %{slot: "message_to"})

      p.text == nil ->
        # The answer is taken down as dictation (the session): one check per pause, never a
        # turn per pause, and an instruction ("no, read …") is never the text (16:05 item 1).
        dict = %{to: p.to, tab: p.tab, asked?: true}
        d = %{d | dictation: dict}
        report(ctx, {:dictation, Map.put(dict, :parts, [])})
        ask(ctx, d, Dialogue.ask_text(p.to), %{slot: "message_text", to: p.to})

      confirm? ->
        ask(ctx, d, Dialogue.confirm_line(p.to, p.text), %{slot: "message_confirm"})

      true ->
        draft(ctx, d, p, lang)
    end
  end

  defp draft(ctx, d, p, lang) do
    # The caller's words as a sentence ("the car is ready" → "The car is ready").
    {f, rest} = String.split_at(String.trim(p.text), 1)
    p = %{p | text: String.upcase(f) <> rest}

    if same_card?(d.card, p),
      do: already(ctx, d, p),
      else: redraft(ctx, d, p, lang)
  end

  # The open card already says exactly this to this person ("send it to Nimali, not Kasun" when
  # it is Nimali's): no second card and never the same read-back twice.
  defp same_card?(%{kind: "message", to: to, text: t} = c, p) when is_binary(to) and is_binary(t),
    do:
      to == p.to and norm_q(t) == norm_q(p.text) and
        (p.tab == nil or (c[:tab] || "private") == p.tab)

  defp same_card?(_, _), do: false

  defp already(ctx, d, p) do
    report(ctx, {:route, "message_write"})

    line =
      case d.awaiting do
        %{slot: "send_confirm"} ->
          "It's already going to #{first_name(p.to)}: #{Dialogue.read_back(p.text)} Send it?"

        _ ->
          "It's already on the card for #{p.to}: #{Dialogue.read_back(p.text)} " <>
            "Tap Send in your Risi chat to send it."
      end

    d = Dialogue.add_person(%{d | pending: nil, dictation: nil}, p.to)
    report(ctx, {:dialogue, d})
    Brain.say_line(ctx, line)
    done(ctx)
  end

  defp redraft(ctx, d, p, lang) do
    d =
      %{d | awaiting: nil, pending: p, dictation: nil}
      |> Dialogue.add_person(p.to)
      |> Dialogue.remember_tab(p.to, p.tab)

    report(ctx, {:dialogue, d})

    {p, vs} = voice_send_setup(ctx, p)

    Brain.tool_turn(
      filler(ctx, "message_write") |> Map.put(:voice_send, vs),
      "Send a message to #{p.to}: #{p.text}",
      lang,
      "message_write",
      {:send, p.to, p.text, p.tab}
    )
  end

  ## v1.55 §41.11: Risi sends an Official message itself after the read-back and a yes

  # The words after a cancel at the start ("never mind, forget the message. Any new messages?"),
  # when they are a request of their own (3 words or more); else nil.
  defp after_cancel(text) do
    re =
      ~r/^\W*(?:(?:no|okay|ok|so)[,.]?\s+)*(?:(?:cancel|forget|scratch)(?:\s+(?:that|it|this|the message|the card|that message))?|never\s*mind)\b[\s,.!]*(?:(?:cancel|forget|scratch)(?:\s+(?:that|it|this|the message|the card|that message))?\b[\s,.!]*)?/iu

    case Regex.run(re, text, return: :index) do
      [{0, n} | _] ->
        rest = text |> String.slice(n..-1//1) |> String.trim()
        if length(String.split(rest)) >= 3, do: rest

      _ ->
        nil
    end
  end

  defp send_impl,
    do: Application.get_env(:risime, :risi_call_send_impl, RisiMe.Agent.CallSend)

  defp send_on?(ctx) do
    send_impl().enabled_for?(ctx.user_id)
  rescue
    _ -> false
  catch
    _, _ -> false
  end

  defp send_record(ctx, outcome) do
    send_impl().record_outcome(ctx.user_id, outcome, nil)
  rescue
    _ -> :ok
  catch
    _, _ -> :ok
  end

  # An Official message for a caller with voice send on: the person's Official chat, and the
  # guard (money, HR, legal, accusations, deletion keep the tap) started at once, in parallel
  # with the draft. Official off in that chat: said, and the message goes to a Private card.
  defp voice_send_setup(ctx, %{tab: "official", to: to} = p) when is_binary(to) do
    id = p.to_id || id_of(ctx, to)

    if is_binary(id) and send_on?(ctx) do
      case send_impl().target(ctx.user_id, id) do
        {:ok, %{conv: conv}} ->
          text = p.text
          guard = Task.async(fn -> safe_needs_tap?(text) end)
          {p, %{conv: conv, to: to, to_id: id, guard: guard, now: p.send_now == true}}

        {:error, :official_off} ->
          Brain.say_text(
            ctx,
            "Official is off in your chat with #{first_name(to)}, so I'll draft it for your Private chat."
          )

          {%{p | tab: "private"}, nil}

        _ ->
          {p, nil}
      end
    else
      {p, nil}
    end
  catch
    _, _ -> {p, nil}
  end

  defp voice_send_setup(_ctx, p), do: {p, nil}

  defp safe_needs_tap?(text) do
    send_impl().needs_tap?(text) != false
  rescue
    _ -> true
  catch
    _, _ -> true
  end

  @doc false
  # The read-back of a voice-sendable message card (Brain's card line): "I'll send … Send it?",
  # and the call now waits for that yes; a text the guard keeps for a tap says so.
  def voice_card_line(ctx, %{conv: conv, to: to, to_id: id, guard: guard}, summary) do
    parts = Dialogue.message_parts(summary) || %{to: to, text: ""}

    needs_tap? =
      case Task.yield(guard, 3_000) || Task.shutdown(guard, :brutal_kill) do
        {:ok, v} -> v
        _ -> true
      end

    rid = (Map.get(ctx, :this_write) || %{})[:request_id]

    cond do
      needs_tap? ->
        send_record(ctx, "needs_tap")

        "Here's your message to #{parts.to}: #{Dialogue.read_back(parts.text)} " <>
          "That one needs your tap: it's on the card in your Risi chat."

      # Item 3: he already said to send it ("send it straight away", "directly"): one read-back
      # and the send, no second question; "Sent." only with the stored post (claim guard).
      Map.get(vs_now(guard, ctx), :now) == true ->
        send_now(ctx, %{to: parts.to, to_id: id, conv: conv, text: parts.text, request_id: rid})

      true ->
        ask_send(ctx, parts, id, conv)
    end
  end

  def voice_card_line(_ctx, _vs, _summary), do: nil

  defp vs_now(_guard, ctx), do: Map.get(ctx, :voice_send) || %{}

  defp ask_send(ctx, parts, id, conv) do
    q = Dialogue.send_question(first_name(parts.to), parts.text)

    report(
      ctx,
      {:awaiting, %{slot: "send_confirm", to: parts.to, to_id: id, conv: conv, question: q}}
    )

    q
  end

  defp send_now(ctx, m) do
    result =
      try do
        send_impl().send(%{
          user_id: ctx.user_id,
          recipient_user_id: m.to_id,
          conv: m.conv,
          name: m.to,
          text: m.text,
          call_id: ctx.call_id,
          request_id: m.request_id
        })
      catch
        _, _ -> {:error, :failed}
      end

    case result do
      {:ok, %{message_id: mid}} when is_binary(mid) ->
        if is_binary(m.request_id), do: _ = cancel_impl().(ctx.user_id, m.request_id)
        report(ctx, {:card, %{request_id: m.request_id, kind: "sent", summary: nil}})

        "Sent to #{first_name(m.to)} on Official: #{Dialogue.read_back(m.text)} " <>
          "Say \"undo that\" in the next 2 minutes to take it back."

      _ ->
        "Here's your message to #{m.to}: #{Dialogue.read_back(m.text)} That didn't go through, " <>
          "so nothing was sent. It's on the card in your Risi chat."
    end
  end

  # The yes: Risi's own post in the Official chat; "Sent." only with the stored post's id.
  defp voice_send(ctx, %{awaiting: %{slot: "send_confirm"} = a, card: %{kind: "message"} = c} = d) do
    text = c[:text] || ""

    result =
      try do
        send_impl().send(%{
          user_id: ctx.user_id,
          recipient_user_id: a.to_id,
          conv: a.conv,
          name: a.to,
          text: text,
          call_id: ctx.call_id,
          request_id: c.request_id
        })
      catch
        _, _ -> {:error, :failed}
      end

    case result do
      {:ok, %{message_id: mid}} when is_binary(mid) ->
        # The card is done with: a tap on it can't send the message a second time.
        _ = cancel_impl().(ctx.user_id, c.request_id)
        report(ctx, {:dialogue, %{d | card: nil, awaiting: nil}})
        Brain.say_text(ctx, "Sent. Say \"undo that\" in the next 2 minutes to take it back.")

      _ ->
        report(ctx, {:dialogue, %{d | awaiting: nil}})

        Brain.say_text(
          ctx,
          "That didn't go through, so nothing was sent. It's still on the card in your Risi chat."
        )
    end

    done(ctx)
  end

  defp voice_send(ctx, d) do
    report(ctx, {:dialogue, %{d | awaiting: nil}})
    Brain.say_text(ctx, "There's nothing waiting to be sent.")
    done(ctx)
  end

  # D-001: every correction the caller makes is a training example (labels only).
  defp correction(ctx, field),
    do: RisiMe.Agent.CallRisi.Escalate.example(ctx, "correction", %{field: field})

  defp id_of(ctx, name) do
    Enum.find_value(FindContact.people(ctx.user_id), fn {id, n} -> if n == name, do: id end)
  end

  defp tab(new, old) do
    cond do
      new in ~w(official private) -> new
      old in ~w(official private) -> old
      true -> nil
    end
  end

  defp first_name(nil), do: "them"

  # Architect 04:20 item 10: never a one-letter name ("M N M Shirazi" is said in full).
  defp first_name(n) do
    case String.split(n) do
      [f | _] = all ->
        if String.length(String.trim(f, ".")) <= 1, do: Enum.join(all, " "), else: f

      [] ->
        n
    end
  end

  # Risi's last spoken line in this call starts with / contains `s`.
  defp last_said?(ctx, s) do
    case for(%{"role" => "assistant", "content" => c} <- ctx[:history] || [], do: c) do
      [] -> false
      l -> String.contains?(List.last(l), s)
    end
  end

  # Asks `q` (the open state waits for its answer), but never the same question twice in a row:
  # the second time Risi says what it heard and offers a choice.
  defp ask(ctx, d, q, awaiting) do
    prev = d.awaiting || %{}

    tries =
      if prev[:slot] == awaiting.slot and (prev[:base] == q or repeat?(d, q)),
        do: (prev[:tries] || 0) + 1,
        else: 0

    d2 = %{d | awaiting: Map.merge(awaiting, %{question: q, base: q, tries: tries})}
    line = if tries == 0, do: q, else: Dialogue.retry_line(d2, ctx[:heard] || "", tries)
    d2 = put_in(d2.awaiting.question, line)

    report(ctx, {:dialogue, d2})
    Brain.say_text(ctx, line)
    done(ctx)
  end

  defp repeat?(%{awaiting: %{question: prev}}, q) when is_binary(prev),
    do: norm_q(prev) == norm_q(q)

  defp repeat?(_d, _q), do: false

  defp norm_q(q), do: q |> String.downcase() |> String.replace(~r/[^\p{L}\p{N}]+/u, " ")

  # The recipient: the name heard is looked up in the caller's people (find_contact) right when
  # it is given; one clear match is used without asking, 2–3 are asked once (the text kept).
  # With the call's context (architect 16:05 item 4, 22e016e0 bug B): "her" is the person in
  # focus; a near tie goes to the pinned recipient or one of this call's people, never silently
  # to someone else.
  defp recipient(ctx, p, said, card, text, d) do
    said = if is_binary(said), do: String.trim(said, " .,!?"), else: ""
    said = heard_name(said, text)
    pronoun? = said != "" and no_name?(said)
    said = if said == "" or pronoun? or author?(ctx, said, text), do: "", else: said
    cands = p.to_candidates || []

    cond do
      said == "" and cands != [] ->
        pick(p, cands, text)

      said == "" and p.to != nil ->
        p

      said == "" and is_binary(card[:to]) ->
        %{p | to: card[:to]}

      said == "" and pronoun? and Dialogue.focus(d) != nil ->
        %{p | to: Dialogue.focus(d)}

      said == "" ->
        p

      cands != [] and pick(p, cands, said).to != nil ->
        pick(p, cands, said)

      same_name?(said, p.to) or same_name?(said, p.said) ->
        p

      same_name?(said, card[:to]) ->
        %{p | to: card[:to], to_candidates: []}

      true ->
        case contact(said, FindContact.people(ctx.user_id), d, FindContact.recent(ctx.user_id)) do
          {:one, %{name: n, user_id: id}} ->
            %{p | to: n, to_id: id, to_candidates: [], said: said}

          {:pick, xs} ->
            %{p | to: nil, to_candidates: Enum.map(xs, & &1.name), said: said}

          # Not one of their people (≥ 0.6): the name as said (the turn checks it again).
          :none ->
            %{p | to: said, to_candidates: [], said: said}
        end
    end
  end

  # recipient-context 12: "send Senarali another message" came back as to = "Kasun Senaratne"
  # (the model resolved the name itself, against this call's context). When no word of the
  # model's name was said, the caller's word closest to it is the name.
  @doc false
  def heard_name(said, text) when is_binary(said) and said != "" and is_binary(text) do
    w = fn s -> s |> String.downcase() |> String.split(~r/[^\p{L}'-]+/u, trim: true) end
    words = w.(text)
    parts = w.(said)

    # Names the speech-to-text wrote as names (capitalised), not a sentence's first word.
    caps =
      text
      |> String.split(~r/(?<=[.!?])\s+/u, trim: true)
      |> Enum.flat_map(fn sentence ->
        sentence
        |> String.split(~r/[^\p{L}'-]+/u, trim: true)
        |> Enum.drop(1)
        |> Enum.filter(&String.match?(&1, ~r/^\p{Lu}/u))
      end)
      |> Enum.map(&String.downcase/1)

    if parts != [] and Enum.any?(parts, &(&1 in words)) do
      said
    else
      people = [{"x", said}]

      named = Enum.filter(words, &(&1 in caps))
      pairs = named |> Enum.chunk_every(2, 1, :discard) |> Enum.map(&Enum.join(&1, " "))

      best =
        (named ++ pairs)
        |> Enum.reject(&(String.length(&1) < 3))
        |> Enum.map(&{&1, (List.first(FindContact.find(&1, people)) || %{score: 0.0}).score})
        |> Enum.max_by(&elem(&1, 1), fn -> {nil, 0.0} end)

      case best do
        {word, score} when is_binary(word) and score >= 0.6 ->
          word |> String.split() |> Enum.map_join(" ", &String.capitalize/1)

        _ ->
          said
      end
    end
  end

  def heard_name(said, _text), do: said

  # One of the names Risi offered ("Nimali", "the second one", spelled out).
  defp pick(p, cands, words) do
    people = Enum.map(cands, &{&1, &1})
    tries = [words | String.split(words, ~r/[^\p{L}\p{N}'-]+/u, trim: true)]

    found =
      Enum.find_value(tries, fn w ->
        case FindContact.decide(FindContact.find(w, people)) do
          {:one, %{name: n, score: sc}} when sc >= 0.75 -> n
          _ -> nil
        end
      end)

    if found, do: %{p | to: found, to_candidates: []}, else: p
  end

  defp same_name?(a, b) when is_binary(a) and is_binary(b) and a != "" and b != "" do
    na = RisiMe.Agent.NameCheck.norm(a)
    nb = RisiMe.Agent.NameCheck.norm(b)
    na == nb or hd(String.split(na) ++ [""]) == hd(String.split(nb) ++ [""])
  end

  defp same_name?(_, _), do: false

  # "This is a message given by Harsha": the author (the caller) is not the recipient.
  defp author?(ctx, said, text) do
    by? = Regex.match?(~r/\b(?:by|from)\s+#{Regex.escape(said)}\b/iu, text)

    me =
      try do
        case RisiMe.Repo.get(RisiMe.Accounts.User, ctx.user_id) do
          %{display_name: n} when is_binary(n) -> n
          _ -> nil
        end
      rescue
        _ -> nil
      catch
        _, _ -> nil
      end

    by? or
      (is_binary(me) and
         match?({:one, _}, FindContact.decide(FindContact.find(said, [{"me", me}]))))
  end

  # The text and whether it is the model's own wording: the model's text, else (Risi waited for
  # the text) the caller's words with only the lead-in stripped, else ("I just said it") the
  # caller's earlier words, else the task's text.
  defp message_body(ctx, dec, p, card, slot, text) do
    cond do
      # "Message Kamal please." came back as the text: the request is no message (Risi asks).
      dec.text != "" and request_words?(dec.text, p.to || dec.to) ->
        message_body(ctx, %{dec | text: ""}, p, card, slot, text)

      # Replay 0430z: "No, you should be saying, … there should be a person called Shirazi" (a
      # correction, Risi waiting for the text) came back with the utterance itself as the text.
      slot == "message_text" and dec.text != "" and Dialogue.correction?(text) and
          copies?(dec.text, text) ->
        message_body(ctx, %{dec | text: ""}, p, card, slot, text)

      dec.text != "" ->
        {dec.text, slot != "message_text"}

      # Risi asked what to say and these words reached the model: the dictation check found
      # them an instruction ("No, it's for Ruwan", "Who is it for again?"): never the text.
      slot == "message_text" ->
        {p.text, false}

      # "Send a message to Nimali saying thank you" with the text left out by the model: the
      # words after "saying" are the text (the caller's own).
      is_binary(said = Dialogue.said_text(text, p.said || p.to || dec.to)) ->
        {said, false}

      Dialogue.said_already?(text) ->
        case earlier_text(ctx) do
          nil -> {p.text || card[:text], false}
          t -> {t, false}
        end

      # "No, Tharindi, not Tharindu." (shared N-05): a correction of the open card keeps its text.
      # … and "Actually send it to Kamal, not Ruwan" (recipient-context 15). Never when Risi asked
      # the text for a new task of its own (replay 0430z t7).
      ctx[:pending?] != true and is_binary(card[:text]) and p.text == nil and is_binary(p.to) and
        p.to != card[:to] and
          (Dialogue.correction?(text) or
             Regex.match?(
               ~r/\b(?:send|forward|give)\s+(?:it|that|this(?:\s+one)?)\b|\bnot\s+(?:to\s+)?\p{L}+\W*$/iu,
               text
             )) ->
        {card[:text], false}

      true ->
        {p.text, false}
    end
  end

  # The model's text is (most of) the caller's whole utterance, not a message inside it.
  defp copies?(t, utterance) do
    w = fn s -> s |> String.downcase() |> String.split(~r/[^\p{L}\p{N}']+/u, trim: true) end
    have = MapSet.new(w.(t))
    said = w.(utterance)
    said != [] and Enum.count(said, &MapSet.member?(have, &1)) / length(said) >= 0.6
  end

  defp request_words?(t, to) do
    first =
      to |> to_string() |> String.split() |> List.first() |> to_string() |> String.downcase()

    Regex.match?(
      ~r/^\W*(?:(?:can|could) you\s+|please\s+)?(?:send|message|text|write)\b/iu,
      t
    ) and (first != "" and String.contains?(String.downcase(t), first)) and
      length(String.split(t)) <= 6
  end

  # The caller's last words that read as a message (≥ 4 words, not a request to Risi).
  defp earlier_text(ctx) do
    ctx
    |> user_words()
    |> Enum.reverse()
    |> Enum.find(fn u ->
      length(String.split(u)) >= 4 and not Dialogue.said_already?(u) and
        not Regex.match?(~r/\b(?:send|message to|can you|could you)\b/iu, u)
    end)
    |> case do
      nil -> nil
      u -> Dialogue.strip_lead(u)
    end
  end

  defp user_words(ctx) do
    for %{"role" => "user", "content" => c} <- Map.get(ctx, :history) || [], is_binary(c), do: c
  end

  @no_names ~w(you me myself him her them someone somebody anyone risi rishi it this that
               yourself everyone nobody she he they)

  # Architect 16:05 item 3: where a message goes is never a person (22e016e0: "I couldn't find
  # work in your contacts").
  @places ~w(work office official private personal home chat group)

  # "my friend", "a colleague", "work", "the work chat": no name (Risi asks who, or uses the
  # person in focus).
  defp no_name?(to) do
    t = String.downcase(String.trim(to, " .,!?"))
    bare = Regex.replace(~r/^(?:the|my|our|her|his|a)\s+/u, t, "")

    t in @no_names or bare in @places or
      Regex.match?(
        ~r/^(?:work|office|official|private|personal|group) (?:chat|tab|group)$/u,
        bare
      ) or
      Regex.match?(
        ~r/^(?:my|a|an|the|our|her|his) (?:friend|colleague|contact|person|someone)s?$/u,
        t
      )
  end

  ## Calendar

  defp calendar_read(ctx, dec, d, text, lang, items_only?) do
    clear_awaiting(ctx, d)
    today = Brain.today(ctx)
    topic = Map.get(ctx, :topic)

    range =
      cond do
        ScheduleIntent.day_named?(text, today) ->
          ScheduleIntent.range(text, today)

        dec.request != "" and ScheduleIntent.day_named?(dec.request, today) ->
          ScheduleIntent.range(dec.request, today)

        match?(%{route: "calendar_read", range: %{}}, topic) ->
          topic.range

        true ->
          ScheduleIntent.range(text, today)
      end

    range = if items_only?, do: Map.put(range, :items_only, true), else: range

    range =
      case Spoken.window(text) do
        nil -> Map.delete(range, :window)
        w -> Map.put(range, :window, w)
      end

    Brain.tool_turn(Map.put(ctx, :filler?, true), text, lang, "calendar_read", range)
  end

  defp write(ctx, dec, d, text, lang, cancelled) do
    route = if dec.tool == "reminder_add", do: "reminder", else: "calendar_write"
    # The caller's own words go to the turn (no paraphrase), except after "cancel that and …",
    # where the model's request is the new request alone.
    today = Brain.today(ctx)

    # … and when the words carry no day but the model's request does ("Yeah." to Risi's
    # question about the meeting "today for two"), the request.
    words =
      cond do
        cancelled != nil and dec.request != "" ->
          dec.request

        # (The model's request names a day the caller didn't say only to answer Risi's question;
        # else it may be a day the model made up: "at one" became "on Friday 16 October".)
        d.awaiting != nil and dec.request != "" and not ScheduleIntent.day_named?(text, today) and
            ScheduleIntent.day_named?(dec.request, today) ->
          dec.request

        true ->
          text
      end

    words =
      case d.awaiting do
        %{slot: "day", pending: p} -> p <> " " <> words
        _ -> words
      end

    # A change of the open card of the same kind (never after "cancel that").
    change? =
      cancelled == nil and
        match?(%{kind: k} when k in ~w(calendar reminder), d.card) and
        d.card.kind == if(route == "reminder", do: "reminder", else: "calendar")

    cond do
      change? ->
        correction(ctx, "card")
        clear_awaiting(ctx, d)
        noun = if route == "reminder", do: "reminder", else: "event"
        req = "Change the #{noun} I just asked you to add: " <> words
        Brain.tool_turn(filler(ctx, route), req, lang, route, nil)

      not ScheduleIntent.day_named?(words, today) ->
        report(ctx, {:write_ask, words})
        report(ctx, {:dialogue, %{d | awaiting: %{slot: "day", pending: words}}})
        Brain.say(ctx, "Which day should I put it on?", :answer)
        done(ctx)

      true ->
        clear_awaiting(ctx, d)
        Brain.tool_turn(filler(ctx, route), words, lang, route, nil)
    end
  end

  ## Helpers

  defp clear_awaiting(ctx, %{awaiting: a} = d) when a != nil,
    do: report(ctx, {:dialogue, %{d | awaiting: nil}})

  defp clear_awaiting(_ctx, _d), do: :ok

  # D: the route's holding line only when no neutral one was said (a calendar read always says
  # its own: the calendar line comes only after the calendar tool was chosen).
  defp filler(ctx, _route),
    do: Map.put(ctx, :filler?, Map.get(ctx, :filler?, true) != false and ctx[:neutral?] != true)

  # Speaks the model's words through the claim guard (F): a sentence that claims or promises an
  # action (no tool ran in a reply) is dropped and logged; when nothing is left, the open card's
  # true status. The no-access guard too. Returns what was said.
  defp speak_guarded(ctx, text, d) do
    {pieces, rest} = Sentences.feed("", Sentences.clean(text) <> " ", true)
    all = pieces ++ Sentences.flush(rest)
    {claims, kept} = Enum.split_with(all, &WriteTruth.claim?/1)
    if claims != [], do: RisiMe.Agent.ClaimGuard.violation("call_v3")
    said = Enum.map(kept, &Brain.no_access_guard/1)

    said =
      if said == [] and claims != [],
        do: [honest_status(ctx, d)],
        else: said

    Enum.each(said, &Brain.say_text(ctx, &1))
    Enum.join(said, " ")
  end

  defp honest_status(_ctx, %{card: %{kind: "message", request_id: rid}}),
    do: WriteTruth.status_line(%{request_id: rid, route: "message_write"}, :claim)

  defp honest_status(ctx, %{card: %{}}), do: WriteTruth.status_line(Map.get(ctx, :write), :claim)
  defp honest_status(_ctx, _d), do: "Okay."

  defp done(ctx), do: report(ctx, {:done, %{status: :ok, request_id: nil}})

  defp tz(ctx) do
    Clock.user_tz(ctx.user_id)
  catch
    _, _ -> "UTC"
  end

  defp now_text(ctx) do
    now = Clock.now()

    local =
      try do
        Clock.local(now, tz(ctx))
      catch
        _, _ -> now
      end

    Calendar.strftime(local, "%A %d %B %Y, %H:%M")
  end

  defp report(ctx, event), do: send(ctx.pid, {:brain, ctx.utt, event})
end
