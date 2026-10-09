defmodule RisiMe.Agent.TimePhrase do
  @moduledoc """
  Times in server tool args (contract v1.25 §25.5): an ISO timestamp or a local phrase
  ("Tuesday 2pm", "tomorrow 06:00", "in 20 minutes"), resolved from the request's `server_ts`
  and the asker's zone (wall-clock conversions in Postgres, `RisiMe.Agent.Clock`). Risi **asks
  instead of guessing**: a bare hour without am/pm ("at 6"), or words it doesn't know, are
  `{:error, :ambiguous}` / `{:error, :unreadable}`, and the tool step fails with that reason.

  `resolve/3` → `{:ok, utc, :datetime | :date}` (a date without a time is `:date` at 00:00
  local) or `{:error, :ambiguous | :unreadable}`.
  """
  alias RisiMe.Agent.Clock

  @weekdays %{
    "monday" => 1,
    "mon" => 1,
    "tuesday" => 2,
    "tue" => 2,
    "tues" => 2,
    "wednesday" => 3,
    "wed" => 3,
    "thursday" => 4,
    "thu" => 4,
    "thur" => 4,
    "thurs" => 4,
    "friday" => 5,
    "fri" => 5,
    "saturday" => 6,
    "sat" => 6,
    "sunday" => 7,
    "sun" => 7
  }

  @units %{
    "minute" => 60,
    "minutes" => 60,
    "min" => 60,
    "mins" => 60,
    "hour" => 3600,
    "hours" => 3600,
    "hr" => 3600,
    "hrs" => 3600,
    "day" => 86_400,
    "days" => 86_400,
    "week" => 604_800,
    "weeks" => 604_800
  }

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

  @fillers ~w(at on this by the in of)

  @doc "Resolves `phrase` (see the module doc)."
  def resolve(phrase, %DateTime{} = now, tz) when is_binary(phrase) do
    p = phrase |> String.trim() |> String.downcase()

    cond do
      p == "" ->
        {:error, :unreadable}

      match?({:ok, _, _}, DateTime.from_iso8601(String.upcase(p))) ->
        {:ok, dt, _} = DateTime.from_iso8601(String.upcase(p))
        {:ok, DateTime.shift_zone!(dt, "Etc/UTC"), :datetime}

      m = Regex.run(~r/^(\d{4}-\d\d-\d\d)[t ](\d\d):(\d\d)(?::(\d\d))?$/, p) ->
        [_, d, h, mi | _] = m

        with {:ok, date} <- Date.from_iso8601(d),
             {:ok, time} <- Time.new(String.to_integer(h), String.to_integer(mi), 0) do
          {:ok, Clock.to_utc(NaiveDateTime.new!(date, time), tz), :datetime}
        else
          _ -> {:error, :unreadable}
        end

      m = Regex.run(~r/^in (\d{1,4}) ([a-z]+)$/, p) ->
        [_, n, unit] = m

        case @units[unit] do
          nil -> {:error, :unreadable}
          s -> {:ok, DateTime.add(now, String.to_integer(n) * s, :second), :datetime}
        end

      true ->
        words(p, now, tz)
    end
  end

  def resolve(_phrase, _now, _tz), do: {:error, :unreadable}

  # A day part and/or a time part, in any order, with filler words.
  defp words(p, now, tz) do
    tokens =
      p
      |> String.replace(~r/[,.](?![0-9])/, " ")
      |> String.replace(~r/(\d)\s+(am|pm)\b/, "\\1\\2")
      |> String.split(~r/\s+/, trim: true)
      |> Enum.reject(&(&1 in @fillers))
      |> month_days()

    local_now = Clock.local(now, tz)

    with {:ok, day, time} <- classify(tokens, %{day: nil, time: nil, next: false}) do
      build(day, time, local_now, tz)
    end
  end

  # P0 2026-10-09: "12 Oct", "October 12th", "12 oct 2026" are one date token
  # (`{:md, month, day, year | nil}`), so the day number is never read as a bare hour.
  defp month_days([d, m, y | rest]) when is_binary(d) and is_binary(m) and is_binary(y) do
    with {:ok, day} <- day_of_month(d),
         {:ok, mon} <- Map.fetch(@months, m),
         {:ok, yr} <- year(y) do
      [{:md, mon, day, yr} | month_days(rest)]
    else
      _ -> month_days_2([d, m, y | rest])
    end
  end

  defp month_days(tokens), do: month_days_2(tokens)

  defp month_days_2([a, b | rest]) when is_binary(a) and is_binary(b) do
    cond do
      match?({:ok, _}, day_of_month(a)) and Map.has_key?(@months, b) ->
        {:ok, day} = day_of_month(a)
        [{:md, @months[b], day, nil} | month_days(rest)]

      Map.has_key?(@months, a) and match?({:ok, _}, day_of_month(b)) ->
        {:ok, day} = day_of_month(b)

        case rest do
          [y | rest2] when is_binary(y) ->
            case year(y) do
              {:ok, yr} -> [{:md, @months[a], day, yr} | month_days(rest2)]
              :error -> [{:md, @months[a], day, nil} | month_days(rest)]
            end

          _ ->
            [{:md, @months[a], day, nil} | month_days(rest)]
        end

      true ->
        [a | month_days([b | rest])]
    end
  end

  defp month_days_2(tokens), do: tokens

  defp day_of_month(t) do
    case Regex.run(~r/^(\d{1,2})(?:st|nd|rd|th)?$/, t) do
      [_, d] -> if String.to_integer(d) in 1..31, do: {:ok, String.to_integer(d)}, else: :error
      _ -> :error
    end
  end

  defp year(t) do
    case Regex.run(~r/^(20\d\d)$/, t) do
      [_, y] -> {:ok, String.to_integer(y)}
      _ -> :error
    end
  end

  defp classify([], acc), do: {:ok, {acc.day, acc.next}, acc.time}

  # An explicit date wins over a weekday said with it ("Monday 12 Oct").
  defp classify([{:md, m, d, y} | rest], acc) do
    case acc.day do
      day when day == nil or (is_tuple(day) and elem(day, 0) == :weekday) ->
        classify(rest, %{acc | day: {:md, m, d, y}})

      _ ->
        {:error, :unreadable}
    end
  end

  defp classify([t | rest], %{day: {:md, _, _, _}} = acc) when is_map_key(@weekdays, t),
    do: classify(rest, acc)

  defp classify(["next" | rest], acc), do: classify(rest, %{acc | next: true})

  defp classify([t | rest], acc) do
    cond do
      t in ~w(today tonight) and acc.day == nil ->
        classify(rest, %{acc | day: :today})

      t == "tomorrow" and acc.day == nil ->
        classify(rest, %{acc | day: :tomorrow})

      Map.has_key?(@weekdays, t) and acc.day == nil ->
        classify(rest, %{acc | day: {:weekday, @weekdays[t]}})

      Regex.match?(~r/^\d{4}-\d\d-\d\d$/, t) and acc.day == nil ->
        case Date.from_iso8601(t) do
          {:ok, d} -> classify(rest, %{acc | day: {:date, d}})
          _ -> {:error, :unreadable}
        end

      acc.time == nil ->
        case time(t) do
          {:ok, tm} -> classify(rest, %{acc | time: tm})
          error -> error
        end

      true ->
        {:error, :unreadable}
    end
  end

  # "2pm", "2:30pm", "14:00", "noon", "midnight"; a bare "6" or "6:00"-less hour is ambiguous.
  defp time("noon"), do: {:ok, ~T[12:00:00]}
  defp time("midnight"), do: {:ok, ~T[00:00:00]}

  defp time(t) do
    cond do
      m = Regex.run(~r/^(\d{1,2})(?::(\d\d))?(am|pm|a\.m\.|p\.m\.)$/, t) ->
        [_, h, mi, ap] = m
        h = String.to_integer(h)
        mi = if mi == "", do: 0, else: String.to_integer(mi)
        pm? = String.starts_with?(ap, "p")

        cond do
          h < 1 or h > 12 or mi > 59 -> {:error, :unreadable}
          pm? -> Time.new(rem(h, 12) + 12, mi, 0)
          true -> Time.new(rem(h, 12), mi, 0)
        end

      m = Regex.run(~r/^(\d{1,2}):(\d\d)$/, t) ->
        [_, h, mi] = m
        {h, mi} = {String.to_integer(h), String.to_integer(mi)}
        if h < 24 and mi < 60, do: Time.new(h, mi, 0), else: {:error, :unreadable}

      m = Regex.run(~r/^(\d{1,2})$/, t) ->
        h = String.to_integer(hd(tl(m)))

        cond do
          h in 13..23 -> Time.new(h, 0, 0)
          h <= 12 -> {:error, :ambiguous}
          true -> {:error, :unreadable}
        end

      true ->
        {:error, :unreadable}
    end
  end

  defp build({nil, _next}, nil, _local_now, _tz), do: {:error, :unreadable}

  # A time alone: its next occurrence.
  defp build({nil, _next}, time, local_now, tz) do
    today = NaiveDateTime.to_date(local_now)
    at = NaiveDateTime.new!(today, time)

    at =
      if NaiveDateTime.compare(at, local_now) == :gt,
        do: at,
        else: NaiveDateTime.add(at, 86_400, :second)

    {:ok, Clock.to_utc(at, tz), :datetime}
  end

  defp build({day, next?}, time, local_now, tz) do
    today = NaiveDateTime.to_date(local_now)

    date =
      case day do
        :today ->
          today

        :tomorrow ->
          Date.add(today, 1)

        {:date, d} ->
          d

        {:md, m, d, y} ->
          month_day(m, d, y, today)

        {:weekday, wd} ->
          diff = Integer.mod(wd - Date.day_of_week(today), 7)

          # Today's weekday: today if that time is still ahead (never with "next"), else in 7 days.
          diff =
            cond do
              diff > 0 ->
                diff

              next? ->
                7

              time != nil and
                  NaiveDateTime.compare(NaiveDateTime.new!(today, time), local_now) == :gt ->
                0

              time == nil ->
                0

              true ->
                7
            end

          Date.add(today, diff)
      end

    case {date, time} do
      {nil, _} -> {:error, :unreadable}
      {date, nil} -> {:ok, Clock.to_utc(NaiveDateTime.new!(date, ~T[00:00:00]), tz), :date}
      {date, t} -> {:ok, Clock.to_utc(NaiveDateTime.new!(date, t), tz), :datetime}
    end
  end

  # A day and month without a year: this year's, or next year's once it has passed.
  defp month_day(m, d, nil, today) do
    case Date.new(today.year, m, d) do
      {:ok, date} ->
        if Date.compare(date, today) == :lt,
          do: month_day(m, d, today.year + 1, today),
          else: date

      _ ->
        nil
    end
  end

  defp month_day(m, d, y, _today) do
    case Date.new(y, m, d) do
      {:ok, date} -> date
      _ -> nil
    end
  end
end
