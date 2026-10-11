defmodule RisiMe.Agent.RisiCalendarFlowTest do
  @moduledoc """
  v1.29 §29.7–§29.12 Risi Calendar flows with a fixed clock (Fri 9 Oct 2026, 10:30 Colombo) and
  a fake model: the Shenika backfill (one proposed "Interview", invites in both Risi chats, the
  Official event card; both accept), offers → invites per audience (calendar users vs §28.5),
  held invites, card actions, reminders (default 30, rescheduled, cancelled), the
  `risi_calendar_add` action card ([Add] creates at once, no phone, no model), the
  `risi_calendar_check` honesty line (and the no-read answer without the data key), titles
  only for our own model, the digest `events`, Official off.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [events: 2]

  alias RisiMe.Agent.{
    Calendar,
    CalendarCards,
    CalendarOffers,
    Commitment,
    LedgerReminders,
    TurnSteps
  }

  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    calendar_world!()
  end

  ## Helpers

  defp item!(ctx, attrs \\ []) do
    {:ok, c} =
      Commitment.seal(
        struct!(
          %Commitment{
            id: Ecto.UUID.generate(),
            conversation_id: ctx.og,
            chat_id: ctx.og,
            state: "proposed",
            text: "Shenika: interview with Harsha",
            owner_id: ctx.s,
            counterpart_ids: [ctx.h],
            due: ~U[2026-10-12 08:30:00.000000Z],
            due_kind: "datetime",
            all_day: false,
            source_message_ids: [],
            proposed_at: ~U[2026-10-09 05:00:00.000000Z]
          },
          attrs
        )
      )

    Repo.insert!(c)
  end

  defp act!(ctx, who, conv, action, target, edit \\ nil) do
    {user, dev} = if who == :h, do: {ctx.harsha.user, ctx.hd}, else: {ctx.shenika.user, ctx.sd}

    id =
      envelope!(
        conv,
        user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => edit
        },
        dev
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    assert :ok = perform_job(Job, job.args)
  end

  defp ask!(ctx, text) do
    rid = Ecto.UUID.generate()

    envelope!(
      ctx.hrc,
      ctx.harsha.user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    assert :ok = perform_job(Job, job.args)
    rid
  end

  defp final(answer, draft \\ nil) do
    f = %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}
    if draft, do: Map.put(f, "draft", draft), else: f
  end

  defp reminder_jobs(event_id) do
    for j <- all_enqueued(worker: Job),
        j.args["kind"] == "calendar_reminder",
        j.args["event_id"] == event_id,
        do: j
  end

  defp only_event(user) do
    {:ok, %{"events" => evs}} =
      Calendar.list(user, "2026-10-09T00:00:00Z", "2026-10-31T00:00:00Z")

    evs
  end

  defp status_of(e, user),
    do: Enum.find_value(e["participants"], &(&1["user_id"] == user && &1["status"]))

  test "the backfill's catch-up events don't use up the chat's daily cap (a live one still fits)",
       ctx do
    for h <- 0..3,
        do:
          item!(ctx,
            text: "Shenika: task #{h}",
            due: DateTime.add(~U[2026-10-12 08:30:00.000000Z], h * 3600)
          )

    # All four old items get their events (the cap of 3 doesn't hold the catch-up).
    assert %{items: 4} = CalendarOffers.backfill()
    assert length(only_event(ctx.h)) == 4

    # Not counted against live chatter: a new item's event (or a note's meeting) still has room.
    assert CalendarOffers.room?(ctx.og, "Walkthrough", ~U[2026-10-13 05:30:00.000000Z]) == :ok
    c = item!(ctx, text: "Shenika: walkthrough", due: ~U[2026-10-13 05:30:00.000000Z])
    {:ok, c} = Commitment.open(c)
    assert {n, _} = CalendarOffers.consider(c, [ctx.s, ctx.h])
    assert n > 0 and length(only_event(ctx.h)) == 5
  end

  ## The Shenika backfill (gate case 2)

  test "backfill: one proposed 'Interview' 14:00–15:00 Colombo, invites in both Risi chats, the
        Official card; both accept → accepted in both calendars; idempotent",
       ctx do
    c = item!(ctx)

    assert CalendarOffers.backfill(dry_run: true) == %{items: 1, cards: 0}
    assert {:ok, %{items: 1, cards: 0}} = RisiMe.Release.risi_calendar_backfill()
    assert posts() == []

    assert %{items: 1, cards: 3} = CalendarOffers.backfill()
    ps = posts()

    # No §28.5 cards for calendar users.
    assert of_kind(ps, "confirm") == []
    invites = for {conv, body, %{"kind" => "calendar_invite"} = r} <- ps, do: {conv, body, r}
    assert Enum.sort(for {conv, _, _} <- invites, do: conv) == Enum.sort([ctx.hrc, ctx.src])

    {_, hbody, hinv} = Enum.find(invites, fn {conv, _, _} -> conv == ctx.hrc end)

    assert hbody ==
             "Invitation: Interview · Mon 12 Oct, 2–3 PM · with Shenika. Accept, decline or " <>
               "suggest another time in RisiMe."

    assert hinv["from"] == nil and hinv["item_id"] == c.id and hinv["reason"] == "new"
    assert hinv["source_conversation_id"] == ctx.og

    [{og, obody, official}] = for {conv, b, %{"kind" => "event_card"} = r} <- ps, do: {conv, b, r}
    assert og == ctx.og and official["mode"] == "official"
    assert official["buttons"] == ~w(accept decline suggest) and official["notify"] == []
    assert obody =~ "Meeting: Interview · Mon 12 Oct, 2–3 PM"

    assert [e] = only_event(ctx.h)
    assert e["title"] == "Interview" and e["tz"] == "Asia/Colombo"
    assert e["start"] == "2026-10-12T08:30:00.000Z" and e["end"] == "2026-10-12T09:30:00.000Z"
    assert e["owner"] == ctx.s and e["created_by"] == "risi" and e["source"]["item_id"] == c.id
    assert status_of(e, ctx.s) == "proposed" and status_of(e, ctx.h) == "proposed"

    # Idempotent (the deploy queues it every time).
    assert %{items: 1, cards: 0} = CalendarOffers.backfill()
    assert posts() == []

    # Both accept (Harsha from his invite, Shenika from the Official card).
    act!(ctx, :h, ctx.hrc, "event_accept", e["event_id"])
    act!(ctx, :s, ctx.og, "event_accept", e["event_id"], %{"reminder_min" => 10})

    for u <- [ctx.h, ctx.s] do
      [v] = only_event(u)
      assert v["my_status"] == "accepted"
    end

    assert [v] = only_event(ctx.s)
    assert v["my_reminder_min"] == 10
    ups = of_kind(posts(), "event_update")
    assert ups != [] and Enum.all?(ups, &(&1["change"] == "status"))

    # Reminders: Harsha 30 min before (default), Shenika 10.
    at = for j <- reminder_jobs(e["event_id"]), into: %{}, do: {j.args["user_id"], j.scheduled_at}
    assert DateTime.compare(at[ctx.h], ~U[2026-10-12 08:00:00Z]) == :eq
    assert DateTime.compare(at[ctx.s], ~U[2026-10-12 08:20:00Z]) == :eq

    # A forged action (not a participant, or another conversation) is ignored.
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")

    assert :ok =
             RisiMe.Agent.CalendarActions.act(ctx.og, kamal.user.id, %{
               "action" => "event_decline",
               "target" => e["event_id"]
             })

    assert :ok =
             RisiMe.Agent.CalendarActions.act(ctx.src, ctx.h, %{
               "action" => "event_decline",
               "target" => e["event_id"]
             })

    assert [%{"my_status" => "accepted"}] = only_event(ctx.h)
  end

  test "offers per audience: a non-calendar participant keeps §28.5 cards and a held invite,
        delivered once a risi_events device appears",
       ctx do
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")

    og =
      RisiMe.TabsHelpers.official_group!(
        "grp:" <> Ecto.UUID.generate(),
        [{ctx.h, "admin"}, {kamal.user.id, "member"}],
        agents: [RisiMe.Risi.user_id()]
      )

    kd = skills_device!(kamal.user)
    krc = own_risi_chat!(kamal.user)

    Repo.insert!(%RisiMe.Agent.Skills.State{
      user_id: kamal.user.id,
      skill_id: "calendar",
      state: "ask",
      ever_on: true,
      changed_at: DateTime.utc_now()
    })

    c =
      item!(ctx,
        conversation_id: og,
        chat_id: og,
        owner_id: ctx.h,
        counterpart_ids: [kamal.user.id],
        text: "Budget review with Kamal"
      )

    assert RisiMe.Agent.Offers.consider_all([c]) == 2
    ps = posts()

    # Harsha (calendar user): an invite; Kamal: the §28.5 calendar_add card.
    assert [{conv, _, _}] = for({cv, b, %{"kind" => "calendar_invite"} = r} <- ps, do: {cv, b, r})
    assert conv == ctx.hrc

    assert [{^krc, _, %{"tool" => "calendar_add"}}] =
             for({cv, b, %{"kind" => "confirm"} = r} <- ps, do: {cv, b, r})

    # No Official card: not every participant is a calendar user.
    assert of_kind(ps, "event_card") == []

    [e] = only_event(ctx.h)
    assert e["title"] == "Budget review"
    assert [{id, "new"}] = CalendarCards.pending(kamal.user.id)
    assert id == e["event_id"]

    # Kamal's phone updates: risi_events → the held invite goes out.
    _ = kd
    calendar_device!(kamal.user)

    assert [job] =
             for(
               j <- all_enqueued(worker: Job),
               j.args["kind"] == "calendar_pending" and j.args["user_id"] == kamal.user.id,
               do: j
             )

    assert :ok = perform_job(Job, job.args)
    assert [{^krc, _, %{"kind" => "calendar_invite"}}] = posts()
    assert CalendarCards.pending(kamal.user.id) == []
  end

  test "a device that gains risi_events after the item exists gets its invite (backfill per user),
        once",
       ctx do
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")

    og =
      RisiMe.TabsHelpers.official_group!(
        "grp:" <> Ecto.UUID.generate(),
        [{ctx.h, "admin"}, {kamal.user.id, "member"}],
        agents: [RisiMe.Risi.user_id()]
      )

    _kd = skills_device!(kamal.user)
    krc = own_risi_chat!(kamal.user)

    item!(ctx,
      conversation_id: og,
      chat_id: og,
      owner_id: ctx.h,
      counterpart_ids: [kamal.user.id],
      text: "Budget review with Kamal"
    )

    refute Enum.any?(
             all_enqueued(worker: Job),
             &(&1.args["kind"] == "calendar_backfill" and &1.args["user_id"] == kamal.user.id)
           )

    calendar_device!(kamal.user)

    assert [job] =
             for(
               j <- all_enqueued(worker: Job),
               j.args["kind"] == "calendar_backfill" and j.args["user_id"] == kamal.user.id,
               do: j
             )

    assert :ok = perform_job(Job, job.args)
    assert length(for {^krc, _, %{"kind" => "calendar_invite"}} <- posts(), do: :invite) == 1

    # A second registration (already risi_events) queues nothing new and sends no second card.
    calendar_device!(kamal.user)

    assert [_] =
             for(
               j <- all_enqueued(worker: Job),
               j.args["kind"] == "calendar_backfill" and j.args["user_id"] == kamal.user.id,
               do: j
             )

    assert :ok = perform_job(Job, job.args)
    assert length(for {^krc, _, %{"kind" => "calendar_invite"}} <- posts(), do: :invite) == 0
  end

  test "a ledger item: the owner's invite at extraction, the counterpart's once tracked", ctx do
    c = item!(ctx, state: "proposed", item_state: "proposed", summary_id: Ecto.UUID.generate())
    {:ok, c} = Commitment.open(c)
    assert RisiMe.Agent.Offers.consider(c) == 1

    assert [{conv, _, _}] =
             for({cv, b, %{"kind" => "calendar_invite"} = r} <- posts(), do: {cv, b, r})

    assert conv == ctx.src

    c = c |> Ecto.Changeset.change(item_state: "confirmed", state: "confirmed") |> Repo.update!()
    {:ok, c} = Commitment.open(c)
    assert RisiMe.Agent.Offers.consider(c) == 2
    ps = posts()
    assert [{conv, _, _}] = for({cv, b, %{"kind" => "calendar_invite"} = r} <- ps, do: {cv, b, r})
    assert conv == ctx.hrc
    assert [%{"mode" => "official"}] = of_kind(ps, "event_card")
    # Still one event.
    assert [_] = only_event(ctx.h)
  end

  test "never from Private: an item outside an Official conversation makes no event", ctx do
    private = "grp:" <> Ecto.UUID.generate()
    c = item!(ctx, conversation_id: private, chat_id: private)
    {:ok, c} = Commitment.open(c)
    assert CalendarOffers.consider(c, [ctx.s, ctx.h]) == {0, []}
    assert only_event(ctx.h) == []
  end

  test "Official off: Risi-made events nobody accepted are cancelled; accepted ones stay", ctx do
    item!(ctx)
    item!(ctx, text: "Shenika: lunch with Harsha", due: ~U[2026-10-13 06:30:00.000000Z])
    CalendarOffers.backfill()
    posts()
    [a, b] = only_event(ctx.h)
    act!(ctx, :h, ctx.hrc, "event_accept", a["event_id"])
    posts()

    Calendar.forget_conversation(ctx.og)
    assert Calendar.get(ctx.h, a["event_id"]) |> elem(1) |> Map.get("state") == "active"
    assert Calendar.get(ctx.h, b["event_id"]) |> elem(1) |> Map.get("state") == "cancelled"
  end

  ## Reminders

  test "reminders: start − 30 by default, rescheduled on change, the card at fire, stale and
        declined ones do nothing",
       ctx do
    {:ok, e} =
      Calendar.create(ctx.h, %{
        title: "Interview",
        notes: nil,
        tz: "Asia/Colombo",
        start: ~U[2026-10-12 08:30:00.000000Z],
        stop: ~U[2026-10-12 09:30:00.000000Z],
        all_day: false,
        with: [ctx.s],
        reminder_min: :default,
        source: nil,
        created_by: "user"
      })

    posts()
    assert [j1] = reminder_jobs(e["event_id"])

    assert j1.args["user_id"] == ctx.h and
             DateTime.compare(j1.scheduled_at, ~U[2026-10-12 08:00:00Z]) == :eq

    assert Map.keys(j1.args) |> Enum.sort() == ~w(event_id kind user_id v)

    {:ok, _} = Calendar.patch(ctx.h, e["event_id"], %{"version" => 1, "reminder_min" => 10})
    jobs = reminder_jobs(e["event_id"]) |> Enum.filter(&(&1.state in ~w(scheduled available)))
    assert [j2] = jobs
    assert DateTime.compare(j2.scheduled_at, ~U[2026-10-12 08:20:00Z]) == :eq

    # The old job is stale (cancelled, and its `v` no longer matches).
    assert :ok = perform_job(Job, j1.args)
    assert posts() == []

    assert :ok = perform_job(Job, j2.args)
    assert [{rc, body, r}] = posts()
    assert rc == ctx.hrc and r["kind"] == "calendar_reminder"
    assert body == "In 10 min: Interview (2:00 PM) with Shenika."
    assert r["buttons"] == ["open"] and r["notify"] == [ctx.h] and r["reminder_min"] == 10

    # A participant: none until they accept; decline cancels.
    {:ok, _} = Calendar.respond(ctx.s, e["event_id"], %{"response" => "accept", "version" => 1})

    assert Enum.any?(
             reminder_jobs(e["event_id"]),
             &(&1.args["user_id"] == ctx.s and &1.state == "scheduled")
           )

    {:ok, _} = Calendar.respond(ctx.s, e["event_id"], %{"response" => "decline", "version" => 1})

    refute Enum.any?(
             reminder_jobs(e["event_id"]),
             &(&1.args["user_id"] == ctx.s and &1.state == "scheduled")
           )
  end

  test "canary: a title never reaches the server log, inbox events, job args or turn steps",
       ctx do
    canary = "CANARY-#{Ecto.UUID.generate()}"

    log =
      ExUnit.CaptureLog.capture_log([level: :debug], fn ->
        {:ok, e} =
          Calendar.create(ctx.h, %{
            title: canary,
            notes: canary,
            tz: "Asia/Colombo",
            start: ~U[2026-10-12 08:30:00.000000Z],
            stop: ~U[2026-10-12 09:30:00.000000Z],
            all_day: false,
            with: [ctx.s],
            reminder_min: :default,
            source: nil,
            created_by: "user"
          })

        {:ok, _} =
          Calendar.respond(ctx.s, e["event_id"], %{"response" => "accept", "version" => 1})

        for j <- reminder_jobs(e["event_id"]), do: perform_job(Job, j.args)

        {:ok, _} =
          Calendar.patch(ctx.h, e["event_id"], %{"version" => 1, "title" => canary <> "!"})

        :ok = Calendar.delete(ctx.h, e["event_id"])
      end)

    refute log =~ "CANARY"
    refute Jason.encode!(events(ctx.h, nil) ++ events(ctx.s, nil)) =~ "CANARY"
    refute Jason.encode!(Enum.map(all_enqueued(worker: Job), & &1.args)) =~ "CANARY"

    refute Repo.query!("SELECT row_to_json(t)::text FROM risi_events t").rows
           |> List.flatten()
           |> Enum.any?(&(&1 =~ "CANARY"))
  end

  test "all-day reminders: 09:00 the day before when ≥ 60 min, else 09:00 on the day", ctx do
    e = %Calendar.Event{all_day: true, start_at: ~U[2026-10-11 18:30:00.000000Z]}

    assert DateTime.compare(
             RisiMe.Agent.CalendarReminders.at(e, 60, "Asia/Colombo"),
             ~U[2026-10-11 03:30:00Z]
           ) == :eq

    assert DateTime.compare(
             RisiMe.Agent.CalendarReminders.at(e, 30, "Asia/Colombo"),
             ~U[2026-10-12 03:30:00Z]
           ) == :eq

    _ = ctx
  end

  ## The action card (gate case 1)

  test "risi_calendar_add: the card with Add/Edit/Cancel; [Add] creates at once (no phone, no
        model), the event card, the invite; no phone calendar permission involved",
       ctx do
    fake_llm!(fn _name, _body ->
      final("Sure, Monday 12 Oct at 2 PM.", %{
        "kind" => "event",
        "title" => "Interview",
        "date" => "Monday 12 Oct",
        "time" => "2pm",
        "with" => ["Shenika"]
      })
    end)

    rid = ask!(ctx, "add my interview with Shenika on Monday 12 Oct at 2pm")
    ps = posts()
    assert [card] = of_kind(ps, "confirm")
    {_, abody, _} = Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)
    assert abody == "Tap Add on the card to put it in your Risi Calendar, or Cancel."

    assert card["tool"] == "risi_calendar_add" and card["request_id"] == rid
    assert card["buttons"] == ["add", "edit", "cancel"]
    assert card["skill_id"] == nil and card["calendar"] == nil and card["origin"] == nil
    assert card["for"] == [ctx.h]

    assert card["summary"] ==
             "Add to your Risi Calendar: Interview · Mon 12 Oct, 2–3 PM · with Shenika"

    assert card["args"] == %{
             "title" => "Interview",
             "start" => "2026-10-12T08:30:00.000Z",
             "end" => "2026-10-12T09:30:00.000Z",
             "all_day" => false,
             "tz" => "Asia/Colombo",
             "with" => [ctx.s],
             "reminder_min" => 30,
             "notes" => nil,
             # Asked in the Risi chat: that is its source.
             "source" => %{"conversation_id" => ctx.hrc, "message_ids" => [], "item_id" => nil}
           }

    assert [%{tool: "risi_calendar_add", status: "ok"}, %{tool: "final"}] =
             TurnSteps.list(card["turn_ref"])

    n = length(llm_requests())
    act!(ctx, :h, ctx.hrc, "confirm_write", card["write_id"])
    assert length(llm_requests()) == n
    assert events(ctx.h, "risi_tool_call") == []

    ps = posts()
    assert [{rc, body, ec}] = for({cv, b, %{"kind" => "event_card"} = r} <- ps, do: {cv, b, r})
    assert rc == ctx.hrc and ec["mode"] == "added" and ec["buttons"] == ["open", "edit", "delete"]
    assert body == "Added to your Risi Calendar: Interview · Mon 12 Oct, 2–3 PM · with Shenika."

    assert Enum.sort(Map.keys(ec)) ==
             Enum.sort(
               ~w(v kind mode event_id version title start end all_day tz owner participants
                          source_conversation_id item_id buttons made_by notify call_ref)
             )

    assert [{src, _, _}] = for({cv, b, %{"kind" => "calendar_invite"} = r} <- ps, do: {cv, b, r})
    assert src == ctx.src

    assert [e] = only_event(ctx.h)

    assert e["title"] == "Interview" and e["my_status"] == "accepted" and
             e["my_reminder_min"] == 30

    # A repeated confirm is a no-op.
    act!(ctx, :h, ctx.hrc, "confirm_write", card["write_id"])
    assert posts() == []
    assert [_] = only_event(ctx.h)
  end

  test "risi_calendar_add [Edit]: title, time, reminder and people changed before it is made",
       ctx do
    fake_llm!(fn _name, _body ->
      final("OK.", %{
        "kind" => "event",
        "title" => "Dentist",
        "date" => "Friday 16 Oct",
        "time" => "10am"
      })
    end)

    ask!(ctx, "dentist friday 10am")
    [card] = of_kind(posts(), "confirm")
    assert card["args"]["with"] == []

    act!(ctx, :h, ctx.hrc, "confirm_write", card["write_id"], %{
      "title" => "Dentist (Dr Swan)",
      "start" => "2026-10-16T05:00:00.000Z",
      "end" => "2026-10-16T05:30:00.000Z",
      "reminder_min" => nil,
      "with" => [ctx.s]
    })

    assert [e] = only_event(ctx.h)
    assert e["title"] == "Dentist (Dr Swan)" and e["my_reminder_min"] == nil
    assert e["end"] == "2026-10-16T05:30:00.000Z" and status_of(e, ctx.s) == "proposed"
  end

  ## Honesty (gate case 4)

  defp check_llm!(answer) do
    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: final(answer),
        else: %{
          "tool" => "risi_calendar_check",
          "args" => %{"from" => "2026-10-12T08:00:00Z", "to" => "2026-10-12T10:00:00Z"}
        }
    end)
  end

  # The `title`s of the risi_calendar_check blocks the model was given in a request.
  defp block_titles(req) do
    for %{"role" => "user", "content" => c} <- req["messages"],
        {:ok, %{"ok" => true, "result" => %{"source" => "risi_calendar", "blocks" => bs}}} <- [
          Jason.decode(c)
        ],
        b <- bs,
        do: b["title"]
  end

  defp answer_of(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)

  test "risi_calendar_check: the answer names what was checked; a proposed event is busy; the
        phone check isn't offered separately",
       ctx do
    check_llm!("Nothing much on Monday afternoon.")
    ask!(ctx, "Monday 2pm ok for the call?")
    {_, body, a} = answer_of(posts())

    assert body ==
             "Nothing much on Monday afternoon.\n\nChecked: Risi Calendar."

    assert %{"type" => "calendar_source", "source" => "risi_calendar", "read_ok" => true} in a[
             "sources"
           ] or
             Enum.any?(a["sources"], &(&1["source"] == "risi_calendar" and &1["read_ok"]))

    [req | _] = llm_requests()

    enum =
      get_in(req, ["response_format", "json_schema", "schema", "anyOf"])
      |> Enum.flat_map(&get_in(&1, ["properties", "tool", "enum"]))

    assert "risi_calendar_check" in enum and "risi_calendar_add" in enum
    refute "calendar_check" in enum

    # A proposed event in the window: "free" is rewritten.
    item!(ctx)
    CalendarOffers.backfill()
    posts()
    check_llm!("You're free then.")
    ask!(ctx, "Monday 2pm ok for the call?")
    {_, body, _} = answer_of(posts())

    assert body =~
             "You're not free then: your calendar has 1 busy time (Mon 12 Oct, 14:00–15:00)."

    assert body =~ "Checked: Risi Calendar."
  end

  test "risi_calendar_check with the phone Calendar skill on: both sources in one step", ctx do
    change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

    {200, _} =
      RisiMe.GroupHelpers.api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{"changes" => [change]},
        ctx.hd
      )

    check_llm!("Nothing on Monday at 2.")

    rid = Ecto.UUID.generate()

    envelope!(
      ctx.hrc,
      ctx.harsha.user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => "Monday 2pm ok for the call?"
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    task = Task.async(fn -> perform_job(Job, job.args) end)
    call = wait_call(ctx.h)
    assert call["data"]["tool"] == "calendar_check" and call["data"]["to_devices"] == [ctx.hd]

    result = %{
      "blocks" => [],
      "sources" => [
        %{
          "source" => "phone_provider",
          "calendars" => [%{"name" => "Work", "account_type" => "com.google", "events" => 0}],
          "read_ok" => true,
          "reason" => nil
        }
      ],
      "connected_sources" => ["phone_provider"]
    }

    {204, _} =
      RisiMe.GroupHelpers.api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => result},
        ctx.hd
      )

    assert :ok = Task.await(task)
    {_, body, a} = answer_of(posts())

    assert body ==
             "Nothing on Monday at 2.\n\nChecked: Risi Calendar · Phone calendar (Work)."

    assert Enum.map(a["sources"], & &1["source"]) == ["risi_calendar", "phone_provider"]
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

  test "risi_calendar_check without RISI_DATA_KEY: no read, the model's text is discarded", ctx do
    # The request itself was read with the key; the key goes before the calendar step runs.
    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")) do
        final("You're free! Your calendar is clear and you're available.")
      else
        Application.delete_env(:risime, :risi_data_key)

        %{
          "tool" => "risi_calendar_check",
          "args" => %{"from" => "2026-10-12T08:00:00Z", "to" => "2026-10-12T10:00:00Z"}
        }
      end
    end)

    _ = ask!(ctx, "Monday 2pm ok for the call?")
    {_, body, a} = answer_of(posts())

    assert body ==
             "I couldn't check your calendar, so I can't tell whether you're free.\n" <>
               "Risi Calendar: it couldn't be opened."

    refute body =~ ~r/\b(clear|available)\b/i
    assert a["made_by"]["model"] == nil
  end

  test "titles reach the model only on our own model", ctx do
    item!(ctx)
    CalendarOffers.backfill()
    posts()

    check_llm!("You have an interview.")
    ask!(ctx, "Monday 2pm ok for the call?")
    assert ["Interview"] == block_titles(List.last(llm_requests()))

    restore_on_exit([:risi_llm])

    Application.put_env(
      :risime,
      :risi_llm,
      Keyword.put(Application.get_env(:risime, :risi_llm, []), :fallback, true)
    )

    check_llm!("You have something.")
    ask!(ctx, "Monday 2pm ok for the call?")
    last = List.last(llm_requests())
    assert block_titles(last) == [nil]
    refute Jason.encode!(last) =~ "Interview"
  end

  ## Digest

  test "digest: today, tomorrow and pending events; posted for events alone", ctx do
    item!(ctx)
    CalendarOffers.backfill()
    posts()
    # Mon 12 Oct 09:05 Colombo.
    now = ~U[2026-10-12 03:35:00Z]
    Application.put_env(:risime, :risi_now, now)

    assert :sent = LedgerReminders.digest(ctx.h, now)
    [{rc, body, d}] = posts()
    assert rc == ctx.hrc and d["kind"] == "digest"
    assert [%{"title" => "Interview", "my_status" => "proposed"} = ref] = d["events"]["today"]
    assert Enum.sort(Map.keys(ref)) == ~w(all_day end event_id my_status start title)
    assert d["events"]["tomorrow"] == []
    assert [_] = d["events"]["pending"]
    assert body =~ "Today: Interview (Mon 12 Oct, 2–3 PM, tentative)."

    {:ok, _} = Calendar.put_settings(ctx.s, %{"digest_events" => false})
    Repo.delete_all(from s in "risi_chat_state", where: s.conversation_id == ^ctx.src)
    LedgerReminders.digest(ctx.s, now)
    assert Enum.all?(posts(), fn {_, _, r} -> not Map.has_key?(r, "events") end)
  end

  ## v1.32 §29.7: sync off / no access / empty, told apart

  defp phone_check!(ctx, phone_source) do
    change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

    {200, _} =
      RisiMe.GroupHelpers.api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{"changes" => [change]},
        ctx.hd
      )

    check_llm!("You are free then.")
    rid = Ecto.UUID.generate()

    envelope!(
      ctx.hrc,
      ctx.harsha.user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => "Monday 2pm ok for the call?"
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    task = Task.async(fn -> perform_job(Job, job.args) end)
    call = wait_call(ctx.h)

    result = %{
      "blocks" => [],
      "sources" => [phone_source],
      "connected_sources" => ["phone_provider"]
    }

    post = fn body ->
      RisiMe.GroupHelpers.api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        body,
        ctx.hd
      )
    end

    {post, result, task}
  end

  @work_cal %{"name" => "Quokka", "account_type" => "com.google", "events" => 3}

  test "sync_off with Risi Calendar in the check: not checked, the model sees counts only", ctx do
    src = %{
      "source" => "phone_provider",
      "calendars" => [@work_cal],
      "read_ok" => false,
      "reason" => "sync_off"
    }

    {post, result, task} = phone_check!(ctx, src)

    # `sync_off` is only for a phone_provider source that was not read.
    bad = &put_in(result, ["sources", Access.at(0), &1], &2)
    assert {422, _} = post.(%{"status" => "ok", "result" => bad.("read_ok", true)})

    assert {422, _} =
             post.(%{
               "status" => "ok",
               "result" =>
                 bad.("source", "google_api")
                 |> put_in(["sources", Access.at(0), "calendars"], [])
             })

    assert {422, _} = post.(%{"status" => "ok", "result" => Map.put(result, "extra", 1)})
    assert {204, _} = post.(%{"status" => "ok", "result" => result})
    assert :ok = Task.await(task)

    {_, body, a} = answer_of(posts())
    assert body =~ "Checked: Risi Calendar."
    assert body =~ "Not checked: Phone calendar (sync is off)"

    assert %{
             "type" => "calendar_source",
             "source" => "phone_provider",
             "read_ok" => false,
             "reason" => "sync_off",
             "names" => ["Quokka"]
           } in a["sources"]

    # The model saw {source, read_ok, reason, calendars: <count>} and no name.
    req = Jason.encode!(llm_requests())

    assert req =~ ~s(\\"reason\\":\\"sync_off\\")
    refute req =~ "Quokka"
  end

  test "empty real read: 0 events, a free claim stands", ctx do
    src = %{
      "source" => "phone_provider",
      "calendars" => [%{@work_cal | "events" => 0}],
      "read_ok" => true,
      "reason" => nil
    }

    {post, result, task} = phone_check!(ctx, src)
    assert {204, _} = post.(%{"status" => "ok", "result" => result})
    assert :ok = Task.await(task)
    {_, body, _} = answer_of(posts())
    assert body =~ "You are free then."
    assert body =~ "Checked: Risi Calendar · Phone calendar (Quokka)."
  end
end
