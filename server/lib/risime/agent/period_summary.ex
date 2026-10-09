defmodule RisiMe.Agent.PeriodSummary do
  @moduledoc """
  `risi_request` `summarise` over a named period (30-day summaries, proposal
  `2026-10-09-risi-30day-summaries.md`): `scope` `"today"`, `"7d"`, `"30d"` (also as
  `{"period": …}`) or `{"from": ts, "to": ts}` (at most 31 days back).

  The answer rests on the stored day and week summaries of the range
  (`RisiMe.Agent.DailySummaries.for_range/3`), the chat's tracked commitments, and the raw
  buffered text of the last 24 h not yet covered by a day summary (raw text is never kept
  longer). The `summary` reply gains `period` (`from`, `to`, `scope`) and `days` (the summaries
  used: `date`, `to`, `scope`, `summary_id`), and its `body` names the period covered.
  """
  alias RisiMe.Agent.{Clock, DailySummaries, Prompts, Requests, Secretary}

  @max_back_s 31 * 86_400
  @buffer_s 24 * 3600

  @doc "Answers one period summary (`complete` is `Requests`' model call + reply). Oban result."
  def run(conv, rid, user, p, complete) do
    now = Clock.now()
    tz = Clock.user_tz(user)

    {from, to} =
      case p do
        "today" ->
          today = now |> Clock.local(tz) |> NaiveDateTime.to_date()
          {Clock.to_utc(NaiveDateTime.new!(today, ~T[00:00:00]), tz), now}

        "7d" ->
          {DateTime.add(now, -7 * 86_400), now}

        "30d" ->
          {DateTime.add(now, -30 * 86_400), now}

        {f, t} ->
          {f, Enum.min([t, now], DateTime)}
      end

    cond do
      DateTime.diff(now, from) > @max_back_s ->
        Requests.reply_error(conv, rid, user, "out_of_window")

      DateTime.compare(to, from) != :gt ->
        Requests.reply_error(conv, rid, user, "nothing_to_summarise")

      true ->
        answer(conv, rid, user, scope_name(p), from, to, now, tz, complete)
    end
  end

  defp scope_name(p) when is_binary(p), do: p
  defp scope_name(_), do: "range"

  defp answer(conv, rid, user, scope, from, to, now, tz, complete) do
    chat_tz = Secretary.chat_tz(conv)
    floor = DateTime.add(now, -@buffer_s)

    rows =
      if DateTime.compare(from, floor) == :lt,
        do: DailySummaries.for_range(conv, local_date(from, chat_tz), local_date(to, chat_tz)),
        else: []

    covered_to =
      rows
      |> Enum.map(& &1.covered_to)
      |> Enum.reject(&is_nil/1)
      |> Enum.max(DateTime, fn -> nil end)

    raw_from = Enum.max(Enum.reject([from, floor, covered_to], &is_nil/1), DateTime)

    raw =
      conv
      |> Secretary.text_messages(raw_from)
      |> Enum.filter(fn m ->
        t = RisiMe.TimeUUID.to_datetime(m.message_id)
        DateTime.compare(t, raw_from) == :gt and DateTime.compare(t, to) != :gt
      end)

    if rows == [] and raw == [] do
      Requests.reply_error(conv, rid, user, "nothing_to_summarise")
    else
      items = Requests.tracked_items(conv)
      {chat, _refs} = Requests.render_messages(conv, raw, now)

      prompt =
        Prompts.summaries_block(Enum.map(rows, &DailySummaries.line/1)) <>
          Prompts.commitments_block(items) <> "\n\n" <> chat

      label = label(scope, from, to, tz)

      days =
        for r <- rows do
          %{
            "date" => Date.to_iso8601(r.period_from),
            "to" => Date.to_iso8601(r.period_to),
            "scope" => r.scope,
            "summary_id" => r.id
          }
        end

      complete.(conv, rid, user, %{
        task: "summarise",
        system: Prompts.period_system(),
        user: prompt,
        schema_name: "summary",
        schema: Prompts.summary_schema(),
        msgs: raw,
        max_tokens: 1_200,
        reply: fn out, call_ref, _conf ->
          out =
            Map.new(out, fn
              {k, s} when is_binary(s) ->
                {k, RisiMe.Agent.Capabilities.strip_labels(s)}

              {k, l} when is_list(l) ->
                {k, Enum.map(l, &RisiMe.Agent.Capabilities.strip_labels/1)}

              kv ->
                kv
            end)

          {"Summary of #{label}: " <> out["summary"],
           %{
             "kind" => "summary",
             "request_id" => rid,
             "summary" => out["summary"],
             "decisions" => out["decisions"],
             "action_items" => out["action_items"],
             "open_questions" => out["open_questions"],
             "partial" => false,
             "period" => %{"from" => Clock.ts(from), "to" => Clock.ts(to), "scope" => scope},
             "days" => days,
             "call_ref" => call_ref
           }}
        end
      })
    end
  end

  defp local_date(dt, tz), do: dt |> Clock.local(tz) |> NaiveDateTime.to_date()

  # "today", "the last 7 days (3–9 Oct)", "3–9 Oct".
  defp label(scope, from, to, tz) do
    a = Clock.local(from, tz)
    b = Clock.local(to, tz)

    range =
      if a.month == b.month,
        do: "#{a.day}–#{Calendar.strftime(b, "%-d %b")}",
        else: "#{Calendar.strftime(a, "%-d %b")}–#{Calendar.strftime(b, "%-d %b")}"

    case scope do
      "today" -> "today"
      "7d" -> "the last 7 days (#{range})"
      "30d" -> "the last 30 days (#{range})"
      _ -> range
    end
  end
end
