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

  @fillers ~w(at on this by the in)

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

    local_now = Clock.local(now, tz)

    with {:ok, day, time} <- classify(tokens, %{day: nil, time: nil, next: false}) do
      build(day, time, local_now, tz)
    end
  end

  defp classify([], acc), do: {:ok, {acc.day, acc.next}, acc.time}

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

    case time do
      nil -> {:ok, Clock.to_utc(NaiveDateTime.new!(date, ~T[00:00:00]), tz), :date}
      t -> {:ok, Clock.to_utc(NaiveDateTime.new!(date, t), tz), :datetime}
    end
  end
end
