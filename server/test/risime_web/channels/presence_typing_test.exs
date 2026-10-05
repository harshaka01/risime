defmodule RisiMeWeb.PresenceTypingTest do
  @moduledoc "PROTOCOL.md v1.2: presence:watch, presence signals, typing signals."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures

  alias Phoenix.Socket.Message
  alias RisiMe.Accounts
  alias RisiMeWeb.{InboxChannel, UserSocket}

  # config/test.exs sets the grace period to 150 ms.
  @grace 150

  setup do
    [a, b, c] = for name <- ~w(A B C), do: logged_in_user(display_name: name)

    sockets =
      for u <- [a, b, c], into: %{} do
        {:ok, sock} = connect(UserSocket, %{"token" => u.token})
        {u.user.id, sock}
      end

    %{a: a.user.id, b: b.user.id, c: c.user.id, sockets: sockets}
  end

  defp join_inbox(sockets, user_id) do
    {:ok, _, chan} =
      subscribe_and_join(sockets[user_id], InboxChannel, "inbox:" <> user_id, %{"since" => nil})

    chan
  end

  defp leave_chan(chan) do
    Process.unlink(chan.channel_pid)
    ref = Process.monitor(chan.channel_pid)
    close(chan)
    assert_receive {:DOWN, ^ref, _, _, _}
  end

  defp reply(chan, event, payload) do
    ref = push(chan, event, payload)

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: status, payload: payload} -> {status, payload}
    after
      2000 -> flunk("no reply to #{event}")
    end
  end

  defp watch(chan, ids), do: reply(chan, "presence:watch", %{"user_ids" => ids})

  defp assert_presence(watcher_id, user_id, online, timeout \\ 1000) do
    topic = "inbox:" <> watcher_id

    assert_receive %Message{
                     topic: ^topic,
                     event: "signal",
                     payload: %{kind: "presence", data: %{"user_id" => ^user_id} = data}
                   },
                   timeout

    assert data["online"] == online
    data
  end

  defp refute_presence(watcher_id, user_id, timeout) do
    topic = "inbox:" <> watcher_id

    refute_receive %Message{
                     topic: ^topic,
                     event: "signal",
                     payload: %{kind: "presence", data: %{"user_id" => ^user_id}}
                   },
                   timeout
  end

  defp last_seen(user_id), do: Accounts.get_user(user_id).last_seen_at

  describe "presence:watch" do
    test "replies for registered users only (self allowed, unknown ids left out)", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      unknown = Uniq.UUID.uuid4()

      assert {:ok, %{presences: presences}} =
               watch(chan_a, [ctx.a, ctx.b, unknown, "not-a-uuid", String.upcase(ctx.c)])

      assert presences == [
               %{"user_id" => ctx.a, "online" => true, "last_seen" => nil},
               %{"user_id" => ctx.b, "online" => false, "last_seen" => nil},
               %{"user_id" => ctx.c, "online" => false, "last_seen" => nil}
             ]
    end

    test "an offline user who connected before has last_seen", ctx do
      chan_b = join_inbox(ctx.sockets, ctx.b)
      leave_chan(chan_b)
      Process.sleep(@grace + 100)

      chan_a = join_inbox(ctx.sockets, ctx.a)
      assert {:ok, %{presences: [p]}} = watch(chan_a, [ctx.b])
      assert %{"online" => false, "last_seen" => ts} = p
      assert ts =~ ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
    end

    test "more than 200 ids, non-string ids or a missing list are bad_request", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      ids = for _ <- 1..201, do: Uniq.UUID.uuid4()
      assert {:error, %{reason: "bad_request"}} = watch(chan_a, ids)
      assert {:ok, %{presences: []}} = watch(chan_a, Enum.take(ids, 200))
      assert {:error, %{reason: "bad_request"}} = watch(chan_a, [1])
      assert {:error, %{reason: "bad_request"}} = reply(chan_a, "presence:watch", %{})
    end

    test "a watched user's online and offline changes arrive as signals", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      assert {:ok, _} = watch(chan_a, [ctx.b])

      chan_b = join_inbox(ctx.sockets, ctx.b)
      assert %{"last_seen" => nil} = assert_presence(ctx.a, ctx.b, true)

      leave_chan(chan_b)
      # Nothing during the grace period, then offline with last_seen.
      refute_presence(ctx.a, ctx.b, div(@grace, 2))
      assert %{"last_seen" => ts} = assert_presence(ctx.a, ctx.b, false)
      assert is_binary(ts)
    end

    test "a reconnect within the grace period publishes nothing", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      chan_b = join_inbox(ctx.sockets, ctx.b)
      assert {:ok, %{presences: [%{"online" => true}]}} = watch(chan_a, [ctx.b])

      leave_chan(chan_b)
      _chan_b = join_inbox(ctx.sockets, ctx.b)
      refute_presence(ctx.a, ctx.b, @grace * 3)
    end

    test "online until the last of several channels leaves", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      {:ok, sock_b2} = connect(UserSocket, %{"token" => login_again(ctx.b)})
      chan_b1 = join_inbox(ctx.sockets, ctx.b)

      {:ok, _, chan_b2} =
        subscribe_and_join(sock_b2, InboxChannel, "inbox:" <> ctx.b, %{"since" => nil})

      assert {:ok, _} = watch(chan_a, [ctx.b])
      leave_chan(chan_b1)
      refute_presence(ctx.a, ctx.b, @grace * 2)
      leave_chan(chan_b2)
      assert_presence(ctx.a, ctx.b, false)
    end

    test "the watch list is replaced; [] stops watching", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      assert {:ok, _} = watch(chan_a, [ctx.b])
      assert {:ok, %{presences: [%{"user_id" => c}]}} = watch(chan_a, [ctx.c])
      assert c == ctx.c

      _chan_b = join_inbox(ctx.sockets, ctx.b)
      refute_presence(ctx.a, ctx.b, 100)

      chan_c = join_inbox(ctx.sockets, ctx.c)
      assert_presence(ctx.a, ctx.c, true)

      assert {:ok, %{presences: []}} = watch(chan_a, [])
      leave_chan(chan_c)
      refute_presence(ctx.a, ctx.c, @grace * 2)
    end

    test "watching yourself is allowed", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      assert {:ok, %{presences: [%{"user_id" => a, "online" => true}]}} = watch(chan_a, [ctx.a])
      assert a == ctx.a
    end

    test "last_seen is written on join and on leave", ctx do
      assert last_seen(ctx.b) == nil
      chan_b = join_inbox(ctx.sockets, ctx.b)
      joined = last_seen(ctx.b)
      assert %DateTime{} = joined

      Process.sleep(5)
      leave_chan(chan_b)
      assert DateTime.compare(last_seen(ctx.b), joined) == :gt
    end
  end

  describe "typing" do
    defp assert_typing(to, from, typing) do
      topic = "inbox:" <> to

      assert_receive %Message{
        topic: ^topic,
        event: "signal",
        payload: %{kind: "typing", data: %{"from" => ^from, "typing" => ^typing} = data}
      }

      data
    end

    test "is forwarded to the recipient's channels as a signal", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      _chan_b = join_inbox(ctx.sockets, ctx.b)

      assert {:ok, %{}} = reply(chan_a, "typing", %{"to" => ctx.b, "typing" => true})
      data = assert_typing(ctx.b, ctx.a, true)
      assert data["conversation_id"] == RisiMe.Messaging.conversation_id(ctx.a, ctx.b)

      assert {:ok, %{}} = reply(chan_a, "typing", %{"to" => ctx.b, "typing" => false})
      assert_typing(ctx.b, ctx.a, false)
    end

    test "unknown_recipient and bad_request as in msg:send", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)

      for to <- [Uniq.UUID.uuid4(), ctx.a, "nope"] do
        assert {:error, %{reason: "unknown_recipient"}} =
                 reply(chan_a, "typing", %{"to" => to, "typing" => true})
      end

      for bad <- [%{"to" => ctx.b}, %{"to" => ctx.b, "typing" => "yes"}, %{"typing" => true}] do
        assert {:error, %{reason: "bad_request"}} = reply(chan_a, "typing", bad)
      end
    end

    test "typing:true above 2 per second is dropped silently; typing:false never", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      _chan_b = join_inbox(ctx.sockets, ctx.b)
      topic_b = "inbox:" <> ctx.b

      for _ <- 1..5,
          do: assert({:ok, %{}} = reply(chan_a, "typing", %{"to" => ctx.b, "typing" => true}))

      for _ <- 1..5,
          do: assert({:ok, %{}} = reply(chan_a, "typing", %{"to" => ctx.b, "typing" => false}))

      Process.sleep(100)

      {:messages, msgs} = Process.info(self(), :messages)

      typings =
        for %Message{topic: ^topic_b, event: "signal", payload: %{kind: "typing", data: d}} <-
              msgs,
            do: d["typing"]

      # The rate limiter's sliding window allows 2 or 3 in a burst depending on alignment.
      assert Enum.count(typings, & &1) in 2..3
      assert Enum.count(typings, &(!&1)) == 5
    end

    test "signals are not stored or replayed on join", ctx do
      chan_a = join_inbox(ctx.sockets, ctx.a)
      chan_b = join_inbox(ctx.sockets, ctx.b)
      assert {:ok, %{}} = reply(chan_a, "typing", %{"to" => ctx.b, "typing" => true})
      assert_typing(ctx.b, ctx.a, true)
      leave_chan(chan_b)

      assert {:ok, %{events: []}, _} =
               subscribe_and_join(ctx.sockets[ctx.b], InboxChannel, "inbox:" <> ctx.b, %{
                 "since" => nil
               })
    end
  end

  # A second token for the same user (a second device).
  defp login_again(user_id) do
    user = Accounts.get_user(user_id)
    :ok = Accounts.request_otp(user.phone, user.email)
    code = receive_code()
    {:ok, token, _} = Accounts.verify_otp(user.phone, code, "second")
    token
  end
end
