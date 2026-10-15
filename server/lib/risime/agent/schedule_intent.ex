defmodule RisiMe.Agent.ScheduleIntent do
  @moduledoc """
  The deterministic schedule-question classifier of contract v1.35 §34.1 (P0 2026-10-11) and the
  "what have you set up for me?" route of §34.4. English, Sinhala, Tamil and Singlish. Its
  required behaviour is the fixture `contract/v1/risi_routing_cases.json`; the word lists here
  are implementation.

  `classify(text, today)` → `{:schedule_read, %{from: Date, to: Date, now?: bool}}` (`to`
  exclusive; `now?` when no day was named: the read starts now), `:risi_items`, v1.36 §35.2
  `{:memory, %{mode, range, people, query}}` (`RisiMe.Agent.MemoryIntent`), or `:none`.

  A request whose main verb writes something (add, put, book, create, set, remind, send,
  schedule a …, and the Sinhala/Tamil/Singlish equivalents) is never a schedule question.
  """

  @max_days 14

  # "what have you set up for me?" (§34.4)
  @items [
    ~r/\bwhat\s+(?:have|did|has)\s+(?:you|risi)\s+(?:set\s*up|setup|added?|scheduled?|created?|put|made|arranged|organi[sz]ed|booked|done)\b/iu,
    ~r/\b(?:show|list|see|view)?\s*(?:me\s+)?(?:all\s+)?my\s+reminders\b/iu,
    ~r/\bwhat\s+reminders\b/iu,
    ~r/\brisi'?s\s+items\b/iu,
    ~r/(?:ඔයා|ඔබ|risi).{0,30}(?:set|සෙට්|හදලා|දාලා|සකස්).{0,10}(?:කරලා|කළ|තියෙන)/iu,
    ~r/මගේ\s+මතක්\s+කිරීම්/u,
    ~r/(?:நீ|நீங்கள்|ரிசி).{0,30}(?:அமைத்த|செய்து\s+வைத்த|சேர்த்த|set)/iu,
    ~r/என்\s+நினைவூட்டல்கள்/u,
    ~r/\b(?:oya|oyaa|obe?)\b.{0,30}\b(?:set|haduwa|dala|damma)\b.{0,15}\b(?:karala|thiyenne|thiyena)\b/iu
  ]

  @write [
    ~r/\b(?:add|put|book|create|remind|send|cancel|delete|remove|reschedule|invite|move|text|message|tell|draft)\b/iu,
    ~r/\bset\s+(?:a|an|up|my|the|me|it|this|that|alarm|reminder)\b/iu,
    ~r/\bschedule\s+(?:a|an|the|my|it|this|that|message|meeting|call|for|to)\b/iu,
    ~r/(?:දාන්න|එකතු\s+කරන්න|මතක්\s+කරන්න|යවන්න|හදන්න)/u,
    ~r/(?:சேர்|நினைவூட்டு|நினைவூட்ட|அனுப்பு|அனுப்ப|உருவாக்கு)/u,
    ~r/\b(?:danna|daanna|mathak\s+karanna|yawanna|yawapan|add\s+karanna|hadanna)\b/iu
  ]

  @schedule [
    ~r/\b(?:schedule|appointments?|appts?|meetings?|calendar|agenda|diary|plans|events?|busy|free|available|availability|engagements?)\b/iu,
    ~r/\bwhat'?s\s+on\b|\bwhats\s+on\b|\bwhat\s+is\s+on\b|\banything\s+on\b|\bam\s+i\s+(?:free|busy|available)\b/iu,
    ~r/(?:හමුවීම|රැස්වීම|කාලසටහන|දින\s*දර්ශන|නිදහස්|වැඩ\s+තියෙනවද)/u,
    ~r/(?:சந்திப்பு|கூட்டம்|கூட்டங்கள்|அட்டவணை|நாட்காட்டி|வேலை\s+இருக்கிறதா)/u,
    ~r/\b(?:thiyenawada|thiyanawada|thiyenne|thiyenawa|mokada|mokakda|monawada|monawa|wada)\b/iu,
    ~r/(?:තියෙනවද|තියෙන්නේ|මොනවද|உள்ளன|இருக்கிறது|இருக்கிறதா|என்ன\s+இருக்கு)/u
  ]

  @other ~r/\b(?:summari[sz]e|summary|translate|explain|pdf|export|share|forward|feel\s+free|for\s+free|free\s+to)\b/iu

  @presence ~r/\b(?:what|how)\s+about\b|\banything\b|\b(?:do|did|have|will)\s+i\s+(?:have|got)\b|\bwhat\s+(?:do|did)\s+i\s+have\b|\bwhat'?s\s+(?:on|happening|planned)\b|\bcheck\b/iu

  @promise ~r/\bpromis(?:e|ed|es)\b|\bcommit(?:ted|ments?)\b|පොරොන්දු|வாக்குறுதி/iu

  @doc "True when `text` is about promises or commitments (P0 2026-10-15)."
  def promise?(text) when is_binary(text), do: Regex.match?(@promise, text)
  def promise?(_), do: false

  @doc "Classifies `text` (see the module doc); `today` is the asker's local date."
  def classify(text, %Date{} = today) when is_binary(text) do
    t = String.trim(text)

    cond do
      t == "" -> :none
      Enum.any?(@items, &Regex.match?(&1, t)) -> :risi_items
      # P0 2026-10-15: "did I promise anything today or tomorrow?" is the day's agenda (it lists
      # the promises, reminders and follow-ups due then); without a day it is memory (below).
      Regex.match?(@promise, t) and day_named?(t, today) -> {:schedule_read, range(t, today)}
      # v1.36 §35.2: summarise a range / what did I agree / what's pending → memory.
      match?({:memory, _}, memory = RisiMe.Agent.MemoryIntent.classify(t, today)) -> memory
      Enum.any?(@write, &Regex.match?(&1, t)) -> :none
      Regex.match?(@other, t) -> :none
      schedule?(t) -> {:schedule_read, range(t, today)}
      true -> :none
    end
  end

  def classify(_text, _today), do: :none

  @doc "True when `text` asks about the schedule (also used on the model's own answer, §34.1)."
  def schedule_words?(text) when is_binary(text),
    do: Enum.any?(@schedule, &Regex.match?(&1, text))

  def schedule_words?(_), do: false

  defp schedule?(t) do
    schedule_words?(t) or (Regex.match?(@presence, t) and day_named?(t))
  end

  defp day_named?(t), do: elem(day(t, ~D[2000-01-01]), 0) != :none

  ## The range

  @doc """
  The range of `text` from `today`: `%{from, to (exclusive), now?}`. No day named: from now to
  the end of today. Clamped to 14 days.
  """
  def range(text, %Date{} = today) do
    case day(text, today) do
      {:none, _} ->
        %{from: today, to: Date.add(today, 1), now?: true}

      {_, {from, to}} ->
        to = if Date.diff(to, from) > @max_days, do: Date.add(from, @max_days), else: to
        %{from: from, to: to, now?: false}
    end
  end

  @months %{
    "jan" => 1,
    "january" => 1,
    "feb" => 2,
    "february" => 2,
    "mar" => 3,
    "march" => 3,
    "apr" => 4,
    "april" => 4,
    "may" => 5,
    "jun" => 6,
    "june" => 6,
    "jul" => 7,
    "july" => 7,
    "aug" => 8,
    "august" => 8,
    "sep" => 9,
    "sept" => 9,
    "september" => 9,
    "oct" => 10,
    "october" => 10,
    "nov" => 11,
    "november" => 11,
    "dec" => 12,
    "december" => 12
  }

  @month_re Enum.join(Map.keys(@months) |> Enum.sort_by(&(-String.length(&1))), "|")

  @weekdays [
    {1, ~w(monday mon sanduda sanduda සඳුදා திங்கள்)},
    {2, ~w(tuesday tue tues angaharuwada angaharuwaada අඟහරුවාදා செவ்வாய்)},
    {3, ~w(wednesday wed badada badaada බදාදා புதன்)},
    {4, ~w(thursday thu thur thurs brahaspathinda brahaspathindaa බ්‍රහස්පතින්දා வியாழன்)},
    {5, ~w(friday fri sikurada sikuraada සිකුරාදා வெள்ளி)},
    {6, ~w(saturday sat senasurada senasuraada සෙනසුරාදා சனி)},
    {7, ~w(sunday sun irida iridaa ඉරිදා ஞாயிறு)}
  ]

  # `{kind, {from, to}}` with `to` exclusive, or `{:none, nil}`.
  defp day(text, today) do
    t = String.downcase(text)

    with :none <- date(t, today),
         :none <- relative(t, today),
         :none <- weekdays(t, today),
         :none <- ordinal(t, today) do
      {:none, nil}
    else
      {from, to} -> {:day, {from, to}}
    end
  end

  defp date(t, today) do
    cond do
      m = Regex.run(~r/\b(\d{4})-(\d{1,2})-(\d{1,2})\b/u, t) ->
        [_, y, mo, d] = m
        one(make(String.to_integer(y), String.to_integer(mo), String.to_integer(d)))

      m = Regex.run(~r/\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(#{@month_re})\b/u, t) ->
        [_, d, mo] = m
        one(in_year(today, @months[mo], String.to_integer(d)))

      m = Regex.run(~r/\b(#{@month_re})\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b/u, t) ->
        [_, mo, d] = m
        one(in_year(today, @months[mo], String.to_integer(d)))

      m = Regex.run(~r/\b(\d{1,2})\/(\d{1,2})\b/u, t) ->
        # Sri Lanka writes day/month.
        [_, d, mo] = m
        one(in_year(today, String.to_integer(mo), String.to_integer(d)))

      true ->
        :none
    end
  end

  defp one(nil), do: :none
  defp one(%Date{} = d), do: {d, Date.add(d, 1)}

  defp make(y, m, d) do
    case Date.new(y, m, d) do
      {:ok, date} -> date
      _ -> nil
    end
  end

  # This year's date, or next year's when it passed more than 60 days ago.
  defp in_year(today, m, d) do
    case make(today.year, m, d) do
      nil -> nil
      date -> if Date.diff(today, date) > 60, do: make(today.year + 1, m, d), else: date
    end
  end

  @today_re ~r/\btoday\b|\btonight\b|අද|\bada\b|இன்று|இன்றைக்கு/u
  @tomorrow_re ~r/\btomorrow\b|\btmrw\b|\btmr\b|හෙට|\bheta\b|நாளை/u
  @week_re ~r/\b(?:this|the|entire|whole|full)\s+week\b|\brest\s+of\s+(?:the|this)\s+week\b|මේ\s+සති|\bme\s+sathi|இந்த\s+வார/u

  defp relative(t, today) do
    dow = Date.day_of_week(today)

    cond do
      Regex.match?(~r/day\s+after\s+tomorrow|අනිද්දා|\banidda\b|\banidda\b|நாளை\s+மறுநாள்/u, t) ->
        one(Date.add(today, 2))

      # P0 2026-10-15 (Harsha's call): "today and tomorrow" is both days, not tomorrow alone.
      Regex.match?(@today_re, t) and Regex.match?(@tomorrow_re, t) ->
        {today, Date.add(today, 2)}

      Regex.match?(@tomorrow_re, t) ->
        one(Date.add(today, 1))

      Regex.match?(~r/\bnext\s+week\b|ලබන\s+සති|\blabana\s+sathi|அடுத்த\s+வார/u, t) ->
        mon = Date.add(today, 8 - dow)
        {mon, Date.add(mon, 7)}

      # P0 2026-10-15 (Harsha): "this week" / "the (entire) week" = today to the end of Sunday.
      # On a Sunday it is the coming seven days (the normative §34 fixture: Sun 11 → Sun 18).
      Regex.match?(@week_re, t) ->
        if dow == 7, do: {today, Date.add(today, 7)}, else: {today, Date.add(today, 8 - dow)}

      Regex.match?(~r/\b(?:this\s+)?weekend\b|සති\s+අන්ත|வார\s+இறுதி/u, t) ->
        sat = if dow == 7, do: Date.add(today, -1), else: Date.add(today, 6 - dow)
        from = if Date.compare(sat, today) == :lt, do: today, else: sat
        {from, Date.add(sat, 2)}

      Regex.match?(~r/\bweekdays\b/u, t) ->
        if dow >= 6,
          do: {Date.add(today, 8 - dow), Date.add(today, 13 - dow)},
          else: {today, Date.add(today, 6 - dow)}

      Regex.match?(@today_re, t) ->
        one(today)

      true ->
        :none
    end
  end

  defp weekdays(t, today) do
    found =
      for {n, words} <- @weekdays,
          w <- words,
          {pos, _} <- positions(t, w),
          do: {pos, n}

    case found |> Enum.uniq_by(&elem(&1, 1)) |> Enum.sort() do
      [] ->
        :none

      [{_, a} | rest] ->
        from = next(today, a)

        case rest do
          [{_, b} | _] ->
            if Regex.match?(~r/\b(?:to|until|till|through|thru)\b|–|-/u, t) do
              to = next(from, b)
              {from, Date.add(to, 1)}
            else
              one(from)
            end

          [] ->
            one(from)
        end
    end
  end

  # P0 2026-10-15: "on the 18th" / "the 18th" = this month's 18th when it is today or later, else
  # next month's (recorded in docs/status/risi.md). After every other day form, so "the 2nd meeting
  # tomorrow" stays tomorrow.
  defp ordinal(t, today) do
    m =
      Regex.run(~r/\b(\d{1,2})(?:st|nd|rd|th)\b/u, t) ||
        Regex.run(~r/\bon\s+the\s+(\d{1,2})\b(?!\s*(?:am|pm|a\.m|p\.m|:|o'?clock))/u, t)

    case m do
      [_, d] -> one(this_or_next_month(today, String.to_integer(d)))
      _ -> :none
    end
  end

  defp this_or_next_month(today, d) when d >= today.day, do: make(today.year, today.month, d)

  defp this_or_next_month(today, d) do
    nm = Date.add(Date.end_of_month(today), 1)
    make(nm.year, nm.month, d)
  end

  @doc "True when `text` names a day or range (not just \"now\")."
  def day_named?(text, %Date{} = today) when is_binary(text),
    do: elem(day(text, today), 0) != :none

  defp positions(t, w) do
    if String.match?(w, ~r/^[a-z]+$/),
      do: Regex.scan(~r/\b#{w}\b/u, t, return: :index) |> Enum.map(&hd/1),
      else: Regex.scan(~r/#{Regex.escape(w)}/u, t, return: :index) |> Enum.map(&hd/1)
  end

  # The next occurrence of weekday `n`, `from` included.
  defp next(from, n), do: Date.add(from, rem(n - Date.day_of_week(from) + 7, 7))
end
