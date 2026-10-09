defmodule RisiMe.Agent.OffersItems810Test do
  @moduledoc """
  Items 8–10 (2026-10-09, Harsha): proactive "Add to calendar?" / "Remind me?" offers in the
  Risi chat (once per item, gated by RISI_SKILLS and the skills; the backfill gives Harsha's
  Shenika interview one), My promises split by direction (digest totals == API totals), and
  near-duplicate merging plus one clarification card for vague items. Fixed clock, fake model.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.LedgerHelpers, except: [clock!: 1]
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.{Commitment, ItemDedupe, LedgerReminders, Offers, Rest, Skills}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  # Fri 9 Oct 2026, 10:30 in Colombo (+05:30).
  @now ~U[2026-10-09 05:00:00Z]

  # 14:00 Colombo on Mon 12 Oct = 08:30Z.
  @interview %{
    "title" => "Interview with Shenika",
    "start" => "2026-10-12T08:30:00.000Z",
    "end" => "2026-10-12T09:30:00.000Z",
    "all_day" => false
  }

  setup do
    restore_on_exit([:risi_now])
    clock!(@now)
    ledger_on!()
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    shenika = RisiMe.Fixtures.logged_in_user(display_name: "Shenika")
    og = risi_chat!([harsha.user, shenika.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()

    dev =
      RisiMe.TabsHelpers.tabs_device!(harsha.user,
        caps: ~w(groups member_devices tabs risi_tools risi_ledger risi_skills)
      )

    ledger_devices!([shenika.user])
    rc = own_risi_chat!(harsha.user)
    skill!(harsha.user.id, "calendar", "ask")

    %{
      harsha: harsha,
      shenika: shenika,
      h: harsha.user.id,
      s: shenika.user.id,
      og: og,
      dev: dev,
      rc: rc
    }
  end

  ## Helpers

  defp clock!(at), do: Application.put_env(:risime, :risi_now, at)

  defp skill!(user, id, state) do
    Repo.insert!(
      %Skills.State{
        user_id: user,
        skill_id: id,
        state: state,
        ever_on: true,
        changed_at: DateTime.utc_now()
      },
      on_conflict: {:replace, [:state]},
      conflict_target: [:user_id, :skill_id]
    )
  end

  defp insert!(ctx, attrs) do
    {:ok, c} =
      Commitment.seal(
        struct!(
          %Commitment{
            id: Ecto.UUID.generate(),
            conversation_id: ctx.og,
            chat_id: ctx.og,
            state: "confirmed",
            item_state: "confirmed",
            text: "Interview with Shenika",
            owner_id: ctx.s,
            counterpart_ids: [ctx.h],
            due: ~U[2026-10-12 08:30:00.000000Z],
            due_kind: "datetime",
            all_day: false,
            source: "chat",
            source_message_ids: [],
            proposed_at: ~U[2026-10-09 05:00:00.000000Z]
          },
          attrs
        )
      )

    Repo.insert!(c)
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp of_kind(ps, kind), do: for({_, _, %{"kind" => ^kind} = r} <- ps, do: r)

  defp act!(ctx, action, target) do
    id =
      envelope!(
        ctx.rc,
        ctx.harsha.user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => nil
        },
        ctx.dev
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    job.args
  end

  defp wait_call(user_id, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      [call | _] ->
        call

      [] when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, tries - 1)

      _ ->
        flunk("no risi_tool_call event")
    end
  end

  ## Item 8: proactive offers

  test "backfill: the Shenika interview gets one Add to calendar card; [Add] adds it; never twice",
       ctx do
    c = insert!(ctx, [])

    assert Offers.backfill(dry_run: true) == %{items: 1, cards: 0}
    assert posts() == []
    assert Offers.backfill() == %{items: 1, cards: 1}

    # Harsha (counterpart of a tracked item, Calendar on) gets it; Shenika has no Risi chat.
    assert [{rc, body, card}] = posts()
    assert rc == ctx.rc

    assert body ==
             "Add \"Interview with Shenika\" to your calendar on Mon 12 Oct, 14:00–15:00? Update RisiMe to answer."

    assert card["kind"] == "confirm" and card["tool"] == "calendar_add"
    assert card["origin"] == "offer" and card["item_id"] == c.id
    assert card["args"] == @interview and card["for"] == [ctx.h]
    assert card["buttons"] == ["add", "cancel"] and card["skill_id"] == "calendar"
    assert Map.has_key?(card, "calendar") and is_binary(card["request_id"])
    # The card lives until the event (not 24 h).
    {:ok, exp, _} = DateTime.from_iso8601(card["expires_at"])
    assert DateTime.diff(exp, DateTime.utc_now()) > 2 * 86_400

    # Idempotent: a second run (and any later consider) offers nothing.
    assert Offers.backfill() == %{items: 1, cards: 0}
    assert Offers.consider_all([c]) == 0
    assert posts() == []

    # [Add] → calendar_add on Harsha's phone, no model call → Google Calendar line.
    task = Task.async(fn -> perform_job(Job, act!(ctx, "confirm_write", card["write_id"])) end)
    d = wait_call(ctx.h)["data"]
    assert d["tool"] == "calendar_add" and d["to_devices"] == [ctx.dev]
    assert d["args"] == Map.put(@interview, "write_id", card["write_id"])

    {204, _} =
      api(
        :post,
        "/api/v1/risi/tool_calls/#{d["tool_call_id"]}/result",
        ctx.harsha.token,
        %{
          "status" => "ok",
          "result" => %{
            "event_id" => "e1",
            "calendar" => %{"name" => "Google Calendar", "account" => nil}
          }
        },
        ctx.dev
      )

    assert :ok = Task.await(task)

    assert [{_, "Added to your Google Calendar: Interview with Shenika · Mon 12 Oct, 2–3 PM", _}] =
             posts()

    assert [%{kind: "calendar", user_id: h, state: "added"}] = Offers.list(c.id)
    assert h == ctx.h
    assert Offers.backfill().cards == 0
  end

  test "the release task (dry run by default) and the offers_backfill job", ctx do
    c = insert!(ctx, [])
    assert {:ok, %{items: 1, cards: 0}} = RisiMe.Release.risi_offers_backfill()
    assert posts() == []
    assert :ok = perform_job(Job, %{"kind" => "offers_backfill"})
    assert [card] = of_kind(posts(), "confirm")
    assert card["item_id"] == c.id
    assert {:ok, %{items: 1, cards: 0}} = RisiMe.Release.risi_offers_backfill(dry_run: false)
  end

  test "gates: RISI_SKILLS, the Calendar skill; Remind me with Reminders; Cancel is final", ctx do
    c = insert!(ctx, [])

    Application.put_env(:risime, :risi_skills, false)
    assert Offers.backfill().cards == 0
    Application.put_env(:risime, :risi_skills, true)

    skill!(ctx.h, "calendar", "off")
    assert Offers.backfill().cards == 0
    assert posts() == [] and Offers.list(c.id) == []

    # Calendar on (even "Allowed": a proactive card is always asked) and Reminders on.
    skill!(ctx.h, "calendar", "allowed")
    skill!(ctx.h, "reminders", "ask")
    assert Offers.backfill().cards == 2
    ps = posts()
    [cal, rem] = of_kind(ps, "confirm")
    assert cal["tool"] == "calendar_add" and rem["tool"] == "set_reminder"
    assert rem["origin"] == "offer" and rem["item_id"] == c.id
    assert rem["summary"] == "Remind you on Mon 12 Oct at 13:45: Interview with Shenika"

    assert rem["when"] == %{
             "start" => "2026-10-12T08:15:00.000Z",
             "end" => nil,
             "all_day" => false
           }

    # [Cancel]: nothing runs, nothing is offered again.
    assert :ok = perform_job(Job, act!(ctx, "cancel_write", cal["write_id"]))
    assert posts() == []
    states = Map.new(Offers.list(c.id), &{&1.kind, &1.state})
    assert states == %{"calendar" => "cancelled", "reminder" => "offered"}
    assert Offers.backfill().cards == 0
  end

  test "not offered: past, all-day, done, or a counterpart before the owner's ✓", ctx do
    insert!(ctx, due: ~U[2026-10-08 08:30:00.000000Z])
    insert!(ctx, text: "Holiday", all_day: true, due_kind: "date")
    insert!(ctx, text: "Paid", state: "done", item_state: "done")
    # A proposed ledger item is nobody's promise yet: its counterpart isn't offered.
    p = insert!(ctx, text: "Site visit", state: "proposed", item_state: "proposed")
    assert Offers.backfill().cards == 0
    assert posts() == []

    # Harsha owns the proposed one: he is offered it.
    p2 =
      insert!(ctx,
        text: "Call the bank",
        owner_id: ctx.h,
        counterpart_ids: [ctx.s],
        state: "proposed",
        item_state: "proposed"
      )

    assert Offers.backfill().cards == 1
    assert [card] = of_kind(posts(), "confirm")
    assert card["item_id"] == p2.id
    assert Offers.list(p.id) == []
  end

  ## Items 8 + 10 from the ledger's extraction

  test "extraction: a timed item gets the card, a vague one one question; a repeat merges", ctx do
    t0 = DateTime.utc_now() |> DateTime.add(-5 * 3600) |> DateTime.truncate(:second)
    t0 = %{t0 | microsecond: {0, 6}}
    clock!(t0)
    day = t0 |> DateTime.add(3 * 86_400) |> DateTime.to_date() |> Date.to_iso8601()
    later = t0 |> DateTime.add(5 * 86_400) |> DateTime.to_date() |> Date.to_iso8601()

    discussion_llm!(fn body ->
      %{
        "items" => [
          item(body, "Harsha", "Interview with Shenika", to: ["Shenika"], due: "#{day}T14:00"),
          item(body, "Harsha", "Book the venue for the offsite",
            to: ["Shenika"],
            due_text: "sometime next week"
          )
        ]
      }
    end)

    {_, last} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], t0)
    clock!(DateTime.add(last, 600))
    assert :ok = quiet!(ctx.og)
    ps = posts()

    rows = Repo.all(Commitment) |> Enum.map(&elem(Commitment.open(&1), 1))
    assert length(rows) == 2
    interview = Enum.find(rows, &(&1.text == "Interview with Shenika"))
    venue = Enum.find(rows, &(&1.text =~ "venue"))
    refute interview.needs_clarification
    assert venue.needs_clarification and venue.due == nil

    # The owner's card for the timed item (in his Risi chat), one question for the vague one.
    assert [card] = of_kind(ps, "confirm")
    assert card["item_id"] == interview.id and card["origin"] == "offer"
    assert [q] = of_kind(ps, "item_clarify")
    assert q["item_id"] == venue.id and q["buttons"] == ["new_date"]

    assert q["question"] ==
             "When is \"Book the venue for the offsite\" due? You said \"sometime next week\". Give me a day and time and I'll track it."

    assert Enum.all?(ps, fn {conv, _, r} ->
             r["kind"] not in ~w(confirm item_clarify) or conv == ctx.rc
           end)

    {200, %{"commitments" => []}} =
      api(:get, "/api/v1/risi/commitments", ctx.harsha.token, nil, ctx.dev)

    {200, %{"commitments" => all}} =
      api(:get, "/api/v1/risi/commitments?state=all", ctx.harsha.token, nil, ctx.dev)

    assert all == []

    # A second discussion repeats both: merged (no new rows, no new summary); the vague one now
    # has a day, so it gets its calendar card; the interview isn't offered twice.
    discussion_llm!(fn body ->
      %{
        "items" => [
          item(body, "Harsha", "Shenika interview", to: ["Shenika"], due: "#{day}T14:00"),
          item(body, "Harsha", "book the offsite venue", due: "#{later}T10:00")
        ]
      }
    end)

    start2 = DateTime.add(last, 2400)
    {_, last2} = talk!(ctx.og, [ctx.harsha.user, ctx.shenika.user], start2)
    clock!(DateTime.add(last2, 600))
    assert :ok = quiet!(ctx.og)
    ps = posts()

    assert Repo.aggregate(Commitment, :count) == 2
    assert of_kind(ps, "discussion_summary") == []
    venue2 = Repo.get(Commitment, venue.id)
    refute venue2.needs_clarification
    assert venue2.due != nil and venue2.counterpart_ids == [ctx.s]
    assert [card2] = of_kind(ps, "confirm")
    assert card2["item_id"] == venue.id
    assert of_kind(ps, "item_clarify") == []
  end

  test "a vague item: one question, never repeated; a new date by item_edit → the card", ctx do
    v =
      insert!(ctx,
        text: "Send the photos",
        owner_id: ctx.h,
        counterpart_ids: [ctx.s],
        due: nil,
        due_kind: nil,
        due_text: "soon",
        needs_clarification: true
      )

    assert Offers.consider_all([v]) == 1
    assert [{rc, body, q}] = posts()
    assert rc == ctx.rc and q["kind"] == "item_clarify"

    assert body ==
             "When is \"Send the photos\" due? You said \"soon\". Give me a day and time and I'll track it."

    assert Offers.consider_all([v]) == 0
    assert Offers.backfill().cards == 0
    assert posts() == []

    {200, %{"commitments" => [w]}} =
      api(:get, "/api/v1/risi/commitments", ctx.harsha.token, nil, ctx.dev)

    assert w["status"] == "needs_clarification" and w["needs_clarification"] == true

    assert Offers.vague?(nil, nil)
    assert Offers.vague?(~U[2026-10-12 08:30:00Z], "sometime next week")
    assert Offers.vague?(~U[2026-10-12 08:30:00Z], "ASAP")
    refute Offers.vague?(~U[2026-10-12 08:30:00Z], "Monday 2pm")
  end

  ## Item 9: My promises split

  test "My promises: direction, owner label, status, tap-through; digest totals == API totals",
       ctx do
    insert!(ctx,
      text: "Send the quote",
      owner_id: ctx.h,
      counterpart_ids: [ctx.s],
      source_message_ids: ["m1", "m2"]
    )

    insert!(ctx, [])

    insert!(ctx,
      text: "Book the hall",
      owner_id: ctx.h,
      counterpart_ids: [],
      due: nil,
      due_kind: nil,
      state: "confirmed",
      item_state: nil
    )

    {200, %{"commitments" => list, "totals" => totals}} =
      api(:get, "/api/v1/risi/commitments", ctx.harsha.token, nil, ctx.dev)

    assert totals == %{"i_promised" => 2, "promised_to_me" => 1, "others" => 0}
    quote = Enum.find(list, &(&1["text"] == "Send the quote"))
    assert quote["direction"] == "i_promised" and quote["owner_name"] == "You"
    assert quote["status"] == "confirmed" and quote["source_conversation_id"] == ctx.og
    assert quote["source_message_id"] == "m1" and quote["source_message_ids"] == ["m1", "m2"]
    owed = Enum.find(list, &(&1["text"] == "Interview with Shenika"))
    assert owed["direction"] == "promised_to_me" and owed["owner_name"] == "Shenika"
    assert owed["due"] == "2026-10-12T08:30:00.000Z" and owed["source_message_id"] == nil

    # Shenika's side of the same rows.
    {200, %{"totals" => st}} =
      api(:get, "/api/v1/risi/commitments", ctx.shenika.token, nil, nil)

    assert st == %{"i_promised" => 1, "promised_to_me" => 1, "others" => 0}
    assert Rest.direction(%{owner_id: "a", counterpart_ids: ["b"]}, "c") == "others"

    # The 09:00 digest (Sat 10 Oct) carries the same totals and per-item directions.
    assert LedgerReminders.digest(ctx.h, ~U[2026-10-10 03:30:00Z]) == :sent
    [d] = of_kind(posts(), "digest")
    assert d["totals"] == totals

    assert Enum.frequencies_by(d["items"], & &1["direction"]) == %{
             "i_promised" => 2,
             "promised_to_me" => 1
           }
  end

  ## Item 10: the duplicate rule

  test "near duplicates: owner, counterparts, text, due window" do
    a = %DateTime{} = ~U[2026-10-12 08:30:00Z]
    assert ItemDedupe.similar?("Interview with Shenika", "Shenika interview on Monday 2pm")
    assert ItemDedupe.similar?("Send the revised quote", "send revised quotes to Harsha")
    refute ItemDedupe.similar?("Send the quote", "Send the invoice")
    refute ItemDedupe.similar?("Book the hall", "Call the bank")
    assert ItemDedupe.counterparts_agree?(["x"], ["x", "y"])
    refute ItemDedupe.counterparts_agree?(["x"], ["y"])
    assert ItemDedupe.same_window?(a, DateTime.add(a, 3600))
    assert ItemDedupe.same_window?(nil, a)
    refute ItemDedupe.same_window?(a, DateTime.add(a, 3 * 86_400))
  end

  test "a repeated legacy-shaped item merges into the live one", ctx do
    c =
      insert!(ctx,
        text: "Send the revised quote",
        owner_id: ctx.h,
        counterpart_ids: [ctx.s],
        due: nil,
        due_kind: nil,
        needs_clarification: true,
        source_message_ids: ["m1"]
      )

    assert ItemDedupe.merged?(%{
             owner: ctx.h,
             counterparts: [ctx.s],
             text: "send revised quote",
             due: ~U[2026-10-12 11:30:00Z],
             due_kind: "datetime",
             due_text: "Monday 5pm",
             source_message_ids: ["m2"]
           })

    {:ok, m} = Commitment.open(Repo.get(Commitment, c.id))
    assert m.due == ~U[2026-10-12 11:30:00.000000Z] and m.due_text == "Monday 5pm"
    assert m.source_message_ids == ["m1", "m2"] and not m.needs_clarification
    assert Repo.aggregate(Commitment, :count) == 1
    # Tracked and now timed: the owner's calendar offer and the reminders are rescheduled.
    assert [card] = of_kind(posts(), "confirm")
    assert card["item_id"] == c.id

    refute ItemDedupe.merged?(%{
             owner: ctx.s,
             counterparts: [],
             text: "send revised quote",
             due: nil
           })
  end
end
