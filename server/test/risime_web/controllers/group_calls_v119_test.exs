defmodule RisiMeWeb.GroupCallsV119Test do
  @moduledoc """
  Contract v1.19 §20 (group calls with LiveKit), the server's part (§20.11), against a fake
  LiveKit API (`RisiMe.FakeLiveKit`): `POST /calls/rooms` (`start`/`join`/`status`, the device
  rule, the room-name HMAC, the token claims and TTL, `503` without config or LiveKit, the rate
  limits), `RemoveParticipant` on leave/removal, device removal and reset, group `call:signal`
  (exactly one of `to`/`conversation_id`, the order of checks, the limits, fan-out per member
  user, the `group_calls` delivery filter, the push without fallback) and `group_calls_ready`.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers

  alias RisiMe.{FakeLiveKit, Messaging}
  alias RisiMe.Calls.LiveKit
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @gc ~w(groups images deletes calls video group_calls)
  @no_gc ~w(groups images deletes calls video)

  setup :with_attestation_key
  setup :test_push!
  setup :fake

  defp fake(ctx), do: FakeLiveKit.setup(ctx)

  setup do
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    a_dev = device!(a, @gc)
    b_dev = device!(b, @gc)
    c_dev = device!(c, @no_gc)
    clear_legacy!()
    id = group!(a, a_dev, [b, c])
    %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, id: id}
  end

  ## Helpers

  defp device!(user, caps, token \\ nil) do
    dev = Ecto.UUID.generate()

    params = %{
      "platform" => "android",
      "mls" => %{"signature_key" => b64(), "capabilities" => caps}
    }

    params = if token, do: Map.put(params, "push_token", token), else: params
    {:ok, _} = RisiMe.Devices.register(user.user.id, dev, params)
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

  defp rooms(user, dev, body) do
    api(:post, "/api/v1/calls/rooms", user.token, body, dev)
  end

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

  defp join_dev(user, dev, payload \\ %{}) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, payload)
    {chan, reply}
  end

  defp request(chan, event, payload) do
    ref = push(chan, event, payload)

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply to #{event}")
    end
  end

  defp gsig(chan, id, extra \\ %{}) do
    request(
      chan,
      "call:signal",
      Map.merge(
        %{
          "client_msg_id" => Ecto.UUID.generate(),
          "conversation_id" => id,
          "call_id" => Ecto.UUID.generate(),
          "ring" => true,
          "media" => "audio",
          "ciphertext" => b64(64),
          "generation" => 1,
          "epoch" => 1
        },
        extra
      )
    )
  end

  defp live_events(topic, kind, timeout \\ 300) do
    receive do
      %Phoenix.Socket.Message{topic: ^topic, event: "event", payload: %{kind: ^kind} = e} ->
        [e | live_events(topic, kind, timeout)]
    after
      timeout -> []
    end
  end

  # An independent HKDF / HMAC of §20.2 (not the server's code).
  defp expected_room(secret, conv, call_id, media) do
    prk = :crypto.mac(:hmac, :sha256, <<0::256>>, secret)
    k = :crypto.mac(:hmac, :sha256, prk, "risime-livekit-room-v1" <> <<1>>)

    :crypto.mac(:hmac, :sha256, k, "risime-room-v1" <> conv <> call_id <> media)
    |> Base.url_encode64(padding: false)
    |> String.slice(0, 22)
  end

  describe "POST /calls/rooms start (§20.2)" do
    test "creates the room with the caps, timeouts and metadata, and answers a token", ctx do
      %{a: a, a_dev: a_dev, id: id, livekit: lk} = ctx

      for {media, max, sources} <- [
            {"audio", 32, ["microphone"]},
            {"video", 8, ["microphone", "camera"]}
          ] do
        body = req(id, %{"media" => media})
        before = System.os_time(:second)
        {200, reply} = rooms(a, a_dev, body)

        room = expected_room(lk.api_secret, id, body["call_id"], media)
        assert reply["room"] == room and byte_size(room) == 22
        assert room == LiveKit.room_name(lk.api_secret, id, body["call_id"], media)
        assert reply["url"] == lk.url
        assert reply["identity"] == "#{a.user.id}/#{a_dev}"
        assert reply["max_participants"] == max

        r = FakeLiveKit.room(room)
        assert r.max_participants == max and r.empty_timeout == 60 and r.departure_timeout == 20
        assert Jason.decode!(r.metadata) == %{"c" => id, "m" => media}

        {:ok, claims} = LiveKit.verify(reply["token"], lk.api_secret)
        assert :error == LiveKit.verify(reply["token"], "another secret")

        assert Map.keys(claims) |> Enum.sort() == ~w(exp iss name nbf sub video)
        assert claims["iss"] == lk.api_key and claims["sub"] == reply["identity"]
        assert claims["name"] == ""
        assert claims["nbf"] in before..(before + 2)
        assert claims["exp"] - claims["nbf"] == 600

        assert claims["video"] == %{
                 "room" => room,
                 "roomJoin" => true,
                 "canSubscribe" => true,
                 "canPublish" => true,
                 "canPublishSources" => sources,
                 "canPublishData" => false,
                 "canUpdateOwnMetadata" => false
               }

        {:ok, exp, _} = DateTime.from_iso8601(reply["expires_at"])
        assert DateTime.to_unix(exp) == claims["exp"]

        # The JWT header is HS256.
        [h | _] = String.split(reply["token"], ".")
        assert %{"alg" => "HS256"} = h |> Base.url_decode64!(padding: false) |> Jason.decode!()
      end
    end

    test "start is idempotent (the same room); the name depends on call and media", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, id: id} = ctx
      body = req(id)
      {200, r1} = rooms(a, a_dev, body)
      {200, r2} = rooms(b, b_dev, body)
      assert r1["room"] == r2["room"]
      assert r1["identity"] != r2["identity"]

      {200, r3} = rooms(a, a_dev, %{body | "media" => "video"})
      {200, r4} = rooms(a, a_dev, %{body | "call_id" => Ecto.UUID.generate()})
      assert length(Enum.uniq([r1["room"], r3["room"], r4["room"]])) == 3
    end
  end

  describe "join and status (§20.2)" do
    test "join: 404 call_ended without a room, 409 call_full at the cap, else a token", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, id: id} = ctx
      body = req(id, %{"media" => "video", "action" => "join"})

      assert {404, %{"error" => %{"code" => "call_ended", "message" => "This call has ended"}}} =
               rooms(b, b_dev, body)

      {200, %{"room" => room}} = rooms(a, a_dev, %{body | "action" => "start"})
      FakeLiveKit.join(room, "#{a.user.id}/#{a_dev}")

      {200, reply} = rooms(b, b_dev, body)
      assert reply["room"] == room and reply["identity"] == "#{b.user.id}/#{b_dev}"
      assert reply["max_participants"] == 8

      for n <- 2..8, do: FakeLiveKit.join(room, "u#{n}/d")

      assert {409, %{"error" => %{"code" => "call_full", "message" => "This call is full"}}} =
               rooms(b, b_dev, body)

      # The room closed: call_ended again.
      FakeLiveKit.close(room)
      assert {404, %{"error" => %{"code" => "call_ended"}}} = rooms(b, b_dev, body)
    end

    test "status: active and counts, no token; inactive when there is no room", ctx do
      %{a: a, a_dev: a_dev, id: id} = ctx
      body = req(id, %{"action" => "status"})

      assert {200, %{"active" => false, "participants" => 0, "max_participants" => 32} = r} =
               rooms(a, a_dev, body)

      refute Map.has_key?(r, "token")

      {200, %{"room" => room}} = rooms(a, a_dev, %{body | "action" => "start"})
      FakeLiveKit.join(room, "x/1")
      FakeLiveKit.join(room, "x/2")
      FakeLiveKit.join(room, "x/3")

      assert {200, %{"active" => true, "participants" => 3, "max_participants" => 32} = r} =
               rooms(a, a_dev, body)

      assert Map.keys(r) |> Enum.sort() == ~w(active max_participants participants)
    end
  end

  describe "who may ask (§20.2, server S4)" do
    test "bad_request, not_found, invalid_device", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, c_dev: c_dev, id: id} = ctx

      for bad <- [
            %{"conversation_id" => "dm:x_y"},
            %{"conversation_id" => "grp:nope"},
            %{"call_id" => "not-a-uuid"},
            %{"call_id" => String.upcase(Ecto.UUID.generate())},
            %{"media" => "screen"},
            %{"action" => "end"}
          ] do
        assert {400, %{"error" => %{"code" => "bad_request"}}} = rooms(a, a_dev, req(id, bad))
      end

      assert {400, _} = rooms(a, a_dev, Map.delete(req(id), "action"))

      # Not a member, a group that doesn't exist.
      d = logged_in_user()
      d_dev = device!(d, @gc)
      assert {404, %{"error" => %{"code" => "not_found"}}} = rooms(d, d_dev, req(id))

      assert {404, _} =
               rooms(a, a_dev, req(id, %{"conversation_id" => "grp:" <> Ecto.UUID.generate()}))

      # No X-Device-Id, a device without group_calls, another user's device, a group_calls
      # device that isn't a leaf of the group.
      assert {403, %{"error" => %{"code" => "invalid_device"}}} = rooms(a, nil, req(id))
      assert {403, _} = rooms(c, c_dev, req(id))
      assert {403, _} = rooms(b, a_dev, req(id))
      assert {403, _} = rooms(a, "not-a-uuid", req(id))
      not_leaf = device!(b, @gc)
      assert {403, _} = rooms(b, not_leaf, req(id))

      # A pending_remove (left) member is not_found at once.
      assert {204, nil} = api(:post, "/api/v1/groups/#{id}/leave", b.token, nil, ctx.b_dev)
      assert {404, _} = rooms(b, ctx.b_dev, req(id))
    end

    test "503 calls_unavailable without config or when LiveKit is unreachable", ctx do
      %{a: a, a_dev: a_dev, id: id} = ctx
      cfg = Application.get_env(:risime, :livekit)

      for k <- [:url, :api_url, :api_key, :api_secret] do
        Application.put_env(:risime, :livekit, Keyword.put(cfg, k, nil))

        assert {503, %{"error" => %{"code" => "calls_unavailable"}}} =
                 rooms(a, a_dev, req(id))
      end

      Application.put_env(:risime, :livekit, Keyword.put(cfg, :api_secret, ""))
      assert {503, _} = rooms(a, a_dev, req(id))
      Application.put_env(:risime, :livekit, cfg)

      FakeLiveKit.down(true)

      for action <- ~w(start join status) do
        assert {503, %{"error" => %{"code" => "calls_unavailable"}}} =
                 rooms(a, a_dev, req(id, %{"action" => action}))
      end

      FakeLiveKit.down(false)
      assert {200, _} = rooms(a, a_dev, req(id))
    end

    test "rate limits: start 10 per user per hour, join/status 60 per user per minute", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, id: id} = ctx
      for _ <- 1..10, do: assert({200, _} = rooms(a, a_dev, req(id)))

      conn =
        Phoenix.ConnTest.build_conn()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> a.token)
        |> Plug.Conn.put_req_header("x-device-id", a_dev)
        |> Phoenix.ConnTest.dispatch(@endpoint, :post, "/api/v1/calls/rooms", req(id))

      assert conn.status == 429
      assert [retry] = Plug.Conn.get_resp_header(conn, "retry-after")
      assert String.to_integer(retry) >= 1

      # join and status share one bucket.
      status = req(id, %{"action" => "status"})
      for _ <- 1..30, do: assert({200, _} = rooms(b, b_dev, status))
      for _ <- 1..30, do: assert({404, _} = rooms(b, b_dev, %{status | "action" => "join"}))
      assert {429, %{"error" => %{"code" => "rate_limited"}}} = rooms(b, b_dev, status)
    end
  end

  describe "removal cuts access at once (§20.2, server S3)" do
    setup ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, id: id} = ctx
      {200, %{"room" => room}} = rooms(a, a_dev, req(id))
      a_id = "#{a.user.id}/#{a_dev}"
      b_id = "#{b.user.id}/#{b_dev}"
      FakeLiveKit.join(room, a_id)
      FakeLiveKit.join(room, b_id)

      # Another group's room with b in it, which must not be touched by a removal from `id`.
      e = logged_in_user()
      befriend!(e, b)
      e_dev = device!(e, @gc)
      other = group!(e, e_dev, [b])
      {200, %{"room" => other_room}} = rooms(e, e_dev, req(other))
      FakeLiveKit.join(other_room, b_id)
      FakeLiveKit.clear_calls()
      %{room: room, other_room: other_room, a_id: a_id, b_id: b_id}
    end

    test "a member who leaves is removed from the group's rooms only", ctx do
      %{b: b, b_dev: b_dev, id: id, room: room, other_room: other_room} = ctx
      assert {204, nil} = api(:post, "/api/v1/groups/#{id}/leave", b.token, nil, b_dev)
      assert FakeLiveKit.participants(room) == [ctx.a_id]
      assert FakeLiveKit.participants(other_room) == [ctx.b_id]
      assert {:remove_participant, [room, ctx.b_id]} in FakeLiveKit.calls()
    end

    test "a member removed by an admin is removed", ctx do
      %{a: a, b: b, a_dev: a_dev, id: id, room: room} = ctx

      {status, _} = api(:delete, "/api/v1/groups/#{id}/members/#{b.user.id}", a.token, nil, a_dev)
      assert status in [200, 204]
      assert FakeLiveKit.participants(room) == [ctx.a_id]
    end

    test "a removed device leaves every group call; the user's other devices stay", ctx do
      %{b: b, b_dev: b_dev, room: room, other_room: other_room} = ctx
      b_tab = device!(b, @gc)
      FakeLiveKit.join(room, "#{b.user.id}/#{b_tab}")

      assert {204, _} = api(:delete, "/api/v1/me/devices/#{b_dev}", b.token)
      assert FakeLiveKit.participants(room) == [ctx.a_id, "#{b.user.id}/#{b_tab}"]
      assert FakeLiveKit.participants(other_room) == []
    end

    test "a reset removes every participant of the group's rooms", ctx do
      %{a: a, a_dev: a_dev, id: id, room: room, other_room: other_room} = ctx

      assert {200, %{"generation" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/reset", a.token, %{"generation" => 1}, a_dev)

      assert FakeLiveKit.participants(room) == []
      assert FakeLiveKit.participants(other_room) == [ctx.b_id]
    end

    test "without LiveKit configured nothing is called and nothing fails", ctx do
      %{b: b, b_dev: b_dev, id: id} = ctx
      Application.put_env(:risime, :livekit, [])
      assert {204, nil} = api(:post, "/api/v1/groups/#{id}/leave", b.token, nil, b_dev)
      assert FakeLiveKit.calls() == []
    end
  end

  describe "group call:signal (§20.3)" do
    test "fan-out to every member user, the group_calls delivery filter, live and join/sync",
         ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, id: id} = ctx
      {:ok, before, _} = Messaging.fetch_events(c.user.id, nil, 500, true)
      since = before |> List.last() |> then(&(&1 && &1.event_id))
      {chan_a, _} = join_dev(a, a_dev)
      {_chan_b, _} = join_dev(b, b_dev)
      {_chan_c, _} = join_dev(c, c_dev)

      {:ok, %{message_id: mid}} = gsig(chan_a, id, %{"media" => "video"})

      for u <- [a, b] do
        topic = "inbox:" <> u.user.id
        assert [ev] = live_events(topic, "call_signal")
        assert ev.event_id == mid
        assert ev.data["conversation_id"] == id and ev.data["media"] == "video"
        assert ev.data["from"] == a.user.id and ev.data["from_device"] == a_dev
        refute Map.has_key?(ev.data, "to")
      end

      # c's device has no group_calls: nothing live, nothing on join/sync, but the row exists.
      assert live_events("inbox:" <> c.user.id, "call_signal") == []
      {:ok, stored, _} = Messaging.fetch_events(c.user.id, since, 500, true)
      assert Enum.any?(stored, &(&1.event_id == mid))
      {_, reply} = join_dev(c, c_dev, %{"since" => since})
      refute Enum.any?(reply.events, &(&1.event_id == mid))

      # b's group_calls device gets it on join.
      {_, reply} = join_dev(b, b_dev, %{"since" => since})
      assert Enum.any?(reply.events, &(&1.event_id == mid))
    end

    test "exactly one of to/conversation_id; not_member; e2ee checks; calls_not_ready", ctx do
      %{a: a, b: b, a_dev: a_dev, id: id} = ctx
      {chan_a, _} = join_dev(a, a_dev)

      assert {:error, %{reason: "bad_request"}} = gsig(chan_a, id, %{"to" => b.user.id})
      assert {:error, %{reason: "bad_request"}} = gsig(chan_a, "dm:a_b")
      assert {:error, %{reason: "bad_request"}} = gsig(chan_a, id, %{"media" => "screen"})
      assert {:error, %{reason: "bad_request"}} = gsig(chan_a, id, %{"ring" => "yes"})

      d = logged_in_user()
      d_dev = device!(d, @gc)
      {chan_d, _} = join_dev(d, d_dev)
      assert {:error, %{reason: "not_member"}} = gsig(chan_d, id)

      assert {:error, %{reason: "not_member"}} =
               gsig(chan_d, id, %{"ciphertext" => b64(24 * 1024 + 1)})

      assert {:error, %{reason: "stale_epoch"}} = gsig(chan_a, id, %{"epoch" => 2})
      assert {:error, %{reason: "stale_epoch"}} = gsig(chan_a, id, %{"generation" => 2})

      assert {:error, %{reason: "too_long"}} =
               gsig(chan_a, id, %{"ciphertext" => b64(24 * 1024 + 1)})

      # A group whose other members have no group_calls device: rings are refused, other
      # signals pass.
      {:ok, _} =
        RisiMe.Devices.register(ctx.b.user.id, ctx.b_dev, %{
          "platform" => "android",
          "mls" => %{"signature_key" => b64(), "capabilities" => @no_gc}
        })

      e = logged_in_user()
      befriend!(a, e)
      _ = device!(e, @no_gc)
      g2 = group!(a, a_dev, [e])
      assert {:error, %{reason: "calls_not_ready"}} = gsig(chan_a, g2)
      assert {:ok, _} = gsig(chan_a, g2, %{"ring" => false})
    end

    test "limits: ring 1 per group per 30 s and 10 per hour; ring: false 30 per group per 10 s",
         ctx do
      %{a: a, a_dev: a_dev, id: id} = ctx
      {chan_a, _} = join_dev(a, a_dev)

      assert {:ok, _} = gsig(chan_a, id)
      assert {:error, %{reason: "rate_limited"}} = gsig(chan_a, id)

      # Ten groups' worth of rings in the hour.
      groups =
        for _ <- 1..9 do
          f = logged_in_user()
          befriend!(a, f)
          _ = device!(f, @gc)
          group!(a, a_dev, [f])
        end

      for g <- groups, do: assert({:ok, _} = gsig(chan_a, g))
      f = logged_in_user()
      befriend!(a, f)
      _ = device!(f, @gc)
      g11 = group!(a, a_dev, [f])
      assert {:error, %{reason: "rate_limited"}} = gsig(chan_a, g11)

      # ring: false: 30 per group per 10 s (another group isn't affected).
      for _ <- 1..30, do: assert({:ok, _} = gsig(chan_a, id, %{"ring" => false}))
      assert {:error, %{reason: "rate_limited"}} = gsig(chan_a, id, %{"ring" => false})
    end

    test "the push: at once to offline group_calls devices of the other members, no fallback",
         ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
      b_off = push_token("fcm-b-off")
      b_live_tok = push_token("fcm-b-live")
      c_tok = push_token("fcm-c")
      a_tok = push_token("fcm-a")
      _ = device!(b, @gc, b_off)
      b_live = device!(b, @gc, b_live_tok)
      _ = device!(c, @no_gc, c_tok)
      _ = device!(a, @gc, a_tok)
      {_, _} = join_dev(b, b_live)
      {chan_a, _} = join_dev(a, a_dev)

      {:ok, _} = gsig(chan_a, id)
      assert_receive {:push, ^b_off, %{"type" => "call", "v" => "1"}}, 1000
      refute_receive {:push, _, _}, 800

      # Non-ring signals push nothing.
      {:ok, _} = gsig(chan_a, id, %{"ring" => false})
      refute_receive {:push, _, _}, 500
    end
  end

  describe "group_calls_ready / missing_group_calls (§20.1)" do
    test "the caller and one other member need a group_calls device", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, c_dev: c_dev, id: id} = ctx
      {200, v} = api(:get, "/api/v1/mls/groups/#{id}", a.token)
      assert v["group_calls_ready"] == true
      assert v["missing_group_calls"] == [%{"user_id" => c.user.id, "device_id" => c_dev}]

      # c's own view: c has no group_calls device.
      {200, v} = api(:get, "/api/v1/mls/groups/#{id}", c.token)
      assert v["group_calls_ready"] == false

      # Without b (left), a is the only one with group_calls.
      assert {204, nil} = api(:post, "/api/v1/groups/#{id}/leave", b.token, nil, ctx.b_dev)
      {200, v} = api(:get, "/api/v1/mls/groups/#{id}", a.token)
      assert v["group_calls_ready"] == false
      _ = a_dev

      # DMs don't carry it.
      conv = RisiMe.MLSHelpers.e2ee_group!(a, b)
      {200, v} = api(:get, "/api/v1/mls/groups/#{conv}", a.token)
      refute Map.has_key?(v, "group_calls_ready")
    end
  end
end
