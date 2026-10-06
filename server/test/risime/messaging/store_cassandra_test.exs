defmodule RisiMe.Messaging.Store.CassandraTest do
  use ExUnit.Case, async: true

  alias RisiMe.Messaging.Store.Cassandra, as: Store

  defp uuid1, do: RisiMe.TimeUUID.generate()
  defp uuid4, do: Uniq.UUID.uuid4()

  test "claim_send is first-writer-wins" do
    sender = uuid4()
    cmid = uuid4()
    ts = ~U[2026-10-06 08:15:30.456Z]
    first = %{message_id: uuid1(), conversation_id: "dm:a_b", server_ts: ts}

    assert :not_found = Store.get_sent(sender, cmid)
    assert :ok = Store.claim_send(sender, cmid, first)
    first = Map.put(first, :kind, nil)
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
    expected = Map.merge(m, %{kind: nil, recipients: nil, deleted_at: nil, deleted_by: nil})
    assert {:ok, %{ttl: ttl} = got} = Store.get_message(m.message_id)
    assert Map.delete(got, :ttl) == expected
    assert ttl in (2_592_000 - 60)..2_592_000

    # v1.8: reactions are indexed with kind = "reaction".
    r = %{m | message_id: uuid1()} |> Map.put(:kind, "reaction")
    assert :ok = Store.put_message(r)
    assert {:ok, %{kind: "reaction"}} = Store.get_message(r.message_id)

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

  test "v1.9 group messages carry recipients; group receipts per member" do
    others = [uuid4(), uuid4()]

    m = %{
      message_id: uuid1(),
      sender_id: uuid4(),
      recipient_id: nil,
      recipients: others,
      client_msg_id: uuid4(),
      conversation_id: "grp:" <> uuid4(),
      status: "sent"
    }

    assert :ok = Store.put_message(m)
    assert {:ok, got} = Store.get_message(m.message_id)
    assert got.recipient_id == nil and Enum.sort(got.recipients) == Enum.sort(others)

    [a, b] = others
    t = DateTime.utc_now() |> DateTime.truncate(:millisecond)
    assert Store.list_group_receipts(m.message_id) == []
    :ok = Store.put_group_receipt(m.message_id, a, t, nil)
    :ok = Store.put_group_receipt(m.message_id, a, nil, t)
    :ok = Store.put_group_receipt(m.message_id, b, t, nil)

    rows = Map.new(Store.list_group_receipts(m.message_id), &{&1.user_id, &1})
    assert %{delivered_at: ^t, read_at: ^t} = rows[a]
    assert %{delivered_at: ^t, read_at: nil} = rows[b]
  end

  test "v1.12 clear_conversation pages (500 per page), filters kind and conversation, keeps the rest" do
    user = uuid4()
    conv = "grp:" <> uuid4()
    other = "dm:" <> uuid4()

    ids =
      for i <- 1..1_103 do
        id = uuid1()

        {kind, c} =
          cond do
            rem(i, 50) == 0 -> {"mls_commit", conv}
            rem(i, 7) == 0 -> {"message", other}
            true -> {Enum.at(~w(message reaction status group_receipt delete), rem(i, 5)), conv}
          end

        :ok =
          Store.append_event(user, %{event_id: id, kind: kind, data: %{"conversation_id" => c}})

        {id, kind, c}
      end

    after_upto = uuid1()

    :ok =
      Store.append_event(user, %{
        event_id: after_upto,
        kind: "message",
        data: %{"conversation_id" => conv}
      })

    {upto, _, _} = List.last(ids)

    expected = Enum.count(ids, fn {_, k, c} -> c == conv and k != "mls_commit" end)

    assert {:ok, ^expected} =
             Store.clear_conversation(
               user,
               conv,
               upto,
               ~w(message reaction status group_receipt delete)
             )

    left = Store.list_events(user, nil, 2_000) |> Enum.map(& &1.event_id)
    assert length(left) == length(ids) - expected + 1
    assert after_upto in left

    assert {:ok, %{kind: "mls_commit", conversation_id: ^conv}} =
             Store.get_event(user, elem(Enum.at(ids, 49), 0))
  end
end
