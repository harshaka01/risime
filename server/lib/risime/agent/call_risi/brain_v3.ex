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

  alias RisiMe.Agent.CallRisi.{Brain, Decider, Dialogue, Fillers, Sentences, Spoken, WriteTruth}
  alias RisiMe.Agent.{Clock, ScheduleIntent}

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

    input = %{
      now: now_text(ctx),
      tz: tz(ctx),
      dialogue: d,
      history: Map.get(ctx, :history) || [],
      text: text
    }

    case decide(ctx, input) do
      {{:ok, dec}, neutral?} ->
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
        # (a question restarted with more words said its holding line already)
        if Map.get(ctx, :filler?, true) != false, do: Brain.say(ctx, Fillers.neutral(), :filler)

        case Task.yield(task, decide_ms()) || Task.shutdown(task, :brutal_kill) do
          {:ok, r} -> {r, true}
          _ -> {{:error, :timeout}, true}
        end
    end
  end

  ## Acting on the decision

  @doc false
  # The route name the session and the turn rows know (chat = no tool, not "holding").
  def route(%{tool: "capabilities"}), do: "capabilities"
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
    spoken = speak_guarded(ctx, q, d)
    report(ctx, {:dialogue, %{d | awaiting: %{slot: "answer", question: spoken}}})
    done(ctx)
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
    d = %{d | card: nil, awaiting: nil}
    report(ctx, {:dialogue, d})
    if kind != "message", do: report(ctx, {:write, nil})
    {d, if(result == :ok, do: kind, else: :gone)}
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

  ## Messages: slots {to, text}, never a made-up text (B)

  defp message(ctx, dec, d, text, lang) do
    awaiting = d.awaiting || %{}
    card = d.card || %{}

    to =
      [dec.to, awaiting[:to], card[:to]]
      |> Enum.find("", &(is_binary(&1) and &1 != ""))

    body =
      cond do
        dec.text != "" -> dec.text
        awaiting[:slot] == "message_to" and is_binary(awaiting[:text]) -> awaiting.text
        true -> ""
      end

    # The text must come from the caller's own words (this utterance, the open card's text or
    # the text Risi was holding while it asked who it is for), never from the model.
    source = Enum.join([text, to, card[:text] || "", awaiting[:text] || ""], " ")
    ok_text? = body != "" and Dialogue.grounded?(body, source)

    cond do
      to == "" ->
        keep = if ok_text?, do: body
        report(ctx, {:dialogue, %{d | awaiting: %{slot: "message_to", text: keep}}})
        Brain.say_text(ctx, Dialogue.ask_to())
        done(ctx)

      not ok_text? ->
        report(ctx, {:dialogue, %{d | awaiting: %{slot: "message_text", to: to}}})
        Brain.say_text(ctx, Dialogue.ask_text(to))
        done(ctx)

      true ->
        report(ctx, {:dialogue, %{d | awaiting: nil}})

        Brain.tool_turn(
          filler(ctx, "message_write"),
          "Send a message to #{to}: #{body}",
          lang,
          "message_write",
          {:send, to, body}
        )
    end
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
    words = if cancelled != nil and dec.request != "", do: dec.request, else: text
    today = Brain.today(ctx)

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
