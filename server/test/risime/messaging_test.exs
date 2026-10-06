defmodule RisiMe.MessagingTest do
  use RisiMe.DataCase, async: true

  import RisiMe.Fixtures

  alias RisiMe.Messaging

  setup do
    %{user: a} = logged_in_user()
    %{user: b} = logged_in_user()
    befriend!(a, b)
    %{a: a.id, b: b.id}
  end

  defp msg(to, body \\ "hello"),
    do: %{"client_msg_id" => Uniq.UUID.uuid4(), "to" => to, "body" => body}

  test "conversation_id sorts the lowercase ids" do
    assert Messaging.conversation_id("B0", "a1") == "dm:a1_b0"
    assert Messaging.conversation_id("a1", "b0") == "dm:a1_b0"
  end

  test "iso/1 always has milliseconds" do
    assert Messaging.iso(~U[2026-10-06 08:15:30Z]) == "2026-10-06T08:15:30.000Z"
    assert Messaging.iso(~U[2026-10-06 08:15:30.123456Z]) == "2026-10-06T08:15:30.123Z"
  end

  test "send stores the message in both inboxes and broadcasts it", %{a: a, b: b} do
    Messaging.subscribe(b)
    params = msg(b)
    assert {:ok, reply} = Messaging.send(a, params)
    assert reply.conversation_id == Messaging.conversation_id(a, b)
    assert reply.server_ts =~ ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/

    assert_receive {:inbox_event, %{kind: "message", data: data} = event}
    assert data["from"] == a and data["to"] == b and data["body"] == "hello"
    assert data["client_msg_id"] == params["client_msg_id"]
    assert data["message_id"] == reply.message_id

    assert {:ok, [^event], false} = Messaging.fetch_events(b, nil)
    # v1.10 §13.1: the sender's copy, the same event_id and payload.
    assert {:ok, [^event], false} = Messaging.fetch_events(a, nil)
  end

  test "send is idempotent per client_msg_id", %{a: a, b: b} do
    params = msg(b)
    assert {:ok, first} = Messaging.send(a, params)
    assert {:ok, ^first} = Messaging.send(a, params)
    assert {:ok, ^first} = Messaging.send(a, %{params | "body" => "changed"})
    assert {:ok, [_one], false} = Messaging.fetch_events(b, nil)
  end

  test "send errors", %{a: a, b: b} do
    assert {:error, :empty_body} = Messaging.send(a, msg(b, ""))
    assert {:error, :empty_body} = Messaging.send(a, msg(b, "  \n "))
    assert {:error, :too_long} = Messaging.send(a, msg(b, String.duplicate("é", 4097)))
    assert {:ok, _} = Messaging.send(a, msg(b, String.duplicate("é", 4096)))
    # v1.6: every non-friend, unknown ids included, is not_friends.
    assert {:error, :not_friends} = Messaging.send(a, msg(Uniq.UUID.uuid4()))
    %{user: stranger} = logged_in_user()
    assert {:error, :not_friends} = Messaging.send(a, msg(stranger.id))
    assert {:error, :unknown_recipient} = Messaging.send(a, msg("nope"))
    assert {:error, :unknown_recipient} = Messaging.send(a, msg(a))
    assert {:error, :bad_request} = Messaging.send(a, %{"to" => b})
  end

  test "send rate limit: 20 per 10 s per user", %{a: a, b: b} do
    for _ <- 1..20, do: assert({:ok, _} = Messaging.send(a, msg(b)))
    assert {:error, :rate_limited} = Messaging.send(a, msg(b))
  end

  test "acks reach the sender as status events and never go backwards", %{a: a, b: b} do
    {:ok, %{message_id: id}} = Messaging.send(a, msg(b))
    Messaging.subscribe(a)

    assert :ok = Messaging.ack(b, [id], "delivered")
    assert_receive {:inbox_event, %{kind: "status", data: %{"status" => "delivered"} = d}}
    assert d["message_id"] == id and d["by"] == b

    assert :ok = Messaging.ack(b, [id], "delivered")
    assert :ok = Messaging.ack(b, [id], "read")
    assert_receive {:inbox_event, %{kind: "status", data: %{"status" => "read"}}}
    assert :ok = Messaging.ack(b, [id], "delivered")
    refute_receive {:inbox_event, _}, 100

    assert {:ok, [%{event_id: ^id, kind: "message"} | events], false} =
             Messaging.fetch_events(a, nil)

    assert Enum.map(events, & &1.data["status"]) == ["delivered", "read"]
  end

  test "only the recipient can ack", %{a: a, b: b} do
    {:ok, %{message_id: id}} = Messaging.send(a, msg(b))
    assert :ok = Messaging.ack(a, [id], "read")
    assert :ok = Messaging.ack(b, [RisiMe.TimeUUID.generate(), "junk"], "read")
    # Only a's own copy (v1.10): no status event.
    assert {:ok, [%{event_id: ^id, kind: "message"}], false} = Messaging.fetch_events(a, nil)
    assert {:ok, %{status: "sent"}} = RisiMe.Messaging.Store.impl().get_message(id)
    assert {:error, :bad_request} = Messaging.ack(b, [id], "seen")
    assert {:error, :bad_request} = Messaging.ack(b, id, "read")
  end

  test "fetch_events cursor, paging and validation", %{a: a, b: b} do
    for i <- 1..5, do: {:ok, _} = Messaging.send(a, msg(b, "m#{i}"))
    {:ok, all, false} = Messaging.fetch_events(b, nil)
    assert Enum.map(all, & &1.data["body"]) == ~w(m1 m2 m3 m4 m5)

    {:ok, page1, true} = Messaging.fetch_events(b, nil, 2)
    {:ok, page2, true} = Messaging.fetch_events(b, List.last(page1).event_id, 2)
    {:ok, page3, false} = Messaging.fetch_events(b, List.last(page2).event_id, 2)
    assert page1 ++ page2 ++ page3 == all

    assert {:error, :bad_request} = Messaging.fetch_events(b, "junk")
    assert {:error, :bad_request} = Messaging.fetch_events(b, Uniq.UUID.uuid4())
  end
end
