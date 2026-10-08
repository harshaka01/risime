defmodule RisiMe.PushWatchdogTest do
  @moduledoc """
  Push watchdog: a joined inbox channel whose socket doesn't answer a WebSocket ping (a phone
  that lost its network without a close) gets the push anyway; a pong holds it.

  In `Phoenix.ChannelTest` the socket's transport is the test process, so the watchdog's ping
  arrives here as `{:socket_push, :ping, data}`; answering means calling `Dispatcher.pong/1`
  (what `RisiMeWeb.UserSocket.handle_control/2` does for a real pong).
  """
  use RisiMeWeb.ChannelCase, async: false

  import ExUnit.CaptureLog
  import RisiMe.Fixtures

  alias Phoenix.Socket.Broadcast
  alias RisiMe.Devices
  alias RisiMe.Push.Dispatcher
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @watchdog 300
  @call_watchdog 200

  setup do
    test_push!()
    Application.put_env(:risime, :push_watchdog_ms, @watchdog)
    Application.put_env(:risime, :push_call_watchdog_ms, @call_watchdog)
    old = Logger.level()
    Logger.configure(level: :info)

    on_exit(fn ->
      Application.put_env(:risime, :push_watchdog_ms, nil)
      Application.put_env(:risime, :push_call_watchdog_ms, nil)
      Logger.configure(level: old)
    end)

    a = logged_in_user()
    b = logged_in_user()
    befriend!(a, b)
    {:ok, sock_a} = connect(UserSocket, %{"token" => a.token})
    {:ok, _, chan_a} = subscribe_and_join(sock_a, InboxChannel, "inbox:" <> a.user.id, %{})

    device_id = Ecto.UUID.generate()
    token = push_token("tok-b")

    {:ok, nil} =
      Devices.register(b.user.id, device_id, %{"platform" => "android", "push_token" => token})

    {:ok, sock_b} = connect(UserSocket, %{"token" => b.token, "device_id" => device_id})
    {:ok, _, chan_b} = subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})

    %{a: a, b: b, chan_a: chan_a, chan_b: chan_b, device_id: device_id, token: token}
  end

  defp send_to(chan, to) do
    ref =
      push(chan, "msg:send", %{
        "client_msg_id" => Uniq.UUID.uuid4(),
        "to" => to,
        "body" => "hello"
      })

    assert_reply ref, :ok, _, 1_000
  end

  test "a dead-but-joined socket gets the push after the watchdog, and is dropped", ctx do
    %{b: b, token: tok, device_id: device_id} = ctx

    log =
      capture_log(fn ->
        send_to(ctx.chan_a, b.user.id)
        # The message itself, then the ping behind it.
        assert_push "event", %{kind: "message"}
        assert_receive {:socket_push, :ping, _data}, 1_000
        # No pong: the push goes out once the watchdog is up, not before.
        refute_receive {:push, ^tok, _}, @watchdog - 100
        assert_receive {:push, ^tok, %{"type" => "inbox", "v" => "1"}}, 1_000
        assert_receive %Broadcast{event: "disconnect"}, 1_000
        Process.sleep(100)
      end)

    hash = Dispatcher.user_hash(b.user.id)
    dev = String.slice(device_id, 0, 8)
    assert log =~ ~r/push: watchdog kind=inbox user=#{hash} device=#{dev} waited_ms=\d+/
    assert log =~ ~r/push: kind=inbox user=#{hash} device=#{dev} result=ok/
    refute log =~ "reason=online"
  end

  test "a pong (the client read the event) holds the push", ctx do
    %{b: b, token: tok} = ctx

    log =
      capture_log(fn ->
        send_to(ctx.chan_a, b.user.id)
        assert_receive {:socket_push, :ping, data}, 1_000
        Dispatcher.pong(data)
        refute_receive {:push, ^tok, _}, @watchdog * 2
        refute_received %Broadcast{event: "disconnect"}
      end)

    assert log =~ ~r/push: skipped kind=inbox user=\w{8} reason=online devices_online=1/
    refute log =~ "push: watchdog"
  end

  test "a burst shares one ping and one push (never one per event)", ctx do
    %{b: b, token: tok} = ctx
    for _ <- 1..4, do: send_to(ctx.chan_a, b.user.id)

    assert_receive {:socket_push, :ping, _}, 1_000
    assert_receive {:push, ^tok, _}, 1_000
    refute_receive {:push, ^tok, _}, @watchdog * 2
    refute_received {:socket_push, :ping, _}
  end

  test "an event during an answered ping is covered by a new ping", ctx do
    %{b: b, token: tok} = ctx
    send_to(ctx.chan_a, b.user.id)
    assert_receive {:socket_push, :ping, first}, 1_000
    # Sent after the first ping: its pong doesn't prove this one arrived.
    send_to(ctx.chan_a, b.user.id)
    Dispatcher.pong(first)

    assert_receive {:socket_push, :ping, second}, 1_000
    assert second != first
    assert_receive {:push, ^tok, _}, 1_000
  end

  test "a late or unknown pong changes nothing", ctx do
    %{b: b, token: tok} = ctx
    Dispatcher.pong(:crypto.strong_rand_bytes(8))
    Dispatcher.pong("short")
    send_to(ctx.chan_a, b.user.id)
    assert_receive {:socket_push, :ping, data}, 1_000
    assert_receive {:push, ^tok, _}, 1_000
    Dispatcher.pong(data)
    refute_receive {:push, ^tok, _}, @watchdog * 2
  end

  test "within the presence grace (no joined channel) the push goes out at once", ctx do
    %{b: b, token: tok} = ctx
    Process.unlink(ctx.chan_b.channel_pid)
    ref = leave(ctx.chan_b)
    assert_reply ref, :ok
    Process.sleep(50)

    assert RisiMe.Presence.online?(b.user.id)
    assert RisiMe.Presence.connections(b.user.id) == []
    send_to(ctx.chan_a, b.user.id)
    assert_receive {:push, ^tok, _}, @watchdog - 100
  end

  test "off (nil): a joined channel holds the push as before", ctx do
    %{b: b, token: tok} = ctx
    Application.put_env(:risime, :push_watchdog_ms, nil)
    send_to(ctx.chan_a, b.user.id)
    refute_receive {:socket_push, :ping, _}, 200
    refute_receive {:push, ^tok, _}, @watchdog * 2
  end

  describe "call ring watchdog (watch_call/2)" do
    test "no pong → the call push to that device", ctx do
      %{b: b, token: tok, device_id: device_id} = ctx

      log =
        capture_log(fn ->
          Dispatcher.watch_call(b.user.id, [{device_id, tok}])
          assert_receive {:socket_push, :ping, _}, 1_000
          refute_receive {:push, ^tok, _}, @call_watchdog - 100
          assert_receive {:push, ^tok, %{"type" => "call"}}, 1_000
          Process.sleep(100)
        end)

      assert log =~ ~r/push: watchdog kind=call user=\w{8} device=\w{8} waited_ms=\d+/
    end

    test "a pong holds the call push", ctx do
      %{b: b, token: tok, device_id: device_id} = ctx
      Dispatcher.watch_call(b.user.id, [{device_id, tok}])
      assert_receive {:socket_push, :ping, data}, 1_000
      Dispatcher.pong(data)
      refute_receive {:push, ^tok, _}, @call_watchdog * 2
    end

    test "a target without a joined channel is pushed at once", ctx do
      %{b: b} = ctx
      other = push_token("tok-b2")
      Dispatcher.watch_call(b.user.id, [{Ecto.UUID.generate(), other}])
      assert_receive {:push, ^other, %{"type" => "call"}}, 100
      refute_receive {:socket_push, :ping, _}, 100
    end
  end
end
