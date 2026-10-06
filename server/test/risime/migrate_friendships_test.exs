defmodule RisiMe.MigrateFriendshipsTest do
  use RisiMe.DataCase, async: false

  import RisiMe.Fixtures

  alias RisiMe.Messaging.Store.Cassandra

  defp message!(from, to) do
    :ok =
      Cassandra.put_message(%{
        message_id: RisiMe.TimeUUID.generate(),
        sender_id: from,
        recipient_id: to,
        client_msg_id: Ecto.UUID.generate(),
        conversation_id: RisiMe.Messaging.conversation_id(from, to),
        status: "sent"
      })
  end

  test "existing conversation pairs become friends; idempotent; vanished users skipped" do
    %{user: a} = logged_in_user()
    %{user: b} = logged_in_user()
    %{user: c} = logged_in_user()
    message!(a.id, b.id)
    message!(b.id, a.id)
    message!(c.id, Ecto.UUID.generate())

    {:ok, created} = RisiMe.Release.migrate_friendships(keyspace: "risime_test")
    assert created == 1
    assert RisiMe.Social.friends?(a.id, b.id)
    refute RisiMe.Social.friends?(a.id, c.id)

    assert {:ok, 0} = RisiMe.Release.migrate_friendships(keyspace: "risime_test")
  end
end
