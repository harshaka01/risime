defmodule RisiMeWeb.CallsV113Test do
  @moduledoc """
  Contract v1.13 §16 (1:1 voice calls), the server's part (§16.16): `call:signal` and its order
  of checks, the short-lived `call_signals` store and the join/sync merge, the `calls` delivery
  filter, the rate limits, the device-level call push with its 3-s fallback, `calls_ready`, and
  `GET /calls/turn`.
  """
  use RisiMeWeb.ChannelCase, async: false

  import ExUnit.CaptureLog
  require Logger
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers

  alias RisiMe.{Messaging, Push, RateLimiter}
  alias RisiMe.MLS.Wire
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @cluster RisiMe.Messaging.Store.Cassandra.Cluster
  @caps ~w(groups images deletes calls)
  @old_caps ~w(groups images deletes)

  setup :with_attestation_key
  setup :test_push!

  setup do
    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    befriend!(a, b)
    a_dev = device!(a, @caps)
    b_dev = device!(b, @caps)
    RisiMe.GroupHelpers.clear_legacy!()
    conv = e2ee_group!(a, b)
    chan_a = join_dev(a, a_dev)
    chan_b = join_dev(b, b_dev)
    %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, conv: conv, chan_a: chan_a, chan_b: chan_b}
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

  defp join_dev(user, dev, payload \\ %{}) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, payload)
    chan
  end

  defp join_reply(user, dev, payload) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => dev})
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, payload)
    {reply, chan}
  end

  defp request(chan, event, payload) do
    ref = push(chan, event, payload)

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply to #{event}")
    end
  end

  defp payload(to, extra \\ %{}) do
    Map.merge(
      %{
        "client_msg_id" => Ecto.UUID.generate(),
        "to" => to,
        "call_id" => Ecto.UUID.generate(),
        "ring" => true,
        "ciphertext" => b64(64),
        "generation" => 1,
        "epoch" => 1,
        "client_ts" => "2026-10-06T08:15:30.140Z"
      },
      extra
    )
  end

  defp sig(chan, to, extra \\ %{}), do: request(chan, "call:signal", payload(to, extra))

  defp cql(statement, params) do
    case Xandra.Cluster.execute(@cluster, statement, params) do
      {:ok, %Xandra.Page{} = page} -> Enum.to_list(page)
      {:ok, _void} -> []
    end
  end

  defp signal_ttl(user_id, event_id) do
    [%{"ttl" => ttl}] =
      cql(
        "SELECT TTL(payload) AS ttl FROM call_signals WHERE user_id = ? AND event_id = ?",
        [{"uuid", user_id}, {"timeuuid", event_id}]
      )

    ttl
  end

  defp live_events(topic, kind, timeout \\ 300) do
    receive do
      %Phoenix.Socket.Message{topic: ^topic, event: "event", payload: %{kind: ^kind} = e} ->
        [e | live_events(topic, kind, timeout)]
    after
      timeout -> []
    end
  end

  defp e2ee_friend!(a, caps \\ @caps, token \\ nil) do
    c = logged_in_user()
    befriend!(a, c)
    dev = device!(c, caps, token)
    e2ee_group!(a, c)
    {c, dev}
  end

  describe "call:signal (§16.3)" do
    test "happy path: one event_id for both users, live, only in call_signals (TTL 60/120 s)",
         ctx do
      %{a: a, b: b, a_dev: a_dev, chan_a: chan_a, conv: conv} = ctx
      p = payload(b.user.id)
      {:ok, reply} = request(chan_a, "call:signal", p)
      assert %{message_id: id, server_ts: ts} = reply
      assert ts =~ ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/

      for u <- [a, b] do
        topic = "inbox:" <> u.user.id
        assert_receive %Phoenix.Socket.Message{topic: ^topic, event: "event", payload: e}
        assert %{event_id: ^id, kind: "call_signal"} = e

        assert e.data == %{
                 "message_id" => id,
                 "conversation_id" => conv,
                 "from" => a.user.id,
                 "to" => b.user.id,
                 "from_device" => a_dev,
                 "call_id" => p["call_id"],
                 "ring" => true,
                 "media" => "audio",
                 "ciphertext" => p["ciphertext"],
                 "generation" => 1,
                 "epoch" => 1,
                 "server_ts" => ts
               }

        assert signal_ttl(u.user.id, id) in 55..60
      end

      {:ok, %{message_id: id2}} = sig(chan_a, b.user.id, %{"ring" => false})
      for u <- [a, b], do: assert(signal_ttl(u.user.id, id2) in 115..120)

      # Never in inbox_events, message_index or sent_dedupe (server R2, R4).
      for u <- [a, b], i <- [id, id2] do
        assert cql("SELECT event_id FROM inbox_events WHERE user_id = ? AND event_id = ?", [
                 {"uuid", u.user.id},
                 {"timeuuid", i}
               ]) == []
      end

      for i <- [id, id2],
          do:
            assert(
              cql("SELECT message_id FROM message_index WHERE message_id = ?", [{"timeuuid", i}]) ==
                []
            )

      assert cql("SELECT message_id FROM sent_dedupe WHERE sender_id = ? AND client_msg_id = ?", [
               {"uuid", a.user.id},
               {"uuid", p["client_msg_id"]}
             ]) == []
    end

    test "idempotent resend (best effort, in memory) returns the original reply", ctx do
      %{b: b, chan_a: chan_a} = ctx
      topic_b = "inbox:" <> b.user.id
      p = payload(b.user.id, %{"ring" => false})
      {:ok, reply} = request(chan_a, "call:signal", p)
      assert [_] = live_events(topic_b, "call_signal")
      assert {:ok, ^reply} = request(chan_a, "call:signal", %{p | "ciphertext" => b64(64)})
      assert live_events(topic_b, "call_signal") == []
    end

    test "malformed pushes are bad_request; too_long over 24 KiB", %{b: b, chan_a: chan_a} do
      to = b.user.id

      for extra <- [
            %{"to" => "grp:" <> Ecto.UUID.generate()},
            %{"conversation_id" => "grp:" <> Ecto.UUID.generate()},
            %{"call_id" => "not-a-uuid"},
            %{"call_id" => String.upcase(Ecto.UUID.generate())},
            %{"ring" => "true"},
            %{"ring" => nil},
            %{"generation" => "1"},
            %{"ciphertext" => "%%%"},
            %{"client_msg_id" => "x"}
          ] do
        assert {:error, %{reason: "bad_request"}} = sig(chan_a, to, extra), inspect(extra)
      end

      assert {:error, %{reason: "bad_request"}} =
               request(chan_a, "call:signal", Map.delete(payload(to), "ciphertext"))

      assert {:error, %{reason: "too_long"}} =
               sig(chan_a, to, %{"ring" => false, "ciphertext" => b64(24 * 1024 + 1)})

      assert {:ok, _} = sig(chan_a, to, %{"ring" => false, "ciphertext" => b64(24 * 1024)})
    end

    test "errors in the order of checks", %{a: a, b: b, chan_a: chan_a} do
      # unknown_recipient (yourself), not_friends before e2ee.
      assert {:error, %{reason: "unknown_recipient"}} = sig(chan_a, a.user.id)
      stranger = logged_in_user()
      assert {:error, %{reason: "not_friends"}} = sig(chan_a, stranger.user.id)

      # not_e2ee (a plaintext DM) comes before calls_not_ready.
      plain = logged_in_user()
      befriend!(a, plain)
      assert {:error, %{reason: "not_e2ee"}} = sig(chan_a, plain.user.id)
      assert {:error, %{reason: "not_e2ee"}} = sig(chan_a, plain.user.id, %{"ring" => false})

      # stale_epoch.
      assert {:error, %{reason: "stale_epoch"}} = sig(chan_a, b.user.id, %{"epoch" => 2})
      assert {:error, %{reason: "stale_epoch"}} = sig(chan_a, b.user.id, %{"generation" => 0})

      # calls_not_ready only for ring: true, and before the ring limits.
      {old, _} = e2ee_friend!(a, @old_caps)
      assert {:error, %{reason: "calls_not_ready"}} = sig(chan_a, old.user.id)
      assert {:ok, _} = sig(chan_a, old.user.id, %{"ring" => false})
      for _ <- 1..6, do: RateLimiter.hit(:call_ring, a.user.id, 6, 60_000)
      assert {:error, %{reason: "calls_not_ready"}} = sig(chan_a, old.user.id)

      capture_log(fn ->
        assert {:error, %{reason: "rate_limited"}} = sig(chan_a, b.user.id)
      end)

      # The total limit comes first, even before parsing.
      for _ <- 1..60, do: RateLimiter.hit(:call_signal, a.user.id, 60, 10_000)
      assert {:error, %{reason: "rate_limited"}} = sig(chan_a, b.user.id, %{"call_id" => "x"})
    end

    test "a socket without a device id can't signal", %{a: a, b: b} do
      {:ok, sock} = connect(UserSocket, %{"token" => a.token})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
      assert {:error, %{reason: "bad_request"}} = sig(chan, b.user.id)
    end
  end

  describe "rate limits (§16.3)" do
    test "1 ring per callee per 5 s; restarts (ring: false) don't count", ctx do
      %{a: a, b: b, chan_a: chan_a} = ctx
      {c, _} = e2ee_friend!(a)
      assert {:ok, _} = sig(chan_a, b.user.id)

      log =
        capture_log(fn ->
          assert {:error, %{reason: "rate_limited"}} = sig(chan_a, b.user.id)
        end)

      # Every refused ring is logged with the pair (ids only).
      assert log =~ "call ring refused"
      assert log =~ a.user.id and log =~ b.user.id

      assert {:ok, _} = sig(chan_a, b.user.id, %{"ring" => false})
      # Another callee is fine.
      assert {:ok, _} = sig(chan_a, c.user.id)
    end

    test "6 rings per minute per caller", %{a: a, chan_a: chan_a} do
      callees = for _ <- 1..7, do: elem(e2ee_friend!(a), 0)
      for c <- Enum.take(callees, 6), do: assert({:ok, _} = sig(chan_a, c.user.id))

      capture_log(fn ->
        assert {:error, %{reason: "rate_limited"}} = sig(chan_a, List.last(callees).user.id)
      end)
    end

    test "20 rings per (caller, callee) per hour", %{a: a, b: b, chan_a: chan_a} do
      for _ <- 1..20,
          do: :ok = RateLimiter.hit(:call_ring_pair, {a.user.id, b.user.id}, 20, 3_600_000)

      capture_log(fn ->
        assert {:error, %{reason: "rate_limited"}} = sig(chan_a, b.user.id)
      end)

      {c, _} = e2ee_friend!(a)
      assert {:ok, _} = sig(chan_a, c.user.id)
    end

    test "ring: false at most 30 per pair per 10 s", %{a: a, b: b, chan_a: chan_a} do
      for _ <- 1..30, do: assert({:ok, _} = sig(chan_a, b.user.id, %{"ring" => false}))

      assert {:error, %{reason: "rate_limited"}} =
               sig(chan_a, b.user.id, %{"ring" => false})

      {c, _} = e2ee_friend!(a)
      assert {:ok, _} = sig(chan_a, c.user.id, %{"ring" => false})
    end

    test "the total: 60 call:signal per user per 10 s", %{b: b, chan_a: chan_a} do
      for _ <- 1..60,
          do: assert({:error, %{reason: "bad_request"}} = sig(chan_a, b.user.id, %{"ring" => 1}))

      assert {:error, %{reason: "rate_limited"}} = sig(chan_a, b.user.id)
    end
  end

  describe "join/sync and the delivery filter (§16.1, §16.3)" do
    defp cipher_msg(chan, to) do
      {:ok, %{message_id: id}} =
        request(chan, "msg:send", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => to,
          "ciphertext" => b64(64),
          "generation" => 1,
          "epoch" => 1
        })

      id
    end

    test "a calls socket merges both stores in event_id order with a correct has_more", ctx do
      %{a: a, b: b, b_dev: b_dev, chan_a: chan_a} = ctx
      {:ok, before, false} = Messaging.fetch_events(b.user.id, nil, 500, true)
      since = before |> List.last() |> then(&(&1 && &1.event_id))

      m1 = cipher_msg(chan_a, b.user.id)
      {:ok, %{message_id: s1}} = sig(chan_a, b.user.id)
      m2 = cipher_msg(chan_a, b.user.id)
      {:ok, %{message_id: s2}} = sig(chan_a, b.user.id, %{"ring" => false})

      {reply, _} = join_reply(b, b_dev, %{"since" => since, "limit" => 3})
      assert Enum.map(reply.events, & &1.event_id) == [m1, s1, m2]
      assert reply.has_more == true

      {reply, _} = join_reply(b, b_dev, %{"since" => m2, "limit" => 3})
      assert Enum.map(reply.events, & &1.event_id) == [s2]
      assert reply.has_more == false

      # Exactly n merged: has_more false.
      {reply, chan} = join_reply(b, b_dev, %{"since" => since, "limit" => 4})
      assert Enum.map(reply.events, & &1.event_id) == [m1, s1, m2, s2]
      assert reply.has_more == false

      {:ok, sync} = request(chan, "sync", %{"since" => s1, "limit" => 1})
      assert Enum.map(sync.events, & &1.event_id) == [m2]
      assert sync.has_more == true

      # The sender's own copies too (one event_id for both users).
      {:ok, a_events, _} = Messaging.fetch_events(a.user.id, m1, 10, true)
      assert Enum.map(a_events, & &1.event_id) == [s1, m2, s2]
    end

    test "a non-calls socket never sees call signals, live or on join/sync", ctx do
      %{a: a, chan_a: chan_a} = ctx
      c = logged_in_user()
      befriend!(a, c)
      c_old = device!(c, @old_caps)
      # A calls device elsewhere, so a ring is allowed.
      _c_new = device!(c, @caps)
      e2ee_group!(a, c)
      {:ok, before, _} = Messaging.fetch_events(c.user.id, nil, 500)
      since = before |> List.last() |> then(&(&1 && &1.event_id))
      chan_old = join_dev(c, c_old)
      topic = "inbox:" <> c.user.id

      {:ok, %{message_id: s1}} = sig(chan_a, c.user.id)
      m1 = cipher_msg(chan_a, c.user.id)

      assert [%{event_id: ^m1}] = live_events(topic, "message")
      refute_received %Phoenix.Socket.Message{topic: ^topic, payload: %{kind: "call_signal"}}

      {reply, _} = join_reply(c, c_old, %{"since" => since})
      assert Enum.map(reply.events, & &1.event_id) == [m1]
      {:ok, sync} = request(chan_old, "sync", %{"since" => since})
      assert Enum.map(sync.events, & &1.event_id) == [m1]

      # The same device gains `calls` while connected: it gets signals from then on.
      key = RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: c_old).mls_signature_key

      {:ok, _} =
        RisiMe.Devices.register(c.user.id, c_old, %{
          "platform" => "android",
          "mls" => %{"signature_key" => Base.encode64(key), "capabilities" => @caps}
        })

      {:ok, %{message_id: s2}} = sig(chan_a, c.user.id, %{"ring" => false})
      # (Every socket of that device here, the join_reply ones included.)
      assert topic |> live_events("call_signal") |> Enum.map(& &1.event_id) |> Enum.uniq() == [s2]
      {:ok, sync} = request(chan_old, "sync", %{"since" => since})
      assert Enum.map(sync.events, & &1.event_id) == [s1, m1, s2]
    end
  end

  describe "not a message (§16.3, server R4)" do
    test "ack ignored, msg:delete reports gone",
         %{a: a, b: b, a_dev: a_dev, chan_a: chan_a} = ctx do
      {:ok, %{message_id: s}} = sig(chan_a, b.user.id)
      topic_a = "inbox:" <> a.user.id
      _ = live_events(topic_a, "call_signal")

      assert {:ok, %{}} =
               request(ctx.chan_b, "msg:ack", %{"message_ids" => [s], "status" => "read"})

      assert live_events(topic_a, "status") == []

      payload = %{
        "client_msg_id" => Ecto.UUID.generate(),
        "conversation_id" => ctx.conv,
        "scope" => "everyone",
        "targets" => [s],
        "client_ts" => "2026-10-06T09:00:00.000Z",
        "ciphertext" =>
          Wire.encode_private_message(
            "gid",
            1,
            Wire.delete_aad([s]),
            :crypto.strong_rand_bytes(20),
            :crypto.strong_rand_bytes(48)
          )
          |> Base.encode64(),
        "generation" => 1,
        "epoch" => 1
      }

      assert {:ok, %{deleted: [], gone: [^s]}} =
               Messaging.Deletes.delete(a.user.id, payload, device_id: a_dev)
    end
  end

  describe "diagnostics (decision 054)" do
    setup do
      level = Logger.level()
      Logger.configure(level: :info)
      on_exit(fn -> Logger.configure(level: level) end)
    end

    test "every call:signal is logged with ids, the ring flag and its outcome; never the ciphertext",
         ctx do
      %{a: a, b: b, chan_a: chan_a} = ctx
      call_id = Ecto.UUID.generate()
      ct = b64(64)

      log =
        capture_log([level: :info], fn ->
          {:ok, _} = sig(chan_a, b.user.id, %{"call_id" => call_id, "ciphertext" => ct})

          {:error, %{reason: "not_friends"}} =
            sig(chan_a, Ecto.UUID.generate(), %{"ring" => false})

          Process.sleep(50)
        end)

      assert log =~ "call:signal call=#{call_id} from=#{a.user.id}"
      assert log =~ "to=#{b.user.id} ring=true ok"
      assert log =~ "ring=false error=not_friends"
      assert log =~ "call ring: call=#{call_id} to=#{b.user.id}"
      refute log =~ ct
    end

    test "the call push result and the fallback push are logged", ctx do
      %{a: a, chan_a: chan_a} = ctx
      c = logged_in_user()
      befriend!(a, c)
      live = push_token("fcm-live-log")
      c_live = device!(c, @caps, live)
      e2ee_group!(a, c)

      log =
        capture_log([level: :info], fn ->
          _chan_c = join_dev(c, c_live)
          {:ok, _} = sig(chan_a, c.user.id)
          assert_receive {:push, ^live, %{"type" => "call"}}, 1000
          Process.sleep(100)
        end)

      assert log =~ "call ring fallback push:"
      assert log =~ "tokens=1"
      assert log =~ "call push: result="
      refute log =~ live
    end
  end

  describe "the call push (§16.8)" do
    test "ring: at once to calls devices without a live channel, never the inbox push", ctx do
      %{a: a, chan_a: chan_a} = ctx
      c = logged_in_user()
      befriend!(a, c)
      off = push_token("fcm-off")
      old = push_token("fcm-old")
      _ = device!(c, @caps, off)
      _ = device!(c, @old_caps, old)
      e2ee_group!(a, c)

      {:ok, _} = sig(chan_a, c.user.id)
      assert_receive {:push, ^off, %{"type" => "call", "v" => "1"} = p}, 1000
      assert p == Push.call_payload()
      assert Push.call_payload() == example("push_call.json")

      # No inbox push (the coalescer would fire within 300 ms in tests), nothing for old apps,
      # and nothing for ring: false signals.
      {:ok, _} = sig(chan_a, c.user.id, %{"ring" => false})
      refute_receive {:push, _, _}, 600
    end

    test "the 3-s fallback reaches a live-looking device without a returning signal", ctx do
      %{a: a, chan_a: chan_a} = ctx
      c = logged_in_user()
      befriend!(a, c)
      live = push_token("fcm-live")
      c_live = device!(c, @caps, live)
      e2ee_group!(a, c)
      _chan_c = join_dev(c, c_live)

      # Live channel: no push at once; the fallback fires (200 ms in tests).
      {:ok, _} = sig(chan_a, c.user.id)
      refute_receive {:push, ^live, _}, 100
      assert_receive {:push, ^live, %{"type" => "call"}}, 1000
    end

    test "no fallback once the callee's user sends a signal of that call", ctx do
      %{a: a, chan_a: chan_a} = ctx
      c = logged_in_user()
      befriend!(a, c)
      live = push_token("fcm-live")
      c_live = device!(c, @caps, live)
      e2ee_group!(a, c)
      chan_c = join_dev(c, c_live)
      call_id = Ecto.UUID.generate()
      {:ok, _} = sig(chan_a, c.user.id, %{"call_id" => call_id})
      {:ok, _} = sig(chan_c, a.user.id, %{"call_id" => call_id, "ring" => false})
      refute RisiMe.Calls.State.pending?(c.user.id, call_id)
      refute_receive {:push, ^live, _}, 600
    end

    test "signals of the caller's own user don't disarm the fallback", ctx do
      %{a: a, chan_a: chan_a} = ctx
      c = logged_in_user()
      befriend!(a, c)
      live = push_token("fcm-live")
      c_live = device!(c, @caps, live)
      e2ee_group!(a, c)
      _chan_c = join_dev(c, c_live)
      call_id = Ecto.UUID.generate()

      {:ok, _} = sig(chan_a, c.user.id, %{"call_id" => call_id})
      {:ok, _} = sig(chan_a, c.user.id, %{"call_id" => call_id, "ring" => false})
      assert_receive {:push, ^live, %{"type" => "call"}}, 1000
    end

    test "call_end is a normal e2ee message: stored, with the inbox push", ctx do
      %{a: a, chan_a: chan_a} = ctx
      token = push_token("fcm-end")
      {c, _} = e2ee_friend!(a, @caps, token)

      {:ok, %{message_id: id}} =
        request(chan_a, "msg:send", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => c.user.id,
          "ciphertext" => b64(80),
          "generation" => 1,
          "epoch" => 1
        })

      assert_receive {:push, ^token, %{"type" => "inbox", "v" => "1"}}, 1000

      for u <- [a, c],
          do: assert(Enum.any?(RisiMe.GroupHelpers.events(u.user.id), &(&1["event_id"] == id)))
    end

    test "FCM options: collapse_key call, ttl 45s, high priority" do
      call = RisiMe.Push.FCM.message("tok", Push.call_payload())
      assert call.message.android == %{priority: "high", collapse_key: "call", ttl: "45s"}
      assert call.message.data == %{"type" => "call", "v" => "1"}

      inbox = RisiMe.Push.FCM.message("tok", Push.payload())
      assert inbox.message.android == %{priority: "high", collapse_key: "inbox", ttl: "3600s"}
    end
  end

  describe "calls_ready / missing_calls (§16.1)" do
    defp group_view(user, conv) do
      {200, view} = RisiMe.GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", user.token)
      view
    end

    test "one calls device per user is enough; the others are listed", ctx do
      %{a: a, b: b, conv: conv} = ctx
      assert %{"calls_ready" => true, "missing_calls" => []} = group_view(a, conv)

      tablet = device!(b, @old_caps)
      view = group_view(a, conv)
      assert view["calls_ready"] == true
      assert view["missing_calls"] == [%{"user_id" => b.user.id, "device_id" => tablet}]

      # A user without any calls device: not ready.
      {c, c_dev} = e2ee_friend!(a, @old_caps)
      conv_c = Messaging.conversation_id(a.user.id, c.user.id)
      view = group_view(a, conv_c)
      assert view["calls_ready"] == false
      assert view["missing_calls"] == [%{"user_id" => c.user.id, "device_id" => c_dev}]

      # A plaintext DM is never ready.
      d = logged_in_user()
      befriend!(a, d)
      _ = device!(d, @caps)
      RisiMe.GroupHelpers.clear_legacy!()
      conv_d = Messaging.conversation_id(a.user.id, d.user.id)
      assert %{"e2ee" => false, "calls_ready" => false} = group_view(a, conv_d)
    end

    test "the capability is kept on PUT /me/devices", %{a: a} do
      dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        RisiMe.GroupHelpers.api(
          :put,
          "/api/v1/me/devices/#{dev}",
          a.token,
          example("device_put_calls.json")
        )

      assert %{capabilities: ["groups", "images", "deletes", "calls"]} =
               RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: dev)
    end
  end

  describe "GET /api/v1/calls/turn (§16.7)" do
    @secret "test-turn-secret-0123456789abcdef-xyz"
    @urls [
      "stun:turn.example.test:3478",
      "turn:turn.example.test:3478?transport=udp",
      "turn:turn.example.test:3478?transport=tcp"
    ]

    setup do
      on_exit(fn -> Application.delete_env(:risime, :turn) end)
      :ok
    end

    defp turn(user) do
      conn =
        Phoenix.ConnTest.build_conn()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> user.token)
        |> Phoenix.ConnTest.dispatch(@endpoint, :get, "/api/v1/calls/turn")

      {conn.status, Jason.decode!(conn.resp_body), conn}
    end

    test "503 calls_unavailable without a secret, with a short one, or without URLs", %{a: a} do
      for cfg <- [
            [],
            [secret: nil, urls: @urls],
            [secret: String.duplicate("x", 31), urls: @urls],
            [secret: @secret, urls: []]
          ] do
        Application.put_env(:risime, :turn, cfg)
        assert {503, body, _} = turn(a)
        assert body == example("error_calls_unavailable.json")
      end
    end

    test "HMAC-SHA1 REST credentials with a 5-h TTL, never logged", %{a: a} do
      Application.put_env(:risime, :turn, secret: @secret, urls: @urls, ttl: 18_000)
      now = System.os_time(:second)

      log =
        capture_log([level: :debug], fn ->
          assert {200, body, _} = turn(a)
          send(self(), {:body, body})
        end)

      assert_received {:body, body}
      assert body["ttl"] == 18_000
      [stun, turn] = body["ice_servers"]
      assert stun == %{"urls" => ["stun:turn.example.test:3478"]}
      assert turn["urls"] == tl(@urls)
      [exp, rand] = String.split(turn["username"], ":")
      assert rand =~ ~r/^[0-9a-f]{16}$/
      assert String.to_integer(exp) in (now + 18_000)..(now + 18_002)

      assert turn["credential"] ==
               :crypto.mac(:hmac, :sha, @secret, turn["username"]) |> Base.encode64()

      assert body["expires_at"] ==
               exp |> String.to_integer() |> DateTime.from_unix!() |> Messaging.iso()

      refute log =~ @secret
      refute log =~ turn["username"]
      refute log =~ turn["credential"]
    end

    test "20 per user per hour, then 429 with Retry-After", %{a: a, b: b} do
      Application.put_env(:risime, :turn, secret: @secret, urls: @urls)
      for _ <- 1..20, do: assert({200, _, _} = turn(a))

      for _ <- 1..3 do
        assert {429, %{"error" => %{"code" => "rate_limited"}}, conn} = turn(a)
        [retry] = Plug.Conn.get_resp_header(conn, "retry-after")
        assert String.to_integer(retry) in 1..3600
      end

      assert {200, _, _} = turn(b)
    end

    test "401 without auth" do
      conn =
        Phoenix.ConnTest.build_conn()
        |> Phoenix.ConnTest.dispatch(@endpoint, :get, "/api/v1/calls/turn")

      assert conn.status == 401
    end
  end

  @examples Path.expand("../../../../contract/v1/examples", __DIR__)
  defp example(name), do: @examples |> Path.join(name) |> File.read!() |> Jason.decode!()
end
