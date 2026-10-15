defmodule RisiMe.Agent.TimeRange do
  @moduledoc """
  The time range in the user's **own words** of a calendar write (Harsha's 3rd Call Risi call, 15 Oct:
  "block it out from 10 to 12" became a card for 10–11 AM, on the pilot and again on the device test).
  Deterministic, no model: the write tool's `start`/`end` are set or overridden from what the user
  said (`apply/3`, run by `RisiMe.Agent.Turn` on `calendar_add` / `risi_calendar_add` before the tool
  runs, so a Call Risi correction "From 9 to 10, discuss racing" is covered too).

  `parse/1` → `%{start: Time, end: Time | nil, duration_min: integer | nil}` or nil:
    * "from 10 to 12", "10–12", "9 to 10 am", "between 3 and 4:30", "11 am to 1 pm", "from 2 till 3";
    * "at 3 for two hours", "from 10 for 90 minutes", "for half an hour" (duration only: `start` nil);
    * am/pm: a side without one takes the other side's; with none, working hours 7–19 (1–6 is
      afternoon, 7–11 morning, 12 noon); an end not after the start moves 12 h later.
  A day's ordinal ("the 18th"), a date ("16 Oct") or a year is never a time.
  """

  alias RisiMe.Agent.{Clock, ScheduleIntent}

  @month "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
  @not_times Regex.compile!(
               "\\b\\d{1,2}(?:st|nd|rd|th)\\b|\\b\\d{3,}\\b|\\b\\d{1,2}\\s+(?:of\\s+)?#{@month}\\b|\\b#{@month}\\s+\\d{1,2}\\b",
               "iu"
             )

  @t "(\\d{1,2})(?:[:.](\\d\\d))?\\s*(a\\.?\\s?m\\.?|p\\.?\\s?m\\.?)?"
  @range Regex.compile!(
           "(?:\\b(?:from|between)\\s+)?\\b#{@t}\\s*(?:to|till|til|until|and|through|-|–|—)\\s*#{@t}(?=\\W|$)",
           "iu"
         )
  @start Regex.compile!("\\b(?:from|at|starting(?:\\s+at)?)\\s+#{@t}(?=\\W|$)", "iu")

  @words %{
    "a" => 1,
    "an" => 1,
    "one" => 1,
    "two" => 2,
    "three" => 3,
    "four" => 4,
    "five" => 5,
    "six" => 6,
    "seven" => 7,
    "eight" => 8
  }

  @doc "The range said in `text`, or nil."
  def parse(text) when is_binary(text) do
    t = Regex.replace(@not_times, text, " ")
    dur = duration_min(t)

    case Regex.run(@range, t) do
      [_ | g] ->
        [h1, m1, p1, h2, m2, p2] = pad(g, 6)

        with {:ok, a} <- hm(h1, m1),
             {:ok, b} <- hm(h2, m2) do
          {s, e} = settle(a, mer(p1), b, mer(p2))
          %{start: s, end: e, duration_min: nil}
        else
          _ -> start_only(t, dur)
        end

      nil ->
        start_only(t, dur)
    end
  end

  def parse(_), do: nil

  defp start_only(t, dur) do
    case Regex.run(@start, t) do
      [_ | g] when is_integer(dur) ->
        [h, m, p] = pad(g, 3)

        case hm(h, m) do
          {:ok, a} ->
            s = one(a, mer(p))
            %{start: s, end: add(s, dur), duration_min: dur}

          _ ->
            %{start: nil, end: nil, duration_min: dur}
        end

      _ when is_integer(dur) ->
        %{start: nil, end: nil, duration_min: dur}

      _ ->
        nil
    end
  end

  defp pad(g, n), do: g ++ List.duplicate("", n - length(g))

  defp hm(h, m) do
    h = String.to_integer(h)
    m = if m in [nil, ""], do: 0, else: String.to_integer(m)
    if h <= 23 and m <= 59, do: {:ok, {h, m}}, else: :error
  end

  defp mer(""), do: nil
  defp mer(nil), do: nil
  defp mer(p), do: if(String.starts_with?(String.downcase(p), "p"), do: :pm, else: :am)

  # One side with its meridiem (or the working-hours guess).
  defp one({h, m}, nil) when h > 12, do: Time.new!(h, m, 0)
  defp one({12, m}, nil), do: Time.new!(12, m, 0)
  defp one({h, m}, nil) when h >= 1 and h <= 6, do: Time.new!(h + 12, m, 0)
  defp one({h, m}, nil), do: Time.new!(h, m, 0)
  defp one({12, m}, :am), do: Time.new!(0, m, 0)
  defp one({h, m}, :am), do: Time.new!(h, m, 0)
  defp one({12, m}, :pm), do: Time.new!(12, m, 0)
  defp one({h, m}, :pm) when h < 12, do: Time.new!(h + 12, m, 0)
  defp one({h, m}, :pm), do: Time.new!(h, m, 0)

  # "9 to 10 am": the start takes the end's meridiem unless that puts it after the end ("11 to 1 pm").
  defp settle(a, nil, b, pb) when pb != nil do
    e = one(b, pb)
    s = one(a, pb)
    s = if Time.compare(s, e) == :lt, do: s, else: one(a, flip(pb))
    {s, e}
  end

  defp settle(a, pa, b, nil) when pa != nil do
    s = one(a, pa)
    {s, later(s, b, pa)}
  end

  defp settle(a, nil, b, nil) do
    s = one(a, nil)
    {s, later(s, b, nil)}
  end

  defp settle(a, pa, b, pb), do: {one(a, pa), one(b, pb)}

  # The end: the same meridiem as the start, else 12 h later when it isn't after the start.
  defp later(s, {h, m} = b, pa) do
    e = if pa, do: one(b, pa), else: Time.new!(rem(h, 24), m, 0)
    e = if pa == :am and h == 12, do: Time.new!(12, m, 0), else: e

    cond do
      Time.compare(e, s) == :gt ->
        e

      e.hour + 12 <= 23 and Time.compare(Time.add(e, 12 * 3600), s) == :gt ->
        Time.add(e, 12 * 3600)

      true ->
        e
    end
  end

  defp flip(:am), do: :pm
  defp flip(:pm), do: :am

  defp add(%Time{} = s, min) do
    e = Time.add(s, min * 60)
    if Time.compare(e, s) == :gt, do: e, else: ~T[23:59:00]
  end

  defp duration_min(t) do
    cond do
      Regex.match?(~r/\bhalf\s+an?\s+hour\b/iu, t) ->
        30

      m =
          Regex.run(
            ~r/\bfor\s+(\d{1,3}|an?|one|two|three|four|five|six|seven|eight)\s*(hours?|hrs?|minutes?|mins?)\b/iu,
            t
          ) ->
        [_, n, unit] = m
        n = Map.get(@words, String.downcase(n)) || String.to_integer(n)
        if String.starts_with?(String.downcase(unit), "h"), do: n * 60, else: n

      m = Regex.run(~r/\bfor\s+(?:an?|one)\s+and\s+a\s+half\s+hours?\b/iu, t) ->
        _ = m
        90

      true ->
        nil
    end
  end

  ## The write tool's args

  @tools ~w(calendar_add risi_calendar_add)

  @doc """
  `args` of a calendar write with `start`/`end` set from the range in `ctx.question` (the user's own
  words): the day is the one the words name (`ScheduleIntent`), else the day the model chose. An
  all-day request, a write without a readable start, or words without a range leave `args` as they
  are.
  """
  def apply(name, args, ctx) when name in @tools and is_map(args) do
    q = Map.get(ctx, :question) || ""

    with %{} = r <- parse(q),
         false <- args["all_day"] == true or RisiMe.Agent.DayPart.all_day?(q),
         {:ok, date} <- day(q, args, ctx) do
      tz = ctx.tz
      model_start = local_start(args, ctx)

      start =
        cond do
          r.start -> NaiveDateTime.new!(date, r.start)
          model_start -> NaiveDateTime.new!(date, NaiveDateTime.to_time(model_start))
          true -> nil
        end

      stop =
        cond do
          r.end && r.start -> NaiveDateTime.new!(date, r.end)
          start && r.duration_min -> NaiveDateTime.add(start, r.duration_min * 60)
          true -> nil
        end

      if start && stop && NaiveDateTime.compare(stop, start) == :gt,
        do:
          Map.merge(args, %{
            "start" => Clock.ts(Clock.to_utc(start, tz)),
            "end" => Clock.ts(Clock.to_utc(stop, tz))
          }),
        else: args
    else
      _ -> args
    end
  rescue
    _ -> args
  end

  def apply(_name, args, _ctx), do: args

  @doc """
  The action draft (`RisiMe.Agent.ActionDraft`: `date`, `time`, `end_time`, `duration_min`) with the
  range and the day of `ctx.question` (the user's own words) over the model's slots.
  """
  def apply_draft(nil, _ctx), do: nil

  def apply_draft(%{} = draft, ctx) do
    q = Map.get(ctx, :question) || ""

    if draft["kind"] in [nil, "event"] and draft["all_day"] != true and
         not RisiMe.Agent.DayPart.all_day?(q) do
      today = ctx.now |> Clock.local(ctx.tz) |> NaiveDateTime.to_date()

      draft =
        if ScheduleIntent.day_named?(q, today),
          do: Map.put(draft, "date", Date.to_iso8601(ScheduleIntent.range(q, today).from)),
          else: draft

      case parse(q) do
        %{start: %Time{} = s, end: %Time{} = e} ->
          draft
          |> Map.put("time", hhmm(s))
          |> Map.put("end_time", hhmm(e))
          |> Map.delete("duration_min")

        %{start: nil, duration_min: d} when is_integer(d) ->
          draft |> Map.put("duration_min", d) |> Map.delete("end_time")

        _ ->
          draft
      end
    else
      draft
    end
  rescue
    _ -> draft
  end

  defp hhmm(%Time{} = t), do: Calendar.strftime(t, "%H:%M")

  defp day(q, args, ctx) do
    today = ctx.now |> Clock.local(ctx.tz) |> NaiveDateTime.to_date()

    if ScheduleIntent.day_named?(q, today) do
      {:ok, ScheduleIntent.range(q, today).from}
    else
      case local_start(args, ctx) do
        %NaiveDateTime{} = s -> {:ok, NaiveDateTime.to_date(s)}
        _ -> :error
      end
    end
  end

  defp local_start(args, ctx) do
    case RisiMe.Agent.TimePhrase.resolve(to_string(args["start"] || ""), ctx.now, ctx.tz) do
      {:ok, t, _} -> Clock.local(t, ctx.tz)
      _ -> nil
    end
  rescue
    _ -> nil
  end
end
