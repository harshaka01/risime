defmodule RisiMe.Agent.DayPart do
  @moduledoc """
  Durations and parts of a day in a request (proposal 2026-10-14-calendar-v2 §44.7; Harsha's P1:
  "Free up calendar on 18th Oct whole day" became 00:00–01:00). Deterministic, English, Sinhala,
  Tamil and Singlish.

  `parse(text)` →
  * `:all_day` — whole day, all day, full day, the entire day, "mulu dawasama", "දවස පුරා",
    "நாள் முழுவதும்", …;
  * `{:part, name, start_time, end_time}` — morning 08–12, afternoon 12–17, evening 17–21, night
    19–22;
  * `{:duration, seconds}` — "2 hours", "90 mins", "half an hour", "an hour", "පැය 2",
    "2 மணி நேரம்", "paya 2";
  * `:unclear` — two different answers in one phrase ("whole day … 2 hours", "morning and evening");
  * `:none`.

  `block?(text)` is true for an item that blocks time rather than names a deadline ("free up",
  "block", "keep … free", "leave", "off", "holiday").

  Not an agent: a deterministic parser the Calendar tools and the ledger use (infrastructure for
  exact times; the model may still pass `all_day`).
  """

  @all_day [
    ~r/\b(?:the\s+)?(?:whole|entire|full|all)[\s-]+day\b/iu,
    ~r/\ball\s+day\s+long\b/iu,
    ~r/\bfull[\s-]+day\b/iu,
    ~r/\bwhole\s+of\s+(?:the\s+)?day\b/iu,
    # Singlish
    ~r/\b(?:mulu|mulu\s+)?dawasa(?:ma)?\s+(?:purama|purá|pura)\b/iu,
    ~r/\bmulu\s+dawas(?:a|e)(?:ma)?\b/iu,
    ~r/\bdawas\s+ekama\b/iu,
    # Sinhala
    ~r/මුළු\s*දවස/u,
    ~r/දවස\s*පුරා/u,
    ~r/දවසම/u,
    # Tamil
    ~r/நாள்\s*முழுவதும்/u,
    ~r/முழு\s*நாள்/u,
    ~r/நாள்\s*முழுக்க/u
  ]

  @parts [
    {"morning", ~T[08:00:00], ~T[12:00:00],
     ~r/\b(?:in\s+the\s+)?morning\b|\bude\b|\budhe\b|\budeta\b|උදේ|காலை/iu},
    {"afternoon", ~T[12:00:00], ~T[17:00:00],
     ~r/\bafternoon\b|\bdawal\b|\bdaval\b|දවල්|மதியம்|பிற்பகல்/iu},
    {"evening", ~T[17:00:00], ~T[21:00:00], ~r/\bevening\b|\bhawasa\b|\bhavasa\b|හවස|மாலை/iu},
    {"night", ~T[19:00:00], ~T[22:00:00], ~r/\bnight\b|\btonight\b|\b(?:ra|raa)\b|රෑ|இரவு/iu}
  ]

  @num_words %{
    "a" => 1,
    "an" => 1,
    "one" => 1,
    "two" => 2,
    "three" => 3,
    "four" => 4,
    "five" => 5,
    "six" => 6,
    "eight" => 8
  }

  @hours ~r/\b(\d+(?:\.\d+)?|a|an|one|two|three|four|five|six|eight)\s*(?:-\s*)?(?:hours?|hrs?|h)\b/iu
  @mins ~r/\b(\d+)\s*(?:minutes?|mins?|m)\b/iu
  @half ~r/\bhalf\s+(?:an?\s+)?hour\b/iu
  @si_hours ~r/පැය\s*(\d+)|(\d+)\s*පැය|\bpaya\s*(\d+)|\b(\d+)\s*paya\b/iu
  @ta_hours ~r/(\d+)\s*மணி\s*நேர/u

  @block ~r/\b(?:free\s+up|clear\s+(?:my|the)?\s*(?:calendar|day|schedule)|block(?:\s+out|\s+off)?|keep\b.{0,30}\bfree|day\s+off|on\s+leave|leave\b|holiday|vacation|out\s+of\s+office|ooo|nidahas|නිදහස්|නිවාඩු|விடுமுறை|ஓய்வு)\b|නිවාඩු|නිදහස්|விடுமுறை/iu

  @doc "The day part / duration of `text` (see the module doc)."
  def parse(text) when is_binary(text) do
    all? = all_day?(text)
    parts = for {name, a, b, re} <- @parts, Regex.match?(re, text), do: {name, a, b}
    dur = duration(text)

    cond do
      all? and dur != nil -> :unclear
      all? and parts != [] -> :unclear
      all? -> :all_day
      length(parts) > 1 -> :unclear
      parts != [] and dur != nil -> :unclear
      length(parts) == 1 -> Tuple.insert_at(hd(parts), 0, :part)
      dur != nil -> {:duration, dur}
      true -> :none
    end
  end

  def parse(_), do: :none

  @doc "True when `text` says the whole day."
  def all_day?(text) when is_binary(text), do: Enum.any?(@all_day, &Regex.match?(&1, text))
  def all_day?(_), do: false

  @doc "True when `text` blocks time (free up, block, leave, holiday…), rather than a deadline."
  def block?(text) when is_binary(text), do: Regex.match?(@block, text)
  def block?(_), do: false

  @doc "True when `text` names a whole-day block: \"free up calendar on 18 Oct whole day\"."
  def all_day_block?(text), do: all_day?(text) and block?(text)

  @doc "The duration in seconds named by `text`, or nil."
  def duration(text) when is_binary(text) do
    cond do
      Regex.match?(@half, text) ->
        1800

      m = Regex.run(@hours, text) ->
        hours(Enum.at(m, 1))

      m = Regex.run(@si_hours, text) ->
        m |> tl() |> Enum.find(&(&1 not in [nil, ""])) |> hours()

      m = Regex.run(@ta_hours, text) ->
        hours(Enum.at(m, 1))

      m = Regex.run(@mins, text) ->
        n = String.to_integer(Enum.at(m, 1))
        if n in 1..1440, do: n * 60

      true ->
        nil
    end
  end

  def duration(_), do: nil

  defp hours(s) do
    s = String.downcase(s)

    h =
      case Map.fetch(@num_words, s) do
        {:ok, n} ->
          n

        :error ->
          case Float.parse(s) do
            {f, _} -> f
            :error -> nil
          end
      end

    if is_number(h) and h > 0 and h <= 24, do: round(h * 3600)
  end
end
