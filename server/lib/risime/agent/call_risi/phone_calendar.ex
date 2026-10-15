defmodule RisiMe.Agent.CallRisi.PhoneCalendar do
  @moduledoc """
  Meeting titles from the caller's phone during a Call Risi (contract v1.52 §41.8, proposal
  2026-10-15-call-risi-calendar-titles). Pure helpers for the session and the brain:

    * `capable?/1`, `capable?/2`: the caller's device advertises `call_risi_cal` (with `call_risi`).
      Without it the server never asks, and the voice answer speaks times only.
    * `request/3`: the media worker's Port command `{"cmd": "cal_req", "id", "from", "to"}`. The
      worker publishes it reliably on topic `risi.cal` to the caller's identity only.
    * `match/3`, `await/3`: the worker's `{"ev": "cal", ...}` event for that id, validated and
      limited (≤ 100 events, titles ≤ 200 characters or `nil`, sorted by start, within the range).
    * `enrich/1`, `context_text/1`: the events with their local times, for the one answer being
      composed.
    * `redact/2`, `transcript_line/2`: the stored transcript line in time-only wording, with
      `"titles_spoken": true`.
    * `log_fields/1`: what the learning log may record (`titles_n`, never a title).

  **Titles live only in the call session's memory for one answer and are then dropped.** Nothing
  here writes, logs or sends them anywhere; callers must not either (§41.8 "Server (normative)").
  """

  @capability "call_risi_cal"
  @topic "risi.cal"
  @timeout_ms 2_000
  @max_events 100
  @max_title 200
  @max_range_s 8 * 86_400
  @reasons ~w(no_permission no_calendars off error)

  @times_only_note "I can see the times; the titles stay on your phone."

  # Words too common to give a meeting away (the paraphrase guard of `redact/2` skips them).
  @common ~w(about after again also call calls catch daily from have into just meet meeting
             meetings monday tuesday wednesday thursday friday saturday sunday morning evening
             night noon review session standup sync team that this today tomorrow week weekly
             with your lunch dinner breakfast busy free event events time talk work office home)

  @doc "The device capability (§41.8)."
  def capability, do: @capability

  @doc "The LiveKit data topic of the request and the reply."
  def topic, do: @topic

  @doc "How long the server waits for the phone's reply (ms)."
  def timeout_ms, do: @timeout_ms

  @doc "What Risi says once per call when it can speak times only."
  def times_only_note, do: @times_only_note

  @doc "The device's capabilities allow asking: `call_risi_cal` together with `call_risi`."
  def capable?(caps) when is_list(caps), do: @capability in caps and "call_risi" in caps
  def capable?(_caps), do: false

  @doc "The same check for a stored device (`RisiMe.Devices.Device`), by user and device id."
  def capable?(user_id, device_id) when is_binary(user_id) and is_binary(device_id) do
    case RisiMe.Repo.get_by(RisiMe.Devices.Device, user_id: user_id, device_id: device_id) do
      %{capabilities: caps} when is_list(caps) -> capable?(caps)
      _ -> false
    end
  rescue
    _ -> false
  end

  def capable?(_user_id, _device_id), do: false

  @doc """
  The Port command for a range: `{:ok, cmd}` or `{:error, :bad_range}` (`to` must be after `from`,
  at most 8 days later). `from` and `to` are UTC `DateTime`s (the server has no time-zone
  database); they go out in RFC 3339 at the user's offset.

  Options: `:offset_s`, the user's UTC offset in seconds (default 0; `offset_s/2` computes it from
  `RisiMe.Agent.Clock.local/2`), and `:id` (default a fresh UUID v4).
  """
  def request(%DateTime{} = from, %DateTime{} = to, opts \\ []) do
    span = DateTime.diff(to, from)
    off = Keyword.get(opts, :offset_s, 0)
    id = Keyword.get_lazy(opts, :id, &Ecto.UUID.generate/0)

    if span > 0 and span <= @max_range_s and is_binary(id) and is_integer(off) and
         abs(off) < 86_400 do
      {:ok,
       %{
         "cmd" => "cal_req",
         "id" => id,
         "from" => rfc3339(from, off),
         "to" => rfc3339(to, off)
       }}
    else
      {:error, :bad_range}
    end
  end

  @doc """
  The UTC offset in seconds of a zone at `utc`, from its wall-clock time there (e.g.
  `offset_s(now, RisiMe.Agent.Clock.local(now, tz))`), rounded to the minute.
  """
  def offset_s(%DateTime{} = utc, %NaiveDateTime{} = local) do
    s = NaiveDateTime.diff(local, DateTime.to_naive(utc))
    round(s / 60) * 60
  end

  @doc """
  Waits (in the calling process) for `{:call_risi_cal, ev}` with the request's id, at most
  `timeout` ms: `match/3` of it, or `{:error, "timeout"}`. Other ids are left in the mailbox.
  """
  def await(%{"id" => id} = cmd, timeout \\ @timeout_ms) do
    receive do
      {:call_risi_cal, %{"id" => ^id} = ev} -> match(ev, cmd)
    after
      timeout -> {:error, "timeout"}
    end
  end

  @doc """
  The worker's event for the request `cmd`: `{:ok, events}` (validated, sorted, ≤ 100, within
  the range), `{:error, reason}` (`no_permission` | `no_calendars` | `off` | `error`, as strings),
  or `:ignore` for another id or another event.
  """
  def match(%{"ev" => "cal", "id" => id} = ev, %{"id" => id} = cmd) do
    window = window(cmd)

    case ev do
      %{"ok" => true, "events" => events} when is_list(events) ->
        {:ok,
         events
         |> Enum.take(@max_events * 4)
         |> Enum.flat_map(&event/1)
         |> Enum.filter(&in_window?(&1, window))
         |> Enum.sort_by(&DateTime.to_unix(&1.start, :millisecond))
         |> Enum.take(@max_events)}

      %{"ok" => false, "reason" => r} when r in @reasons ->
        {:error, r}

      _ ->
        {:error, "error"}
    end
  end

  def match(_ev, _cmd), do: :ignore

  defp window(%{"from" => f, "to" => t}) do
    with {:ok, a, _} <- DateTime.from_iso8601(f), {:ok, b, _} <- DateTime.from_iso8601(t) do
      {a, b}
    else
      _ -> nil
    end
  end

  defp window(_), do: nil

  defp in_window?(_e, nil), do: true

  defp in_window?(e, {from, to}),
    do: DateTime.compare(e.end, from) == :gt and DateTime.compare(e.start, to) == :lt

  defp event(%{"start" => s, "end" => e} = m) when is_binary(s) and is_binary(e) do
    with {:ok, start, off} <- DateTime.from_iso8601(s),
         {:ok, stop, _} <- DateTime.from_iso8601(e),
         true <- DateTime.compare(stop, start) != :lt do
      [
        %{
          start: start,
          end: stop,
          offset_s: off,
          all_day: m["all_day"] == true,
          busy: m["busy"] != false,
          title: title(m["title"])
        }
      ]
    else
      _ -> []
    end
  end

  defp event(_), do: []

  defp title(t) when is_binary(t) do
    t
    |> String.replace(~r/[\p{Cc}\p{Cf}]+/u, " ")
    |> String.replace(~r/\s+/u, " ")
    |> String.trim()
    |> String.slice(0, @max_title)
    |> case do
      "" -> nil
      s -> s
    end
  end

  defp title(_), do: nil

  @doc """
  The events with their wording in the event's own offset: `time_text` ("10:00–10:30" or
  "all day"), `day_text` ("Thu 15 Oct"), `status` ("busy" | "free") and `times_only`
  ("10:00–10:30 busy").
  """
  def enrich(events) when is_list(events) do
    for e <- events do
      local_start = DateTime.add(e.start, e.offset_s, :second)
      local_end = DateTime.add(e.end, e.offset_s, :second)

      time =
        if e.all_day,
          do: "all day",
          else: "#{hm(local_start)}–#{hm(local_end)}"

      status = if e.busy, do: "busy", else: "free"

      Map.merge(e, %{
        time_text: time,
        day_text: Calendar.strftime(local_start, "%a %d %b"),
        status: status,
        times_only: "#{time} #{status}"
      })
    end
  end

  defp hm(dt), do: Calendar.strftime(dt, "%H:%M")

  @doc """
  The calendar as the model's context for **this answer only** (titles included; `nil` titles
  read "no title"). Never store, log or post this text.
  """
  def context_text([]), do: "The user's phone calendar has no events in this range."

  def context_text(events) when is_list(events) do
    lines =
      for e <- enrich(events) do
        "- #{e.day_text} #{e.time_text}, #{e.status}: #{e.title || "(no title)"}"
      end

    Enum.join(
      [
        "The user's phone calendar (titles are for this spoken answer only; say them naturally):"
        | lines
      ],
      "\n"
    )
  end

  @doc """
  The spoken text in time-only wording for the stored transcript: `{text, titles?}`. Each title
  becomes its event's time-only wording ("10:00–10:30 busy"); if a distinctive word of any title
  is still there afterwards (a paraphrase), the whole text becomes the time-only list instead.
  `titles?` is true when the events carried any title (the answer may have spoken one).
  """
  def redact(text, events) when is_binary(text) and is_list(events) do
    titled = events |> enrich() |> Enum.filter(&is_binary(&1.title))

    if titled == [] do
      {text, false}
    else
      replaced =
        titled
        |> Enum.sort_by(&(-String.length(&1.title)))
        |> Enum.reduce(text, fn e, acc ->
          String.replace(acc, ~r/#{Regex.escape(e.title)}/iu, e.times_only)
        end)

      if leaks?(replaced, titled),
        do: {times_only_text(events), true},
        else: {replaced, true}
    end
  end

  defp leaks?(text, titled) do
    words =
      titled
      |> Enum.flat_map(&title_words(&1.title))
      |> Enum.uniq()

    down = String.downcase(text)

    Enum.any?(
      words,
      &Regex.match?(~r/(?<![\p{L}\p{N}])#{Regex.escape(&1)}(?![\p{L}\p{N}])/u, down)
    )
  end

  defp title_words(title) do
    title
    |> String.downcase()
    |> String.split(~r/[^\p{L}\p{N}]+/u, trim: true)
    |> Enum.filter(&(String.length(&1) >= 4 and &1 not in @common))
  end

  @doc "The events in time-only wording, e.g. \"Thu 15 Oct 10:00–10:30 busy; Fri 16 Oct all day free.\""
  def times_only_text([]), do: "No events in that range."

  def times_only_text(events) do
    (events |> enrich() |> Enum.map_join("; ", &"#{&1.day_text} #{&1.times_only}")) <> "."
  end

  @doc """
  A `risi_call_transcript` line (§41.5) of an answer that had the phone's events: its `text` in
  time-only wording and `"titles_spoken": true` when any title was available. Other lines and
  answers without titles are returned unchanged.
  """
  def transcript_line(%{"who" => "risi", "text" => text} = line, events) when is_list(events) do
    case redact(text, events) do
      {_, false} ->
        line

      {safe, true} ->
        line |> Map.put("text", String.slice(safe, 0, 2000)) |> Map.put("titles_spoken", true)
    end
  end

  def transcript_line(line, _events), do: line

  @doc "The learning-log fields of a phone read: counts only, never a title."
  def log_fields({:ok, events}),
    do: %{"cal_ok" => true, "events_n" => length(events), "titles_n" => titles_n(events)}

  def log_fields({:error, reason}),
    do: %{"cal_ok" => false, "cal_reason" => reason, "events_n" => 0, "titles_n" => 0}

  @doc "How many events carry a title."
  def titles_n(events), do: Enum.count(events, &is_binary(&1.title))

  # "2026-10-15T00:00:00+05:30": the wall-clock time at `off` seconds east of UTC.
  defp rfc3339(%DateTime{} = dt, off) do
    local =
      dt
      |> DateTime.add(off, :second)
      |> DateTime.to_naive()
      |> NaiveDateTime.truncate(:second)
      |> NaiveDateTime.to_iso8601()

    sign = if off < 0, do: "-", else: "+"
    a = abs(off)
    pad = &String.pad_leading(Integer.to_string(&1), 2, "0")
    local <> sign <> pad.(div(a, 3600)) <> ":" <> pad.(div(rem(a, 3600), 60))
  end
end
