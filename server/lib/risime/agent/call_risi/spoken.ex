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
    window = f[:window]
    events = f.events |> Enum.map(&with_title(&1, titles)) |> normalise() |> in_window(window)
    missing? = Enum.any?(events, &(&1.kind == "busy" and blank?(&1.title)))
    days = days(f.from, f.to)
    by_day = Enum.group_by(events, &day_of/1)
    # Harsha's 3rd call: "anything from 2 am until 10 pm?" — the day is said with its window.
    label = fn d -> day_label(d, f.today) <> window_phrase(window) end

    {busy_days, free_days} = Enum.split_with(days, &Map.has_key?(by_day, &1))
    # Device test 15 Oct: the phone's calendar didn't answer: its days are not "free", only
    # Risi's own items are known (and the unread line comes first).
    phone_unread? = Enum.any?(f.unread || [], &(&1 =~ "your phone calendar"))
    items_only? = f[:items_only] == true or phone_unread?

    text =
      cond do
        busy_days == [] and items_only? ->
          "You have no promises, reminders or follow-ups #{range_phrase(f.from, f.to, f.today)}#{window_phrase(window)}."

        busy_days == [] ->
          "You have nothing on your calendar #{range_phrase(f.from, f.to, f.today)}#{window_phrase(window)}."

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

                    cap(label.(d)) <>
                      ": " <> join(Enum.map(said, &phrase/1)) <> tail <> "."
                end

              {s, left - length(said)}
            end)

          extra = length(events) - @max_items

          day_lines =
            if items_only?,
              do: items_days(days, Map.new(Enum.zip(busy_days, sentences)), label),
              else: [
                Enum.reject(sentences, &is_nil/1),
                if(free_days != [] and length(days) > 1,
                  do: ["Nothing on " <> join(Enum.map(free_days, label)) <> "."],
                  else: []
                )
              ]

          [
            day_lines,
            if(extra > 0, do: ["The full list is in your Risi chat."], else: [])
          ]
          |> List.flatten()
          |> Enum.join(" ")
      end

    unread = Enum.join(f.unread || [], " ")

    text =
      if phone_unread?,
        do: String.trim(unread <> " " <> text),
        else: String.trim(text <> " " <> unread)

    {text, missing?}
  end

  # Device test 15 Oct (call 2): Risi's own items, day by day in order; a run of days without any
  # says "No promises or reminders today." (never "nothing scheduled": the phone may have events).
  defp items_days(days, said, label) do
    days
    |> Enum.chunk_by(&Map.has_key?(said, &1))
    |> Enum.flat_map(fn [d | _] = run ->
      if Map.has_key?(said, d),
        do: run |> Enum.map(&said[&1]) |> Enum.reject(&is_nil/1),
        else: ["No promises or reminders " <> join(Enum.map(run, label)) <> "."]
    end)
  end

  ## All-day items, duplicates, natural titles, the time window (Harsha's 3rd call, 15 Oct 14:13Z)

  @doc """
  The events as they are spoken: an item that is all day ("Free up calendar on 18th Oct whole
  day" stored as 00:00–01:00, or a block of 23 h or more from midnight) is `all_day`; a phone busy
  block at the same time as one of Risi's own items is that item (the Risi Calendar shows on the
  phone too), so it is said once; Risi's own titles are said naturally ("free up calendar", not
  "Free up calendar on 18th Oct whole day").
  """
  def normalise(events) do
    events = Enum.map(events, &(&1 |> all_day() |> natural()))

    own =
      for e <- events, e.kind != "busy", s = e.start_utc, is_binary(s), into: MapSet.new() do
        {unix(s), unix(e.end_utc)}
      end

    Enum.reject(events, fn e ->
      e.kind == "busy" and blank?(e.title) and
        MapSet.member?(own, {unix(e.start_utc), unix(e.end_utc)})
    end)
  end

  defp unix(s) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, d, _} -> DateTime.to_unix(d)
      _ -> s
    end
  end

  defp unix(s), do: s

  defp all_day(%{all_day: true} = e), do: e

  defp all_day(%{start: %NaiveDateTime{hour: 0, minute: 0} = s} = e) do
    long? =
      case e[:end] do
        %NaiveDateTime{} = x -> NaiveDateTime.diff(x, s) >= 23 * 3600
        _ -> false
      end

    if long? or (e.kind != "busy" and RisiMe.Agent.DayPart.all_day?(e.title || "")),
      do: %{e | all_day: true},
      else: e
  end

  defp all_day(e), do: e

  # Risi's own titles lose their date words; a Risi Calendar title follows "on your Risi
  # Calendar," so it starts lower case ("free up calendar"). Others keep their case ("Call Ruwan").
  defp natural(%{kind: "risi_calendar_event", title: t} = e) when is_binary(t) and t != "",
    do: %{e | title: natural_title(t)}

  defp natural(%{kind: k, title: t} = e) when k != "busy" and is_binary(t) and t != "",
    do: %{e | title: strip_title(t)}

  defp natural(e), do: e

  @month "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
  @wday "(?:mon|tues?|wed(?:nes)?|thu(?:rs)?|fri|sat(?:ur)?|sun)(?:day)?"
  @title_noise [
    Regex.compile!(
      "\\s*\\b(?:on\\s+|for\\s+|by\\s+)?(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?#{@month}\\b\\.?",
      "iu"
    ),
    Regex.compile!(
      "\\s*\\b(?:on\\s+|for\\s+|by\\s+)?#{@month}\\s+\\d{1,2}(?:st|nd|rd|th)?\\b",
      "iu"
    ),
    Regex.compile!("\\s*\\b(?:on\\s+|by\\s+|this\\s+|next\\s+)#{@wday}\\b", "iu"),
    ~r/\s*\b(?:the\s+)?(?:whole|entire|full|all)[\s-]+day(?:\s+long)?\b/iu,
    ~r/\s*\b(?:by|at|before|from|until|till)\s+\d{1,2}(?::\d\d)?\s*(?:a\.?\s?m\.?|p\.?\s?m\.?)(?=\W|$)/iu,
    ~r/\s*\b(?:today|tomorrow|tonight)\b/iu
  ]

  @doc ~S|"Free up calendar on 18th Oct whole day" → "free up calendar".|
  def natural_title(title) when is_binary(title) do
    case strip(title) do
      "" -> String.trim(title)
      t -> lower_first(t)
    end
  end

  @doc false
  def strip_title(title) do
    case strip(title) do
      "" -> String.trim(title)
      t -> t
    end
  end

  defp strip(title) do
    @title_noise
    |> Enum.reduce(title, &Regex.replace(&1, &2, ""))
    |> String.replace(~r/\s{2,}/u, " ")
    |> String.trim()
    |> String.replace(~r/[\s,;:.\-–]+$/u, "")
    |> String.replace(~r/\s+\b(?:on|for|by|at|from|of|the)$/iu, "")
    |> String.trim()
  end

  # "Free up calendar" → "free up calendar"; "SSH access" and "iPhone" stay.
  defp lower_first(<<c::utf8, d::utf8, rest::binary>> = t) do
    up = String.upcase(<<c::utf8>>) == <<c::utf8>> and String.downcase(<<c::utf8>>) != <<c::utf8>>
    d_lower = String.downcase(<<d::utf8>>) == <<d::utf8>>

    if up and d_lower,
      do: String.downcase(<<c::utf8>>) <> <<d::utf8>> <> rest,
      else: t
  end

  defp lower_first(t), do: t

  @doc """
  The time window of a calendar question ("from 2 am until 10 pm", "between 9 and 5", "after
  lunch", "in the morning", "after 6 pm", "before noon"): `{from :: Time, to :: Time | nil
  (end of day), phrase}`, or nil.
  """
  def window(text) when is_binary(text) do
    t = String.downcase(text)
    times = times(t)

    cond do
      length(times) >= 2 and Regex.match?(~r/\b(?:from|between|since)\b/u, t) and
          Regex.match?(~r/\b(?:to|until|till|til|and|through|up\s+to)\b|[–-]/u, t) ->
        [{a, a_m?}, {b, b_m?} | _] = times
        {a, b} = settle(a, a_m?, b, b_m?)
        if Time.compare(a, b) == :lt, do: {a, b, "between #{hm(a)} and #{hm(b)}"}

      length(times) == 1 and Regex.match?(~r/\b(?:after|from|since|later\s+than)\b/u, t) ->
        [{a, m?}] = times
        a = if m?, do: a, else: guess(a)
        {a, nil, "after #{hm(a)}"}

      length(times) == 1 and Regex.match?(~r/\b(?:before|until|till|by|up\s+to)\b/u, t) ->
        [{b, m?}] = times
        b = if m?, do: b, else: guess(b)
        {~T[00:00:00], b, "before #{hm(b)}"}

      Regex.match?(~r/\bafter\s+lunch\b/u, t) ->
        {~T[13:00:00], nil, "after lunch"}

      Regex.match?(~r/\bbefore\s+lunch\b/u, t) ->
        {~T[00:00:00], ~T[12:00:00], "before lunch"}

      Regex.match?(~r/\b(?:at|around|over)\s+lunch(?:time)?\b/u, t) ->
        {~T[12:00:00], ~T[14:00:00], "around lunch"}

      Regex.match?(~r/\b(?:in\s+the|this|tomorrow|that)\s+morning\b|\bmorning\b/u, t) ->
        {~T[00:00:00], ~T[12:00:00], "in the morning"}

      Regex.match?(~r/\bafternoon\b/u, t) ->
        {~T[12:00:00], ~T[17:00:00], "in the afternoon"}

      Regex.match?(~r/\bevening\b/u, t) ->
        {~T[17:00:00], nil, "in the evening"}

      Regex.match?(~r/\b(?:tonight|at\s+night)\b/u, t) ->
        {~T[18:00:00], nil, "tonight"}

      true ->
        nil
    end
  end

  def window(_), do: nil

  @not_times Regex.compile!(
               "\\b\\d{1,2}(?:st|nd|rd|th)\\b|\\b\\d{3,}\\b|\\b\\d{1,2}\\s+(?:of\\s+)?#{@month}\\b|\\b#{@month}\\s+\\d{1,2}\\b",
               "u"
             )

  @time_re ~r/\b(\d{1,2})(?::(\d\d))?\s*(a\.?\s?m\.?|p\.?\s?m\.?|o'?clock)?(?=\W|$)|\b(noon|midday|midnight)\b/u

  # The times said, in order: `{Time, meridiem_known?}` (a day's ordinal "18th" is no time).
  defp times(t) do
    t = Regex.replace(@not_times, t, " ")

    for m <- Regex.scan(@time_re, t), x = time_of(m), do: x
  end

  defp time_of([_, "", "", "", w]) when w in ["noon", "midday"], do: {~T[12:00:00], true}
  defp time_of([_, "", "", "", "midnight"]), do: {~T[00:00:00], true}

  defp time_of([_, h | rest]) do
    {mm, mer} =
      case rest do
        [m, mer | _] -> {m, mer}
        [m] -> {m, ""}
        [] -> {"", ""}
      end

    h = String.to_integer(h)
    m = if mm in [nil, ""], do: 0, else: String.to_integer(mm)
    pm? = String.starts_with?(mer, "p")
    am? = String.starts_with?(mer, "a")

    cond do
      h > 23 or m > 59 -> nil
      pm? and h < 12 -> {Time.new!(h + 12, m, 0), true}
      am? and h == 12 -> {Time.new!(0, m, 0), true}
      am? or pm? -> {Time.new!(h, m, 0), true}
      true -> {Time.new!(h, m, 0), h > 12}
    end
  end

  # "between 9 and 5": the second is after the first.
  defp settle(a, true, b, true), do: {a, b}
  defp settle(a, false, b, true), do: {fit_before(a, b), b}
  defp settle(a, true, b, false), do: {a, fit_after(b, a)}
  defp settle(a, false, b, false), do: (a = guess(a)) && {a, fit_after(b, a)}

  defp fit_after(b, a) do
    if Time.compare(b, a) != :gt and b.hour < 12,
      do: Time.add(b, 12 * 3600),
      else: b
  end

  defp fit_before(a, b) do
    if a.hour < 12 and Time.compare(Time.add(a, 12 * 3600), b) == :lt and b.hour >= 12 and
         a.hour < 7,
       do: Time.add(a, 12 * 3600),
       else: a
  end

  # A bare hour: 1–6 is afternoon, 7–11 morning.
  defp guess(%Time{hour: h} = t) when h >= 1 and h <= 6, do: Time.add(t, 12 * 3600)
  defp guess(t), do: t

  defp hm(%Time{} = t), do: clock(NaiveDateTime.new!(~D[2000-01-01], t))

  defp window_phrase(nil), do: ""
  defp window_phrase({_, _, phrase}), do: " " <> phrase

  # Keeps all-day items and those that overlap the window on their own day.
  defp in_window(events, nil), do: events

  defp in_window(events, {from, to, _}) do
    Enum.filter(events, fn
      %{all_day: true} ->
        true

      %{start: %NaiveDateTime{} = s} = e ->
        day = NaiveDateTime.to_date(s)
        w_from = NaiveDateTime.new!(day, from)

        w_to =
          if to,
            do: NaiveDateTime.new!(day, to),
            else: NaiveDateTime.new!(Date.add(day, 1), ~T[00:00:00])

        stop = e[:end] || s

        NaiveDateTime.compare(s, w_to) == :lt and
          (NaiveDateTime.compare(stop, w_from) == :gt or NaiveDateTime.compare(s, w_from) != :lt)

      _ ->
        true
    end)
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
        {"risi_calendar_event", t} when t != nil -> "on your Risi Calendar, #{t},"
        {"risi_calendar_event", nil} -> "on your Risi Calendar, an event,"
        {_, t} when t != nil -> t
        _ -> "something"
      end

    (base <> " " <> when_) |> String.trim() |> String.trim_trailing(",")
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

  @compose_items """
  You are Risi, speaking on a live phone call. Rephrase the ITEMS below as a natural spoken answer \
  to the user's question, in one to four short sentences. The ITEMS are only the promises, \
  reminders and follow-ups you keep for the user, NOT their whole calendar: a day without any has \
  "no promises or reminders"; never say a day is free, clear or has nothing scheduled or nothing on \
  the calendar. Use ONLY what the ITEMS say: never add, guess, merge or drop an item, and keep every \
  time exactly as written. Say "you", never "I". No lists, no markdown, no emoji. English.
  """

  @doc "The messages for risi-l1 to phrase `rendered` (the deterministic rendering) for `question`."
  def compose_messages(rendered, question, items_only? \\ false)

  def compose_messages(rendered, question, true) do
    [
      %{"role" => "system", "content" => @compose_items},
      %{
        "role" => "user",
        "content" => "Question: #{String.slice(question || "", 0, 300)}\nITEMS: #{rendered}"
      }
    ]
  end

  def compose_messages(rendered, question, false) do
    [
      %{"role" => "system", "content" => @compose},
      %{
        "role" => "user",
        "content" => "Question: #{String.slice(question || "", 0, 300)}\nCALENDAR: #{rendered}"
      }
    ]
  end

  @free ~r/\b(?:nothing\s+(?:scheduled|planned|on)|free|clear|no\s+(?:events|plans|appointments|meetings))\b/iu

  @comment ~r/\b(?:access|notes?|permission|should\s+(?:have|add|be\s+able))\b/iu

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
      said == "" ->
        nil

      Regex.match?(@refuse, said) ->
        nil

      # Harsha's 3rd call: never a write claim in a read's phrasing.
      RisiMe.Agent.ClaimGuard.claim?(said) and not RisiMe.Agent.ClaimGuard.claim?(rendered) ->
        RisiMe.Agent.ClaimGuard.violation("call_rephrase")
        nil

      # Pilot call 15 Oct, 13:10Z: "You have access and should add the notes in Calendar." The
      # model echoed the user's complaint: only the calendar, never a comment on the question.
      Regex.match?(@comment, said) and not Regex.match?(@comment, rendered) ->
        nil

      # What couldn't be read is always said.
      Regex.match?(~r/couldn'?t reach/u, rendered) and
          not Regex.match?(
            ~r/(?:couldn'?t|could not|can'?t|cannot|wasn'?t able to)\s+(?:reach|read|get to)/iu,
            said
          ) ->
        nil

      # Risi's own items only: a day without any is never "free" (the phone may have events).
      Regex.match?(~r/\b[Nn]o promises\b/u, rendered) and Regex.match?(@free, said) ->
        nil

      # The calendar is the user's: "I am busy", "I can get the calendar" are wrong.
      not Regex.match?(~r/\bI\b/u, rendered) and Regex.match?(~r/\bI\b/u, said) ->
        nil

      Regex.match?(~r/\b(?:From|Checked|Not checked):/u, said) ->
        nil

      String.length(said) > String.length(rendered) * 2 + 120 ->
        nil

      not MapSet.subset?(got, allowed) ->
        nil

      not MapSet.subset?(want, got) ->
        nil

      not Enum.all?(titles, &String.contains?(String.downcase(said), String.downcase(&1))) ->
        nil

      true ->
        said
    end
  end

  def checked(_said, _rendered, _titles), do: nil

  @doc "The titles `render/2` speaks for `facts` (for `checked/3`)."
  def titles(%{ok?: true, events: evs} = f, titles) do
    evs = evs |> Enum.map(&with_title(&1, titles)) |> normalise() |> in_window(f[:window])
    for e <- evs, t = e.title, is_binary(t), t != "", do: Sentences.clean(t)
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
    {~w(memory_search summarise_range),
     "read your Official messages (ask me \"any new messages?\" or whether someone replied) and recall what was said or agreed there"},
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
