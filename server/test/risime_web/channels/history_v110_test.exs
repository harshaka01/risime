defmodule RisiMeWeb.HistoryV110Test do
  @moduledoc """
  Contract v1.10 §13 (decision 043), the server half of §13.6: sender copies, ignored self-acks,
  `history_before`, and the `mls` blob quota. The backfill is in
  `test/risime/backfill_sender_copies_test.exs`.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.{Accounts, Messaging, Repo}
  alias RisiMe.Messaging.Store
  alias RisiMeWeb.{InboxChannel, UserSocket}

  # Delegates to the real store and reports each inbox write to the test, in order.
  defmodule RecordingStore do
    @behaviour RisiMe.Messaging.Store
    alias RisiMe.Messaging.Store.Cassandra

    @impl true
    def append_event(user_id, event) do
      if pid = Application.get_env(:risime, :recording_store_pid),
        do: send(pid, {:appended, user_id, event.event_id})

      Cassandra.append_event(user_id, event)
    end

    # The DM path writes both rows in one request; it broadcasts in the same order.
    @impl true
    def append_event_to_all(user_ids, event) do
      if pid = Application.get_env(:risime, :recording_store_pid),
        do: for(u <- user_ids, do: send(pid, {:appended, u, event.event_id}))

      Cassandra.append_event_to_all(user_ids, event)
    end

    @impl true
    defdelegate get_sent(s, c), to: Cassandra
    @impl true
    defdelegate claim_send(s, c, x), to: Cassandra
    @impl true
    defdelegate put_message(m), to: Cassandra
    @impl true
    defdelegate get_message(id), to: Cassandra
    @impl true
    defdelegate compare_and_set_status(id, e, n), to: Cassandra
    @impl true
    defdelegate list_events(u, s, l, c), to: Cassandra
    @impl true
    defdelegate append_call_signal(us, e), to: Cassandra
    @impl true
    defdelegate put_group_receipt(m, u, d, r), to: Cassandra
    @impl true
    defdelegate list_group_receipts(m), to: Cassandra
    @impl true
    defdelegate backfill_sender_copies(k, o), to: Cassandra
    @impl true
    defdelegate health(), to: Cassandra
    @impl true
    defdelegate tombstone_message(m, b, a, t), to: Cassandra
    @impl true
    defdelegate get_event(u, e), to: Cassandra
    @impl true
    defdelegate delete_events(u, e), to: Cassandra
    @impl true
    defdelegate put_message_ref(m, u, e, r), to: Cassandra
    @impl true
    defdelegate list_message_refs(m, u), to: Cassandra
    @impl true
    defdelegate delete_message_refs(m, u), to: Cassandra
    @impl true
    defdelegate delete_group_receipts(m), to: Cassandra
    @impl true
    defdelegate clear_conversation(u, c, t, k), to: Cassandra
  end

  setup :with_attestation_key

  setup do
    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    befriend!(a, b)
    %{a: a, b: b}
  end

  defp msg(to, body \\ "hello"),
    do: %{"client_msg_id" => Ecto.UUID.generate(), "to" => to, "body" => body}

  defp cql!(statement, params) do
    {:ok, page} = Xandra.Cluster.execute(Store.Cassandra.Cluster, statement, params)
    page
  end

  defp rows_with(user_id, event_id) do
    {:ok, events, _} = Messaging.fetch_events(user_id, nil)
    Enum.filter(events, &(&1.event_id == event_id))
  end

  describe "sender copy (§13.1)" do
    test "both inboxes hold the event with the same event_id and payload; only the recipient is pushed",
         %{a: a, b: b} do
      test_push!()

      for {u, prefix} <- [{a, "fcm-a"}, {b, "fcm-b"}] do
        {:ok, nil} =
          RisiMe.Devices.register(u.user.id, Ecto.UUID.generate(), %{
            "platform" => "android",
            "push_token" => push_token(prefix)
          })
      end

      [a_token] = RisiMe.Devices.push_tokens(a.user.id)
      [b_token] = RisiMe.Devices.push_tokens(b.user.id)

      {:ok, %{message_id: id}} = Messaging.send(a.user.id, msg(b.user.id))

      assert [ev_a] = rows_with(a.user.id, id)
      assert [ev_b] = rows_with(b.user.id, id)
      assert ev_a == ev_b
      assert ev_a.kind == "message" and ev_a.data["from"] == a.user.id
      assert ev_a.data["body"] == "hello"

      assert_receive {:push, ^b_token, _}, 1_000
      refute_receive {:push, ^a_token, _}, 300
    end

    test "the sender's copy is published before the recipient's event", %{a: a, b: b} do
      Application.put_env(:risime, :message_store, RecordingStore)
      Application.put_env(:risime, :recording_store_pid, self())

      on_exit(fn ->
        Application.delete_env(:risime, :message_store)
        Application.delete_env(:risime, :recording_store_pid)
      end)

      {:ok, %{message_id: id}} = Messaging.send(a.user.id, msg(b.user.id))
      assert appended() == [{a.user.id, id}, {b.user.id, id}]

      # Reactions follow the same order.
      {:ok, %{message_id: rid}} =
        Messaging.send(a.user.id, %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => b.user.id,
          "reaction" => %{"target" => id, "emoji" => "👍", "op" => "add"}
        })

      assert appended() == [{a.user.id, rid}, {b.user.id, rid}]
    end

    defp appended(acc \\ []) do
      receive do
        {:appended, u, e} -> appended([{u, e} | acc])
      after
        0 -> Enum.reverse(acc)
      end
    end

    test "the live order on the sender's topic: copy, then the status of the recipient's ack",
         %{a: a, b: b} do
      Messaging.subscribe(a.user.id)
      {:ok, %{message_id: id}} = Messaging.send(a.user.id, msg(b.user.id))
      :ok = Messaging.ack(b.user.id, [id], "delivered")
      assert_receive {:inbox_event, %{event_id: ^id, kind: "message"}}
      assert_receive {:inbox_event, %{kind: "status", data: %{"message_id" => ^id}}}
    end

    test "an idempotent resend with the message index missing rewrites both rows (one each)",
         %{a: a, b: b} do
      params = msg(b.user.id)
      {:ok, %{message_id: id} = reply} = Messaging.send(a.user.id, params)
      [original] = rows_with(b.user.id, id)

      # As if the node died after claim_send: no index, no inbox rows.
      cql!("DELETE FROM message_index WHERE message_id = ?", [{"timeuuid", id}])

      for u <- [a.user.id, b.user.id],
          do:
            cql!("DELETE FROM inbox_events WHERE user_id = ? AND event_id = ?", [
              {"uuid", u},
              {"timeuuid", id}
            ])

      assert rows_with(a.user.id, id) == []
      assert {:ok, ^reply} = Messaging.send(a.user.id, params)
      assert [^original] = rows_with(a.user.id, id)
      assert [^original] = rows_with(b.user.id, id)

      # A further resend (index present) changes nothing.
      assert {:ok, ^reply} = Messaging.send(a.user.id, params)
      assert [_] = rows_with(a.user.id, id)
      assert [_] = rows_with(b.user.id, id)
    end
  end

  describe "self-acks are ignored (§13.1)" do
    test "a sender's DM ack makes no status event and leaves the message at sent", %{
      a: a,
      b: b
    } do
      {:ok, %{message_id: id}} = Messaging.send(a.user.id, msg(b.user.id))

      for status <- ~w(delivered read), do: :ok = Messaging.ack(a.user.id, [id], status)

      assert {:ok, %{status: "sent"}} = Store.impl().get_message(id)

      for u <- [a.user.id, b.user.id] do
        {:ok, events, _} = Messaging.fetch_events(u, nil)
        assert Enum.map(events, & &1.kind) == ["message"]
      end
    end

    test "a sender's group ack writes no receipt row and no group_receipt event", %{a: a, b: b} do
      a_dev = groups_device!(a)
      groups_device!(b)
      clear_legacy!()

      body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]}

      %{"group" => %{"id" => id}} =
        api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

      api(
        :post,
        "/api/v1/mls/groups/#{id}/commit",
        a.token,
        create_commit([a.user.id, b.user.id], {a.user.id, a_dev}),
        a_dev
      )
      |> assert_status(200)

      {:ok, %{message_id: mid}} =
        Messaging.send(
          a.user.id,
          %{
            "client_msg_id" => Ecto.UUID.generate(),
            "conversation_id" => id,
            "ciphertext" => b64(),
            "generation" => 1,
            "epoch" => 1
          },
          device_id: a_dev
        )

      for status <- ~w(delivered read), do: :ok = Messaging.ack(a.user.id, [mid], status)

      assert Store.impl().list_group_receipts(mid) == []
      assert events(a.user.id, "group_receipt") == []
    end
  end

  describe "history_before (§13.2)" do
    defp join_as(token, device_id, payload \\ %{}) do
      params = if device_id, do: %{"token" => token, "device_id" => device_id}, else: %{}
      params = Map.put(params, "token", token)
      {:ok, sock} = connect(UserSocket, params)
      user_id = sock.assigns.user_id
      {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user_id, payload)
      {reply, chan}
    end

    defp first_seen(user_id, device_id) do
      Repo.one(
        from i in "app_instances",
          where:
            i.user_id == type(^user_id, :binary_id) and
              i.instance_key == ^("device:" <> device_id),
          select: type(i.first_seen_at, :utc_datetime_usec)
      )
    end

    test "set on the first connect, stable across reconnects, the same on every sync page", %{
      a: a,
      b: b
    } do
      dev = Ecto.UUID.generate()
      for i <- 1..3, do: {:ok, _} = Messaging.send(b.user.id, msg(a.user.id, "m#{i}"))

      {%{history_before: hb} = first, chan} = join_as(a.token, dev, %{"limit" => 1})
      assert hb =~ ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
      assert first.has_more
      assert Messaging.iso(first_seen(a.user.id, dev)) == hb

      ref = push(chan, "sync", %{"since" => List.last(first.events).event_id, "limit" => 1})
      assert_reply ref, :ok, %{history_before: ^hb, has_more: true} = p2
      ref = push(chan, "sync", %{"since" => List.last(p2.events).event_id, "limit" => 1})
      assert_reply ref, :ok, %{history_before: ^hb, has_more: false}

      Process.sleep(5)
      {%{history_before: ^hb}, _} = join_as(a.token, dev)
    end

    test "null without a device_id and for pre-v1.10 rows", %{a: a} do
      {%{history_before: nil}, _} = join_as(a.token, nil)

      dev = Ecto.UUID.generate()
      {%{history_before: hb}, _} = join_as(a.token, dev)
      assert is_binary(hb)

      # A census row from before the migration: first_seen_at null, no reset flag.
      Repo.update_all(
        from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
        set: [first_seen_at: nil]
      )

      {%{history_before: nil}, _} = join_as(a.token, dev)
      assert first_seen(a.user.id, dev) == nil
    end

    test "reset by a device removal: the next connect sets a new value", %{a: a} do
      dev = Ecto.UUID.generate()

      {:ok, nil} =
        RisiMe.Devices.register(a.user.id, dev, %{
          "platform" => "android",
          "push_token" => "t-#{dev}"
        })

      {%{history_before: hb1}, _} = join_as(a.token, dev)

      # DELETE /me/devices/{id}
      Process.sleep(5)
      {204, _} = api(:delete, "/api/v1/me/devices/#{dev}", a.token)
      {%{history_before: hb2}, _} = join_as(a.token, dev)
      assert hb2 > hb1

      # A pre-v1.10 row is reset too (the removal wipes MLS state).
      {:ok, nil} =
        RisiMe.Devices.register(a.user.id, dev, %{
          "platform" => "android",
          "push_token" => "t-#{dev}"
        })

      Repo.update_all(
        from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
        set: [first_seen_at: nil]
      )

      {204, _} = api(:delete, "/api/v1/me/devices/#{dev}", a.token)
      {%{history_before: hb3}, _} = join_as(a.token, dev)
      assert is_binary(hb3)
    end

    test "reset by logout (same device_id, new login)", %{a: a} do
      dev = Ecto.UUID.generate()

      {204, _} =
        api(:put, "/api/v1/me/devices/#{dev}", a.token, %{
          "platform" => "android",
          "push_token" => "t-#{dev}"
        })

      {%{history_before: hb1}, _} = join_as(a.token, dev)
      Process.sleep(5)
      {204, _} = api(:post, "/api/v1/auth/logout", a.token)

      :ok = Accounts.request_otp(a.entry.phone, a.entry.email)
      {:ok, token2, _} = Accounts.verify_otp(a.entry.phone, receive_code(), "test")

      {%{history_before: hb2}, _} = join_as(token2, dev)
      assert hb2 > hb1
      {%{history_before: ^hb2}, _} = join_as(token2, dev)
    end
  end

  describe "mls blob quota (§13.4)" do
    setup %{a: a, b: b} do
      a_dev = groups_device!(a)
      groups_device!(b)
      clear_legacy!()
      body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]}

      %{"group" => %{"id" => id}} =
        api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

      Application.put_env(:risime, :mls_blob_quota, 150_000)
      on_exit(fn -> Application.delete_env(:risime, :mls_blob_quota) end)
      %{id: id}
    end

    defp upload(path, token, bytes, declared) do
      conn =
        Phoenix.ConnTest.build_conn()
        |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
        |> Plug.Conn.put_req_header("content-type", "application/octet-stream")
        |> Plug.Conn.put_req_header("content-length", Integer.to_string(declared))
        |> Phoenix.ConnTest.dispatch(@endpoint, :post, path, bytes)

      {conn.status, Jason.decode!(conn.resp_body)}
    end

    test "413 quota_exceeded from Content-Length before the body is read, with used and limit",
         %{a: a, id: id} do
      path = "/api/v1/blobs?purpose=mls&conversation_id=#{id}"
      bytes = :crypto.strong_rand_bytes(100_000)
      {201, _} = upload(path, a.token, bytes, 100_000)

      # The declared length is over the quota while the actual body is tiny: refused unread.
      assert {413, %{"error" => %{"code" => "quota_exceeded"} = err}} =
               upload(path, a.token, "tiny", 60_000)

      assert err["used"] == 100_000 and err["limit"] == 150_000

      # Membership comes first; too_large before quota.
      stranger = logged_in_user()
      assert {404, _} = upload(path, stranger.token, "tiny", 60_000)

      assert {413, %{"error" => %{"code" => "too_large"}}} =
               upload(path, a.token, "tiny", 2 * 1024 * 1024 + 1)

      # A lying Content-Length is aborted mid-stream (v1.11 §14.2) before the quota recheck.
      assert {413, %{"error" => %{"code" => "too_large"}}} =
               upload(path, a.token, :crypto.strong_rand_bytes(60_000), 10)

      # The quota frees up when blobs are deleted.
      [blob_id] =
        Repo.all(
          from x in "blobs",
            where: x.owner == type(^a.user.id, :binary_id),
            select: type(x.id, :binary_id)
        )

      {204, nil} = api(:delete, "/api/v1/blobs/#{blob_id}", a.token)
      assert {201, _} = upload(path, a.token, :crypto.strong_rand_bytes(60_000), 60_000)
    end
  end
end
