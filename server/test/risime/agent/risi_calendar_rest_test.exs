defmodule RisiMe.Agent.RisiCalendarRestTest do
  @moduledoc """
  v1.29 §29.1–§29.6 Risi Calendar on the server: the switch and capability (old apps see v1.28
  exactly), the migration (no plaintext title/notes column, the CHECKs), sealing, every REST
  endpoint (range list, cursor change feed with 410, idempotent create, versioned PATCH with
  409, delete, respond accept/decline/suggest, resolve, settings, delete-all), validation (the
  Private refusal, invitable), the content-free `risi_calendar_changed`, rate limits.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.{Calendar, Seal}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    calendar_world!()
  end

  defp create!(ctx, who \\ :h, extra \\ %{}) do
    {201, %{"event" => e}} = as(ctx, who, :post, "/api/v1/risi/calendar/events", interview(extra))
    e
  end

  defp event!(ctx, who, id) do
    {200, %{"event" => e}} = as(ctx, who, :get, "/api/v1/risi/calendar/events/#{id}")
    e
  end

  defp status_of(e, user),
    do: Enum.find_value(e["participants"], &(&1["user_id"] == user && &1["status"]))

  ## Migration

  test "migration: no plaintext title/notes column; the CHECK constraints exist and hold" do
    cols =
      Repo.query!(
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'risi_events'"
      ).rows
      |> List.flatten()

    assert "title_sealed" in cols and "notes_sealed" in cols
    for c <- ~w(title notes text body), do: refute(c in cols)

    checks =
      Repo.query!(
        "SELECT conname FROM pg_constraint WHERE contype = 'c' AND conrelid IN " <>
          "('risi_events'::regclass, 'risi_event_participants'::regclass)"
      ).rows
      |> List.flatten()

    for c <- ~w(risi_events_time risi_events_created_by risi_events_state risi_events_title_sealed
                risi_events_notes_sealed risi_event_participants_status
                risi_event_participants_reminder),
        do: assert(c in checks)

    id = Ecto.UUID.generate()
    now = DateTime.utc_now()

    insert = fn title_sealed, start, stop ->
      Repo.query(
        "INSERT INTO risi_events (event_id, owner, title_sealed, start_at, end_at, all_day, tz, " <>
          "created_by, created_at, updated_at) VALUES ($1, $2, $3, $4, $5, false, 'UTC', 'user', $6, $6)",
        [
          Ecto.UUID.dump!(id),
          Ecto.UUID.dump!(Ecto.UUID.generate()),
          title_sealed,
          start,
          stop,
          now
        ]
      )
    end

    # A plaintext title is too short to be sealed; start ≥ end; > 14 days.
    assert {:error, %Postgrex.Error{postgres: %{code: :check_violation}}} =
             insert.("Interview", now, DateTime.add(now, 60))

    sealed = :crypto.strong_rand_bytes(40)

    assert {:error, %Postgrex.Error{postgres: %{code: :check_violation}}} =
             insert.(sealed, now, now)

    assert {:error, %Postgrex.Error{postgres: %{code: :check_violation}}} =
             insert.(sealed, now, DateTime.add(now, 15 * 86_400))
  end

  ## Switch and capability

  test "switch off: /auth/config has no risi_events, every call is 503, v1.28 exactly", ctx do
    {200, cfg} = api(:get, "/api/v1/auth/config", ctx.harsha.token, nil, nil)
    assert cfg["risi_events"] == "on"

    Application.put_env(:risime, :risi_events, false)
    {200, cfg} = api(:get, "/api/v1/auth/config", ctx.harsha.token, nil, nil)
    refute Map.has_key?(cfg, "risi_events")

    assert {503, %{"error" => %{"code" => "agent_unavailable"}}} =
             as(ctx, :h, :get, "/api/v1/risi/calendar/settings")

    refute Calendar.calendar_user?(ctx.h)
  end

  test "capability: risi_events is kept only with risi_tools, risi_skills and risi_ledger; the
        device gate is 403 invalid_device",
       ctx do
    d =
      RisiMe.TabsHelpers.tabs_device!(ctx.harsha.user,
        caps: ~w(groups member_devices tabs risi_tools risi_skills risi_events)
      )

    refute "risi_events" in RisiMe.Devices.get(ctx.h, d).capabilities

    assert {403, %{"error" => %{"code" => "invalid_device"}}} =
             api(:get, "/api/v1/risi/calendar/settings", ctx.harsha.token, nil, d)

    assert {403, _} = api(:get, "/api/v1/risi/calendar/settings", ctx.harsha.token, nil, nil)
    assert {200, _} = as(ctx, :h, :get, "/api/v1/risi/calendar/settings")
  end

  ## Create, get, list, sealing

  test "create: 201, idempotent 200, sealed at rest, invite to the other participant", ctx do
    body =
      interview(%{
        "title" => "CANARY-#{Ecto.UUID.generate()}",
        "with" => [ctx.s],
        "reminder_min" => 15
      })

    {201, %{"event" => e}} = as(ctx, :h, :post, "/api/v1/risi/calendar/events", body)
    {200, %{"event" => again}} = as(ctx, :h, :post, "/api/v1/risi/calendar/events", body)
    assert again["event_id"] == e["event_id"]

    assert Enum.sort(Map.keys(e)) ==
             Enum.sort(
               ~w(event_id version owner title notes start end all_day tz participants
                          my_status my_reminder_min source created_by state created_at updated_at)
             )

    assert e["version"] == 1 and e["owner"] == ctx.h and e["created_by"] == "user"
    assert e["my_status"] == "accepted" and e["my_reminder_min"] == 15
    assert status_of(e, ctx.s) == "proposed"

    assert e["source"] == %{
             "conversation_id" => nil,
             "message_ids" => [],
             "item_id" => nil,
             "note_id" => nil
           }

    # Sealed at rest: no plaintext; the AAD binds the row and column.
    row = Repo.get!(Calendar.Event, e["event_id"])
    refute row.title_sealed =~ body["title"]
    {:ok, key} = Seal.data()

    assert {:ok, body["title"]} ==
             Seal.open(key, "risi_events:#{e["event_id"]}:title", row.title_sealed)

    assert :error == Seal.open(key, "risi_events:#{Ecto.UUID.generate()}:title", row.title_sealed)

    # Shenika's view and her invite card.
    se = event!(ctx, :s, e["event_id"])
    assert se["my_status"] == "proposed" and se["my_reminder_min"] == 30

    ps = posts()
    assert [inv] = of_kind(ps, "calendar_invite")
    assert {src, body_text, ^inv} = Enum.find(ps, fn {_, _, r} -> r == inv end)
    assert src == ctx.src

    assert body_text ==
             "Invitation: #{body["title"]} · Mon 12 Oct, 2–3 PM · with Harsha. Accept, decline " <>
               "or suggest another time in RisiMe."

    assert Enum.sort(Map.keys(inv)) ==
             Enum.sort(~w(v kind event_id version title start end all_day tz owner participants
                          from reason source_conversation_id item_id note_id buttons expires_at
                          made_by notify call_ref))

    assert inv["reason"] == "new" and inv["from"] == ctx.h and inv["notify"] == [ctx.s]
    assert inv["buttons"] == ~w(accept decline suggest) and inv["expires_at"] == e["start"]
    assert inv["made_by"]["model"] == nil

    # Content-free sync events; never a title in an inbox event or a job's args.
    for u <- [ctx.h, ctx.s] do
      assert [ev | _] = events(u, "risi_calendar_changed")
      assert Enum.sort(Map.keys(ev["data"])) == ["cursor", "server_ts"]
      refute Jason.encode!(events(u, nil)) =~ body["title"]
    end

    refute Jason.encode!(Enum.map(all_enqueued(worker: Job), & &1.args)) =~ body["title"]

    # GET /events: the range; a third user can't see it.
    {200, list} =
      as(
        ctx,
        :s,
        :get,
        "/api/v1/risi/calendar/events?from=2026-10-12T00:00:00Z&to=2026-10-13T00:00:00Z"
      )

    assert [%{"event_id" => id}] = list["events"]
    assert id == e["event_id"] and is_binary(list["cursor"])

    {200, empty} =
      as(
        ctx,
        :s,
        :get,
        "/api/v1/risi/calendar/events?from=2026-10-13T00:00:00Z&to=2026-10-14T00:00:00Z"
      )

    assert empty["events"] == []

    assert {422, _} =
             as(
               ctx,
               :s,
               :get,
               "/api/v1/risi/calendar/events?from=2026-10-01T00:00:00Z&to=2027-03-01T00:00:00Z"
             )

    other = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    kd = calendar_device!(other.user)

    assert {404, _} =
             api(:get, "/api/v1/risi/calendar/events/#{e["event_id"]}", other.token, nil, kd)
  end

  test "validation: 422 bad_request / not_invitable; a Private or dm: source is refused", ctx do
    bad = [
      %{"end" => "2026-10-12T08:30:00.000Z"},
      %{"title" => String.duplicate("x", 201)},
      %{"title" => "  "},
      %{"tz" => "Mars/Olympus"},
      %{"all_day" => true},
      %{"end" => "2026-10-27T08:30:00.000Z"},
      %{"with" => for(_ <- 1..20, do: Ecto.UUID.generate())},
      %{"source" => %{"conversation_id" => "dm:#{ctx.h}:#{ctx.s}", "message_ids" => []}},
      %{"reminder_min" => 20_000},
      %{"surprise" => true}
    ]

    for extra <- bad do
      assert {422, %{"error" => %{"code" => "bad_request"}}} =
               as(ctx, :h, :post, "/api/v1/risi/calendar/events", interview(extra)),
             inspect(extra)
    end

    # A Private group the caller is in: refused (Risi never takes anything from Private).
    private = private_group!([ctx.h, ctx.s])

    assert {422, _} =
             as(
               ctx,
               :h,
               :post,
               "/api/v1/risi/calendar/events",
               interview(%{"source" => %{"conversation_id" => private, "message_ids" => []}})
             )

    # The Official chat and the caller's own Risi chat are fine.
    for conv <- [ctx.og, ctx.hrc] do
      assert {201, %{"event" => e}} =
               as(
                 ctx,
                 :h,
                 :post,
                 "/api/v1/risi/calendar/events",
                 interview(%{"source" => %{"conversation_id" => conv, "message_ids" => []}})
               )

      assert e["source"]["conversation_id"] == conv
    end

    # Shenika's Risi chat isn't Harsha's.
    assert {422, _} =
             as(
               ctx,
               :h,
               :post,
               "/api/v1/risi/calendar/events",
               interview(%{"source" => %{"conversation_id" => ctx.src, "message_ids" => []}})
             )

    # Someone who shares no chat with Harsha can't be invited.
    stranger = RisiMe.Fixtures.logged_in_user(display_name: "Stranger")

    assert {422, %{"error" => %{"code" => "not_invitable"}}} =
             as(
               ctx,
               :h,
               :post,
               "/api/v1/risi/calendar/events",
               interview(%{"with" => [stranger.user.id]})
             )

    # An all-day event runs midnight to midnight in its zone.
    assert {201, %{"event" => d}} =
             as(
               ctx,
               :h,
               :post,
               "/api/v1/risi/calendar/events",
               interview(%{
                 "all_day" => true,
                 "start" => "2026-10-11T18:30:00.000Z",
                 "end" => "2026-10-12T18:30:00.000Z"
               })
             )

    assert d["all_day"]
  end

  defp private_group!(users) do
    id = "grp:" <> Ecto.UUID.generate()
    now = DateTime.utc_now()
    [creator | _] = users

    Repo.insert!(%RisiMe.Groups.Group{
      id: id,
      created_by: creator,
      client_group_id: Ecto.UUID.generate(),
      state: "active",
      generation: 1,
      created_at: now,
      chat_id: id,
      tab: "private",
      chat_kind: "group"
    })

    Repo.insert_all(
      RisiMe.Groups.Member,
      for u <- users do
        %{
          group_id: id,
          user_id: u,
          role: "member",
          kind: "user",
          state: "active",
          joined_at: now,
          inserted_at: now
        }
      end
    )

    id
  end

  ## PATCH and respond

  test "PATCH: owner-only fields (403), stale version (409 with the event), time change
        re-invites, title change updates, own reminder for anyone",
       ctx do
    e = create!(ctx, :h, %{"with" => [ctx.s]})
    id = e["event_id"]
    path = "/api/v1/risi/calendar/events/#{id}"
    posts()

    assert {403, %{"error" => %{"code" => "not_owner"}}} =
             as(ctx, :s, :patch, path, %{"version" => 1, "title" => "Mine"})

    assert {409, %{"error" => %{"code" => "version_conflict", "event" => cur}}} =
             as(ctx, :h, :patch, path, %{"version" => 7, "title" => "Late"})

    assert cur["version"] == 1 and cur["title"] == "Interview"

    {200, %{"event" => e2}} =
      as(ctx, :h, :patch, path, %{"version" => 1, "title" => "Job interview"})

    assert e2["version"] == 2 and e2["title"] == "Job interview"
    ups = of_kind(posts(), "event_update")
    assert length(ups) == 2 and Enum.all?(ups, &(&1["change"] == "title" and &1["notify"] == []))

    {200, %{"event" => a}} =
      as(ctx, :s, :post, path <> "/respond", %{"response" => "accept", "version" => 2})

    assert a["my_status"] == "accepted"
    assert [%{"change" => "status", "by" => by} | _] = of_kind(posts(), "event_update")
    assert by == ctx.s

    # Accept is idempotent.
    {200, _} = as(ctx, :s, :post, path <> "/respond", %{"response" => "accept", "version" => 2})
    assert of_kind(posts(), "event_update") == []

    {200, %{"event" => moved}} =
      as(ctx, :h, :patch, path, %{
        "version" => 2,
        "start" => "2026-10-12T09:30:00.000Z",
        "end" => "2026-10-12T10:30:00.000Z"
      })

    assert moved["version"] == 3 and moved["my_status"] == "accepted"
    assert status_of(moved, ctx.s) == "proposed"
    ps = posts()
    assert [inv] = of_kind(ps, "calendar_invite")
    assert inv["reason"] == "time_changed" and inv["notify"] == [ctx.s]
    assert Enum.any?(of_kind(ps, "event_update"), &(&1["change"] == "time"))

    # Anyone sets their own reminder (no new version).
    {200, %{"event" => r}} = as(ctx, :s, :patch, path, %{"version" => 3, "reminder_min" => 10})
    assert r["my_reminder_min"] == 10 and r["version"] == 3

    # Participants: add (invite "added") and remove.
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")

    RisiMe.TabsHelpers.official_group!("grp:" <> Ecto.UUID.generate(), [
      {ctx.h, "admin"},
      {kamal.user.id, "member"}
    ])

    {200, %{"event" => p}} =
      as(ctx, :h, :patch, path, %{"version" => 3, "add" => [kamal.user.id]})

    assert p["version"] == 4 and status_of(p, kamal.user.id) == "proposed"
    # Kamal isn't a calendar user: his invite is held.
    assert [{^id, "added"}] = RisiMe.Agent.CalendarCards.pending(kamal.user.id)

    {200, %{"event" => q}} =
      as(ctx, :h, :patch, path, %{"version" => 4, "remove" => [kamal.user.id]})

    assert q["version"] == 5 and status_of(q, kamal.user.id) == nil
    assert RisiMe.Agent.CalendarCards.pending(kamal.user.id) == []

    assert {422, _} =
             as(ctx, :h, :patch, path, %{"version" => 5, "end" => "2026-10-12T09:00:00.000Z"})

    assert {422, _} = as(ctx, :h, :patch, path, %{"title" => "no version"})

    assert {404, _} =
             as(ctx, :h, :patch, "/api/v1/risi/calendar/events/#{Ecto.UUID.generate()}", %{
               "version" => 1
             })
  end

  test "respond decline / suggest; resolve keep and use; at most 3 open suggestions", ctx do
    e = create!(ctx, :h, %{"with" => [ctx.s]})
    path = "/api/v1/risi/calendar/events/#{e["event_id"]}"
    posts()

    {200, %{"event" => d}} =
      as(ctx, :s, :post, path <> "/respond", %{"response" => "decline", "version" => 1})

    assert d["my_status"] == "declined"

    sug = %{
      "start" => "2026-10-13T09:30:00.000Z",
      "end" => "2026-10-13T10:30:00.000Z",
      "all_day" => false
    }

    {200, %{"event" => s1}} =
      as(ctx, :s, :post, path <> "/respond", %{
        "response" => "suggest",
        "version" => 1,
        "suggest" => sug
      })

    assert s1["my_status"] == "proposed"
    ps = posts()
    assert [card] = of_kind(ps, "calendar_suggestion")
    assert {conv, body, _} = Enum.find(ps, fn {_, _, r} -> r == card end)
    assert conv == ctx.hrc
    assert body == "Shenika suggests Tue 13 Oct, 3–4 PM for 'Interview'."

    assert card["buttons"] == ["use", "keep"] and card["by"] == ctx.s and
             card["notify"] == [ctx.h]

    # The owner can't suggest; Shenika can't resolve.
    assert {422, _} =
             as(ctx, :h, :post, path <> "/respond", %{
               "response" => "suggest",
               "version" => 1,
               "suggest" => sug
             })

    rpath = "/api/v1/risi/calendar/suggestions/#{card["suggestion_id"]}/resolve"

    assert {403, %{"error" => %{"code" => "not_owner"}}} =
             as(ctx, :s, :post, rpath, %{"action" => "use"})

    {200, %{"event" => kept}} = as(ctx, :h, :post, rpath, %{"action" => "keep"})
    assert kept["start"] == e["start"]

    assert [{conv, "Harsha kept the original time for 'Interview'.", _}] =
             Enum.filter(posts(), fn {_, _, r} -> r["kind"] == "event_update" end)

    assert conv == ctx.src

    {200, _} =
      as(ctx, :s, :post, path <> "/respond", %{
        "response" => "suggest",
        "version" => 1,
        "suggest" => sug
      })

    [card2] = of_kind(posts(), "calendar_suggestion")

    {200, %{"event" => used}} =
      as(ctx, :h, :post, "/api/v1/risi/calendar/suggestions/#{card2["suggestion_id"]}/resolve", %{
        "action" => "use"
      })

    assert used["start"] == "2026-10-13T09:30:00.000Z" and used["version"] == 2
    assert status_of(used, ctx.s) == "accepted"

    for _ <- 1..3 do
      assert {200, _} =
               as(ctx, :s, :post, path <> "/respond", %{
                 "response" => "suggest",
                 "version" => 2,
                 "suggest" => sug
               })
    end

    assert {422, _} =
             as(ctx, :s, :post, path <> "/respond", %{
               "response" => "suggest",
               "version" => 2,
               "suggest" => sug
             })
  end

  ## Delete

  test "DELETE: a participant declines and removes it; the owner cancels it for everyone; the
        purge after 7 days",
       ctx do
    e = create!(ctx, :h, %{"with" => [ctx.s]})

    {200, %{"cursor" => cur}} =
      as(ctx, :s, :get, "/api/v1/risi/calendar/changes?since=" <> Calendar.cursor(ctx.s))

    assert {204, nil} = as(ctx, :s, :delete, "/api/v1/risi/calendar/events/#{e["event_id"]}")
    assert status_of(event!(ctx, :h, e["event_id"]), ctx.s) == "declined"
    assert {404, _} = as(ctx, :s, :get, "/api/v1/risi/calendar/events/#{e["event_id"]}")

    {200, ch} =
      as(ctx, :s, :get, "/api/v1/risi/calendar/changes?since=" <> URI.encode_www_form(cur))

    assert [%{"event_id" => id, "removed" => true}] = ch["changes"]
    assert id == e["event_id"]

    f = create!(ctx, :h, %{"with" => [ctx.s]})

    {200, _} =
      as(ctx, :s, :post, "/api/v1/risi/calendar/events/#{f["event_id"]}/respond", %{
        "response" => "accept",
        "version" => 1
      })

    posts()
    assert {204, nil} = as(ctx, :h, :delete, "/api/v1/risi/calendar/events/#{f["event_id"]}")
    cancelled = event!(ctx, :s, f["event_id"])
    assert cancelled["state"] == "cancelled" and cancelled["version"] == 2

    assert Enum.any?(
             of_kind(posts(), "event_update"),
             &(&1["change"] == "cancelled" and &1["state"] == "cancelled")
           )

    # Reminders cancelled with it.
    refute Enum.any?(
             all_enqueued(worker: Job),
             &(&1.args["event_id"] == f["event_id"] and &1.state == "scheduled")
           )

    Calendar.prune(DateTime.add(DateTime.utc_now(), 8 * 86_400, :second))
    assert Repo.get(Calendar.Event, f["event_id"]) == nil
    assert {404, _} = as(ctx, :s, :get, "/api/v1/risi/calendar/events/#{f["event_id"]}")
  end

  ## Changes feed

  test "changes: coalesced per event, paginated, 410 cursor_expired, 422 malformed", ctx do
    c0 = Calendar.cursor(ctx.s)
    a = create!(ctx, :h, %{"with" => [ctx.s]})
    b = create!(ctx, :h, %{"with" => [ctx.s], "title" => "Lunch"})
    path = "/api/v1/risi/calendar/events/#{a["event_id"]}"
    {200, _} = as(ctx, :h, :patch, path, %{"version" => 1, "title" => "Interview 2"})
    {200, _} = as(ctx, :h, :patch, path, %{"version" => 2, "notes" => "Bring CV"})

    q = fn since, limit ->
      as(
        ctx,
        :s,
        :get,
        "/api/v1/risi/calendar/changes?since=#{URI.encode_www_form(since)}&limit=#{limit}"
      )
    end

    {200, p1} = q.(c0, 1)
    assert p1["has_more"]
    assert [%{"event" => %{"event_id" => first}}] = p1["changes"]
    assert first == b["event_id"]
    {200, p2} = q.(p1["cursor"], 1)
    assert [%{"event" => latest}] = p2["changes"]
    # Coalesced: only the latest view of the event.
    assert latest["event_id"] == a["event_id"] and latest["version"] == 3 and
             latest["notes"] == "Bring CV"

    {200, p3} = q.(p2["cursor"], 500)
    assert p3["changes"] == [] and p3["has_more"] == false

    old = System.system_time(:millisecond) - 31 * 86_400_000
    expired = "rc1." <> Base.url_encode64("0.#{old}", padding: false)

    assert {410, %{"error" => %{"code" => "cursor_expired"}}} = q.(expired, 10)
    assert {422, _} = q.("garbage", 10)
    assert {422, _} = q.(c0, 501)
  end

  test "risi_calendar_changed: content-free, at most one per user per 5 s (the rest coalesced),
        only for calendar users",
       ctx do
    e = create!(ctx, :h, %{"with" => [ctx.s]})
    n = length(events(ctx.h, "risi_calendar_changed"))
    assert n >= 1

    {200, _} =
      as(ctx, :h, :patch, "/api/v1/risi/calendar/events/#{e["event_id"]}", %{
        "version" => 1,
        "title" => "T2"
      })

    # Within 5 s: no new stored event, one coalescing job.
    assert length(events(ctx.h, "risi_calendar_changed")) == n

    assert [job] =
             for(
               j <- all_enqueued(worker: Job),
               j.args["kind"] == "calendar_changed",
               j.args["user_id"] == ctx.h,
               do: j
             )

    assert :ok = perform_job(Job, job.args)
    [_ | _] = evs = events(ctx.h, "risi_calendar_changed")
    assert length(evs) == n + 1

    # A user who isn't a calendar user gets none.
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    Calendar.notify_changed([kamal.user.id])
    assert events(kamal.user.id, "risi_calendar_changed") == []
  end

  ## Settings, delete all, limits

  test "settings: defaults, PATCH, 422; DELETE /risi/calendar cancels owned events and resets",
       ctx do
    assert {200, %{"settings" => s}} = as(ctx, :h, :get, "/api/v1/risi/calendar/settings")

    assert s == %{
             "default_reminder_min" => 30,
             "default_duration_min" => 60,
             "digest_events" => true
           }

    {200, %{"settings" => s2}} =
      as(ctx, :h, :patch, "/api/v1/risi/calendar/settings", %{
        "default_reminder_min" => nil,
        "digest_events" => false
      })

    assert s2 == %{
             "default_reminder_min" => nil,
             "default_duration_min" => 60,
             "digest_events" => false
           }

    assert {422, _} =
             as(ctx, :h, :patch, "/api/v1/risi/calendar/settings", %{"default_duration_min" => 2})

    assert {422, _} = as(ctx, :h, :patch, "/api/v1/risi/calendar/settings", %{})

    # The new default reminder applies to new events.
    assert create!(ctx)["my_reminder_min"] == nil

    mine = create!(ctx, :h, %{"with" => [ctx.s]})
    theirs = create!(ctx, :s, %{"with" => [ctx.h]})
    assert {204, nil} = as(ctx, :h, :delete, "/api/v1/risi/calendar")
    assert event!(ctx, :s, mine["event_id"])["state"] == "cancelled"
    assert status_of(event!(ctx, :s, theirs["event_id"]), ctx.h) == "declined"

    assert {200, %{"settings" => %{"default_reminder_min" => 30}}} =
             as(ctx, :h, :get, "/api/v1/risi/calendar/settings")
  end

  test "rate limits: 120 reads and 60 writes per user per minute", ctx do
    for _ <- 1..120, do: assert({200, _} = as(ctx, :s, :get, "/api/v1/risi/calendar/settings"))

    assert {429, %{"error" => %{"code" => "rate_limited"}}} =
             as(ctx, :s, :get, "/api/v1/risi/calendar/settings")

    for _ <- 1..60 do
      assert {200, _} =
               as(ctx, :h, :patch, "/api/v1/risi/calendar/settings", %{"digest_events" => true})
    end

    assert {429, _} =
             as(ctx, :h, :patch, "/api/v1/risi/calendar/settings", %{"digest_events" => true})
  end

  test "without the data key the calendar is 503 for a caller with rows", ctx do
    e = create!(ctx)
    Application.delete_env(:risime, :risi_data_key)

    assert {503, %{"error" => %{"code" => "agent_unavailable"}}} =
             as(ctx, :h, :get, "/api/v1/risi/calendar/events/#{e["event_id"]}")

    assert {503, _} =
             as(
               ctx,
               :h,
               :get,
               "/api/v1/risi/calendar/events?from=2026-10-12T00:00:00Z&to=2026-10-13T00:00:00Z"
             )

    # Shenika has no rows: an empty calendar is fine.
    assert {200, %{"events" => []}} =
             as(
               ctx,
               :s,
               :get,
               "/api/v1/risi/calendar/events?from=2026-10-12T00:00:00Z&to=2026-10-13T00:00:00Z"
             )

    assert {503, _} = as(ctx, :h, :post, "/api/v1/risi/calendar/events", interview())
  end
end
