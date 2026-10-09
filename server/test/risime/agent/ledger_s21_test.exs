defmodule RisiMe.Agent.LedgerS21Test do
  @moduledoc """
  v1.27 S21 (§27.2): the quiet rule (every condition, the 4-h rule, spacing, the daily cap) and
  the `discussion_summarise` extraction (owners/counterparts checked against the members; no
  items → nothing posted). Fake `risi-l1`, fixed clock, sealed at rest.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Agent.{Commitment, Discussion, Ledger}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  # The buffer is partitioned by the real UTC day (yesterday and today are read), so the
  # discussion is placed in the last hours of real time and Risi's clock is set from there.
  setup do
    t0 = DateTime.utc_now() |> DateTime.add(-5 * 3600) |> DateTime.truncate(:second)
    t0 = %{t0 | microsecond: {0, 6}}
    clock!(t0)

    ledger_on!()
    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    shenika = RisiMe.GroupHelpers.fast_user!("Shenika")
    kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
    for u <- [harsha, shenika, kamal], do: tz!(u, "Asia/Colombo")
    og = risi_chat!([harsha, shenika, kamal])
    %{harsha: harsha, shenika: shenika, kamal: kamal, og: og, t0: t0}
  end

  defp tz!(u, tz), do: u |> Ecto.Changeset.change(tz: tz) |> Repo.update!()

  defp state(og),
    do:
      Repo.one(
        from s in "risi_chat_state",
          where: s.conversation_id == ^og,
          select: %{
            cutoff: type(s.summary_cutoff_ts, :utc_datetime_usec),
            since: type(s.discussion_since, :utc_datetime_usec),
            last: type(s.last_summary_at, :utc_datetime_usec),
            today: s.summaries_today
          }
      )

  defp quiet_job(og) do
    [j] =
      for j <- all_enqueued(worker: Job),
          j.args == %{"kind" => "discussion_quiet", "conv" => og},
          do: j

    j
  end

  defp model_calls, do: length(llm_requests())

  defp one_item(_ctx) do
    discussion_llm!(fn body ->
      %{"items" => [item(body, "Shenika", "Send the revised quote", to: ["Harsha"])]}
    end)
  end

  test "the quiet rule: checked at newest + 600 s; not quiet → rescheduled, no model call", ctx do
    one_item(ctx)
    {_ids, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)

    # One job per conversation, moved by every counted message.
    assert DateTime.compare(quiet_job(ctx.og).scheduled_at, DateTime.add(last, 600)) == :eq
    assert state(ctx.og).since == ctx.t0

    clock!(DateTime.add(last, 599))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 0
    assert Repo.aggregate(Discussion, :count) == 0

    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    assert [req] = llm_requests()
    assert get_in(req, ["response_format", "json_schema", "name"]) == "discussion_summary"

    [d] = Repo.all(Discussion)
    assert d.source == "chat" and d.conversation_id == ctx.og
    assert d.started_at == ctx.t0 and d.ended_at == last
    assert Enum.sort(d.participants) == Enum.sort([ctx.harsha.id, ctx.shenika.id])
    assert d.items_count == 1

    [c] = Repo.all(Commitment)
    assert c.item_state == "proposed" and c.state == "proposed" and c.summary_id == d.summary_id
    assert c.owner_id == ctx.shenika.id and c.counterpart_ids == [ctx.harsha.id]

    st = state(ctx.og)
    assert st.cutoff == last and st.since == nil and st.today == 1

    # The 48-h expiry of the proposal.
    assert [_] = for(j <- all_enqueued(worker: Job), j.args["kind"] == "item_expire", do: j)
  end

  test "enough: at least 6 counted messages from at least 2 people", ctx do
    one_item(ctx)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0, 5)
    clock!(DateTime.add(last, 700))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 0

    # 6 from one person only.
    {_, last} = talk!(ctx.og, [ctx.kamal], DateTime.add(last, 800), 6)

    Repo.update_all(from(s in "risi_chat_state", where: s.conversation_id == ^ctx.og),
      set: [summary_cutoff_ts: DateTime.add(last, -151)]
    )

    clock!(DateTime.add(last, 700))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 0

    # Requests, actions and Risi's own posts are never counted.
    envelope!(ctx.og, ctx.harsha, %{
      "v" => 1,
      "type" => "risi_action",
      "target" => Ecto.UUID.generate(),
      "action" => "done",
      "edit" => nil
    })

    assert Ledger.counted(ctx.og, DateTime.add(last, -151)) |> length() == 6
  end

  test "no items: nothing is posted or kept, the cutoff moves on", ctx do
    discussion_llm!(fn _ -> %{"items" => []} end)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 1
    refute_receive {:risi_post, _, _, _}
    assert Repo.aggregate(Discussion, :count) == 0 and Repo.aggregate(Commitment, :count) == 0
    st = state(ctx.og)
    assert st.cutoff == last and st.today == 0 and st.last == nil

    # Nothing new since the cutoff: no second call.
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 1
  end

  test "spacing: 30 min between summaries (re-checked then), at most 8 a day", ctx do
    one_item(ctx)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    first = DateTime.add(last, 600)
    clock!(first)
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 1

    {_, last2} = talk!(ctx.og, [ctx.harsha, ctx.shenika], DateTime.add(last, 660))
    now = DateTime.add(last2, 600)
    assert DateTime.diff(now, first) < 1800
    clock!(now)
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 1
    assert DateTime.compare(quiet_job(ctx.og).scheduled_at, DateTime.add(first, 1800)) == :eq

    clock!(DateTime.add(first, 1800))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 2

    # The day's cap (8, in the chat's zone).
    Repo.update_all(from(s in "risi_chat_state", where: s.conversation_id == ^ctx.og),
      set: [summaries_today: 8, last_summary_at: DateTime.add(first, -7200)]
    )

    {_, last3} = talk!(ctx.og, [ctx.harsha, ctx.shenika], DateTime.add(first, 1900))
    clock!(DateTime.add(last3, 600))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 2

    # The next local day counts again (those 8 were yesterday's).
    Repo.update_all(from(s in "risi_chat_state", where: s.conversation_id == ^ctx.og),
      set: [summaries_on: Date.add(Date.utc_today(), -2)]
    )

    assert :ok = quiet!(ctx.og)
    assert model_calls() == 3
  end

  test "long discussions: after 4 h it triggers at the next 3-min gap", ctx do
    one_item(ctx)
    # The discussion began at t0 (the oldest counted message) and is still going 4 h later.
    say!(ctx.og, ctx.kamal, "kick-off", ctx.t0)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], DateTime.add(ctx.t0, 4 * 3600 - 120), 5)
    assert state(ctx.og).since == ctx.t0
    # Scheduled at the 3-min gap, not newest + 600.
    assert DateTime.compare(quiet_job(ctx.og).scheduled_at, DateTime.add(last, 180)) == :eq

    clock!(DateTime.add(last, 179))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 0

    clock!(DateTime.add(last, 180))
    assert :ok = quiet!(ctx.og)
    assert model_calls() == 1
  end

  test "the extraction is checked: owners and counterparts against the members, confidence, repeats",
       ctx do
    outsider = RisiMe.GroupHelpers.fast_user!("Outsider")

    discussion_llm!(fn body ->
      %{
        "items" =>
          [
            # Kamal didn't speak but owns an item: he is a recipient.
            item(body, "Kamal", "Book the site visit transport",
              to: ["Harsha", "Kamal", "u9"],
              due: "2026-10-12",
              due_text: "by Monday"
            ),
            item(body, "u9", "An unknown owner"),
            item(body, "Shenika", "Too unsure", confidence: 0.3),
            item(body, "Shenika", "  ")
            # The same item twice counts once.
          ] ++ for(i <- [1, 2, 3, 4, 5, 5], do: item(body, "Harsha", "Task #{i}"))
      }
    end)

    _ = outsider
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)

    [d] = Repo.all(Discussion)
    assert d.items_count == 6
    assert Enum.sort(d.recipients) == Enum.sort([ctx.harsha.id, ctx.shenika.id, ctx.kamal.id])

    items = Repo.all(Commitment)
    assert length(items) == 6
    kamal = Enum.find(items, &(&1.owner_id == ctx.kamal.id))
    assert kamal.counterpart_ids == [ctx.harsha.id]

    # A date-only due: the end of that local day, all_day.
    assert kamal.all_day == true
    assert RisiMe.Agent.Clock.ts(kamal.due) == "2026-10-12T18:29:59.999Z"
    refute Enum.any?(items, &(&1.owner_id == outsider.id))
  end

  test "sealed at rest: no key point, summary or item text in plaintext anywhere", ctx do
    canary = "CANARY-#{Ecto.UUID.generate()}"

    discussion_llm!(fn body ->
      %{
        "key_points" => ["#{canary} point"],
        "summary" => "#{canary} line",
        "items" => [item(body, "Shenika", "#{canary} task", due_text: "#{canary} due")]
      }
    end)

    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)

    for table <- ~w(risi_discussions risi_commitments risi_chat_state oban_jobs) do
      %{rows: rows} = Repo.query!("SELECT t::text FROM #{table} t")
      refute Enum.any?(rows, fn [r] -> r =~ canary end), table
    end

    # Opened with the key, it is all there.
    [d] = Repo.all(Discussion)
    {:ok, d} = Discussion.open(d)
    assert d.key_points == ["#{canary} point"] and d.summary == "#{canary} line"
    [c] = Repo.all(Commitment)
    {:ok, c} = Commitment.open(c)
    assert c.text == "#{canary} task"
  end

  test "RISI_LEDGER off: the v1.24 extraction, no quiet job; never in a Risi chat", ctx do
    Application.put_env(:risime, :risi_ledger, false)
    say!(ctx.og, ctx.harsha, "hello", ctx.t0)
    kinds = for j <- all_enqueued(worker: Job), do: j.args["kind"]
    assert "extract" in kinds and "discussion_quiet" not in kinds

    Application.put_env(:risime, :risi_ledger, true)
    rc = own_risi_chat!(ctx.harsha)
    say!(rc, ctx.harsha, "a note to self", ctx.t0)

    refute Enum.any?(
             all_enqueued(worker: Job),
             &(&1.args == %{"kind" => "discussion_quiet", "conv" => rc})
           )

    assert :ok = quiet!(rc)
  end

  test "a v1.24 action on a ledger item is ignored", ctx do
    one_item(ctx)
    {_, last} = talk!(ctx.og, [ctx.harsha, ctx.shenika], ctx.t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    [c] = Repo.all(Commitment)

    id =
      envelope!(ctx.og, ctx.shenika, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => c.id,
        "action" => "confirm",
        "edit" => nil
      })

    perform_job(Job, %{
      "kind" => "action",
      "conv" => ctx.og,
      "message_id" => id,
      "user_id" => ctx.shenika.id
    })

    assert Repo.get!(Commitment, c.id).state == "proposed"
  end
end
