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

  alias RisiMe.Agent.CallRisi.{Brain, Decider, Dialogue, Sentences, Spoken, WriteTruth}
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
    hold(ctx, text)
    d = ctx |> Map.get(:dialogue) |> Dialogue.get() |> fresh_card()
    ctx = Map.put(ctx, :heard, text)

    case answer_pending(ctx, d, text, lang) do
      :model -> answer_model(ctx, d, text, lang)
      :done -> :ok
    end
  end

  # The open question about the pending task, answered without the model when the answer is
  # plain (architect 11:45): a non-answer ("Hello?") keeps the task and says what Risi heard; a
  # yes (or the name) confirms the read-back; one of the names offered picks it.
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
  def in_task(%{tool: "message_draft", action: a} = dec, d, text) when a != "tool",
    do: in_task(%{dec | action: "tool", say: ""}, d, text)

  # Safety: a bare "yeah" with nothing open (no card, no task, no question) repeats no write.
  def in_task(%{tool: t} = dec, %{card: nil, pending: nil, awaiting: nil}, text)
      when t in ~w(calendar_add reminder_add message_draft) do
    if Dialogue.yes?(text) and length(String.split(text)) <= 2,
      do: %{dec | action: "reply", tool: "none", say: "Okay.", cancel_open: false},
      else: dec
  end

  def in_task(
        %{action: a, tool: "none", cancel_open: false} = dec,
        %{awaiting: %{slot: slot}},
        text
      )
      when slot in ~w(message_to message_pick message_text) and
             (a == "ask" or (a == "reply" and dec.say != "")) do
    base = %{dec | action: "tool", tool: "message_draft", say: ""}

    if slot == "message_text",
      do: %{base | to: "", text: ""},
      else: %{base | to: name_words(text), text: ""}
  end

  def in_task(dec, _d, _text), do: dec

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
        dec = in_task(dec, d, text)
        report(ctx, {:decided, {:v3, dec.tool, dec.action}})
        act(Map.put(ctx, :neutral?, neutral?), dec, d, text, lang)

      {{:error, _}, neutral?} ->
        # The model failed: the legacy brain answers this turn (no second holding line).
        Brain.legacy_answer(Map.put(ctx, :filler?, not neutral?), text, lang)
    end
  end

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
  def route(%{tool: "calendar_read"}), do: "calendar_read"
  def route(%{tool: "calendar_add"}), do: "calendar_write"
  def route(%{tool: "reminder_add"}), do: "reminder"
  def route(%{tool: "promises_read"}), do: "ledger_items"
  def route(%{tool: "message_draft"}), do: "message_write"
  def route(%{tool: t}) when t in ~w(cancel_open confirm_open card_status), do: "write_status"
  def route(_), do: "chat"

  defp act(ctx, %{tool: "capabilities"}, _d, _text, _lang) do
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

  defp act(ctx, %{say: say}, d, _text, _lang) do
    report(ctx, {:route, "chat"})

    if say == "" do
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

  # "Cancel that": the open card is cancelled for real (its row), and what Risi waited for goes.
  defp cancel(ctx, %{card: %{request_id: rid, kind: kind}} = d) do
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

  defp tool(ctx, %{tool: "cancel_open"}, _d, _text, _lang, cancelled) do
    line =
      case cancelled do
        k when k in ~w(message calendar reminder) -> Dialogue.cancelled_line(k)
        :gone -> "That card is already closed, so nothing will be sent or added."
        :awaiting -> "Okay, cancelled."
        nil -> "There's nothing open to cancel."
      end

    Brain.say_text(ctx, line)
    done(ctx)
  end

  defp tool(ctx, %{tool: "message_draft"} = dec, d, text, lang, _cancelled),
    do: message(ctx, dec, d, text, lang)

  defp tool(ctx, %{tool: "calendar_read"} = dec, d, text, lang, _c),
    do: calendar_read(ctx, dec, d, text, lang, false)

  defp tool(ctx, %{tool: "promises_read"} = dec, d, text, lang, _c) do
    today = Brain.today(ctx)

    # "Did I promise anything today or tomorrow?" (or a follow-up of that read): Risi's items of
    # that range (the read).
    if ScheduleIntent.day_named?(text, today) or
         (dec.request != "" and ScheduleIntent.day_named?(dec.request, today)) or
         match?(%{route: "calendar_read", range: %{}}, Map.get(ctx, :topic)) do
      report(ctx, {:route, "calendar_read"})
      calendar_read(ctx, dec, d, text, lang, true)
    else
      clear_awaiting(ctx, d)
      Brain.tool_turn(filler(ctx, "ledger_items"), text, lang, "ledger_items", nil)
    end
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
    case d.card do
      %{kind: "message"} ->
        Brain.say_text(ctx, Dialogue.message_tap_line())
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

  ## Messages: a pending task {to, to_candidates, text}, never a made-up text (B, architect 11:45)

  defp message(ctx, dec, d, text, lang) do
    p = d.pending || Dialogue.message_task()
    card = d.card || %{}
    slot = (d.awaiting || %{})[:slot]

    p = recipient(ctx, p, dec.to, card, text)
    {body, from_model?} = message_body(ctx, dec, p, card, slot, text)

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
    report(ctx, {:dialogue, %{d | awaiting: nil, pending: p}})

    Brain.tool_turn(
      filler(ctx, "message_write"),
      "Send a message to #{p.to}: #{p.text}",
      lang,
      "message_write",
      {:send, p.to, p.text}
    )
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
  defp recipient(ctx, p, said, card, text) do
    said = if is_binary(said), do: String.trim(said, " .,!?"), else: ""
    said = if said == "" or no_name?(said) or author?(ctx, said, text), do: "", else: said
    cands = p.to_candidates || []

    cond do
      said == "" and cands != [] ->
        pick(p, cands, text)

      said == "" ->
        if p.to == nil and is_binary(card[:to]), do: %{p | to: card[:to]}, else: p

      cands != [] and pick(p, cands, said).to != nil ->
        pick(p, cands, said)

      same_name?(said, p.to) or same_name?(said, p.said) ->
        p

      same_name?(said, card[:to]) ->
        %{p | to: card[:to], to_candidates: []}

      true ->
        case FindContact.decide(FindContact.find(said, FindContact.people(ctx.user_id))) do
          {:one, %{name: n}} -> %{p | to: n, to_candidates: [], said: said}
          {:pick, xs} -> %{p | to: nil, to_candidates: Enum.map(xs, & &1.name), said: said}
          # Not one of their people (≥ 0.6): the name as said (the turn checks it again).
          :none -> %{p | to: said, to_candidates: [], said: said}
        end
    end
  end

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
      dec.text != "" ->
        {dec.text, slot != "message_text"}

      slot == "message_text" ->
        {Dialogue.strip_lead(text), false}

      Dialogue.said_already?(text) ->
        case earlier_text(ctx) do
          nil -> {p.text || card[:text], false}
          t -> {t, false}
        end

      true ->
        {p.text, false}
    end
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
               yourself everyone nobody)

  # "my friend", "a colleague": no name (Risi asks who).
  defp no_name?(to) do
    t = String.downcase(String.trim(to, " .,!?"))

    t in @no_names or
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

        dec.request != "" and not ScheduleIntent.day_named?(text, today) and
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
