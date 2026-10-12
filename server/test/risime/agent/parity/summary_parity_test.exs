defmodule RisiMe.Agent.Parity.SummaryTest do
  @moduledoc """
  Deep agents step 0: parity pins for summaries as they are today (fake `risi-l1`, Risi's clock
  fixed): the `summarise` action in an Official chat ("summarise this chat": the v1.24 `since`
  scope and `today`), and the sealed day summary written by `DailySummaries.maybe_day/2`.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Agent.{Clock, DailySummaries}
  alias RisiMe.Agent.DailySummaries.Row
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :risi_parity
  @moduletag capture_log: true

  @tz "Asia/Colombo"
  @bound_ms 3_000

  setup do
    ledger_on!()
    now = DateTime.utc_now() |> DateTime.truncate(:second)
    l = Clock.local(now, @tz)

    now =
      if l.hour < 6,
        do: DateTime.add(now, -(l.hour * 3600 + l.minute * 60 + l.second + 60)),
        else: now

    t0 = DateTime.add(now, -5 * 3600)
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

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

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

  defp schema_names,
    do: Enum.map(llm_requests(), &get_in(&1, ~w(response_format json_schema name)))

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
    t = System.monotonic_time(:millisecond)
    assert :ok = perform_job(Job, job.args)
    assert System.monotonic_time(:millisecond) - t < @bound_ms
    assert_receive {:risi_post, conv, body, risi}
    assert conv == ctx.og
    {rid, body, risi}
  end

  test "summarise this chat (since 6 h): one 'summary' call over the buffered lines", ctx do
    summary_llm!("Quote talk; Thursday visit.")
    talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)

    {rid, body, s} =
      request!(ctx, %{"since" => Clock.ts(DateTime.add(ctx.now, -6 * 3600))})

    assert body == "Summary: Quote talk; Thursday visit."

    assert keys(s) ==
             ~w(action_items call_ref decisions kind made_by notify open_questions partial
                request_id summary v)

    assert s["kind"] == "summary" and s["request_id"] == rid and s["v"] == 1
    assert s["summary"] == "Quote talk; Thursday visit." and s["partial"] == false
    assert s["decisions"] == ["Thursday site visit"]
    assert s["action_items"] == [] and s["open_questions"] == []
    assert s["notify"] == [ctx.harsha.id] and is_binary(s["call_ref"])
    assert s["made_by"]["model"] == "risi-l1" and s["made_by"]["provider"] == "risime"
    assert schema_names() == ["summary"]
    [req] = llm_requests()
    input = List.last(req["messages"])["content"]
    assert input =~ "line 0 from Harsha" and input =~ "line 5 from Shenika"
  end

  test "summarise today: 'Summary of today: …', the period, no stored days", ctx do
    summary_llm!("Today.")
    talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    {_rid, body, s} = request!(ctx, %{"period" => "today"})
    assert body == "Summary of today: Today."

    assert keys(s) ==
             ~w(action_items call_ref days decisions kind made_by notify open_questions partial
                period request_id summary v)

    assert s["kind"] == "summary" and s["days"] == [] and s["partial"] == false
    assert keys(s["period"]) == ~w(from scope to)
    assert s["period"]["scope"] == "today" and s["period"]["to"] == Clock.ts(ctx.now)

    local_midnight =
      Clock.to_utc(
        NaiveDateTime.new!(Clock.local(ctx.now, @tz) |> NaiveDateTime.to_date(), ~T[00:00:00]),
        @tz
      )

    assert s["period"]["from"] == Clock.ts(local_midnight)
    assert schema_names() == ["summary"]
  end

  test "nothing to summarise: the error card, no model call", ctx do
    fake_llm!(fn _, _ -> flunk("no model call") end)
    from = Clock.ts(DateTime.add(ctx.now, -20 * 86_400))
    to = Clock.ts(DateTime.add(ctx.now, -15 * 86_400))
    {_rid, body, e} = request!(ctx, %{"from" => from, "to" => to})
    assert body == "There's nothing to summarise in that period."
    assert keys(e) == ~w(call_ref code kind made_by notify request_id v)
    assert e["kind"] == "error" and e["code"] == "nothing_to_summarise"
    assert e["call_ref"] == nil and e["made_by"]["model"] == nil
    assert e["notify"] == [ctx.harsha.id]
    assert llm_requests() == []
  end

  test "the day summary at 23:35 local: one sealed row, made by risi-l1", ctx do
    summary_llm!("the quote was discussed")
    talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    date = ctx.t0 |> Clock.local(@tz) |> NaiveDateTime.to_date()
    at = Clock.to_utc(NaiveDateTime.new!(date, ~T[23:35:00]), @tz)

    t = System.monotonic_time(:millisecond)
    assert :ok = DailySummaries.maybe_day(ctx.og, at)
    assert System.monotonic_time(:millisecond) - t < @bound_ms

    [r] = Repo.all(from r in Row, where: r.scope == "day")
    assert r.period_from == date and r.period_to == date and r.message_count == 6
    assert r.made_by["model"] == "risi-l1" and r.tz == @tz
    {:ok, r} = DailySummaries.open(r)

    assert r.summary == %{
             "summary" => "the quote was discussed",
             "decisions" => ["Thursday site visit"],
             "action_items" => [],
             "open_questions" => []
           }

    # A local Sunday also rolls up the week (a second call).
    sunday? = Date.day_of_week(date) == 7
    assert schema_names() == if(sunday?, do: ["summary", "summary"], else: ["summary"])
    assert DailySummaries.maybe_day(ctx.og, DateTime.add(at, 600)) == :skipped
  end
end
