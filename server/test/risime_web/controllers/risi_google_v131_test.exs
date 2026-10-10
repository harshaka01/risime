defmodule RisiMeWeb.RisiGoogleV131Test do
  @moduledoc """
  v1.31 §31.3: `/api/v1/risi/calendar/google` (GET/PUT/DELETE), the `risi_gcal_links` storage and
  the stored `google_calendar_link` event.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.CalendarHelpers
  import RisiMe.RisiHelpers, only: [skills_on!: 0]
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.GoogleLink
  alias RisiMe.Repo

  @moduletag capture_log: true

  @put %{
    "connect" => true,
    "state" => "connected",
    "read_calendars" => 2,
    "write_calendar" => true,
    "mirror" => true
  }

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    gcal_on!()
    events_on!()
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    u = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    d1 = gcal_device!(u.user)
    d2 = gcal_device!(u.user)
    plain = calendar_device!(u.user)
    # The app turns the Calendar skill on after connecting (§31.2 step 5).
    ctx0 = %{u: u, d1: d1}
    calendar_skill!(ctx0, "ask")
    %{u: u, id: u.user.id, d1: d1, d2: d2, plain: plain}
  end

  defp call(ctx, dev, method, path \\ "/api/v1/risi/calendar/google", body \\ nil),
    do: api(method, path, ctx.u.token, body, dev)

  defp calendar_skill!(ctx, state) do
    {200, _} =
      api(
        :patch,
        "/api/v1/risi/skills",
        ctx.u.token,
        %{
          "changes" => [%{"id" => "calendar", "state" => state, "client_permission" => "granted"}]
        },
        ctx.d1
      )
  end

  test "no link: not_connected", ctx do
    assert {200, %{"google" => g}} = call(ctx, ctx.d1, :get)

    assert g == %{
             "state" => "not_connected",
             "device_id" => nil,
             "device_name" => nil,
             "read_calendars" => 0,
             "write_calendar" => false,
             "mirror" => false,
             "connected_at" => nil,
             "updated_at" => nil
           }
  end

  test "503 while the switch is off; 403 for a non-google device", ctx do
    assert {403, %{"error" => %{"code" => "invalid_device"}}} = call(ctx, ctx.plain, :get)
    assert {403, _} = call(ctx, nil, :get)
    assert {403, _} = call(ctx, Ecto.UUID.generate(), :get)
    Application.put_env(:risime, :risi_gcal, false)
    assert {503, %{"error" => %{"code" => "agent_unavailable"}}} = call(ctx, ctx.d1, :get)
    assert {503, _} = call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)
  end

  test "RISI_GCAL without RISI_EVENTS is off", ctx do
    Application.put_env(:risime, :risi_events, false)
    assert {503, _} = call(ctx, ctx.d1, :get)
  end

  test "connect, read, update, disconnect", ctx do
    assert {200, %{"google" => g}} = call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)
    assert g["state"] == "connected" and g["device_id"] == ctx.d1
    assert g["read_calendars"] == 2 and g["write_calendar"] and g["mirror"]
    assert is_binary(g["connected_at"]) and is_binary(g["updated_at"])

    assert {200, %{"google" => ^g}} = call(ctx, ctx.d2, :get)

    upd = %{@put | "connect" => false} |> Map.put("read_calendars", 3) |> Map.put("mirror", false)
    assert {200, %{"google" => g2}} = call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", upd)
    assert g2["read_calendars"] == 3 and g2["mirror"] == false
    assert g2["connected_at"] == g["connected_at"]

    assert {204, nil} = call(ctx, ctx.d2, :delete)
    assert {200, %{"google" => %{"state" => "not_connected"}}} = call(ctx, ctx.d1, :get)
    assert Repo.get(GoogleLink, ctx.id) == nil
    # Idempotent.
    assert {204, nil} = call(ctx, ctx.d2, :delete)
  end

  test "stored events: connected, updated, replaced, disconnected (google devices only)", ctx do
    call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)
    call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", %{@put | "connect" => false})
    call(ctx, ctx.d2, :put, "/api/v1/risi/calendar/google", @put)
    call(ctx, ctx.d1, :delete, "/api/v1/risi/calendar/google?remove_copies=true")

    evs = events(ctx.id, "google_calendar_link") |> Enum.map(& &1["data"])
    assert Enum.map(evs, & &1["reason"]) == ~w(connected updated replaced disconnected)
    assert Enum.map(evs, & &1["state"]) == ~w(connected connected connected not_connected)
    # The replaced event names the new holder; disconnected names the one that held it.
    assert Enum.at(evs, 2)["device_id"] == ctx.d2
    assert Enum.at(evs, 3)["device_id"] == ctx.d2
    assert Enum.map(evs, & &1["remove_copies"]) == [false, false, false, true]

    for e <- evs do
      assert Enum.sort(Map.keys(e)) == ~w(device_id reason remove_copies server_ts state)
    end
  end

  test "the event is delivered to google_calendar sockets only", ctx do
    join = fn dev ->
      {:ok, sock} =
        connect(RisiMeWeb.UserSocket, %{"token" => ctx.u.token, "device_id" => dev})

      {:ok, _, _} =
        subscribe_and_join(sock, RisiMeWeb.InboxChannel, "inbox:" <> ctx.id, %{})
    end

    join.(ctx.d2)
    join.(ctx.plain)
    call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)

    assert_receive %Phoenix.Socket.Message{
      event: "event",
      payload: %{kind: "google_calendar_link"}
    }

    # One delivery (the d2 socket); the plain risi_events device gets none.
    refute_receive %Phoenix.Socket.Message{
                     event: "event",
                     payload: %{kind: "google_calendar_link"}
                   },
                   100
  end

  test "connect:false from another device or without a link is 409 not_google_device", ctx do
    upd = %{@put | "connect" => false}

    assert {409, %{"error" => %{"code" => "not_google_device"}}} =
             call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", upd)

    call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)

    assert {409, %{"error" => %{"code" => "not_google_device", "message" => m}}} =
             call(ctx, ctx.d2, :put, "/api/v1/risi/calendar/google", upd)

    assert m == "Google Calendar is connected on another device"
  end

  test "422 for unknown keys, missing keys and out-of-range values", ctx do
    path = "/api/v1/risi/calendar/google"

    for bad <- [
          Map.put(@put, "token", "x"),
          Map.delete(@put, "mirror"),
          %{@put | "read_calendars" => 11},
          %{@put | "read_calendars" => -1},
          %{@put | "read_calendars" => "2"},
          %{@put | "state" => "paused"},
          %{@put | "state" => "not_connected"},
          %{@put | "connect" => "yes"},
          %{@put | "mirror" => 1}
        ] do
      assert {422, %{"error" => %{"code" => "bad_request"}}} = call(ctx, ctx.d1, :put, path, bad)
    end

    assert {422, _} = call(ctx, ctx.d1, :delete, path <> "?remove_copies=maybe")
    assert {200, %{"google" => %{"state" => "not_connected"}}} = call(ctx, ctx.d1, :get)
  end

  test "rate limit: 30 writes per user per minute", ctx do
    path = "/api/v1/risi/calendar/google"
    for _ <- 1..30, do: assert({200, _} = call(ctx, ctx.d1, :put, path, @put))
    assert {429, _} = call(ctx, ctx.d1, :put, path, @put)
    assert {200, _} = call(ctx, ctx.d1, :get)
  end

  test "paused is computed from the Calendar skill", ctx do
    call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)
    assert {200, %{"google" => %{"state" => "connected"}}} = call(ctx, ctx.d1, :get)
    calendar_skill!(ctx, "off")
    assert {200, %{"google" => %{"state" => "paused"}}} = call(ctx, ctx.d1, :get)
    calendar_skill!(ctx, "allowed")
    assert {200, %{"google" => %{"state" => "connected"}}} = call(ctx, ctx.d1, :get)
  end

  test "reauth_needed is reported and survives a reconnect", ctx do
    path = "/api/v1/risi/calendar/google"
    call(ctx, ctx.d1, :put, path, @put)

    assert {200, %{"google" => %{"state" => "reauth_needed"}}} =
             call(ctx, ctx.d1, :put, path, %{
               @put
               | "connect" => false,
                 "state" => "reauth_needed"
             })

    assert {200, %{"google" => %{"state" => "connected"}}} = call(ctx, ctx.d1, :put, path, @put)
  end

  test "removing the device removes the link", ctx do
    call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)
    :ok = RisiMe.Devices.delete(ctx.id, ctx.d1)
    assert Repo.get(GoogleLink, ctx.id) == nil
    assert {200, %{"google" => %{"state" => "not_connected"}}} = call(ctx, ctx.d2, :get)
    evs = events(ctx.id, "google_calendar_link") |> Enum.map(& &1["data"]["reason"])
    assert List.last(evs) == "disconnected"
  end

  test "the table holds ids, states and counts only" do
    {:ok, %{rows: rows}} =
      Ecto.Adapters.SQL.query(
        Repo,
        "SELECT column_name FROM information_schema.columns WHERE table_name = 'risi_gcal_links'",
        []
      )

    assert rows |> List.flatten() |> Enum.sort() ==
             ~w(connected_at device_id mirror read_calendars reconnect_card_at state updated_at user_id write_calendar)
             |> Enum.sort()
  end

  test "CHECK constraints", ctx do
    now = DateTime.utc_now()

    ins = fn state, n ->
      Repo.insert_all("risi_gcal_links", [
        %{
          user_id: Ecto.UUID.bingenerate(),
          device_id: Ecto.UUID.bingenerate(),
          state: state,
          read_calendars: n,
          write_calendar: false,
          mirror: false,
          connected_at: now,
          updated_at: now
        }
      ])
    end

    assert_raise Postgrex.Error, fn -> ins.("paused", 1) end
    assert_raise Postgrex.Error, fn -> ins.("connected", 11) end
    _ = ctx
  end

  test "logs carry ids and state only", ctx do
    Logger.configure(level: :info)
    on_exit(fn -> Logger.configure(level: :warning) end)

    log =
      ExUnit.CaptureLog.capture_log(fn ->
        call(ctx, ctx.d1, :put, "/api/v1/risi/calendar/google", @put)
        call(ctx, ctx.d1, :delete)
      end)

    assert log =~ "gcal link connected user=#{ctx.id} device=#{ctx.d1} state=connected"
    assert log =~ "gcal link disconnected"
  end
end
