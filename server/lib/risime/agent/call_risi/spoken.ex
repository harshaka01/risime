defmodule RisiMe.Agent.CallRisi.Spoken do
  @moduledoc """
  P0 2026-10-15 (Harsha's Call Risi call): what Risi **says** on a call is a spoken answer built
  from the tool's results, never a card's text or footer.

    * `calendar/2`: the schedule read's facts (`RisiMe.Agent.RisiItems.voice_facts/4`: the phone's
      busy times, Risi Calendar events, reminders, promises, follow-ups, scheduled messages; local
      times) → a deterministic spoken rendering (`render/2`), which risi-l1 may rephrase in one to
      four sentences (`compose/3`, in `RisiMe.Agent.CallRisi.Brain`). The model's text is used only
      when it keeps every time of the facts and adds no number of its own; else the rendering is
      spoken. Never invents an item: only what the facts hold.
    * Titles: the phone's events carry no titles on the server (§29.7). `opts[:titles]` (a
      function `(start_utc, end_utc) -> title | nil`, the hook for the phone's data-channel titles)
      enriches them for **speech only**: the stored rendering (`render(facts, titles: nil)`) never
      has them.
    * `capabilities/1`: a TRUE spoken list of what Risi can do on this call (the tools the
      registry offers this user).
    * `speakable/1`: a non-calendar answer card's text without its footer lines.

  Pure functions; no I/O.
  """

  alias RisiMe.Agent.CallRisi.Sentences

  @max_items 8

  ## Calendar

  @doc """
  The spoken rendering of `facts`. `opts`: `titles` (fn or nil), `question` (unused here).
  Returns `{text, titles_missing?}`: `titles_missing?` when a phone event was spoken without a
  title (the caller adds the once-per-call titles line).
  """
  def render(facts, opts \\ [])

  def render(%{ok?: false, failed: failed}, _opts), do: {failed, false}

  def render(%{ok?: true} = f, opts) do
    titles = Keyword.get(opts, :titles)
    events = Enum.map(f.events, &with_title(&1, titles))
    missing? = Enum.any?(events, &(&1.kind == "busy" and blank?(&1.title)))
    days = days(f.from, f.to)
    by_day = Enum.group_by(events, &day_of/1)

    {busy_days, free_days} = Enum.split_with(days, &Map.has_key?(by_day, &1))

    text =
      cond do
        busy_days == [] and f[:items_only] == true ->
          "You have no promises, reminders or follow-ups #{range_phrase(f.from, f.to, f.today)}."

        busy_days == [] ->
          "You have nothing on your calendar #{range_phrase(f.from, f.to, f.today)}."

        true ->
          {sentences, _left} =
            Enum.map_reduce(busy_days, @max_items, fn d, left ->
              items = Map.fetch!(by_day, d)
              said = Enum.take(items, max(left, 0))
              more = length(items) - length(said)

              s =
                cond do
                  said == [] ->
                    nil

                  true ->
                    tail = if more > 0, do: ", and #{more} more", else: ""

                    cap(day_label(d, f.today)) <>
                      ": " <> join(Enum.map(said, &phrase/1)) <> tail <> "."
                end

              {s, left - length(said)}
            end)

          extra = length(events) - @max_items

          [
            Enum.reject(sentences, &is_nil/1),
            if(free_days != [] and length(days) > 1,
              do: ["Nothing on " <> join(Enum.map(free_days, &day_label(&1, f.today))) <> "."],
              else: []
            ),
            if(extra > 0, do: ["The full list is in your Risi chat."], else: [])
          ]
          |> List.flatten()
          |> Enum.join(" ")
      end

    unread = Enum.join(f.unread || [], " ")
    {String.trim(text <> " " <> unread), missing?}
  end

  @own ~w(promise follow_up reminder scheduled_message)

  @doc "Only Risi's own items of `facts` (promises, follow-ups, reminders, scheduled messages)."
  def items_only(%{ok?: true, events: evs} = f) do
    own = for e <- evs, e.kind in @own or Enum.any?(e.tags || [], &(&1 in @own)), do: e
    Map.merge(f, %{events: own, items_only: true})
  end

  def items_only(f), do: f

  defp with_title(%{title: t} = e, _titles) when is_binary(t) and t != "", do: e

  defp with_title(%{kind: "busy"} = e, titles) when is_function(titles, 2) do
    case safe_title(titles, e.start_utc, e.end_utc) do
      t when is_binary(t) and t != "" -> %{e | title: String.slice(t, 0, 80)}
      _ -> e
    end
  end

  defp with_title(e, _titles), do: e

  defp safe_title(f, s, e) do
    f.(s, e)
  rescue
    _ -> nil
  end

  @doc "True when any phone event in `facts` got a title from `titles` (speech only)."
  def titled?(%{ok?: true, events: evs}, titles) when is_function(titles, 2),
    do: Enum.any?(evs, &(&1.kind == "busy" and with_title(&1, titles).title != nil))

  def titled?(_facts, _titles), do: false

  defp blank?(t), do: not (is_binary(t) and String.trim(t) != "")

  defp day_of(%{start: %NaiveDateTime{} = s}), do: NaiveDateTime.to_date(s)
  defp day_of(_), do: nil

  defp days(from, to) do
    n = max(Date.diff(to, from), 1)
    for i <- 0..(n - 1), do: Date.add(from, i)
  end

  # One item, spoken: "busy from 2 to 3 pm", "Weekly sync from 10 to 11 am", "a reminder, Call
  # Ruwan, at 5 pm", "your promise, Send the quote", "a scheduled message at 9 am".
  defp phrase(%{kind: kind, title: title} = e) do
    when_ = when_phrase(e)
    t = if blank?(title), do: nil, else: Sentences.clean(title)

    base =
      case {kind, t} do
        {"busy", nil} -> "busy"
        {"reminder", t} when t != nil -> "a reminder, #{t},"
        {"reminder", nil} -> "a reminder"
        {"promise", t} when t != nil -> "your promise, #{t},"
        {"follow_up", t} when t != nil -> "a follow-up, #{t},"
        {"scheduled_message", _} -> "a scheduled message"
        {_, t} when t != nil -> t
        _ -> "something"
      end

    tail = if kind == "risi_calendar_event", do: " on your Risi Calendar", else: ""
    (base <> " " <> when_) |> String.trim() |> String.trim_trailing(",") |> Kernel.<>(tail)
  end

  defp when_phrase(%{all_day: true}), do: "all day"

  defp when_phrase(%{kind: k, start: %NaiveDateTime{} = s, end: e})
       when k in ~w(reminder scheduled_message) or e == nil,
       do: "at " <> clock(s)

  defp when_phrase(%{kind: k, start: %NaiveDateTime{} = s})
       when k in ~w(promise follow_up),
       do: "due " <> clock(s)

  defp when_phrase(%{start: %NaiveDateTime{} = s, end: %NaiveDateTime{} = e}), do: span(s, e)
  defp when_phrase(_), do: ""

  @doc ~S|"2 pm", "2:30 pm", "12 noon", "midnight".|
  def clock(%NaiveDateTime{hour: 0, minute: 0}), do: "midnight"
  def clock(%NaiveDateTime{hour: 12, minute: 0}), do: "12 noon"

  def clock(%NaiveDateTime{hour: h, minute: m}) do
    h12 = if rem(h, 12) == 0, do: 12, else: rem(h, 12)
    mm = if m == 0, do: "", else: ":" <> String.pad_leading(Integer.to_string(m), 2, "0")
    "#{h12}#{mm} #{if h < 12, do: "am", else: "pm"}"
  end

  @doc ~S|"from 2 to 3 pm", "from 11 am to 1 pm".|
  def span(%NaiveDateTime{} = s, %NaiveDateTime{} = e) do
    if s.hour < 12 == e.hour < 12 and s.hour not in [0, 12] and
         not (e.hour == 12 and e.minute == 0) do
      [a, _] = String.split(clock(s), " ")
      "from #{a} to #{clock(e)}"
    else
      "from #{clock(s)} to #{clock(e)}"
    end
  end

  @doc ~S|"today", "tomorrow", "Saturday the 17th".|
  def day_label(%Date{} = d, %Date{} = today) do
    case Date.diff(d, today) do
      0 -> "today"
      1 -> "tomorrow"
      _ -> Elixir.Calendar.strftime(d, "%A") <> " the " <> ordinal(d.day)
    end
  end

  def day_label(_, _), do: "that day"

  defp range_phrase(from, to, today) do
    case Date.diff(to, from) do
      1 -> day_label(from, today)
      _ -> "from #{day_label(from, today)} to #{day_label(Date.add(to, -1), today)}"
    end
  end

  @doc false
  def ordinal(n) when n in [11, 12, 13], do: "#{n}th"

  def ordinal(n) do
    case rem(n, 10) do
      1 -> "#{n}st"
      2 -> "#{n}nd"
      3 -> "#{n}rd"
      _ -> "#{n}th"
    end
  end

  defp join([]), do: ""
  defp join([a]), do: a
  defp join([a, b]), do: a <> " and " <> b
  defp join(xs), do: Enum.join(Enum.drop(xs, -1), ", ") <> ", and " <> List.last(xs)

  defp cap(<<c::utf8, rest::binary>>), do: String.upcase(<<c::utf8>>) <> rest
  defp cap(s), do: s

  ## The model's phrasing (checked)

  @compose """
  You are Risi, speaking on a live phone call. Rephrase the CALENDAR below as a natural spoken \
  answer to the user's question, in one to four short sentences. Use ONLY what the CALENDAR says: \
  never add, guess, merge or drop an item, keep every time exactly as written, keep "busy" for an \
  item without a name, and say a day with nothing is free. It is the USER's calendar: say "you" \
  ("you're busy from 2 to 3 pm"), never "I", and don't comment on the question, just give the \
  calendar. No lists, no markdown, no emoji. English.
  """

  @doc "The messages for risi-l1 to phrase `rendered` (the deterministic rendering) for `question`."
  def compose_messages(rendered, question) do
    [
      %{"role" => "system", "content" => @compose},
      %{
        "role" => "user",
        "content" => "Question: #{String.slice(question || "", 0, 300)}\nCALENDAR: #{rendered}"
      }
    ]
  end

  @refuse ~r/\b(?:can(?:'|no)?t|cannot|unable to|don'?t have access|no access)\b.{0,30}\b(?:see|access|read|check|get)\b/iu

  @doc """
  The model's phrasing `said` is used only when it is safe against `rendered`: it keeps every
  number of the rendering, adds none, never refuses ("I can't see your calendar"), carries no
  card text ("From:", "Checked:"), names every title in `titles`, and is not much longer. Else nil.
  """
  def checked(said, rendered, titles \\ [])

  def checked(said, rendered, titles) when is_binary(said) do
    said = Sentences.clean(said)
    # Every time must stay; a day's ordinal ("the 17th") may be dropped ("Saturday").
    allowed = numbers(rendered)
    want = numbers(Regex.replace(~r/\d+(?:st|nd|rd|th)\b/u, rendered, ""))
    got = numbers(said)

    cond do
      said == "" -> nil
      Regex.match?(@refuse, said) -> nil
      # The calendar is the user's: "I am busy", "I can get the calendar" are wrong.
      not Regex.match?(~r/\bI\b/u, rendered) and Regex.match?(~r/\bI\b/u, said) -> nil
      Regex.match?(~r/\b(?:From|Checked|Not checked):/u, said) -> nil
      String.length(said) > String.length(rendered) * 2 + 120 -> nil
      not MapSet.subset?(got, allowed) -> nil
      not MapSet.subset?(want, got) -> nil
      not Enum.all?(titles, &String.contains?(String.downcase(said), String.downcase(&1))) -> nil
      true -> said
    end
  end

  def checked(_said, _rendered, _titles), do: nil

  @doc "The titles `render/2` speaks for `facts` (for `checked/3`)."
  def titles(%{ok?: true, events: evs}, titles) do
    for e <- evs, t = with_title(e, titles).title, is_binary(t), t != "", do: Sentences.clean(t)
  end

  def titles(_facts, _titles), do: []

  defp numbers(text) do
    ~r/\d+/u |> Regex.scan(text) |> List.flatten() |> MapSet.new()
  end

  ## Capabilities

  @caps [
    {~w(risi_calendar_check calendar_check),
     "tell you what's on your calendar for any day or week"},
    {~w(risi_items), "go through the reminders, promises and follow-ups I'm keeping for you"},
    {~w(memory_search summarise_range), "recall what was said or agreed in your Official chats"},
    {~w(set_reminder set_alarm), "set reminders"},
    {~w(risi_calendar_add calendar_add), "add events to your calendar"},
    {~w(send_message), "send a message for you"},
    {~w(schedule_message), "schedule a message"}
  ]

  @writes ~w(set_reminder set_alarm risi_calendar_add calendar_add send_message schedule_message)

  @doc """
  The spoken answer to "what can you do?" on a call: only what the registry offers this user
  (`names`, the allowed tool names), plus chat. Writes wait for the user's OK in the Risi chat.
  """
  def capabilities(names) when is_list(names) do
    can = for {tools, line} <- @caps, Enum.any?(tools, &(&1 in names)), do: line

    case can do
      [] ->
        "On this call I can chat with you and answer general questions. " <>
          "Your calendar, reminders and promises aren't switched on for me yet."

      lines ->
        ok_line =
          if Enum.any?(@writes, &(&1 in names)),
            do: " Anything I set up waits for your OK in your Risi chat.",
            else: ""

        "On this call I can " <>
          join(lines) <> ". I can also just chat or answer general questions." <> ok_line
    end
  end

  ## Non-calendar answers

  @footer ~r/^\s*(?:From:|Checked:|Not checked:|✓)/u

  @doc "A card's answer text without its footer lines, cleaned for speech."
  def speakable(text) when is_binary(text) do
    text
    |> String.split("\n")
    |> Enum.reject(&Regex.match?(@footer, &1))
    |> Enum.map(&Sentences.clean/1)
    |> Enum.reject(&(&1 == ""))
    |> Enum.map(&say_times/1)
    |> Enum.map(fn l -> if Regex.match?(~r/[.!?…:]$/u, l), do: l, else: l <> "." end)
    |> Enum.join(" ")
  end

  def speakable(_), do: ""

  # "17:00" → "5 pm", "10:00–11:00" → "10 am to 11 am", " · " → ", " (the voice reads them aloud).
  defp say_times(line) do
    line
    |> String.replace(~r/\s*·\s*/u, ", ")
    |> String.replace(~r/\b([01]?\d|2[0-3]):([0-5]\d)\b/u, fn hm ->
      [h, m] = hm |> String.split(":") |> Enum.map(&String.to_integer/1)
      clock(NaiveDateTime.new!(~D[2000-01-01], Time.new!(h, m, 0)))
    end)
    |> String.replace(~r/(am|pm|noon|midnight)\s*[–-]\s*/u, "\\1 to ")
  end
end
