defmodule RisiMe.Agent.MemoryIntent do
  @moduledoc """
  The memory route of the deterministic intent router (contract v1.36 §35.2, extending §34.1):
  "summarise <range | person | project>", "what did I agree with <person>", "what's pending with
  <person>", in English, Sinhala, Tamil and Singlish.

  `classify(text, today)` → `{:memory, %{mode, range, people, query}}` or `:none`:

    * `mode`: `:summarise` (→ `summarise_range`), `:agreed` or `:pending` (→ `memory_search`);
    * `range`: `%{from: Date, to: Date}` (`to` exclusive, past-oriented: "this month" is the 1st
      to today, "last week" is the previous Monday to Sunday) or nil (all memory);
    * `people`: the names the text names ("with Upali", "Upali එක්ක", "Upali உடன்", "Upali
      ekka"), as written; the turn resolves them against the asker's contacts;
    * `query`: the text itself (the retrieval query).

  "summarise" alone (this chat now, the §27 discussion summary) is not a memory question: it
  needs a range, a person or a work word. Word lists are implementation.
  """

  @summarise [
    ~r/\b(?:summari[sz]e|summary|summaries|recap|round[- ]?up|overview|digest)\b/iu,
    ~r/\bwhat\s+(?:work|things?)\s+(?:was|were|got|have\s+been|has\s+been)\s+done\b/iu,
    ~r/\bwhat\s+(?:did|have)\s+(?:i|we)\s+(?:done|do|work(?:ed)?\s+on|achieve[d]?|finish(?:ed)?|complete[d]?)\b/iu,
    ~r/\bwhat\s+happened\b/iu,
    ~r/සාරාංශ|සාරාංශය|කෙටියෙන්\s+කියන්න|කරපු\s+වැඩ|කළ\s+වැඩ/u,
    ~r/சுருக்க|சுருக்கம்|செய்த\s+வேலை|நடந்தவை|என்ன\s+நடந்தது/u,
    ~r/\b(?:summary\s+ekak|karapu\s+wada|kala\s+wada)\b/iu
  ]

  @agreed [
    ~r/\bwhat\s+(?:did|have)\s+(?:i|we|you)\s+(?:agree[d]?|decide[d]?|promise[d]?|say|said|discuss(?:ed)?|talk(?:ed)?\s+about|settle[d]?\s+on|commit(?:ted)?\s+to)\b/iu,
    ~r/\bwhat\s+(?:was|were|has\s+been|had\s+been)\s+(?:agreed|decided|promised|discussed|settled)\b/iu,
    # P0 2026-10-15 (Harsha's voice note): "have I promised…", "what I promised", "my promises".
    ~r/\b(?:have|did|had)\s+i\s+(?:promised?|committed)\b|\bwhat\s+(?:i|we)\s+(?:promised|committed\s+to|agreed)\b|\bmy\s+(?:promises|commitments)\b|\bany\s+promises\b/iu,
    ~r/\b(?:agreement|agreements|decisions?)\s+(?:with|about|on)\b/iu,
    ~r/\bwhat\s+did\s+\p{Lu}[\p{L}'-]+\s+(?:say|agree|promise|decide)\b/u,
    ~r/එකඟ|එකග|තීරණය|පොරොන්දු|කතා\s+කර(?:පු|ගත්|ගත්තේ|ගත්ත)|කිව්වේ|කිව්ව/u,
    ~r/ஒப்புக்|ஒப்புக்கொண்ட|முடிவு\s*செய்த|முடிவெடுத்த|வாக்குறுதி|பேசிய(?:து|வை)?|சொன்ன(?:து|வை)?/u,
    ~r/\b(?:agree\s+une|agree\s+wuna|ekanga|kathakarapu|katha\s+karapu|kiwwe|decide\s+kale)\b/iu
  ]

  @pending [
    ~r/\b(?:pending|outstanding|open\s+items?|unresolved|unfinished|overdue|follow[- ]?ups?)\b/iu,
    ~r/\bwhat'?s\s+left\b|\bwhat\s+is\s+left\b|\bwaiting\s+(?:on|for)\b|\bstill\s+(?:open|to\s+do|owe)\b/iu,
    ~r/ඉතිරි|පොරොත්තු|බාකි|තාම\s+නැති|තවම\s+කරලා\s+නැති/u,
    ~r/நிலுவை|மீதமுள்ள|மீதம்|காத்திருக்கும்|இன்னும்\s+முடியாத/u,
    ~r/\b(?:ithiri|baki|thama\s+nathi)\b/iu
  ]

  @doc "Classifies `text` (see the module doc); `today` is the asker's local date."
  def classify(text, %Date{} = today) when is_binary(text) do
    t = String.trim(text)
    range = range(t, today)
    people = people(t)

    mode =
      cond do
        t == "" -> nil
        any?(@agreed, t) -> :agreed
        any?(@pending, t) -> :pending
        any?(@summarise, t) and (range != nil or people != [] or work?(t)) -> :summarise
        true -> nil
      end

    if mode,
      do: {:memory, %{mode: mode, range: range, people: people, query: t}},
      else: :none
  end

  def classify(_text, _today), do: :none

  defp any?(res, t), do: Enum.any?(res, &Regex.match?(&1, t))

  defp work?(t),
    do:
      Regex.match?(
        ~r/\b(?:work|progress|project|projects|month|week|year)\b|වැඩ|ව්‍යාපෘති|வேலை|திட்ட|\bwada\b/iu,
        t
      )

  ## Range (past-oriented)

  @months %{
    "january" => 1,
    "february" => 2,
    "march" => 3,
    "april" => 4,
    "may" => 5,
    "june" => 6,
    "july" => 7,
    "august" => 8,
    "september" => 9,
    "october" => 10,
    "november" => 11,
    "december" => 12,
    "jan" => 1,
    "feb" => 2,
    "mar" => 3,
    "apr" => 4,
    "jun" => 6,
    "jul" => 7,
    "aug" => 8,
    "sep" => 9,
    "sept" => 9,
    "oct" => 10,
    "nov" => 11,
    "dec" => 12
  }

  @doc "The past range `text` names from `today` (`%{from, to}`, `to` exclusive), or nil."
  def range(text, %Date{} = today) do
    t = String.downcase(text)
    tomorrow = Date.add(today, 1)
    monday = Date.add(today, 1 - Date.day_of_week(today))
    first = Date.beginning_of_month(today)

    cond do
      m =
          Regex.run(
            ~r/\b(?:last|past|previous)\s+(\d{1,3})\s+(day|days|week|weeks|month|months)\b/u,
            t
          ) ->
        [_, n, unit] = m
        n = String.to_integer(n)

        days =
          cond do
            String.starts_with?(unit, "day") -> n
            String.starts_with?(unit, "week") -> 7 * n
            true -> 30 * n
          end

        %{from: Date.add(tomorrow, -days), to: tomorrow}

      Regex.match?(
        ~r/\blast\s+week\b|\bprevious\s+week\b|පසුගිය\s+සති|ගිය\s+සති|කලින්\s+සති|\bgiya\s+sathi|\bpasugiya\s+sathi|கடந்த\s+வார|போன\s+வார|முந்தைய\s+வார/u,
        t
      ) ->
        %{from: Date.add(monday, -7), to: monday}

      Regex.match?(
        ~r/\blast\s+month\b|\bprevious\s+month\b|පසුගිය\s+මාස|ගිය\s+මාස|කලින්\s+මාස|\bgiya\s+mas|\bpasugiya\s+mas|கடந்த\s+மாத|போன\s+மாத|முந்தைய\s+மாத/u,
        t
      ) ->
        prev = first |> Date.add(-1) |> Date.beginning_of_month()
        %{from: prev, to: first}

      Regex.match?(
        ~r/\bthis\s+month\b|\bthe\s+month\b|\bmonth\s+so\s+far\b|මේ\s+මාස|මෙම\s+මාස|\bme\s+mas|இந்த\s+மாத/u,
        t
      ) ->
        %{from: first, to: tomorrow}

      Regex.match?(
        ~r/\bthis\s+week\b|\bthe\s+week\b|\bweek\s+so\s+far\b|මේ\s+සති|මෙම\s+සති|\bme\s+sathi|இந்த\s+வார/u,
        t
      ) ->
        %{from: monday, to: tomorrow}

      Regex.match?(~r/\byesterday\b|ඊයේ|\biye\b|\beeye\b|நேற்று/u, t) ->
        %{from: Date.add(today, -1), to: today}

      Regex.match?(~r/\btoday\b|අද|\bada\b|இன்று|இன்றைக்கு/u, t) ->
        %{from: today, to: tomorrow}

      m = Regex.run(~r/\b(?:in|for|during|of)\s+(#{month_re()})\b/u, t) ->
        [_, name] = m
        month(today, @months[name])

      true ->
        nil
    end
  end

  defp month_re,
    do: @months |> Map.keys() |> Enum.sort_by(&(-String.length(&1))) |> Enum.join("|")

  # The named month of this year, or of last year when it is still ahead.
  defp month(today, m) do
    y = if m > today.month, do: today.year - 1, else: today.year
    from = Date.new!(y, m, 1)
    to = from |> Date.end_of_month() |> Date.add(1)

    %{
      from: from,
      to: if(Date.compare(to, Date.add(today, 1)) == :gt, do: Date.add(today, 1), else: to)
    }
  end

  ## People

  @not_names ~w(me my myself i you we us our the a an this that last this next past previous
                everyone everybody all them him her it risi team chat group work today
                yesterday month week ඔයා මම අපි එයා මට ඔබ நான் நீங்கள் உங்கள் அவர்
                oya mama api eya mata) ++ Map.keys(@months)

  @doc "The person names `text` names (as written, at most 3)."
  def people(text) do
    latin =
      Regex.scan(
        ~r/\b(?:with|from|to|by|about|and|for)\s+(\p{Lu}[\p{L}'.-]*(?:\s+\p{Lu}[\p{L}'-]*)?)/u,
        text
      )
      |> Enum.map(fn [_, n] -> n end)

    asked =
      Regex.scan(~r/\bdid\s+(\p{Lu}[\p{L}'-]+)\s+(?:say|agree|promise|decide)\b/u, text)
      |> Enum.map(fn [_, n] -> n end)

    si =
      Regex.scan(~r/([\p{L}\p{M}][\p{L}\p{M}'.-]*)\s*(?:එක්ක|සමඟ|සමග|ගෙන්|ගෙ\s+එක්ක)/u, text)
      |> Enum.map(fn [_, n] -> n end)

    ta =
      Regex.scan(~r/([\p{L}\p{M}][\p{L}\p{M}'.]*)\s*-?\s*(?:உடன்|கூட|இடம்|விடம்|ஓடு)/u, text)
      |> Enum.map(fn [_, n] -> n end)

    singlish =
      Regex.scan(~r/\b([\p{L}][\p{L}'-]*)\s+(?:ekka|samaga|gen)\b/iu, text)
      |> Enum.map(fn [_, n] -> n end)

    (latin ++ asked ++ si ++ ta ++ singlish)
    |> Enum.map(&String.trim(&1, ".-'"))
    |> Enum.reject(fn n -> n == "" or String.downcase(n) in @not_names end)
    |> Enum.uniq_by(&String.downcase/1)
    |> Enum.take(3)
  end
end
