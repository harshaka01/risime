defmodule RisiMeWeb.InboxChannelTest do
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures

  alias Phoenix.Socket.Message
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup do
    a = logged_in_user(display_name: "A")
    b = logged_in_user(display_name: "B")
    {:ok, sock_a} = connect(UserSocket, %{"token" => a.token})
    {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})
    %{a: a.user.id, b: b.user.id, sock_a: sock_a, sock_b: sock_b}
  end

  defp join_inbox(socket, user_id, payload \\ %{"since" => nil}) do
    subscribe_and_join(socket, InboxChannel, "inbox:" <> user_id, payload)
  end

  defp send_msg(chan, to, body \\ "hi", cmid \\ Uniq.UUID.uuid4()) do
    ref =
      push(chan, "msg:send", %{
        "client_msg_id" => cmid,
        "to" => to,
        "body" => body,
        "client_ts" => "2026-10-06T08:15:30.123Z"
      })

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: status, payload: payload} -> {status, payload}
    after
      2000 -> flunk("no reply to msg:send")
    end
  end

  defp assert_event(user_id, kind) do
    topic = "inbox:" <> user_id
    assert_receive %Message{topic: ^topic, event: "event", payload: %{kind: ^kind} = event}
    event
  end

  test "socket refuses a missing, unknown or revoked token" do
    assert :error = connect(UserSocket, %{})
    assert :error = connect(UserSocket, %{"token" => "nope"})

    %{token: token} = logged_in_user()
    {_, record} = RisiMe.Accounts.fetch_by_token(token)
    RisiMe.Accounts.revoke_token(record)
    assert :error = connect(UserSocket, %{"token" => token})
  end

  test "joining another user's inbox is unauthorized", %{sock_a: sock_a, b: b} do
    assert {:error, %{reason: "unauthorized"}} = join_inbox(sock_a, b)
  end

  test "join with since: null on an empty inbox", %{sock_a: sock_a, a: a} do
    assert {:ok, %{events: [], has_more: false, server_time: t}, _} = join_inbox(sock_a, a)
    assert t =~ ~r/\.\d{3}Z$/
    assert {:ok, %{events: []}, _} = join_inbox(sock_a, a, %{})
  end

  test "A sends, connected B receives the event live", %{
    sock_a: sock_a,
    sock_b: sock_b,
    a: a,
    b: b
  } do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    {:ok, _, _chan_b} = join_inbox(sock_b, b)

    cmid = Uniq.UUID.uuid4()
    assert {:ok, reply} = send_msg(chan_a, b, "hello B", cmid)
    assert reply.conversation_id == RisiMe.Messaging.conversation_id(a, b)

    event = assert_event(b, "message")
    assert event.data["message_id"] == reply.message_id
    assert event.data["client_msg_id"] == cmid
    assert event.data["from"] == a and event.data["body"] == "hello B"
    assert event.data["server_ts"] == reply.server_ts
  end

  test "B offline: A sends, B joins with since: null and gets it", %{
    sock_a: sock_a,
    sock_b: sock_b,
    a: a,
    b: b
  } do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    {:ok, %{message_id: id}} = send_msg(chan_a, b, "while you were away")

    assert {:ok, %{events: [event], has_more: false}, _} = join_inbox(sock_b, b)
    assert event.kind == "message" and event.data["message_id"] == id
  end

  test "the cursor: since excludes older events", %{sock_a: sock_a, sock_b: sock_b, a: a, b: b} do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    for i <- 1..3, do: {:ok, _} = send_msg(chan_a, b, "m#{i}")

    {:ok, %{events: [e1, e2, e3]}, _} = join_inbox(sock_b, b)
    assert Enum.map([e1, e2, e3], & &1.data["body"]) == ~w(m1 m2 m3)

    {:ok, %{events: rest, has_more: false}, chan_b} =
      join_inbox(sock_b, b, %{"since" => e1.event_id})

    assert rest == [e2, e3]

    ref = push(chan_b, "sync", %{"since" => e3.event_id})
    assert_reply ref, :ok, %{events: [], has_more: false}

    ref = push(chan_b, "sync", %{"since" => "junk"})
    assert_reply ref, :error, %{reason: "bad_request"}
  end

  test "has_more pagination via sync", %{sock_a: sock_a, sock_b: sock_b, a: a, b: b} do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    for i <- 1..5, do: {:ok, _} = send_msg(chan_a, b, "m#{i}")

    {:ok, %{events: page1, has_more: true}, chan_b} =
      join_inbox(sock_b, b, %{"since" => nil, "limit" => 2})

    ref = push(chan_b, "sync", %{"since" => List.last(page1).event_id, "limit" => 2})
    assert_reply ref, :ok, %{events: page2, has_more: true}
    ref = push(chan_b, "sync", %{"since" => List.last(page2).event_id, "limit" => 2})
    assert_reply ref, :ok, %{events: page3, has_more: false}

    assert Enum.map(page1 ++ page2 ++ page3, & &1.data["body"]) == ~w(m1 m2 m3 m4 m5)
  end

  test "delivered/read acks reach A as status events; status never goes backwards",
       %{sock_a: sock_a, sock_b: sock_b, a: a, b: b} do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    {:ok, _, chan_b} = join_inbox(sock_b, b)
    {:ok, %{message_id: id}} = send_msg(chan_a, b)
    assert_event(b, "message")

    ref = push(chan_b, "msg:ack", %{"message_ids" => [id], "status" => "delivered"})
    assert_reply ref, :ok, %{}
    delivered = assert_event(a, "status")
    assert %{"message_id" => ^id, "status" => "delivered", "by" => ^b} = delivered.data

    ref = push(chan_b, "msg:ack", %{"message_ids" => [id], "status" => "read"})
    assert_reply ref, :ok, %{}
    assert %{data: %{"status" => "read"}} = assert_event(a, "status")

    ref = push(chan_b, "msg:ack", %{"message_ids" => [id], "status" => "delivered"})
    assert_reply ref, :ok, %{}
    refute_receive %Message{event: "event"}, 200

    # A's sender-side ack is ignored: only the recipient moves status.
    ref = push(chan_a, "msg:ack", %{"message_ids" => [id], "status" => "read"})
    assert_reply ref, :ok, %{}
    refute_receive %Message{event: "event"}, 200

    ref = push(chan_b, "msg:ack", %{"message_ids" => [id], "status" => "seen"})
    assert_reply ref, :error, %{reason: "bad_request"}
  end

  test "an idempotent resend returns the same message_id", %{
    sock_a: sock_a,
    sock_b: sock_b,
    a: a,
    b: b
  } do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    cmid = Uniq.UUID.uuid4()
    {:ok, first} = send_msg(chan_a, b, "once", cmid)
    {:ok, second} = send_msg(chan_a, b, "once", cmid)
    assert first == second

    assert {:ok, %{events: [_only_one]}, _} = join_inbox(sock_b, b)
  end

  test "error cases: too_long, empty_body, unknown_recipient", %{sock_a: sock_a, a: a, b: b} do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    assert {:error, %{reason: "too_long"}} = send_msg(chan_a, b, String.duplicate("x", 4097))
    assert {:error, %{reason: "empty_body"}} = send_msg(chan_a, b, "")
    assert {:error, %{reason: "unknown_recipient"}} = send_msg(chan_a, Uniq.UUID.uuid4())
  end

  test "rate_limited after 20 messages in 10 s", %{sock_a: sock_a, a: a, b: b} do
    {:ok, _, chan_a} = join_inbox(sock_a, a)
    for _ <- 1..20, do: assert({:ok, _} = send_msg(chan_a, b))
    assert {:error, %{reason: "rate_limited"}} = send_msg(chan_a, b)
  end

  test "logout disconnects the token's sockets" do
    %{token: token} = logged_in_user()
    {:ok, socket} = connect(UserSocket, %{"token" => token})
    @endpoint.subscribe(UserSocket.id(socket))

    Phoenix.ConnTest.build_conn()
    |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
    |> Phoenix.ConnTest.dispatch(@endpoint, :post, "/api/v1/auth/logout")

    assert_receive %Phoenix.Socket.Broadcast{event: "disconnect"}
  end
end
