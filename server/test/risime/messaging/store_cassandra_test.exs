defmodule RisiMe.Messaging.Store.CassandraTest do
  use ExUnit.Case, async: true

  alias RisiMe.Messaging.Store.Cassandra, as: Store

  defp uuid1, do: Uniq.UUID.uuid1()
  defp uuid4, do: Uniq.UUID.uuid4()

  test "claim_send is first-writer-wins" do
    sender = uuid4()
    cmid = uuid4()
    ts = ~U[2026-10-06 08:15:30.456Z]
    first = %{message_id: uuid1(), conversation_id: "dm:a_b", server_ts: ts}

    assert :not_found = Store.get_sent(sender, cmid)
    assert :ok = Store.claim_send(sender, cmid, first)
    assert {:ok, ^first} = Store.get_sent(sender, cmid)
    assert {:exists, ^first} = Store.claim_send(sender, cmid, %{first | message_id: uuid1()})
  end

  test "message index and compare-and-set status" do
    m = %{
      message_id: uuid1(),
      sender_id: uuid4(),
      recipient_id: uuid4(),
      client_msg_id: uuid4(),
      conversation_id: "dm:a_b",
      status: "sent"
    }

    assert Store.get_message(m.message_id) == :not_found
    assert :ok = Store.put_message(m)
    assert {:ok, ^m} = Store.get_message(m.message_id)
    assert :ok = Store.compare_and_set_status(m.message_id, "sent", "delivered")
    assert {:conflict, "delivered"} = Store.compare_and_set_status(m.message_id, "sent", "read")
  end

  test "inbox events page in order after a cursor" do
    user = uuid4()
    events = for i <- 1..5, do: %{event_id: uuid1(), kind: "message", data: %{"n" => i}}
    Enum.each(events, &Store.append_event(user, &1))

    assert Store.list_events(user, nil, 10) == events
    assert Store.list_events(user, nil, 2) == Enum.take(events, 2)
    assert Store.list_events(user, Enum.at(events, 2).event_id, 10) == Enum.drop(events, 3)
    assert Store.list_events(uuid4(), nil, 10) == []
  end
end
