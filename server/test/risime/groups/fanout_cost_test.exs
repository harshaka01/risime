defmodule RisiMe.Groups.FanoutCostTest do
  @moduledoc """
  Fan-out cost of a full group (contract v1.9 caps: 256 users): the epoch-0 commit, one group
  message, and 255 acks with aggregated receipts. Prints the timings (see docs/status/server.md)
  and asserts loose bounds so a regression to per-member round trips fails the gate.
  """
  use RisiMe.DataCase, async: false

  import RisiMe.GroupHelpers
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.{Groups, Messaging}
  alias RisiMe.Groups.Commit

  setup :with_attestation_key

  @members 256

  test "256 members: create commit, one message, 255 acks" do
    creator = fast_user!("Creator")
    c_dev = groups_device!(creator)
    now = DateTime.utc_now()

    others =
      for i <- 1..(@members - 1) do
        u = fast_user!("Member #{i}")
        RisiMe.Social.make_friends!(creator.id, u.id)

        Repo.insert_all(RisiMe.Devices.Device, [
          %{
            user_id: u.id,
            device_id: Ecto.UUID.generate(),
            platform: "android",
            mls_signature_key: :crypto.strong_rand_bytes(32),
            mls_attestation: "x",
            capabilities: ["groups"],
            last_seen_at: now,
            inserted_at: now,
            updated_at: now
          }
        ])

        u.id
      end

    {t_create, {:ok, :created, %{id: id}}} =
      :timer.tc(fn ->
        Groups.create(creator.id, c_dev, %{
          "client_group_id" => Ecto.UUID.generate(),
          "member_ids" => others
        })
      end)

    body = create_commit([creator.id | others], {creator.id, c_dev})
    {t_commit, {:ok, 1}} = :timer.tc(fn -> Commit.commit(creator.id, c_dev, id, body) end)

    send = %{
      "client_msg_id" => Ecto.UUID.generate(),
      "conversation_id" => id,
      "ciphertext" => b64(1024),
      "generation" => 1,
      "epoch" => 1
    }

    {t_send, {:ok, %{message_id: mid}}} =
      :timer.tc(fn -> Messaging.send(creator.id, send, device_id: c_dev) end)

    {t_acks, _} =
      :timer.tc(fn ->
        others
        |> Task.async_stream(&Messaging.ack(&1, [mid], "delivered"), max_concurrency: 16)
        |> Stream.run()
      end)

    receipts = events(creator.id, "group_receipt")
    final = List.last(receipts)["data"]

    IO.puts(
      "\n[fanout 256] create #{div(t_create, 1000)} ms, epoch-0 commit (#{@members} inbox users, " <>
        "255 welcomes) #{div(t_commit, 1000)} ms, message fan-out #{div(t_send, 1000)} ms, " <>
        "255 acks #{div(t_acks, 1000)} ms → #{length(receipts)} group_receipt event(s)"
    )

    assert length(events(List.last(others), "message")) == 1
    assert final["all_delivered"] and final["delivered"] == 255 and final["of"] == 255
    # Aggregated: a handful of receipts, never one per ack.
    assert length(receipts) <= 3
    assert t_send < 5_000_000 and t_commit < 10_000_000
  end
end
