defmodule RisiMe.BackfillSenderCopiesTest do
  @moduledoc "Contract v1.10 §13.5: the one-off sender-copy backfill (`RisiMe.Release`)."
  use RisiMe.DataCase, async: false

  import RisiMe.Fixtures

  alias RisiMe.Messaging
  alias RisiMe.Messaging.Store.Cassandra

  @cluster Cassandra.Cluster

  defp cql!(statement, params) do
    {:ok, page} = Xandra.Cluster.execute(@cluster, statement, params)
    page
  end

  # A pre-v1.10 event: only in the recipient's inbox, with an explicit TTL and write time.
  defp old_event!(owner, data, ttl, kind \\ "message") do
    id = RisiMe.TimeUUID.generate()
    data = Map.put(data, "message_id", id)
    wt = System.os_time(:microsecond) - 3_600_000_000

    cql!(
      "INSERT INTO inbox_events (user_id, event_id, kind, payload) VALUES (?, ?, ?, ?) " <>
        "USING TTL ? AND TIMESTAMP ?",
      [
        {"uuid", owner},
        {"timeuuid", id},
        {"text", kind},
        {"text", Jason.encode!(data)},
        {"int", ttl},
        {"bigint", wt}
      ]
    )

    {id, wt}
  end

  defp dm(from, to, extra \\ %{}) do
    Map.merge(
      %{
        "client_msg_id" => Ecto.UUID.generate(),
        "conversation_id" => Messaging.conversation_id(from, to),
        "from" => from,
        "to" => to,
        "body" => "hi",
        "server_ts" => "2026-10-06T08:15:30.456Z"
      },
      extra
    )
  end

  defp row(user, id) do
    cql!(
      "SELECT kind, payload, TTL(payload) AS ttl, WRITETIME(payload) AS wt FROM inbox_events " <>
        "WHERE user_id = ? AND event_id = ?",
      [{"uuid", user}, {"timeuuid", id}]
    )
    |> Enum.at(0)
  end

  test "copies only plaintext DMs of existing users, with the source TTL and write time; idempotent" do
    %{user: a} = logged_in_user()
    %{user: b} = logged_in_user()
    gone = Ecto.UUID.generate()

    {copy, wt} = old_event!(b.id, dm(a.id, b.id), 1_000_000)
    {copy2, _} = old_event!(a.id, dm(b.id, a.id), 500_000)
    # Not copied: a deleted sender, a deleted recipient, under 60 s left, e2ee, a reaction,
    # a row that already is a sender copy.
    {from_gone, _} = old_event!(b.id, dm(gone, b.id), 1_000_000)
    {to_gone, _} = old_event!(gone, dm(a.id, gone), 1_000_000)
    {short, _} = old_event!(b.id, dm(a.id, b.id), 30)

    {e2ee, _} =
      old_event!(
        b.id,
        dm(a.id, b.id) |> Map.delete("body") |> Map.put("ciphertext", "AA=="),
        1000
      )

    {reaction, _} = old_event!(b.id, dm(a.id, b.id) |> Map.delete("body"), 1000, "reaction")
    {own, _} = old_event!(a.id, dm(a.id, b.id), 1_000_000)

    # Dry run (the default): counts, no writes.
    {:ok, dry} = RisiMe.Release.backfill_sender_copies(keyspace: "risime_test")
    assert dry.dry_run and dry.copied == 2 and dry.existing == 0
    # The test keyspace also holds other tests' DMs, whose users are gone (sandboxed).
    assert dry.skipped_missing_user >= 2 and dry.skipped_ttl == 1
    assert row(a.id, copy) == nil

    {:ok, run} = RisiMe.Release.backfill_sender_copies(keyspace: "risime_test", dry_run: false)
    assert %{copied: 2, existing: 0, skipped_ttl: 1, dry_run: false} = run
    assert run.skipped_missing_user >= 2

    src = row(b.id, copy)
    dst = row(a.id, copy)
    assert dst["kind"] == "message" and dst["payload"] == src["payload"]
    assert dst["wt"] == wt and dst["wt"] == src["wt"]
    assert abs(dst["ttl"] - src["ttl"]) <= 2
    assert dst["ttl"] < 1_000_001 and dst["ttl"] > 999_000
    assert abs(row(b.id, copy2)["ttl"] - row(a.id, copy2)["ttl"]) <= 2

    assert row(gone, from_gone) == nil
    assert row(a.id, to_gone) == nil
    assert row(a.id, short) == nil
    assert row(a.id, e2ee) == nil
    assert row(a.id, reaction) == nil
    assert row(b.id, own) == nil

    # Idempotent: a second run finds both copies.
    assert {:ok, %{copied: 0, existing: 2}} =
             RisiMe.Release.backfill_sender_copies(keyspace: "risime_test", dry_run: false)

    # The copy is what a fresh install of the sender replays.
    {:ok, events, false} = Messaging.fetch_events(a.id, nil)
    assert copy in Enum.map(events, & &1.event_id)
  end
end
