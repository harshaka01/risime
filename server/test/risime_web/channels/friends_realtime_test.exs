defmodule RisiMeWeb.FriendsRealtimeTest do
  @moduledoc "Contract v1.6 §9.3 on the channel."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures

  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup do
    a = logged_in_user()
    b = logged_in_user()
    befriend!(a, b)
    {:ok, sock} = connect(UserSocket, %{"token" => a.token})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
    %{a: a, b: b, chan: chan}
  end

  defp send_msg(chan, to, cmid \\ Uniq.UUID.uuid4()) do
    ref = push(chan, "msg:send", %{"client_msg_id" => cmid, "to" => to, "body" => "hi"})

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply")
    end
  end

  test "after unfriend: new sends are not_friends; a resend returns the original reply; acks work",
       %{a: a, b: b, chan: chan} do
    cmid = Uniq.UUID.uuid4()
    {:ok, %{message_id: id}} = send_msg(chan, b.user.id, cmid)

    :ok = RisiMe.Social.unfriend(a.user, b.user.id)

    assert {:error, %{reason: "not_friends"}} = send_msg(chan, b.user.id)
    assert {:ok, %{message_id: ^id}} = send_msg(chan, b.user.id, cmid)

    # b can still ack the message it received, and the event queued for b stays deliverable.
    assert :ok = RisiMe.Messaging.ack(b.user.id, [id], "read")
    assert {:ok, events, _} = RisiMe.Messaging.fetch_events(b.user.id, nil)
    assert Enum.any?(events, &(&1.event_id == id))
  end

  test "a block in either direction is not_friends for messages and typing", %{
    a: a,
    b: b,
    chan: chan
  } do
    :ok = RisiMe.Social.block(b.user, a.user.id)
    assert {:error, %{reason: "not_friends"}} = send_msg(chan, b.user.id)
    ref = push(chan, "typing", %{"to" => b.user.id, "typing" => true})
    assert_reply ref, :error, %{reason: "not_friends"}
  end

  test "presence:watch covers friends only; unfriend drops the watch at once", %{
    a: a,
    b: b,
    chan: chan
  } do
    %{user: stranger} = logged_in_user()
    ref = push(chan, "presence:watch", %{"user_ids" => [b.user.id, stranger.id]})
    assert_reply ref, :ok, %{presences: [%{"user_id" => bid}]}
    assert bid == b.user.id

    :ok = RisiMe.Social.unfriend(a.user, b.user.id)
    # Watch set dropped: b coming online produces no signal for a.
    Process.sleep(50)
    {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})
    {:ok, _, _} = subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})
    topic = "inbox:" <> a.user.id

    refute_receive %Phoenix.Socket.Message{
                     topic: ^topic,
                     event: "signal",
                     payload: %{kind: "presence"}
                   },
                   300
  end

  test "the friend signal is pushed on the channel", %{a: a, chan: _chan} do
    %{user: c, token: ct} = logged_in_user(display_name: "Carol")
    :ok = RisiMe.Social.request(c, a.user.phone)
    topic = "inbox:" <> a.user.id

    assert_receive %Phoenix.Socket.Message{
      topic: ^topic,
      event: "signal",
      payload: %{
        kind: "friend",
        data: %{"action" => "request_received", "user" => %{"display_name" => "Carol"}}
      }
    }

    _ = ct
  end
end
