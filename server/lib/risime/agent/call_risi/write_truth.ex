defmodule RisiMe.Agent.CallRisi.WriteTruth do
  @moduledoc """
  P0 2026-10-15 (Harsha's 3rd Call Risi call, 14:13–14:17Z): on a call Risi said "I have added the
  racing discussion to your calendar" and then "I have added the entry for you" when nothing was
  added (the chat route has no tools; the card in the Risi chat was never confirmed).

  **Rule:** on a call, Risi never claims a write (added, saved, scheduled, set, sent, booked,
  created, put, blocked, reminded …) unless a tool result of THIS call reported it done. The truth
  comes from the write's own row (`risi_pending_writes`, by the request id of the call's write
  turn), never from the model.

    * `claim?/1` — a sentence that claims (or promises) a write;
    * `confirmation?/1`, `complaint?/1`, `correction?/1` — how a reply after a write turn is read
      (a "that's correct" is for Risi; "I can't see that you added it" asks for the truth; "from 9
      to 10, discuss racing" changes the card);
    * `status_line/3` — the true spoken status of the call's last write card;
    * `state/1` — the write's state (`config :risime, :risi_call_write_state` may replace it).

  Pure functions except `state/1` (one indexed read).
  """

  import Ecto.Query

  @doc "True when `text` claims (or promises) a write (the shared `RisiMe.Agent.ClaimGuard`)."
  def claim?(text), do: RisiMe.Agent.ClaimGuard.claim?(text, true)

  # "That's correct", "yes", "yeah, that's for you, just add that entry", "go ahead", "add it".
  @confirm ~r/^\W*(?:(?:yes|yeah|yep|yup|ya|ok(?:ay)?|sure|right|correct|exactly|perfect|great|good|fine|please|alright|absolutely|definitely|of\s+course)\b|that'?s\s+(?:correct|right|it|fine|good|perfect|great)\b|that\s+is\s+(?:correct|right|it|fine)\b|go\s+ahead\b|do\s+it\b|confirm\b|(?:just\s+|please\s+)?(?:add|put|save|book|block)\s+(?:it|that|this|them)\b)|\b(?:that'?s|it'?s|this\s+is)\s+for\s+you\b|\b(?:just|please)\s+(?:add|put|save)\b/iu

  @doc "True when `text` confirms or approves what Risi just offered (after a write turn)."
  def confirmation?(text) when is_binary(text), do: Regex.match?(@confirm, text)
  def confirmation?(_), do: false

  # "I can't see that you have added something", "did you add it?", "is it there?", "nothing
  # happened", "where is it?".
  @complaint [
    ~r/\b(?:can'?t|cannot|don'?t|do\s+not|didn'?t|did\s+not|doesn'?t|isn'?t|is\s+not|not)\b.{0,40}\b(?:see|find|added|add|there|showing|show|appear\w*|in\s+(?:my|the)\s+calendar|happen\w*|work\w*)\b/iu,
    ~r/\b(?:did|have|has)\s+(?:you|it)\s+(?:really\s+|actually\s+)?(?:add|added|put|save|saved|book|booked|done|gone|go)\b/iu,
    ~r/\b(?:is|was)\s+(?:it|that|the\s+\w+)\s+(?:really\s+|actually\s+)?(?:added|there|in|saved|done|booked)\b/iu,
    ~r/\bwhere\s+(?:is|did)\s+(?:it|that)\b/iu,
    ~r/\bnothing\s+(?:happened|was\s+added|there|changed)\b/iu
  ]

  @doc "True when `text` asks whether the write happened (or says it can't see it)."
  def complaint?(text) when is_binary(text), do: Enum.any?(@complaint, &Regex.match?(&1, text))
  def complaint?(_), do: false

  # A reply that changes the card being made: a time ("from 9 to 10", "at 3", "10 am"), a title
  # ("discuss racing", "about the budget", "call it …"), a day, a length, or a change word.
  @time ~r/\b(?:from|at|to|till|until|between|by)\s+\d{1,2}(?::\d\d)?\b|\b\d{1,2}(?::\d\d)?\s*(?:a\.?\s?m\.?|p\.?\s?m\.?|o'?clock)|\b(?:noon|midnight|morning|afternoon|evening|tonight)\b/iu
  @detail ~r/\b(?:discuss\w*|about|regarding|called|call\s+it|name\s+it|title|make\s+it|change|instead|actually|rather|not\s+\d|with\s+\p{Lu}|hours?|minutes?|mins?|whole\s+day|all\s+day|tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b/iu

  @doc "True when `text` (a reply after a write turn) carries a change for the card."
  def correction?(text) when is_binary(text),
    do: Regex.match?(@time, text) or Regex.match?(@detail, text)

  def correction?(_), do: false

  ## The truth

  @doc """
  The state of the call's write for `request_id`: `%{state, confirmed?, tool}` of the newest
  `risi_pending_writes` row of that request, or nil (no card was made).
  """
  def state(nil), do: nil

  def state(request_id) do
    case Application.get_env(:risime, :risi_call_write_state) do
      f when is_function(f, 1) -> f.(request_id)
      _ -> db_state(request_id)
    end
  end

  defp db_state(request_id) do
    with {:ok, rid} <- Ecto.UUID.cast(request_id) do
      RisiMe.Repo.one(
        from w in RisiMe.Agent.Writes.Write,
          where: w.request_id == ^rid,
          order_by: [desc: w.inserted_at],
          limit: 1,
          select: %{state: w.state, confirmed_at: w.confirmed_at, tool: w.tool}
      )
      |> case do
        nil -> nil
        w -> %{state: w.state, confirmed?: w.confirmed_at != nil, tool: w.tool}
      end
    else
      _ -> nil
    end
  rescue
    _ -> nil
  end

  @doc """
  What Risi truthfully says about the call's last write (`write`: `%{request_id, route}` or nil)
  when the user confirms (`:confirm`), asks or complains (`:complaint`), or the chat route tried
  to claim a write (`:claim`).
  """
  def status_line(write, why, state_fun \\ &state/1)

  def status_line(nil, _why, _state_fun),
    do:
      "I haven't added anything on this call. Tell me what to add, with the day and time, " <>
        "and I'll put a card in your Risi chat for you to confirm."

  def status_line(%{} = write, why, state_fun) do
    s = state_fun.(write[:request_id])
    where = target(s, write)

    case s do
      %{state: st} when st in ~w(done running) ->
        if why == :complaint,
          do: "Yes, it's added: it's in your #{where}. Open your #{where} in the app to see it.",
          else: "It's done: it's in your #{where}."

      %{state: "confirmed"} ->
        "It was confirmed, but it didn't go through, so it isn't in your #{where}. " <>
          "Tap Retry on the card in your Risi chat."

      # Contract §25.3: the phone adds to its own calendar only after its user's own tap (or a
      # typed request): a call can't confirm it.
      %{state: "pending", tool: t} when t in ~w(calendar_add set_alarm) and why != :complaint ->
        "I haven't added it yet. Your phone adds this one itself, so tap Add on the card in your " <>
          "Risi chat to put it in your #{where}."

      %{state: "pending"} ->
        if why == :complaint,
          do:
            "It isn't added yet: the card in your Risi chat hasn't been confirmed. " <>
              "Tap Add on it to put it in your #{where}.",
          else:
            "I haven't added it yet. Tap Add on the card in your Risi chat to put it in your " <>
              "#{where}, or Cancel."

      %{state: "clarify"} ->
        "I haven't added it yet: I asked you a question about it in your Risi chat."

      %{state: "cancelled"} ->
        "That card was cancelled, so nothing was added."

      %{state: st} when st in ~w(superseded void) ->
        "Nothing was added: that card was replaced. Tell me what to add and I'll put a new " <>
          "card in your Risi chat."

      _ ->
        "I haven't added anything yet. Tell me what to add, with the day and time, and I'll " <>
          "put a card in your Risi chat for you to confirm."
    end
  end

  defp target(%{tool: "calendar_add"}, _w), do: "calendar"
  defp target(%{tool: t}, _w) when t in ~w(set_reminder set_alarm), do: "reminders"
  defp target(_s, %{route: "reminder"}), do: "reminders"
  defp target(_s, _w), do: "Risi Calendar"

  ## The card, spoken

  @doc """
  The card's summary ("Add to your Risi Calendar: Discuss racing · Fri 16 Oct, 9–10 AM"), spoken:
  "I've put a card in your Risi chat: Discuss racing, tomorrow, 9 to 10 am. Tap Add to put it in
  your Risi Calendar, or Cancel."
  """
  def card_line(summary, today, voice_confirm? \\ false)

  def card_line(summary, today, voice_confirm?) when is_binary(summary) do
    {where, what} = parts(summary, today)

    cond do
      voice_confirm? and where ->
        "I haven't added it yet. I've drafted it: " <> what <> ". Shall I add it to #{where}?"

      where ->
        "I haven't added it yet. I've put a card in your Risi chat: " <>
          what <>
          ". Tap Add to put it in #{where}, or Cancel."

      true ->
        "I haven't added it yet. I've put a card in your Risi chat: " <>
          what <>
          ". Tap Add, or Cancel."
    end
  end

  def card_line(_summary, _today, _voice?), do: nil

  @doc """
  "Added to your Risi Calendar: Discuss racing, tomorrow, 9 to 10 am." — said only after the
  write's own row is `done` (the caller checks with `RisiMe.Agent.ClaimGuard.done?/1`).
  """
  def added_line(summary, today) when is_binary(summary) do
    case parts(summary, today) do
      {nil, what} -> "Added: " <> what <> "."
      {where, what} -> "Added to #{where}: " <> what <> "."
    end
  end

  def added_line(_summary, _today), do: "Added."

  # The card's summary → `{where, what}`. Summaries: "Add to your Risi Calendar: T · Fri 16 Oct, 9–10
  # AM", "Add \"T\" to your calendar on Fri 16 Oct, 16:00–17:00", "Remind you on <when>: T".
  defp parts(summary, today) do
    {where, what} =
      cond do
        m = Regex.run(~r/^Add to your Risi Calendar:\s*(.+)$/su, summary) ->
          {"your Risi Calendar", Enum.at(m, 1)}

        m = Regex.run(~r/^Add "(.+)" to your calendar(?: on)?\s*(.*)$/su, summary) ->
          {"your calendar", Enum.at(m, 1) <> ", " <> Enum.at(m, 2)}

        m = Regex.run(~r/^Remind you (?:on|at)\s+(.+):\s+(.+)$/su, summary) ->
          {"your reminders", Enum.at(m, 2) <> ", " <> Enum.at(m, 1)}

        true ->
          {nil, summary}
      end

    what =
      what
      |> String.trim()
      |> String.trim_trailing("?")
      |> String.replace(~r/\s*·\s*/u, ", ")
      |> days(today)
      |> String.replace(
        ~r/\b(\d{1,2}(?::\d\d)?)\s*[–-]\s*(\d{1,2}(?::\d\d)?)\s*(AM|PM)\b/u,
        "\\1 to \\2 \\3"
      )
      |> String.replace(~r/\b(AM|PM)\s*[–-]\s*/u, "\\1 to ")
      |> String.replace(~r/\b(AM|PM)\b/u, &String.downcase/1)
      |> RisiMe.Agent.CallRisi.Spoken.speakable()
      |> String.trim_trailing(".")

    {where, what}
  end

  @months ~w(Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec)

  # "Fri 16 Oct" → "tomorrow" / "Saturday the 17th".
  defp days(text, %Date{} = today) do
    Regex.replace(
      ~r/\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\b/u,
      text,
      fn whole, d, m ->
        month = Enum.find_index(@months, &(&1 == m)) + 1
        year = if month < today.month - 6, do: today.year + 1, else: today.year

        case Date.new(year, month, String.to_integer(d)) do
          {:ok, date} -> RisiMe.Agent.CallRisi.Spoken.day_label(date, today)
          _ -> whole
        end
      end
    )
  end

  defp days(text, _), do: text
end
