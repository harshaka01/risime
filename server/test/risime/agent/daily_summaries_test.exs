defmodule RisiMe.Agent.DailySummariesTest do
  @moduledoc """
  30-day summaries (Harsha 2026-10-09; proposal 2026-10-09-risi-30day-summaries): a sealed day
  summary per Official chat per local day, weekly rollups, `summarise` with `scope` today / 7d /
  30d / {from, to} answered from the rollups + commitments + the last 24 h of raw text, and the
  "Summaries" group of GET/DELETE /api/v1/risi/facts. Fake `risi-l1`, fixed clock.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Agent.{Clock, DailySummaries, Rest, Seal}
  alias RisiMe.Agent.DailySummaries.Row
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true
  @tz "Asia/Colombo"

  setup do
    ledger_on!()
    now = DateTime.utc_now() |> DateTime.truncate(:second)
    t0 = DateTime.add(now, -5 * 3600)
    # Keep the discussion before 22:00 local so its day's 23:35 sweep comes after it.
    local = Clock.local(t0, @tz)
    t0 = if local.hour >= 22, do: DateTime.add(t0, -3 * 3600), else: t0
    t0 = %{t0 | microsecond: {0, 6}}
    clock!(now)

    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    shenika = RisiMe.GroupHelpers.fast_user!("Shenika")
    for u <- [harsha, shenika], do: u |> Ecto.Changeset.change(tz: @tz) |> Repo.update!()
    og = risi_chat!([harsha, shenika])
    %{harsha: harsha, shenika: shenika, og: og, t0: t0, now: now}
  end

  defp summary_llm!(text) do
    fake_llm!(fn "summary", _body ->
      %{
        "summary" => text,
        "decisions" => ["Thursday site visit"],
        "action_items" => [],
        "open_questions" => []
      }
    end)
  end

  defp put_summary!(og, scope, from, to, text) do
    id = Ecto.UUID.generate()
    {:ok, key} = Seal.data()

    Repo.insert!(%Row{
      id: id,
      conversation_id: og,
      chat_id: og,
      scope: scope,
      period_from: from,
      period_to: to,
      tz: @tz,
      covered_from: DateTime.new!(from, ~T[03:00:00.000000]),
      covered_to: DateTime.new!(to, ~T[12:00:00.000000]),
      message_count: 3,
      summary_sealed:
        Seal.seal(key, Seal.aad("risi_daily_summaries", id, "summary"), %{
          "summary" => text,
          "decisions" => [],
          "action_items" => [],
          "open_questions" => []
        }),
      created_at: Clock.usec(Clock.now())
    })

    id
  end

  defp day_of(dt), do: dt |> Clock.local(@tz) |> NaiveDateTime.to_date()

  defp request!(ctx, scope) do
    rid = Ecto.UUID.generate()

    envelope!(ctx.og, ctx.harsha, %{
      "v" => 1,
      "type" => "risi_request",
      "request_id" => rid,
      "action" => "summarise",
      "text" => nil,
      "scope" => scope
    })

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert :ok = perform_job(Job, job.args)
    assert_receive {:risi_post, _, body, risi}
    {body, risi}
  end

  test "a sealed day summary at 23:30 local, once a day; nothing without messages", ctx do
    canary = "CANARY-#{Ecto.UUID.generate()}"
    summary_llm!("#{canary} the quote was discussed")
    talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    date = day_of(ctx.t0)

    assert DailySummaries.maybe_day(
             ctx.og,
             Clock.to_utc(NaiveDateTime.new!(date, ~T[23:29:00]), @tz)
           ) ==
             :skipped

    at = Clock.to_utc(NaiveDateTime.new!(date, ~T[23:35:00]), @tz)
    assert :ok = DailySummaries.maybe_day(ctx.og, at)
    [r] = Repo.all(Row)
    assert r.scope == "day" and r.period_from == date and r.message_count == 6
    assert r.made_by["model"] == "risi-l1"

    %{rows: rows} = Repo.query!("SELECT t::text FROM risi_daily_summaries t")
    refute Enum.any?(rows, fn [x] -> x =~ canary end)
    {:ok, r} = DailySummaries.open(r)
    assert r.summary["summary"] =~ canary

    # Once a day.
    assert DailySummaries.maybe_day(ctx.og, DateTime.add(at, 600)) == :skipped
    assert length(llm_requests()) == 1

    # Another chat without messages: no summary, no model call.
    other = risi_chat!([ctx.harsha, ctx.shenika])
    assert DailySummaries.maybe_day(other, at) == :skipped
    assert length(llm_requests()) == 1
  end

  test "weekly rollup from the day summaries only; for_range prefers whole weeks", ctx do
    summary_llm!("The week: quote revised, visit booked.")
    sunday = Date.add(Date.utc_today(), -(Date.day_of_week(Date.utc_today()) |> rem(7)))
    days = for d <- 0..6, do: Date.add(sunday, -d)
    for d <- days, do: put_summary!(ctx.og, "day", d, d, "day #{d}")

    assert :ok = DailySummaries.rollup_week(ctx.og, sunday, @tz)
    [week] = Repo.all(from r in Row, where: r.scope == "week")
    assert week.period_from == Date.add(sunday, -6) and week.period_to == sunday
    assert week.message_count == 21

    # The rollup's model input is the derived day summaries, not chat text.
    [req] = llm_requests()
    input = List.last(req["messages"])["content"]
    assert input =~ "<summaries>" and input =~ "day #{sunday}"
    refute input =~ "<chat>"

    later = Date.add(sunday, 1)
    put_summary!(ctx.og, "day", later, later, "the Monday after")
    used = DailySummaries.for_range(ctx.og, Date.add(sunday, -6), later)
    assert Enum.map(used, & &1.scope) == ["week", "day"]
  end

  test "summarise 7d: from the stored summaries + commitments + the last 24 h, naming the period",
       ctx do
    summary_llm!("A week of quote talk.")
    today = day_of(ctx.now)
    d3 = Date.add(today, -3)
    d5 = Date.add(today, -5)
    id3 = put_summary!(ctx.og, "day", d3, d3, "three days ago: the quote")
    id5 = put_summary!(ctx.og, "day", d5, d5, "five days ago: the visit")
    # Older than the range: not used.
    put_summary!(ctx.og, "day", Date.add(today, -12), Date.add(today, -12), "too old")
    talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)

    {body, s} = request!(ctx, "7d")
    assert body =~ ~r/^Summary of the last 7 days \(.+\): A week of quote talk\.$/
    assert s["kind"] == "summary" and s["period"]["scope"] == "7d"
    assert s["period"]["to"] == Clock.ts(ctx.now)
    assert s["period"]["from"] == Clock.ts(DateTime.add(ctx.now, -7 * 86_400))
    assert Enum.map(s["days"], & &1["summary_id"]) == [id5, id3]

    assert hd(s["days"]) == %{
             "date" => "#{d5}",
             "to" => "#{d5}",
             "scope" => "day",
             "summary_id" => id5
           }

    [req] = llm_requests()
    input = List.last(req["messages"])["content"]
    assert input =~ "five days ago" and input =~ "three days ago"
    refute input =~ "too old"
    # The raw text of the last 24 h too.
    assert input =~ "line 0 from Harsha"
  end

  test "today, {period}, {from, to}; out_of_window past 31 days; nothing to summarise", ctx do
    summary_llm!("Today.")
    talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    {body, s} = request!(ctx, %{"period" => "today"})
    assert s["period"]["scope"] == "today" and s["days"] == []
    assert body == "Summary of today: Today."

    old = Clock.ts(DateTime.add(ctx.now, -40 * 86_400))
    {_, e} = request!(ctx, %{"from" => old, "to" => Clock.ts(ctx.now)})
    assert e["kind"] == "error" and e["code"] == "out_of_window"

    from = Clock.ts(DateTime.add(ctx.now, -20 * 86_400))
    to = Clock.ts(DateTime.add(ctx.now, -15 * 86_400))
    {_, e} = request!(ctx, %{"from" => from, "to" => to})
    assert e["code"] == "nothing_to_summarise"

    # The v1.24 scope still works.
    {_, s} = request!(ctx, %{"since" => Clock.ts(DateTime.add(ctx.now, -6 * 3600))})
    assert s["kind"] == "summary" and not Map.has_key?(s, "period")
  end

  test "the Summaries group of GET /risi/facts (risi_tools devices), deletable by a member",
       ctx do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    dev = RisiMe.TabsHelpers.risi_tools_device!(ctx.harsha)
    old_dev = RisiMe.TabsHelpers.tabs_device!(ctx.harsha)
    d = Date.add(day_of(ctx.now), -1)
    id = put_summary!(ctx.og, "day", d, d, "yesterday: the quote")

    {:ok, facts} = Rest.facts(ctx.harsha.id, dev)
    [f] = for f <- facts, f.kind == "summary", do: f
    assert f.fact_id == id and f.text == "yesterday: the quote" and f.scope == "day"
    assert f.period == %{from: "#{d}", to: "#{d}"} and f.chat_id == ctx.og

    # Older apps don't get the kind.
    {:ok, facts} = Rest.facts(ctx.harsha.id, old_dev)
    refute Enum.any?(facts, &(&1.kind == "summary"))

    # A non-member can't delete it; a member can (for the chat).
    stranger = RisiMe.GroupHelpers.fast_user!("Stranger")
    assert Rest.delete_fact(stranger.id, id) == {:error, :not_found}
    assert Rest.delete_fact(ctx.shenika.id, id) == :ok
    assert Repo.all(Row) == []
  end

  test "kept 35 days; Official off deletes them", ctx do
    d = Date.add(day_of(ctx.now), -1)
    put_summary!(ctx.og, "day", d, d, "x")
    clock!(DateTime.add(ctx.now, 36 * 86_400))
    DailySummaries.prune()
    assert Repo.all(Row) == []

    clock!(ctx.now)
    put_summary!(ctx.og, "day", d, d, "x")
    RisiMe.Agent.Secretary.forget(ctx.og)
    assert Repo.all(Row) == []
  end
end
