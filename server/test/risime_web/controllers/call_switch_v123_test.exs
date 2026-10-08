defmodule RisiMeWeb.CallSwitchV123Test do
  @moduledoc """
  Contract v1.23 §23 (mid-call voice↔video switching and screen sharing), the server's part
  (§23.12), against the fake LiveKit API (`RisiMe.FakeLiveKit`): `call_switch`/`screen_share`
  kept in `mls.capabilities`; `POST /calls/rooms` `upgrade` (the order of checks, the cap from
  present identities plus tokens minted in 600 s, `UpdateRoomMetadata`, `UpdateParticipant` per
  identity with screen share only for `screen_share` devices, idempotency, the requester failure,
  the rate limit); `join` after an upgrade (cap 8 from the metadata, video grants, a present
  identity is a refresh); `media` in `start`/`join`/`status`; 1:1 `call:signal` unchanged.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, e2ee_group!: 2]
  import RisiMe.GroupHelpers

  alias RisiMe.{FakeLiveKit, Messaging, Repo}
  alias RisiMe.Calls.{LiveKit, RoomMemory}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @gc ~w(groups images deletes calls video group_calls)
  @switch @gc ++ ~w(call_switch)
  @screen @gc ++ ~w(call_switch screen_share)

  setup :with_attestation_key
  setup :test_push!
  setup :fake

  defp fake(ctx), do: FakeLiveKit.setup(ctx)

  setup do
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    # a: v1.23 with screen sharing; b: v1.23 without; c: a v1.19–v1.22 group_calls device.
    a_dev = device!(a, @screen)
    b_dev = device!(b, @switch)
    c_dev = device!(c, @gc)
    clear_legacy!()
    id = group!(a, a_dev, [b, c])

    %{
      a: a,
      b: b,
      c: c,
      a_dev: a_dev,
      b_dev: b_dev,
      c_dev: c_dev,
      id: id,
      a_id: "#{a.user.id}/#{a_dev}",
      b_id: "#{b.user.id}/#{b_dev}",
      c_id: "#{c.user.id}/#{c_dev}"
    }
  end

  ## Helpers

  defp device!(user, caps) do
    dev = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(user.user.id, dev, %{
        "platform" => "android",
        "mls" => %{"signature_key" => b64(), "capabilities" => caps}
      })

    :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end

  defp group!(a, a_dev, others) do
    body = %{
      "client_group_id" => Ecto.UUID.generate(),
      "member_ids" => Enum.map(others, & &1.user.id)
    }

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    api(
      :post,
      "/api/v1/mls/groups/#{id}/commit",
      a.token,
      create_commit([a.user.id | Enum.map(others, & &1.user.id)], {a.user.id, a_dev}),
      a_dev
    )
    |> assert_status(200)

    id
  end

  defp rooms(user, dev, body), do: api(:post, "/api/v1/calls/rooms", user.token, body, dev)

  defp req(id, extra \\ %{}) do
    Map.merge(
      %{
        "conversation_id" => id,
        "call_id" => Ecto.UUID.generate(),
        "media" => "audio",
        "action" => "start"
      },
      extra
    )
  end

  # A voice room started by a with a, b and c connected.
  defp voice_room(ctx) do
    body = req(ctx.id)
    {200, %{"room" => room, "media" => "audio"}} = rooms(ctx.a, ctx.a_dev, body)
    for i <- [ctx.a_id, ctx.b_id, ctx.c_id], do: FakeLiveKit.join(room, i)
    FakeLiveKit.clear_calls()
    {room, body}
  end

  defp sources(token, secret) do
    {:ok, claims} = LiveKit.verify(token, secret)
    claims["video"]["canPublishSources"]
  end

  defp meta(room), do: Jason.decode!(FakeLiveKit.room(room).metadata)

  defp perm(screen?) do
    sources = if screen?, do: ~w(MICROPHONE CAMERA SCREEN_SHARE), else: ~w(MICROPHONE CAMERA)

    %{
      "can_subscribe" => true,
      "can_publish" => true,
      "can_publish_data" => false,
      "can_publish_sources" => sources,
      "can_update_metadata" => false,
      "hidden" => false
    }
  end

  defp writes,
    do:
      for(
        {f, _} = c <- FakeLiveKit.calls(),
        f in [:update_room_metadata, :update_participant],
        do: c
      )

  describe "capabilities (§23.1)" do
    test "call_switch and screen_share are kept in mls.capabilities", ctx do
      dev = Ecto.UUID.generate()

      body = %{
        "platform" => "android",
        "mls" => %{"signature_key" => b64(), "capabilities" => @screen ++ ~w(nope)}
      }

      {200, _} = api(:put, "/api/v1/me/devices/#{dev}", ctx.a.token, body)

      d = Repo.get_by(RisiMe.Devices.Device, device_id: dev)
      assert d.capabilities == @screen

      assert %{call_switch: true, screen_share: true, group_calls: true} =
               RisiMe.Devices.call_caps(d)

      assert %{call_switch: false, screen_share: false} = RisiMe.Devices.call_caps(@gc)
    end
  end

  describe "upgrade (§23.6, server S4, S5)" do
    test "UpdateRoomMetadata then UpdateParticipant per identity; a video token; status follows",
         ctx do
      %{a: a, a_dev: a_dev, id: id, livekit: lk} = ctx
      {room, body} = voice_room(ctx)

      {200, reply} = rooms(a, a_dev, %{body | "action" => "upgrade"})
      assert reply["media"] == "video" and reply["max_participants"] == 8
      assert reply["room"] == room and reply["identity"] == ctx.a_id
      assert sources(reply["token"], lk.api_secret) == ~w(microphone camera screen_share)

      assert meta(room) == %{"c" => id, "m" => "video"}
      # LiveKit's own max_participants stays as created.
      assert FakeLiveKit.room(room).max_participants == 32

      assert [
               {:update_room_metadata, [^room, _]},
               {:update_participant, [^room, a_id, pa]},
               {:update_participant, [^room, b_id, pb]},
               {:update_participant, [^room, c_id, pc]}
             ] = writes()

      assert {a_id, b_id, c_id} == {ctx.a_id, ctx.b_id, ctx.c_id}
      # Screen share only for the screen_share device; the old device gets the camera grant.
      assert pa == perm(true) and pb == perm(false) and pc == perm(false)

      # The order of reads: ListRooms, ListParticipants, then the writes.
      assert [:list_rooms, :list_participants | _] = Enum.map(FakeLiveKit.calls(), &elem(&1, 0))

      {200, st} = rooms(a, a_dev, %{body | "action" => "status"})

      assert st == %{
               "active" => true,
               "participants" => 3,
               "max_participants" => 8,
               "media" => "video"
             }
    end

    test "idempotent: a room already video answers the same and changes nothing", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, livekit: lk} = ctx
      {room, body} = voice_room(ctx)
      up = %{body | "action" => "upgrade"}
      {200, _} = rooms(a, a_dev, up)
      FakeLiveKit.clear_calls()

      {200, r2} = rooms(b, b_dev, up)
      assert r2["media"] == "video" and r2["max_participants"] == 8
      assert r2["identity"] == ctx.b_id
      assert sources(r2["token"], lk.api_secret) == ~w(microphone camera)
      assert writes() == []
      assert meta(room)["m"] == "video"

      # A room started as video is video already.
      vbody = req(ctx.id, %{"media" => "video"})
      {200, %{"room" => vroom}} = rooms(a, a_dev, vbody)
      FakeLiveKit.join(vroom, ctx.a_id)
      FakeLiveKit.clear_calls()
      assert {200, %{"media" => "video"}} = rooms(a, a_dev, %{vbody | "action" => "upgrade"})
      assert writes() == []
    end

    test "the order: invalid_device, call_ended, not_in_call, too_many_for_video", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, id: id} = ctx

      # No room yet: invalid_device first for a device without call_switch, then call_ended.
      up = req(id, %{"action" => "upgrade"})
      assert {403, %{"error" => %{"code" => "invalid_device"}}} = rooms(c, c_dev, up)

      assert {404, %{"error" => %{"code" => "call_ended", "message" => "This call has ended"}}} =
               rooms(a, a_dev, up)

      # A room b isn't connected to: not_in_call.
      {200, %{"room" => room}} = rooms(a, a_dev, %{up | "action" => "start"})
      FakeLiveKit.join(room, ctx.a_id)

      assert {409,
              %{"error" => %{"code" => "not_in_call", "message" => "You're not in this call"}}} =
               rooms(b, b_dev, up)

      # In the call but no call_switch: still invalid_device.
      FakeLiveKit.join(room, ctx.c_id)
      assert {403, _} = rooms(c, c_dev, up)

      # 6 more present (8) plus one token minted in the last 600 s that isn't present: 9.
      for n <- 1..6, do: FakeLiveKit.join(room, "u#{n}/d")
      RoomMemory.minted(room, "late/joiner")
      FakeLiveKit.clear_calls()

      assert {409,
              %{
                "error" => %{
                  "code" => "too_many_for_video",
                  "message" => "Video is available with 8 people or fewer"
                }
              }} = rooms(a, a_dev, up)

      assert writes() == []
      assert meta(room)["m"] == "audio"

      # One leaves: 7 present + the pending token = 8 (a's own start token counts once).
      :ok = FakeLiveKit.remove_participant(nil, room, "u6/d")
      assert {200, %{"media" => "video"}} = rooms(a, a_dev, up)
    end

    test "the cap counts present identities plus tokens minted by start/join in 600 s", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, id: id} = ctx
      up = req(id, %{"action" => "upgrade"})
      {200, %{"room" => room}} = rooms(a, a_dev, %{up | "action" => "start"})
      # b and c got tokens (join) but haven't connected yet.
      {200, _} = rooms(b, b_dev, %{up | "action" => "join"})
      {200, _} = rooms(c, c_dev, %{up | "action" => "join"})
      FakeLiveKit.join(room, ctx.a_id)
      for n <- 1..5, do: FakeLiveKit.join(room, "u#{n}/d")
      # 6 present + b + c = 8.
      assert Enum.sort(RoomMemory.recent(room)) == Enum.sort([ctx.a_id, ctx.b_id, ctx.c_id])
      assert {200, %{"media" => "video"}} = rooms(a, a_dev, up)

      # Another room: 7 present + b + c = 9.
      up2 = req(id, %{"action" => "upgrade"})
      {200, %{"room" => room2}} = rooms(a, a_dev, %{up2 | "action" => "start"})
      {200, _} = rooms(b, b_dev, %{up2 | "action" => "join"})
      {200, _} = rooms(c, c_dev, %{up2 | "action" => "join"})
      FakeLiveKit.join(room2, ctx.a_id)
      for n <- 1..6, do: FakeLiveKit.join(room2, "u#{n}/d")
      assert {409, %{"error" => %{"code" => "too_many_for_video"}}} = rooms(a, a_dev, up2)
    end

    test "requester UpdateParticipant failure → 503 (after one retry); others retried once and logged",
         ctx do
      %{a: a, a_dev: a_dev} = ctx
      {room, body} = voice_room(ctx)
      up = %{body | "action" => "upgrade"}

      FakeLiveKit.fail(:update_participant, 2, ctx.a_id)
      assert {503, %{"error" => %{"code" => "calls_unavailable"}}} = rooms(a, a_dev, up)
      # The metadata stays video: harmless; a retry is idempotent.
      assert meta(room)["m"] == "video"

      assert length(
               for {:update_participant, [_, i, _]} <- FakeLiveKit.calls(), i == ctx.a_id, do: i
             ) == 2

      assert {200, %{"media" => "video"}} = rooms(a, a_dev, up)

      # A fresh room: b fails once (the retry works), c fails twice (logged, the upgrade stands).
      {room2, body2} = voice_room(ctx)
      FakeLiveKit.fail(:update_participant, 1, ctx.b_id)
      FakeLiveKit.fail(:update_participant, 2, ctx.c_id)
      assert {200, %{"media" => "video"}} = rooms(a, a_dev, %{body2 | "action" => "upgrade"})
      assert FakeLiveKit.permission(room2, ctx.a_id) == perm(true)
      assert FakeLiveKit.permission(room2, ctx.b_id) == perm(false)
      assert FakeLiveKit.permission(room2, ctx.c_id) == nil

      # UpdateRoomMetadata failing (twice) → 503 and no grants.
      {room3, body3} = voice_room(ctx)
      FakeLiveKit.fail(:update_room_metadata, 2)
      assert {503, _} = rooms(a, a_dev, %{body3 | "action" => "upgrade"})
      assert meta(room3)["m"] == "audio"
      assert FakeLiveKit.permission(room3, ctx.a_id) == nil

      FakeLiveKit.down(true)
      assert {503, _} = rooms(a, a_dev, %{body3 | "action" => "upgrade"})
    end

    test "rate limit: upgrade 10 per user per hour (own bucket), 429 with Retry-After", ctx do
      %{a: a, a_dev: a_dev} = ctx
      {_room, body} = voice_room(ctx)
      up = %{body | "action" => "upgrade"}
      for _ <- 1..10, do: assert({200, _} = rooms(a, a_dev, up))

      conn =
        Phoenix.ConnTest.build_conn()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> a.token)
        |> Plug.Conn.put_req_header("x-device-id", a_dev)
        |> Phoenix.ConnTest.dispatch(@endpoint, :post, "/api/v1/calls/rooms", up)

      assert conn.status == 429
      assert [retry] = Plug.Conn.get_resp_header(conn, "retry-after")
      assert String.to_integer(retry) >= 1

      # join/status aren't affected.
      assert {200, _} = rooms(a, a_dev, %{body | "action" => "status"})
    end
  end

  describe "start, join and status after v1.23 (§23.6, server S2, S3)" do
    test "join after an upgrade: cap 8 from the metadata, video grants, a present identity is a refresh",
         ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, livekit: lk} = ctx
      {room, body} = voice_room(ctx)
      {200, _} = rooms(a, a_dev, %{body | "action" => "upgrade"})
      join = %{body | "action" => "join"}

      # A present identity: a fresh video token and UpdateParticipant re-applied.
      FakeLiveKit.clear_calls()
      {200, r} = rooms(a, a_dev, join)
      assert r["media"] == "video" and r["max_participants"] == 8
      assert sources(r["token"], lk.api_secret) == ~w(microphone camera screen_share)
      assert [{:update_participant, [^room, a_id, p]}] = writes()
      assert a_id == ctx.a_id and p == perm(true)

      # The old device's join in a video room: camera, no screen share.
      {200, r} = rooms(c, c_dev, join)
      assert sources(r["token"], lk.api_secret) == ~w(microphone camera)

      # Fill to 8 (LiveKit's own max stays 32): a newcomer is call_full, a present one isn't.
      FakeLiveKit.close(room)
      {200, %{"room" => ^room}} = rooms(a, a_dev, %{body | "action" => "start"})
      for i <- [ctx.a_id, ctx.c_id], do: FakeLiveKit.join(room, i)
      {200, _} = rooms(a, a_dev, %{body | "action" => "upgrade"})
      for n <- 1..6, do: FakeLiveKit.join(room, "u#{n}/d")
      assert FakeLiveKit.room(room).max_participants == 32

      assert {409, %{"error" => %{"code" => "call_full"}}} = rooms(b, b_dev, join)
      FakeLiveKit.clear_calls()
      assert {200, %{"media" => "video"}} = rooms(c, c_dev, join)
      assert [{:update_participant, [^room, c_id, p]}] = writes()
      assert c_id == ctx.c_id and p == perm(false)

      # A refresh whose UpdateParticipant fails still gets its token.
      FakeLiveKit.fail(:update_participant, 2)
      assert {200, _} = rooms(a, a_dev, join)
    end

    test "a voice room: cap 32, a present identity is a refresh (not re-granted)", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, id: id, livekit: lk} = ctx
      body = req(id)
      {200, %{"room" => room}} = rooms(a, a_dev, body)
      FakeLiveKit.join(room, ctx.a_id)
      for n <- 1..31, do: FakeLiveKit.join(room, "u#{n}/d")
      join = %{body | "action" => "join"}

      assert {409, %{"error" => %{"code" => "call_full"}}} = rooms(b, b_dev, join)

      FakeLiveKit.clear_calls()
      {200, r} = rooms(a, a_dev, join)
      assert r["media"] == "audio" and r["max_participants"] == 32
      assert sources(r["token"], lk.api_secret) == ~w(microphone)
      assert writes() == []
    end

    test "start: media in the reply; screen share for screen_share devices in video", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, id: id, livekit: lk} = ctx
      body = req(id, %{"media" => "video"})
      {200, ra} = rooms(a, a_dev, body)
      {200, rb} = rooms(b, b_dev, body)
      assert ra["media"] == "video" and rb["media"] == "video"
      assert sources(ra["token"], lk.api_secret) == ~w(microphone camera screen_share)
      assert sources(rb["token"], lk.api_secret) == ~w(microphone camera)

      # A voice start: microphone only, even for a screen_share device.
      {200, rv} = rooms(a, a_dev, req(id))
      assert rv["media"] == "audio" and sources(rv["token"], lk.api_secret) == ~w(microphone)

      # An idempotent start of an upgraded room answers its current media.
      {_room, vbody} = voice_room(ctx)
      {200, _} = rooms(a, a_dev, %{vbody | "action" => "upgrade"})
      {200, again} = rooms(b, b_dev, vbody)
      assert again["media"] == "video" and again["max_participants"] == 8
      assert sources(again["token"], lk.api_secret) == ~w(microphone camera)
    end

    test "status: media and max_participants follow the mode; inactive reports the start media",
         ctx do
      %{a: a, a_dev: a_dev, id: id} = ctx

      assert {200, %{"active" => false, "media" => "video", "max_participants" => 8}} =
               rooms(a, a_dev, req(id, %{"media" => "video", "action" => "status"}))

      {_room, body} = voice_room(ctx)
      st = %{body | "action" => "status"}

      assert {200, %{"active" => true, "media" => "audio", "max_participants" => 32}} =
               rooms(a, a_dev, st)

      {200, _} = rooms(a, a_dev, %{body | "action" => "upgrade"})

      assert {200, %{"active" => true, "media" => "video", "max_participants" => 8}} =
               rooms(a, a_dev, st)
    end
  end

  describe "1:1 needs no server change (§23.0, server S1)" do
    test "ring: false switch/re-offer signals with the start media pass the v1.18 filter", ctx do
      %{a: a, b: b} = ctx
      a1 = device!(a, ~w(groups images deletes calls video call_switch screen_share))
      _b_new = device!(b, ~w(groups images deletes calls video call_switch))
      _b_old = device!(b, ~w(groups images deletes calls))
      clear_legacy!()
      e2ee_group!(a, b)
      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a1})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
      call_id = Ecto.UUID.generate()

      ids =
        for media <- ["audio", "audio", "video"] do
          ref =
            push(chan, "call:signal", %{
              "client_msg_id" => Ecto.UUID.generate(),
              "to" => b.user.id,
              "call_id" => call_id,
              "ring" => false,
              "media" => media,
              # A ~7 KiB re-offer inside MLS.
              "ciphertext" => b64(8 * 1024),
              "generation" => 1,
              "epoch" => 1
            })

          assert_reply ref, :ok, %{message_id: id}
          id
        end

      {:ok, events, _} = Messaging.fetch_events(b.user.id, nil, 500, true)

      for {id, media} <- Enum.zip(ids, ["audio", "audio", "video"]) do
        ev = Enum.find(events, &(&1.event_id == id))
        assert ev.data["media"] == media and ev.data["ring"] == false
      end
    end
  end
end
