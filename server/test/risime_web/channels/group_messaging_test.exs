defmodule RisiMeWeb.GroupMessagingTest do
  @moduledoc "Contract v1.9 §12.1 filter, §12.7 receipts, §12.9 group msg:send and typing, §12.6 blobs."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers

  alias RisiMe.Messaging
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  setup do
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    a_dev = groups_device!(a)
    b_dev = groups_device!(b)
    c_dev = groups_device!(c)

    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, c.user.id]}

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    api(
      :post,
      "/api/v1/mls/groups/#{id}/commit",
      a.token,
      create_commit([a.user.id, b.user.id, c.user.id], {a.user.id, a_dev}),
      a_dev
    )
    |> assert_status(200)

    %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, id: id}
  end

  defp join!(user, device, payload \\ %{}) do
    params =
      if device,
        do: %{"token" => user.token, "device_id" => device},
        else: %{"token" => user.token}

    {:ok, sock} = connect(UserSocket, params)
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, payload)
    {chan, reply}
  end

  defp call(chan, event, payload) do
    ref = push(chan, event, payload)

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply")
    end
  end

  defp send_payload(id, extra \\ %{}) do
    Map.merge(
      %{
        "client_msg_id" => Ecto.UUID.generate(),
        "conversation_id" => id,
        "ciphertext" => b64(),
        "generation" => 1,
        "epoch" => 1,
        "client_ts" => "2026-10-06T08:15:30.123Z"
      },
      extra
    )
  end

  test "group msg:send: fan-out, idempotency, order of checks", ctx do
    %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
    {chan, _} = join!(a, a_dev)
    p = send_payload(id)

    assert {:ok, %{message_id: mid, conversation_id: ^id}} = call(chan, "msg:send", p)
    assert {:ok, %{message_id: ^mid}} = call(chan, "msg:send", p)

    for u <- [a, b, c] do
      ev = last_event(u.user.id, "message")
      assert ev["event_id"] == mid and ev["data"]["from_device"] == a_dev
      refute Map.has_key?(ev["data"], "to")
    end

    assert {:error, %{reason: "bad_request"}} =
             call(chan, "msg:send", send_payload(id, %{"to" => b.user.id}))

    assert {:error, %{reason: "bad_request"}} =
             call(chan, "msg:send", send_payload("dm:x", %{}))

    assert {:error, %{reason: "not_member"}} =
             call(chan, "msg:send", send_payload("grp:" <> Ecto.UUID.generate()))

    assert {:error, %{reason: "not_member"}} = call(chan, "msg:send", send_payload("grp:nope"))

    body = send_payload(id) |> Map.delete("ciphertext") |> Map.put("body", "hi")
    assert {:error, %{reason: "e2ee_required"}} = call(chan, "msg:send", body)

    assert {:error, %{reason: "stale_epoch"}} =
             call(chan, "msg:send", send_payload(id, %{"epoch" => 0}))

    big = Base.encode64(:crypto.strong_rand_bytes(24 * 1024 + 1))

    assert {:error, %{reason: "too_long"}} =
             call(chan, "msg:send", send_payload(id, %{"ciphertext" => big}))

    # A retry after removal still gets its original reply (resend check first).
    {204, nil} = api(:delete, "/api/v1/groups/#{id}/members/#{b.user.id}", a.token, nil, a_dev)
    {bchan, _} = join!(b, ctx.b_dev)
    assert {:error, %{reason: "not_member"}} = call(bchan, "msg:send", send_payload(id))
  end

  test "sockets without the groups capability never see grp: traffic", ctx do
    %{a: a, b: b, a_dev: a_dev, id: id} = ctx
    # A legacy socket (no device_id) of b, and a groups socket.
    {legacy, reply} = join!(b, nil)

    assert Enum.all?(
             reply.events,
             &(not String.starts_with?(
                 &1.data["conversation_id"] || &1.data["group_id"] || "",
                 "grp:"
               ))
           )

    {_groups, reply} = join!(b, ctx.b_dev)
    assert length(reply.events) == 3

    {chan, _} = join!(a, a_dev)
    {:ok, %{message_id: mid}} = call(chan, "msg:send", send_payload(id))
    {:ok, _} = call(chan, "typing", %{"conversation_id" => id, "typing" => true})

    topic = "inbox:" <> b.user.id

    assert_receive %Phoenix.Socket.Message{
      topic: ^topic,
      event: "event",
      payload: %{event_id: ^mid}
    }

    assert_receive %Phoenix.Socket.Message{
      topic: ^topic,
      event: "signal",
      payload: %{kind: "typing"}
    }

    refute_receive %Phoenix.Socket.Message{
                     topic: ^topic,
                     event: "event",
                     payload: %{event_id: ^mid}
                   },
                   100

    # A page made only of filtered events is skipped (the cursor still advances).
    for _ <- 1..3, do: {:ok, _} = call(chan, "msg:send", send_payload(id))

    {:ok, dm} =
      Messaging.send(a.user.id, %{
        "client_msg_id" => Ecto.UUID.generate(),
        "to" => b.user.id,
        "body" => "dm"
      })

    {:ok, %{events: events, has_more: false}} =
      call(legacy, "sync", %{"since" => nil, "limit" => 2})

    assert Enum.map(events, & &1.event_id) == [dm.message_id]

    # The device losing `groups` mid-session (here: removed) stops receiving at once.
    {204, nil} = api(:delete, "/api/v1/me/devices/#{ctx.b_dev}", b.token)
    {:ok, %{message_id: later}} = call(chan, "msg:send", send_payload(id))
    refute_receive %Phoenix.Socket.Message{topic: ^topic, payload: %{event_id: ^later}}, 100
  end

  test "group typing: not_member, bad_request, 1 per 3 s", ctx do
    %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
    {chan, _} = join!(a, a_dev)
    Phoenix.PubSub.subscribe(RisiMe.PubSub, Messaging.topic(b.user.id))

    assert {:ok, _} = call(chan, "typing", %{"conversation_id" => id, "typing" => true})

    assert_receive {:signal,
                    %{kind: "typing", data: %{"conversation_id" => ^id, "typing" => true}}}

    assert {:ok, _} = call(chan, "typing", %{"conversation_id" => id, "typing" => true})
    refute_receive {:signal, %{kind: "typing"}}, 50
    assert {:ok, _} = call(chan, "typing", %{"conversation_id" => id, "typing" => false})
    assert_receive {:signal, %{kind: "typing", data: %{"typing" => false}}}

    assert {:error, %{reason: "bad_request"}} =
             call(chan, "typing", %{"conversation_id" => id, "to" => c.user.id, "typing" => true})

    assert {:error, %{reason: "not_member"}} =
             call(chan, "typing", %{
               "conversation_id" => "grp:" <> Ecto.UUID.generate(),
               "typing" => true
             })
  end

  test "acks become aggregated group_receipts; receipts GET for the sender only", ctx do
    %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
    {chan, _} = join!(a, a_dev)
    {:ok, %{message_id: mid}} = call(chan, "msg:send", send_payload(id))

    :ok = Messaging.ack(b.user.id, [mid], "delivered")
    # The first change goes out at once (nothing sent for 10 s), with no status events.
    r = last_event(a.user.id, "group_receipt")["data"]
    assert %{"delivered" => 1, "read" => 0, "of" => 2, "all_delivered" => false} = r
    assert events(a.user.id, "status") == []

    # c's delivered flips all_delivered: sent at once although within the window.
    :ok = Messaging.ack(c.user.id, [mid], "delivered")

    assert %{"delivered" => 2, "all_delivered" => true} =
             last_event(a.user.id, "group_receipt")["data"]

    # A read inside the window is coalesced, then sent once the window ends.
    :ok = Messaging.ack(b.user.id, [mid], "read")
    :ok = Messaging.ack(b.user.id, [mid], "read")
    n = length(events(a.user.id, "group_receipt"))
    Process.sleep(300)
    assert length(events(a.user.id, "group_receipt")) == n + 1
    assert %{"read" => 1, "all_read" => false} = last_event(a.user.id, "group_receipt")["data"]

    {200, reply} = api(:get, "/api/v1/groups/#{id}/messages/#{mid}/receipts", a.token)
    assert reply["of"] == 2
    rows = Map.new(reply["receipts"], &{&1["user_id"], &1})
    assert rows[b.user.id]["read_at"] && rows[b.user.id]["delivered_at"]
    assert rows[c.user.id]["delivered_at"] && rows[c.user.id]["read_at"] == nil

    assert {404, _} = api(:get, "/api/v1/groups/#{id}/messages/#{mid}/receipts", b.token)
  end

  describe "blobs (§12.6)" do
    test "upload, read rules, refs in commits, delete, cleanup", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
      bytes = :crypto.strong_rand_bytes(100_000)
      path = "/api/v1/blobs?purpose=mls&conversation_id=#{id}"

      {201, up} = api_raw(path, a.token, bytes)
      assert up["size"] == 100_000 and up["sha256"] == Base.encode64(:crypto.hash(:sha256, bytes))
      assert String.starts_with?(RisiMe.Blobs.blob_dir(), System.tmp_dir!())

      stranger = logged_in_user()
      assert {404, _} = api_raw(path, stranger.token, bytes)

      assert {400, _} =
               api_raw("/api/v1/blobs?purpose=media&conversation_id=#{id}", a.token, bytes)

      assert {413, %{"error" => %{"code" => "too_large"}}} =
               api_raw(path, a.token, :binary.copy(<<0>>, 2 * 1024 * 1024 + 1))

      get = fn token ->
        conn =
          Phoenix.ConnTest.build_conn()
          |> Plug.Conn.put_req_header("authorization", "Bearer " <> token)
          |> Phoenix.ConnTest.dispatch(@endpoint, :get, "/api/v1/blobs/#{up["blob_id"]}")

        {conn.status, conn.resp_body}
      end

      assert {200, ^bytes} = get.(b.token)
      assert {404, _} = get.(stranger.token)

      # A Welcome by reference lets the added user read it; only the uploader's blobs qualify.
      d = logged_in_user()
      befriend!(a, d)
      d_dev = groups_device!(d)

      {200, %{"group" => %{"pending" => [op]}}} =
        api(:post, "/api/v1/groups/#{id}/members", a.token, %{"user_ids" => [d.user.id]}, a_dev)

      commit = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => nil,
        "commit_ref" => Map.take(up, ~w(blob_id size sha256)),
        "welcome_ref" => Map.take(up, ~w(blob_id size sha256)),
        "added" => [ref(d.user.id, d_dev)],
        "op_id" => op["op_id"]
      }

      {201, b_up} = api_raw(path, b.token, bytes)

      assert {400, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 a.token,
                 %{commit | "commit_ref" => Map.take(b_up, ~w(blob_id size sha256))},
                 a_dev
               )

      assert {400, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 a.token,
                 %{
                   commit
                   | "commit_ref" => %{up | "size" => 1} |> Map.take(~w(blob_id size sha256))
                 },
                 a_dev
               )

      assert {200, %{"epoch" => 2}} =
               api(:post, "/api/v1/mls/groups/#{id}/commit", a.token, commit, a_dev)

      ev = last_event(c.user.id, "mls_commit")["data"]
      assert ev["commit"] == nil and ev["commit_ref"]["blob_id"] == up["blob_id"]
      w = last_event(d.user.id, "mls_welcome")["data"]
      assert w["welcome"] == nil and w["welcome_ref"]["blob_id"] == up["blob_id"]
      {200, page} = api(:get, "/api/v1/mls/groups/#{id}/commits?since_epoch=1", c.token)
      assert [%{"commit" => nil, "commit_ref" => %{"blob_id" => _}}] = page["commits"]

      # d (now active anyway) can read; delete is owner-only and idempotent.
      assert {200, _} = get.(d.token)
      assert {404, _} = api(:delete, "/api/v1/blobs/#{up["blob_id"]}", b.token)
      assert {204, _} = api(:delete, "/api/v1/blobs/#{up["blob_id"]}", a.token)
      assert {204, _} = api(:delete, "/api/v1/blobs/#{up["blob_id"]}", a.token)
      assert {404, _} = get.(a.token)

      # TTL cleanup removes the row and the file.
      assert RisiMe.Blobs.cleanup(DateTime.add(DateTime.utc_now(), 31, :day)) >= 1
      assert {404, _} = get.(b.token)
    end
  end
end
