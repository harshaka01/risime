defmodule RisiMeWeb.ProfilePhotosV117Test do
  @moduledoc """
  Contract v1.17 §18.3 and §18.4: the `avatar` blob purpose (no conversation, 512 KiB, 3/h and
  10/day, one current avatar per user with 24 h for the previous one, the reader rule, DELETE)
  and the optional `silent: true` on the e2ee `msg:send` and the `message` event (stored,
  replayed, never pushed).
  """
  use RisiMeWeb.ChannelCase, async: false

  import Ecto.Query
  import Plug.Conn, only: [put_req_header: 3, get_resp_header: 2]
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, e2ee_group!: 2, mls_device!: 1]
  import RisiMe.GroupHelpers

  alias RisiMe.{Blobs, Repo}
  alias RisiMe.Groups.{Group, Member}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  setup do
    dir = Path.join(System.tmp_dir!(), "risime-blobs-v117-#{System.unique_integer([:positive])}")
    prev = Application.get_env(:risime, :blob_dir)
    Application.put_env(:risime, :blob_dir, dir)

    on_exit(fn ->
      Application.put_env(:risime, :blob_dir, prev)
      File.rm_rf(dir)
    end)

    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    %{a: a, b: b, c: c}
  end

  defp avatar_params(extra \\ %{}),
    do: Map.merge(%{"purpose" => "avatar", "client_blob_id" => Ecto.UUID.generate()}, extra)

  defp upload(token, params, bytes) do
    conn =
      Phoenix.ConnTest.build_conn()
      |> put_req_header("authorization", "Bearer " <> token)
      |> put_req_header("content-type", "application/octet-stream")
      |> put_req_header("content-length", to_string(byte_size(bytes)))
      |> Phoenix.ConnTest.dispatch(
        @endpoint,
        :post,
        "/api/v1/blobs?" <> URI.encode_query(params),
        bytes
      )

    {conn.status, if(conn.resp_body == "", do: nil, else: Jason.decode!(conn.resp_body)), conn}
  end

  defp up!(token, params \\ avatar_params(), bytes \\ :crypto.strong_rand_bytes(1000)) do
    {status, body, _} = upload(token, params, bytes)
    assert status == 201, "expected 201, got #{status}: #{inspect(body)}"
    body
  end

  defp get_status(token, id, method \\ :get) do
    Phoenix.ConnTest.build_conn()
    |> put_req_header("authorization", "Bearer " <> token)
    |> Phoenix.ConnTest.dispatch(@endpoint, method, "/api/v1/blobs/#{id}")
    |> Map.fetch!(:status)
  end

  defp expires_at(id) do
    Repo.one(
      from b in "blobs",
        where: b.id == type(^id, :binary_id),
        select: type(b.expires_at, :utc_datetime_usec)
    )
  end

  defp current_avatars(owner) do
    Repo.all(
      from b in "blobs",
        where:
          b.owner == type(^owner, :binary_id) and b.purpose == "avatar" and
            is_nil(b.expires_at) and is_nil(b.deleted_at),
        select: type(b.id, :binary_id)
    )
  end

  # Rows that count as avatar uploads (rate) without files.
  defp insert_uploads(owner, n, at) do
    rows =
      for _ <- 1..n,
          do: %{
            id: Ecto.UUID.dump!(Ecto.UUID.generate()),
            owner: Ecto.UUID.dump!(owner),
            purpose: "avatar",
            conversation_id: nil,
            size: 1,
            sha256: :crypto.hash(:sha256, ""),
            expires_at: DateTime.add(DateTime.utc_now(), 1, :day),
            inserted_at: at
          }

    Repo.insert_all("blobs", rows)
  end

  defp group_with!(members) do
    id = "grp:" <> Ecto.UUID.generate()
    now = DateTime.utc_now()

    Repo.insert!(%Group{
      id: id,
      client_group_id: Ecto.UUID.generate(),
      state: "active",
      created_at: now
    })

    for {user, state} <- members do
      Repo.insert!(%Member{
        group_id: id,
        user_id: user.user.id,
        role: "member",
        state: state,
        inserted_at: now
      })
    end

    id
  end

  defp set_state!(group_id, user, state) do
    Repo.update_all(
      from(m in Member, where: m.group_id == ^group_id and m.user_id == ^user.user.id),
      set: [state: state]
    )
  end

  describe "avatar upload (§18.3)" do
    test "no conversation_id: 201 with expires_at null; one present is 400", %{a: a} do
      bytes = :crypto.strong_rand_bytes(61_456)
      reply = up!(a.token, avatar_params(), bytes)

      assert reply["expires_at"] == nil
      assert reply["size"] == 61_456
      assert reply["sha256"] == Base.encode64(:crypto.hash(:sha256, bytes))
      assert expires_at(reply["blob_id"]) == nil

      grp = "grp:" <> Ecto.UUID.generate()

      assert {400, %{"error" => %{"code" => "bad_request"}}, _} =
               upload(a.token, avatar_params(%{"conversation_id" => grp}), "x")

      # client_blob_id is required, as for media and icon.
      assert {400, _, _} = upload(a.token, %{"purpose" => "avatar"}, "x")
      # Not counted in usage.
      {200, usage} = api(:get, "/api/v1/blobs/usage", a.token)
      refute Map.has_key?(usage, "avatar")
    end

    test "the 512 KiB cap is exact (413 above)", %{a: a} do
      up!(a.token, avatar_params(), :crypto.strong_rand_bytes(512 * 1024))

      assert {413, %{"error" => %{"code" => "too_large"}}, _} =
               upload(a.token, avatar_params(), :crypto.strong_rand_bytes(512 * 1024 + 1))
    end

    test "3 per hour and 10 per day; replays are free", %{a: a, b: b} do
      first_params = avatar_params()
      bytes = :crypto.strong_rand_bytes(100)
      first = up!(a.token, first_params, bytes)
      up!(a.token)
      up!(a.token)

      {429, %{"error" => %{"code" => "rate_limited"}}, conn} =
        upload(a.token, avatar_params(), "x")

      [retry] = get_resp_header(conn, "retry-after")
      assert String.to_integer(retry) in 1..3601

      # An idempotent replay of an earlier upload doesn't count and still answers 200.
      # (It was replaced since, so its reply now shows the 24-h expiry.)
      first_id = first["blob_id"]
      assert {200, %{"blob_id" => ^first_id}, _} = upload(a.token, first_params, bytes)

      # The day: 9 uploads two hours ago + 1 now = 10; the 11th is refused.
      insert_uploads(b.user.id, 9, DateTime.add(DateTime.utc_now(), -2, :hour))
      up!(b.token)
      assert {429, _, _} = upload(b.token, avatar_params(), "x")
    end

    test "one current avatar: the newest is current, the previous gets 24 h, a replay of an old one never becomes current",
         %{a: a} do
      p1 = avatar_params()
      bytes1 = :crypto.strong_rand_bytes(10)
      %{"blob_id" => id1} = up!(a.token, p1, bytes1)
      assert current_avatars(a.user.id) == [id1]

      %{"blob_id" => id2} = up!(a.token)
      assert current_avatars(a.user.id) == [id2]
      exp = expires_at(id1)
      diff = DateTime.diff(exp, DateTime.utc_now(), :second)
      assert diff in (24 * 3600 - 60)..(24 * 3600)

      # The replay answers 200 with its (now set) expiry and changes nothing.
      assert {200, %{"blob_id" => ^id1, "expires_at" => e}, _} = upload(a.token, p1, bytes1)
      assert is_binary(e)
      assert current_avatars(a.user.id) == [id2]
      assert expires_at(id1) == exp

      # The replaced avatar is still readable until it expires (late receivers).
      assert get_status(a.token, id1) == 200

      # After the expiry the sweep removes it.
      Repo.update_all(from(b in "blobs", where: b.id == type(^id1, :binary_id)),
        set: [expires_at: DateTime.add(DateTime.utc_now(), -1, :second)]
      )

      assert get_status(a.token, id1) == 404
      assert Blobs.cleanup() >= 1
      assert expires_at(id1) == nil and current_avatars(a.user.id) == [id2]
    end

    test "concurrent uploads: exactly one current, the last committed row", %{a: a} do
      tasks =
        for _ <- 1..3 do
          Task.async(fn ->
            {status, body, _} = upload(a.token, avatar_params(), :crypto.strong_rand_bytes(5000))
            {status, body}
          end)
        end

      results = Task.await_many(tasks, 10_000)
      assert Enum.all?(results, fn {s, _} -> s == 201 end)

      [current] = current_avatars(a.user.id)

      last =
        Repo.one(
          from b in "blobs",
            where: b.owner == type(^a.user.id, :binary_id) and b.purpose == "avatar",
            order_by: [desc: b.inserted_at],
            limit: 1,
            select: type(b.id, :binary_id)
        )

      assert current == last
    end

    test "DELETE removes it at once; then nothing is current", %{a: a} do
      %{"blob_id" => id} = up!(a.token)
      {204, nil} = api(:delete, "/api/v1/blobs/#{id}", a.token)
      assert get_status(a.token, id) == 404
      assert current_avatars(a.user.id) == []
    end
  end

  describe "avatar readers (§18.3, server S4)" do
    test "owner, friend, blocked friend, co-members, ex-co-member, stranger", ctx do
      %{a: a, b: b, c: c} = ctx
      d = logged_in_user(display_name: "Dilan")
      e = logged_in_user(display_name: "Eranga")
      %{"blob_id" => id} = up!(a.token)

      # Owner, GET and HEAD.
      assert get_status(a.token, id) == 200
      assert get_status(a.token, id, :head) == 200

      # Friend with no block.
      befriend!(a, b)
      assert get_status(b.token, id) == 200
      assert get_status(b.token, id, :head) == 200

      # A block either way hides it (a block also ends the friendship; the rule holds even
      # with a friendship row left over).
      :ok = RisiMe.Social.block(b.user, a.user.id)
      assert get_status(b.token, id) == 404
      befriend!(a, b)
      assert get_status(b.token, id) == 404
      :ok = RisiMe.Social.unblock(b.user, a.user.id)
      assert get_status(b.token, id) == 200
      :ok = RisiMe.Social.block(a.user, b.user.id)
      befriend!(a, b)
      assert get_status(b.token, id) == 404

      # Stranger.
      assert get_status(e.token, id) == 404

      # Co-members (not friends): active and pending_add read, pending_remove doesn't.
      g = group_with!([{a, "active"}, {c, "active"}, {d, "pending_add"}])
      assert get_status(c.token, id) == 200
      assert get_status(d.token, id) == 200

      set_state!(g, c, "pending_remove")
      assert get_status(c.token, id) == 404

      # The owner pending_add, the reader active: still shared.
      set_state!(g, c, "active")
      set_state!(g, a, "pending_add")
      assert get_status(c.token, id) == 200

      # The owner removed: nobody in that group reads it any more.
      set_state!(g, a, "pending_remove")
      assert get_status(c.token, id) == 404
      assert get_status(d.token, id) == 404

      # A deleted membership row (left completely) is the same.
      set_state!(g, a, "active")
      assert get_status(c.token, id) == 200
      Repo.delete_all(from m in Member, where: m.group_id == ^g and m.user_id == ^c.user.id)
      assert get_status(c.token, id) == 404
    end
  end

  describe "silent (§18.4)" do
    setup %{a: a, b: b} do
      test_push!()
      befriend!(a, b)
      a_dev = mls_device!(a)
      _b_dev = mls_device!(b)
      conv = e2ee_group!(a, b)

      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})

      {:ok, nil} =
        RisiMe.Devices.register(b.user.id, Ecto.UUID.generate(), %{
          "platform" => "android",
          "push_token" => tok = push_token("fcm-b")
        })

      %{chan: chan, conv: conv, tok: tok}
    end

    defp send_msg(chan, payload) do
      ref = push(chan, "msg:send", Map.merge(%{"client_msg_id" => Ecto.UUID.generate()}, payload))

      receive do
        %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
      after
        2000 -> flunk("no reply")
      end
    end

    defp cipher(to, extra \\ %{}),
      do:
        Map.merge(
          %{"to" => to, "ciphertext" => b64(64), "generation" => 1, "epoch" => 1},
          extra
        )

    test "a silent DM message is stored for both, carries silent: true, and is never pushed",
         %{a: a, b: b, chan: chan, tok: tok} do
      assert {:ok, %{message_id: id}} = send_msg(chan, cipher(b.user.id, %{"silent" => true}))

      for u <- [a, b] do
        ev = last_event(u.user.id, "message")
        assert ev["event_id"] == id
        assert ev["data"]["silent"] == true
      end

      # Sync replays it with the flag.
      {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})

      {:ok, %{events: events}, _} =
        subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})

      assert Enum.any?(events, &(&1.event_id == id and &1.data["silent"] == true))
      refute_receive {:push, ^tok, _}, 600
    end

    test "silent false is the same as absent; neither carries the field", %{b: b, chan: chan} do
      assert {:ok, _} = send_msg(chan, cipher(b.user.id, %{"silent" => false}))
      ev = last_event(b.user.id, "message")
      refute Map.has_key?(ev["data"], "silent")
    end

    test "no coalesced push either: a silent message inside the window adds no trailing push",
         %{b: b, chan: chan, tok: tok} do
      assert {:ok, _} = send_msg(chan, cipher(b.user.id))
      assert_receive {:push, ^tok, _}, 1_000
      assert {:ok, _} = send_msg(chan, cipher(b.user.id, %{"silent" => true}))
      assert {:ok, _} = send_msg(chan, cipher(b.user.id, %{"silent" => true}))
      refute_receive {:push, ^tok, _}, 800
    end

    test "bad_request: a non-boolean silent, silent with body or reaction",
         %{a: a, b: b, chan: chan} do
      for v <- ["true", 1, nil, %{}] do
        assert {:error, %{reason: "bad_request"}} =
                 send_msg(chan, cipher(b.user.id, %{"silent" => v}))
      end

      # Plaintext (a DM that isn't e2ee) with silent: true.
      c = logged_in_user()
      befriend!(a, c)

      assert {:error, %{reason: "bad_request"}} =
               send_msg(chan, %{"to" => c.user.id, "body" => "hi", "silent" => true})

      assert {:error, %{reason: "bad_request"}} =
               send_msg(chan, %{
                 "to" => c.user.id,
                 "reaction" => %{"target" => Ecto.UUID.generate(), "emoji" => "👍", "op" => "add"},
                 "silent" => true
               })

      # silent: false with a body is a normal plaintext send.
      assert {:ok, _} = send_msg(chan, %{"to" => c.user.id, "body" => "hi", "silent" => false})
    end
  end

  describe "silent in groups (§18.4)" do
    test "stored for every member, sender copy included, never pushed", %{a: a, b: b, c: c} do
      test_push!()
      befriend!(a, b)
      befriend!(a, c)
      a_dev = groups_device!(a)
      _ = groups_device!(b)
      _ = groups_device!(c)

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

      tokens =
        for u <- [b, c] do
          {:ok, nil} =
            RisiMe.Devices.register(u.user.id, Ecto.UUID.generate(), %{
              "platform" => "android",
              "push_token" => tok = push_token("fcm-g")
            })

          tok
        end

      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})

      payload = %{
        "client_msg_id" => Ecto.UUID.generate(),
        "conversation_id" => id,
        "ciphertext" => b64(),
        "generation" => 1,
        "epoch" => 1,
        "silent" => true
      }

      ref = push(chan, "msg:send", payload)
      assert_reply ref, :ok, %{message_id: mid}

      for u <- [a, b, c] do
        ev = last_event(u.user.id, "message")
        assert ev["event_id"] == mid and ev["data"]["silent"] == true
      end

      for tok <- tokens, do: refute_receive({:push, ^tok, _}, 500)

      ref =
        push(
          chan,
          "msg:send",
          %{payload | "client_msg_id" => Ecto.UUID.generate()} |> Map.put("silent", "yes")
        )

      assert_reply ref, :error, %{reason: "bad_request"}

      # A normal group message still pushes.
      ref =
        push(
          chan,
          "msg:send",
          payload |> Map.put("client_msg_id", Ecto.UUID.generate()) |> Map.delete("silent")
        )

      assert_reply ref, :ok, _
      for tok <- tokens, do: assert_receive({:push, ^tok, _}, 1_000)
    end
  end
end
