defmodule RisiMe.Agent.LedgerS24Test do
  @moduledoc """
  v1.27 S24 (§27.6): every reminder moment in the addressee's zone (owner before/at, the
  counterpart's "due today" only once confirmed, the overdue follow-up and the nudge once each),
  the personal 09:00 digest, and the group digest retired where everyone has `risi_ledger`.
  Fixed clock, fake `risi-l1`.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers

  alias RisiMe.Agent.{Commitment, Commitments, LedgerReminders}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    ledger_on!()
    t0 = DateTime.utc_now() |> DateTime.add(-5 * 3600) |> DateTime.truncate(:second)
    t0 = %{t0 | microsecond: {0, 6}}
    clock!(t0)

    harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
    shenika = RisiMe.GroupHelpers.fast_user!("Shenika")
    for u <- [harsha, shenika], do: tz!(u, "Asia/Colombo")
    RisiMe.MLSHelpers.with_attestation_key(%{})
    ledger_devices!([harsha, shenika])
    og = risi_chat!([harsha, shenika])
    rc = %{harsha.id => own_risi_chat!(harsha), shenika.id => own_risi_chat!(shenika)}

    discussion_llm!(fn body ->
      %{
        "items" => [
          item(body, "Harsha", "Send the revised quote",
            to: ["Shenika"],
            due: "2026-12-01T17:00"
          ),
          item(body, "Shenika", "Book the site visit transport",
            to: ["Harsha"],
            due: "2026-12-07",
            due_text: "by Monday"
          ),
          item(body, "Harsha", "Think about it", to: ["Shenika"])
        ]
      }
    end)

    {_, last} = talk!(og, [harsha, shenika], t0)
    clock!(DateTime.add(last, 600))
    :ok = quiet!(og)
    drain()

    by_text = fn text ->
      Repo.all(Commitment)
      |> Enum.map(fn c -> elem(Commitment.open(c), 1) end)
      |> Enum.find(&(&1.text == text))
      |> Map.fetch!(:id)
    end

    %{
      harsha: harsha,
      shenika: shenika,
      og: og,
      rc: rc,
      quote: by_text.("Send the revised quote"),
      transport: by_text.("Book the site visit transport"),
      vague: by_text.("Think about it")
    }
  end

  defp tz!(u, tz), do: u |> Ecto.Changeset.change(tz: tz) |> Repo.update!()

  defp drain do
    receive do
      {:risi_post, _, _, _} -> drain()
    after
      20 -> :ok
    end
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp act!(ctx, user, target, action, edit \\ nil) do
    conv = ctx.rc[user.id]

    id =
      envelope!(conv, user, %{
        "v" => 1,
        "type" => "risi_action",
        "target" => target,
        "action" => action,
        "edit" => edit
      })

    perform_job(Job, %{
      "kind" => "action",
      "conv" => conv,
      "message_id" => id,
      "user_id" => user.id
    })

    drain()
  end

  defp reminders(id) do
    for j <- all_enqueued(worker: Job),
        j.args["kind"] == "item_reminder" and j.args["commitment_id"] == id,
        do: {j.args["moment"], j.args["to"], j.scheduled_at}
  end

  defp at(moments, moment, to) do
    Enum.find_value(moments, fn {m, t, at} -> if m == moment and t == to, do: at end)
  end

  defp fire!(id, moment, to, now) do
    [job] =
      for j <- all_enqueued(worker: Job),
          j.args["kind"] == "item_reminder" and j.args["commitment_id"] == id and
            j.args["moment"] == moment and j.args["to"] == to,
          do: j

    clock!(now)
    assert :ok = perform_job(Job, job.args)
    posts()
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()
  defp example(n), do: RisiMe.TabsHelpers.example(n)["risi"]

  test "no reminder before the owner's ✓; then every moment of a timed due", ctx do
    assert reminders(ctx.quote) == []
    act!(ctx, ctx.harsha, ctx.quote, "item_confirm")
    r = reminders(ctx.quote)

    # Due 2026-12-01 17:00 Colombo = 11:30Z.
    assert at(r, "before", ctx.harsha.id) == ~U[2026-12-01 10:30:00.000000Z]
    assert at(r, "at", ctx.harsha.id) == ~U[2026-12-01 11:30:00.000000Z]
    assert at(r, "today", ctx.shenika.id) == ~U[2026-12-01 03:30:00.000000Z]
    assert at(r, "overdue", ctx.harsha.id) == ~U[2026-12-01 13:30:00.000000Z]
    assert length(r) == 4

    rc_h = ctx.rc[ctx.harsha.id]
    rc_s = ctx.rc[ctx.shenika.id]

    [{^rc_h, body, b}] = fire!(ctx.quote, "before", ctx.harsha.id, ~U[2026-12-01 10:30:00Z])
    assert body == "Reminder: send the revised quote to Shenika (due 17:00)."
    assert keys(b) == keys(example("envelope_risi_item_due.json"))
    assert b["moment"] == "before" and b["role"] == "owner" and b["buttons"] == ~w(done new_date)
    assert b["notify"] == [ctx.harsha.id] and b["made_by"]["model"] == nil
    assert b["made_by"]["at"] == "2026-12-01T10:30:00.000Z"

    [{^rc_h, "Due now: send the revised quote to Shenika.", %{"moment" => "at"}}] =
      fire!(ctx.quote, "at", ctx.harsha.id, ~U[2026-12-01 11:30:00Z])

    [{^rc_s, body, t}] = fire!(ctx.quote, "today", ctx.shenika.id, ~U[2026-12-01 03:30:00Z])
    assert body == "Harsha's item 'Send the revised quote' is due today."
    assert keys(t) == keys(example("envelope_risi_item_due_counterpart.json"))
    assert t["role"] == "counterpart" and t["buttons"] == [] and t["notify"] == [ctx.shenika.id]

    [{^rc_h, body, o}] = fire!(ctx.quote, "overdue", ctx.harsha.id, ~U[2026-12-01 13:30:00Z])
    assert body == "'Send the revised quote' was due at 17:00. Done, or a new date?"
    assert keys(o) == keys(example("envelope_risi_item_overdue.json"))
    assert o["overdue_by"] == 7200 and o["buttons"] == ~w(done new_date)

    # The nudge, 24 h after the follow-up, to the counterpart.
    [{^rc_s, body, n}] = fire!(ctx.quote, "nudge", nil, ~U[2026-12-02 13:30:00Z])

    assert body ==
             "Harsha's item 'Send the revised quote' was due yesterday and isn't marked done."

    assert keys(n) == keys(example("envelope_risi_item_nudge.json"))
    assert n["overdue_by"] == 93_600 and n["notify"] == [ctx.shenika.id]

    # Once each: a re-run sends nothing.
    clock!(~U[2026-12-03 13:30:00Z])

    for j <- all_enqueued(worker: Job),
        j.args["kind"] == "item_reminder" and j.args["moment"] in ~w(overdue nudge),
        do: assert(:ok = perform_job(Job, j.args))

    assert posts() == []
  end

  test "all-day dues: 09:00 the day before / on the day, follow-up 10:00 the next day", ctx do
    act!(ctx, ctx.shenika, ctx.transport, "item_confirm")
    r = reminders(ctx.transport)
    assert at(r, "before", ctx.shenika.id) == ~U[2026-12-06 03:30:00.000000Z]
    assert at(r, "at", ctx.shenika.id) == ~U[2026-12-07 03:30:00.000000Z]
    assert at(r, "today", ctx.harsha.id) == ~U[2026-12-07 03:30:00.000000Z]
    assert at(r, "overdue", ctx.shenika.id) == ~U[2026-12-08 04:30:00.000000Z]

    [{_, body, _}] = fire!(ctx.transport, "at", ctx.shenika.id, ~U[2026-12-07 03:30:00Z])
    assert body == "Due today: book the site visit transport to Harsha."
  end

  test "times are in the addressee's zone", ctx do
    tz!(ctx.shenika, "Europe/London")
    act!(ctx, ctx.harsha, ctx.quote, "item_confirm")
    # 09:00 London on 1 Dec is 09:00Z, before due − 1 h (10:30Z).
    assert at(reminders(ctx.quote), "today", ctx.shenika.id) == ~U[2026-12-01 09:00:00.000000Z]
  end

  test "no due: no reminder; stale or done items: nothing", ctx do
    act!(ctx, ctx.harsha, ctx.vague, "item_confirm")
    assert reminders(ctx.vague) == []

    act!(ctx, ctx.harsha, ctx.quote, "item_confirm")
    [{_, _, at} | _] = reminders(ctx.quote)
    _ = at

    # An edit re-schedules (old jobs cancelled; the job of the old version is a no-op).
    old = Repo.get!(Commitment, ctx.quote).schedule_v

    act!(ctx, ctx.harsha, ctx.quote, "item_edit", %{"due" => "2026-12-03T11:30:00.000Z"})
    assert at(reminders(ctx.quote), "at", ctx.harsha.id) == ~U[2026-12-03 11:30:00.000000Z]

    clock!(~U[2026-12-01 11:30:00Z])

    assert :ok =
             perform_job(Job, %{
               "kind" => "item_reminder",
               "conv" => ctx.og,
               "commitment_id" => ctx.quote,
               "v" => old,
               "moment" => "at",
               "to" => ctx.harsha.id
             })

    assert posts() == []

    # Done: every pending reminder is a no-op.
    act!(ctx, ctx.shenika, ctx.quote, "done")
    assert reminders(ctx.quote) == []
  end

  test "confirmed after due − 1 h: no 'before'; after the follow-up time: follow-up now", ctx do
    # Within the 48 h of the summary: a due 30 min ahead (its `before` already past).
    now = RisiMe.Agent.Clock.now()
    due = DateTime.add(now, 1800)
    Repo.update_all(from(c in Commitment, where: c.id == ^ctx.quote), set: [due: due])
    act!(ctx, ctx.harsha, ctx.quote, "item_confirm")
    r = reminders(ctx.quote)
    assert at(r, "before", ctx.harsha.id) == nil
    assert DateTime.compare(at(r, "at", ctx.harsha.id), due) == :eq

    # A due 3 h ago: no before or at, the follow-up now.
    Repo.update_all(from(c in Commitment, where: c.id == ^ctx.transport),
      set: [due: DateTime.add(now, -3 * 3600), all_day: false]
    )

    act!(ctx, ctx.shenika, ctx.transport, "item_confirm")
    r = reminders(ctx.transport)
    assert at(r, "before", ctx.shenika.id) == nil and at(r, "at", ctx.shenika.id) == nil
    assert DateTime.compare(at(r, "overdue", ctx.shenika.id), now) == :eq
  end

  test "the personal digest at 09:00 in the user's zone, once a day, only with open items",
       ctx do
    act!(ctx, ctx.harsha, ctx.quote, "item_confirm")
    act!(ctx, ctx.shenika, ctx.transport, "item_confirm")

    # 08:59 Colombo: nothing.
    assert LedgerReminders.digest(ctx.harsha.id, ~U[2026-12-01 03:29:00Z]) == :skipped

    nine = ~U[2026-12-01 03:30:00Z]
    assert LedgerReminders.digest(ctx.harsha.id, nine) == :sent
    rc_h = ctx.rc[ctx.harsha.id]
    [{^rc_h, body, d}] = posts()
    # v1.28 §28.8: the digest and its items as in the v1.28 example, key for key.
    ex = example("envelope_risi_digest_personal_v128.json")
    assert keys(d) == keys(ex)
    for i <- d["items"], do: assert(keys(i) == keys(hd(ex["items"])))
    assert d["totals"] == %{"i_promised" => 1, "promised_to_me" => 1, "others" => 0}

    assert d["scope"] == "personal" and d["date"] == "2026-12-01" and
             d["notify"] == [ctx.harsha.id]

    assert length(d["items"]) == 2

    assert body ==
             "Your open items: send the revised quote (due today 17:00). " <>
               "Owed to you: Shenika, book the site visit transport (Monday)."

    # Once a day.
    assert LedgerReminders.digest(ctx.harsha.id, DateTime.add(nine, 900)) == :skipped

    # P0 2026-10-09: the digest lists exactly "My promises", also those due later than 7 days.
    assert LedgerReminders.digest(ctx.harsha.id, ~U[2026-11-20 03:30:00Z]) == :sent
    [{^rc_h, _body, d}] = posts()
    assert length(d["items"]) == 2
  end

  test "the group digest is retired where every human has risi_ledger", ctx do
    assert Commitments.group_digest_retired?(ctx.og)

    Repo.update_all(
      from(d in RisiMe.Devices.Device, where: d.user_id == ^ctx.shenika.id),
      set: [capabilities: ~w(groups member_devices tabs risi_tools)]
    )

    refute Commitments.group_digest_retired?(ctx.og)

    Application.put_env(:risime, :risi_ledger, false)
    refute Commitments.group_digest_retired?(ctx.og)
  end
end
