defmodule RisiMeWeb.DeletesV112Test do
  @moduledoc """
  Contract v1.12 §15 (decision 047), the server half of §15.12: `msg:delete` for me and for
  everyone, the `delete` event, `chat:clear`, the `deletes` capability, and every path that must
  treat a deleted message as gone.
  """
  use RisiMeWeb.ChannelCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, e2ee_group!: 2]
  import RisiMe.GroupHelpers
  import ExUnit.CaptureLog

  alias RisiMe.{Messaging, TimeUUID}
  alias RisiMe.Messaging.{Deletes, Store}
  alias RisiMe.MLS.Wire
  alias RisiMe.Workers.{ChatClear, DeleteFinish}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @cluster RisiMe.Messaging.Store.Cassandra.Cluster

  # Delegates to the real store and reports message_index reads and inbox appends to the test.
  defmodule SpyStore do
    @behaviour RisiMe.Messaging.Store
    alias RisiMe.Messaging.Store.Cassandra

    defp report(msg) do
      if pid = Application.get_env(:risime, :spy_store_pid), do: send(pid, msg)
    end

    @impl true
    def get_message(id) do
      report({:get_message, id})
      Cassandra.get_message(id)
    end

    @impl true
    def append_event(u, e) do
      report({:appended, u, e.event_id})
      Cassandra.append_event(u, e)
    end

    @impl true
    def append_event_to_all(us, e) do
      for u <- us, do: report({:appended, u, e.event_id})
      Cassandra.append_event_to_all(us, e)
    end

    @impl true
    defdelegate get_sent(s, c), to: Cassandra
    @impl true
    defdelegate claim_send(s, c, x), to: Cassandra
    @impl true
    defdelegate put_message(m), to: Cassandra
    @impl true
    defdelegate compare_and_set_status(id, e, n), to: Cassandra
    @impl true
    defdelegate list_events(u, s, l), to: Cassandra
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
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    a_dev = device!(a)
    b_dev = device!(b)
    c_dev = device!(c)
    clear_legacy!()

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

    # a–b: an e2ee DM; a–c: a plaintext (legacy) DM.
    dm = e2ee_group!(a, b)
    plain = Messaging.conversation_id(a.user.id, c.user.id)

    %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, id: id, dm: dm, plain: plain}
  end

  defp device!(user, caps \\ ["groups", "images", "deletes"]) do
    device_id = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(user.user.id, device_id, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => caps
        }
      })

    :ok = RisiMe.MLS.record_instance(user.user.id, device_id, nil, "0.3.0-test")
    device_id
  end

  defp uuid, do: Ecto.UUID.generate()

  defp set_role(id, user, role) do
    import Ecto.Query, only: [from: 2]
    uid = user.user.id
    q = from(m in RisiMe.Groups.Member, where: m.group_id == ^id and m.user_id == ^uid)
    {1, _} = RisiMe.Repo.update_all(q, set: [role: role])
  end

  # A group message (e2ee, opaque bytes).
  defp gsend(user, dev, id) do
    {:ok, %{message_id: m}} =
      Messaging.send(
        user.user.id,
        %{
          "client_msg_id" => uuid(),
          "conversation_id" => id,
          "ciphertext" => b64(),
          "generation" => 1,
          "epoch" => 1
        },
        device_id: dev
      )

    m
  end

  # An e2ee DM message.
  defp dsend(from, dev, to) do
    {:ok, %{message_id: m}} =
      Messaging.send(
        from.user.id,
        %{
          "client_msg_id" => uuid(),
          "to" => to.user.id,
          "ciphertext" => b64(),
          "generation" => 1,
          "epoch" => 1
        },
        device_id: dev
      )

    m
  end

  # A plaintext DM message; returns {message_id, payload}.
  defp psend(from, to, body \\ "hello") do
    p = %{"client_msg_id" => uuid(), "to" => to.user.id, "body" => body}
    {:ok, %{message_id: m}} = Messaging.send(from.user.id, p)
    {m, p}
  end

  # The delete control's PrivateMessage with the §15.3 authenticated_data.
  defp ct(targets, aad \\ nil) do
    aad = aad || Wire.delete_aad(targets)

    Wire.encode_private_message("gid", 1, aad, :crypto.strong_rand_bytes(20), b64raw(48))
    |> Base.encode64()
  end

  defp b64raw(n), do: :crypto.strong_rand_bytes(n)

  defp payload(conv, targets, extra \\ %{}) do
    base = %{
      "client_msg_id" => uuid(),
      "conversation_id" => conv,
      "scope" => "everyone",
      "targets" => targets,
      "client_ts" => "2026-10-06T09:00:00.000Z"
    }

    base =
      if String.starts_with?(conv, "grp:") or RisiMe.MLS.e2ee?(conv),
        do: Map.merge(base, %{"ciphertext" => ct(targets), "generation" => 1, "epoch" => 1}),
        else: base

    Map.merge(base, extra)
  end

  defp del(user, dev, conv, targets, extra \\ %{}),
    do: Deletes.delete(user.user.id, payload(conv, targets, extra), device_id: dev)

  defp ids(user), do: user.user.id |> events() |> Enum.map(& &1["event_id"])
  defp has?(user, id), do: id in ids(user)
  defp event(user, id), do: user.user.id |> events() |> Enum.find(&(&1["event_id"] == id))

  # A message stored `age` ago (its TimeUUID time), in every holder's inbox.
  defp plant(sender, conv, recipients, age_ms, opts \\ []) do
    id = TimeUUID.at(DateTime.add(DateTime.utc_now(), -age_ms, :millisecond))

    base = %{
      message_id: id,
      sender_id: sender.user.id,
      client_msg_id: uuid(),
      conversation_id: conv,
      status: "sent"
    }

    m =
      if String.starts_with?(conv, "dm:"),
        do: Map.put(base, :recipient_id, hd(recipients)),
        else: Map.merge(base, %{recipient_id: nil, recipients: recipients})

    :ok = Store.impl().put_message(m)

    data = %{
      "message_id" => id,
      "conversation_id" => conv,
      "from" => sender.user.id,
      "ciphertext" => b64(),
      "server_ts" => Messaging.iso(TimeUUID.to_datetime(id))
    }

    holders = Keyword.get(opts, :holders, [sender.user.id | recipients])
    :ok = Store.impl().append_event_to_all(holders, %{event_id: id, kind: "message", data: data})
    id
  end

  defp spy! do
    Application.put_env(:risime, :message_store, SpyStore)
    Application.put_env(:risime, :spy_store_pid, self())

    on_exit(fn ->
      Application.delete_env(:risime, :message_store)
      Application.delete_env(:risime, :spy_store_pid)
    end)
  end

  defp exhaust_send_limit(user) do
    for _ <- 1..25, do: RisiMe.RateLimiter.hit(:msg_send, user.user.id, 20, 10_000)
  end

  defp cql(statement, params) do
    case Xandra.Cluster.execute(@cluster, statement, params) do
      {:ok, %Xandra.Page{} = page} -> Enum.to_list(page)
      {:ok, _void} -> []
    end
  end

  describe "authorisation (§15.4)" do
    test "group: own < 48 h, another member's is not_admin (all-or-nothing), admin any age",
         ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, id: id} = ctx
      m_b = gsend(b, b_dev, id)
      m_c = gsend(c, ctx.c_dev, id)

      # All-or-nothing: nothing is deleted and nothing stored.
      assert {:error, :not_admin, [%{target: ^m_c, reason: "not_admin"}]} =
               del(b, b_dev, id, [m_b, m_c])

      assert has?(a, m_b) and has?(c, m_b)
      assert {:ok, %{deleted_at: nil}} = Store.impl().get_message(m_b)

      assert {:ok, %{deleted: [^m_b], gone: [], message_id: mid}} = del(b, b_dev, id, [m_b])
      refute has?(a, m_b) or has?(b, m_b) or has?(c, m_b)
      assert has?(a, mid) and has?(b, mid) and has?(c, mid)

      # An admin deletes another member's message.
      assert {:ok, %{deleted: [^m_c]}} = del(a, a_dev, id, [m_c])

      # 48 h: an own message of 47 h is fine, one of 49 h is too_old for a member…
      young = plant(b, id, [a.user.id, c.user.id], 47 * 3_600_000)
      old = plant(b, id, [a.user.id, c.user.id], 49 * 3_600_000)
      old_c = plant(c, id, [a.user.id, b.user.id], 49 * 3_600_000)
      assert {:ok, %{deleted: [^young]}} = del(b, b_dev, id, [young])

      assert {:error, :too_old, failures} = del(b, b_dev, id, [old, old_c])

      assert failures == [
               %{target: old, reason: "too_old"},
               %{target: old_c, reason: "not_admin"}
             ]

      # …and an admin has no age limit, for own and others' messages.
      own_old = plant(a, id, [b.user.id, c.user.id], 29 * 86_400_000)
      assert {:ok, %{deleted: [^old, ^own_old]}} = del(a, a_dev, id, [old, own_old])
    end

    test "a demoted admin is judged by the landed role at the time of the request", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
      m_c = gsend(c, ctx.c_dev, id)
      # Promote b directly (a landed role); b may now delete c's message.
      set_role(id, b, "admin")

      assert {:ok, %{deleted: [^m_c]}} = del(b, ctx.b_dev, id, [m_c])

      set_role(id, b, "member")

      m_a = gsend(a, a_dev, id)
      assert {:error, :not_admin, _} = del(b, ctx.b_dev, id, [m_a])
    end

    test "DMs: not_sender; own e2ee and plaintext deletes; no friendship needed", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, dm: dm, plain: plain} = ctx
      m_a = dsend(a, a_dev, b)
      m_b = dsend(b, b_dev, a)

      assert {:error, :not_sender, [%{target: ^m_b, reason: "not_sender"}]} =
               del(a, a_dev, dm, [m_b])

      assert {:error, :not_sender, _} = del(a, a_dev, dm, [m_a, m_b])

      # Unfriended and blocked: the own delete still works (§15.2).
      :ok = RisiMe.Social.block(a.user, b.user.id)
      assert {:ok, %{deleted: [^m_a]}} = del(a, a_dev, dm, [m_a])
      refute has?(b, m_a) or has?(a, m_a)

      {m_p, _} = psend(a, c)
      assert {:ok, %{deleted: [^m_p], message_id: mid}} = del(a, nil, plain, [m_p])
      refute has?(c, m_p)
      assert %{"kind" => "delete"} = event(c, mid)

      # A stranger isn't a participant.
      stranger = logged_in_user()
      assert {:error, :not_member} = del(stranger, nil, plain, [m_p])
    end

    test "gone: unknown/expired, tombstoned, other conversation, reaction, delete", ctx do
      %{a: a, c: c, a_dev: a_dev, id: id, plain: plain} = ctx
      unknown = TimeUUID.generate()
      {m1, _} = psend(a, c)
      {m2, _} = psend(a, c)

      {:ok, %{message_id: reaction}} =
        Messaging.send(c.user.id, %{
          "client_msg_id" => uuid(),
          "to" => a.user.id,
          "reaction" => %{"target" => m1, "emoji" => "👍", "op" => "add"}
        })

      assert {:ok, %{deleted: [^m2], message_id: d1}} = del(a, nil, plain, [m2])
      grp_msg = gsend(a, a_dev, id)

      # Another conversation's message, a reaction, a delete: gone; and with no new target, no
      # event is stored (msg_delete_reply_gone.json).
      assert {:ok, %{message_id: nil, server_ts: nil, deleted: [], gone: gone}} =
               del(a, nil, plain, [unknown, m2, grp_msg, d1])

      assert gone == [unknown, m2, grp_msg, d1]

      # A reaction target is gone even for its own sender.
      assert {:ok, %{deleted: [], gone: [^reaction]}} = del(c, nil, plain, [reaction])
      assert has?(a, grp_msg)
    end
  end

  describe "shape, binding and order of checks (§15.2, §15.3)" do
    test "1–100 targets; malformed fields; the authenticated_data binding", ctx do
      %{a: a, a_dev: a_dev, id: id, dm: dm, plain: plain} = ctx
      hundred = for _ <- 1..100, do: TimeUUID.generate()
      assert {:ok, %{gone: g}} = del(a, a_dev, id, hundred)
      assert length(g) == 100
      # Duplicates are removed (101 entries, 100 distinct).
      assert {:ok, %{gone: ^hundred}} = del(a, a_dev, id, hundred ++ [hd(hundred)])

      assert {:error, :bad_request} = del(a, a_dev, id, [TimeUUID.generate() | hundred])
      assert {:error, :bad_request} = del(a, a_dev, id, [])
      assert {:error, :bad_request} = del(a, a_dev, id, [uuid()])
      assert {:error, :bad_request} = del(a, a_dev, id, [hd(hundred)], %{"scope" => "all"})
      assert {:error, :bad_request} = del(a, a_dev, id, [hd(hundred)], %{"blob_ids" => ["x"]})
      assert {:error, :bad_request} = del(a, a_dev, "dm:nope", [hd(hundred)])

      [t1, t2 | _] = hundred
      # AAD naming another set, an empty AAD, unparseable bytes: bad_request before anything.
      assert {:error, :bad_request} = del(a, a_dev, id, [t1], %{"ciphertext" => ct([t1, t2])})

      assert {:error, :bad_request} =
               del(a, a_dev, id, [t1], %{"ciphertext" => ct([t1], <<1, ?D>>)})

      assert {:error, :bad_request} = del(a, a_dev, id, [t1], %{"ciphertext" => b64()})
      # The order of targets doesn't matter (set equality, canonical sort).
      assert {:ok, _} = del(a, a_dev, id, [t2, t1], %{"ciphertext" => ct([t1, t2])})

      # e2ee: no ciphertext → e2ee_required; no device → bad_request; stale epoch; too long.
      no_ct = %{"ciphertext" => nil}
      assert {:error, :e2ee_required} = del(a, a_dev, id, [t1], no_ct)
      assert {:error, :e2ee_required} = del(a, a_dev, dm, [t1], no_ct)
      assert {:error, :bad_request} = del(a, nil, dm, [t1])
      assert {:error, :stale_epoch} = del(a, a_dev, id, [t1], %{"epoch" => 0})
      assert {:error, :stale_epoch} = del(a, a_dev, dm, [t1], %{"epoch" => 7})
      big = Base.encode64(:crypto.strong_rand_bytes(24 * 1024 + 1))
      assert {:error, :too_long} = del(a, a_dev, id, [t1], %{"ciphertext" => big})

      # Plaintext: ciphertext or blob ids are bad_request.
      assert {:error, :bad_request} =
               del(a, nil, plain, [t1], %{
                 "ciphertext" => ct([t1]),
                 "generation" => 1,
                 "epoch" => 1
               })

      assert {:error, :bad_request} = del(a, nil, plain, [t1], %{"blob_ids" => [uuid()]})

      # Not a member.
      assert {:error, :not_member} = del(a, a_dev, "grp:" <> uuid(), [t1])
    end

    test "msg:send refuses a non-delete application message with authenticated_data", ctx do
      %{a: a, a_dev: a_dev, id: id} = ctx
      t = TimeUUID.generate()

      assert {:error, :bad_request} =
               Messaging.send(
                 a.user.id,
                 %{
                   "client_msg_id" => uuid(),
                   "conversation_id" => id,
                   "ciphertext" => ct([t]),
                   "generation" => 1,
                   "epoch" => 1
                 },
                 device_id: a_dev
               )

      empty = Wire.encode_private_message("g", 1, "", "s", "c") |> Base.encode64()

      assert {:ok, _} =
               Messaging.send(
                 a.user.id,
                 %{
                   "client_msg_id" => uuid(),
                   "conversation_id" => id,
                   "ciphertext" => empty,
                   "generation" => 1,
                   "epoch" => 1
                 },
                 device_id: a_dev
               )
    end

    test "the rate limit comes before any message_index read; refusals count", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, dm: dm} = ctx
      m_b = dsend(b, b_dev, a)
      m_a = dsend(a, ctx.a_dev, b)
      spy!()
      exhaust_send_limit(a)

      assert {:error, :rate_limited} = del(a, a_dev, dm, [m_b])

      me = %{
        "client_msg_id" => uuid(),
        "conversation_id" => dm,
        "scope" => "me",
        "targets" => [m_b]
      }

      assert {:error, :rate_limited} = Deletes.delete(a.user.id, me)
      refute_received {:get_message, _}
      refute_received {:get_event, _}

      # Refused (not_sender) requests count against the limit, once each.
      results = for _ <- 1..25, do: del(b, b_dev, dm, [m_a])
      {refused, limited} = Enum.split_while(results, &match?({:error, :not_sender, _}, &1))
      assert length(refused) in 15..19
      assert hd(limited) == {:error, :rate_limited}
    end

    test "a client_msg_id used by msg:send is bad_request for msg:delete, and back", ctx do
      %{a: a, c: c, plain: plain} = ctx
      {m, p} = psend(a, c)

      assert {:error, :bad_request} =
               del(a, nil, plain, [m], %{"client_msg_id" => p["client_msg_id"]})

      assert {:error, :bad_request} =
               del(a, nil, plain, [m], %{"client_msg_id" => p["client_msg_id"], "scope" => "me"})

      d = payload(plain, [m])
      assert {:ok, %{deleted: [^m]}} = Deletes.delete(a.user.id, d)

      assert {:error, :bad_request} =
               Messaging.send(a.user.id, %{
                 "client_msg_id" => d["client_msg_id"],
                 "to" => c.user.id,
                 "body" => "x"
               })
    end
  end

  describe "fan-out and the delete event (§15.5)" do
    test "every member inbox gets one event_id, the deleter's copy first and never pushed", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, id: id} = ctx
      test_push!()

      tokens =
        for u <- [a, b, c], into: %{} do
          {:ok, nil} =
            RisiMe.Devices.register(u.user.id, uuid(), %{
              "platform" => "android",
              "push_token" => push_token("fcm-del")
            })

          [t] = RisiMe.Devices.push_tokens(u.user.id)
          {u.user.id, t}
        end

      m = plant(b, id, [a.user.id, c.user.id], 1_000)
      spy!()
      req = payload(id, [m])

      assert {:ok, %{message_id: mid, deleted: [^m]} = reply} =
               Deletes.delete(b.user.id, req, device_id: b_dev)

      appended = collect_appended()
      assert [{first, ^mid} | rest] = appended
      assert first == b.user.id
      assert Enum.sort(for {u, ^mid} <- rest, do: u) == Enum.sort([a.user.id, c.user.id])

      for u <- [a, b, c] do
        ev = event(u, mid)
        assert ev["kind"] == "delete"
        assert ev["data"]["from"] == b.user.id and ev["data"]["from_device"] == b_dev
        assert ev["data"]["ciphertext"] == req["ciphertext"]
        assert ev["data"]["server_ts"] == reply.server_ts
        refute Map.has_key?(ev["data"], "to")

        assert ev["data"]["targets"] == [
                 %{
                   "message_id" => m,
                   "from" => b.user.id,
                   "server_ts" => Messaging.iso(TimeUUID.to_datetime(m))
                 }
               ]
      end

      a_token = tokens[a.user.id]
      c_token = tokens[c.user.id]
      b_token = tokens[b.user.id]
      assert_receive {:push, ^a_token, %{"type" => "inbox"}}, 1_000
      assert_receive {:push, ^c_token, %{"type" => "inbox"}}, 1_000
      refute_receive {:push, ^b_token, _}, 300

      # The delete is indexed (kind delete): not ackable, not a target.
      assert {:ok, %{kind: "delete", sender_id: sid}} = Store.impl().get_message(mid)
      assert sid == b.user.id
    end

    test "a member who didn't have a target gets null metadata (R4); gone targets are null",
         ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
      # A message from before b joined: recipients at send time were c only.
      m = plant(a, id, [c.user.id], 60_000)
      unknown = TimeUUID.generate()
      assert {:ok, %{message_id: mid}} = del(a, a_dev, id, [m, unknown])

      full = %{
        "message_id" => m,
        "from" => a.user.id,
        "server_ts" => Messaging.iso(TimeUUID.to_datetime(m))
      }

      null = fn t -> %{"message_id" => t, "from" => nil, "server_ts" => nil} end

      assert event(a, mid)["data"]["targets"] == [full, null.(unknown)]
      assert event(c, mid)["data"]["targets"] == [full, null.(unknown)]
      assert event(b, mid)["data"]["targets"] == [null.(m), null.(unknown)]
    end

    test "DM events: plaintext has `to` and no MLS fields; e2ee adds them", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, dm: dm, plain: plain} = ctx
      {m, _} = psend(c, a)
      {:ok, %{message_id: mid}} = del(c, nil, plain, [m])
      data = event(a, mid)["data"]
      assert data["to"] == a.user.id and data["from"] == c.user.id

      for k <- ~w(from_device ciphertext generation epoch), do: refute(Map.has_key?(data, k))

      m2 = dsend(a, a_dev, b)
      {:ok, %{message_id: mid2}} = del(a, a_dev, dm, [m2])
      data = event(b, mid2)["data"]
      assert data["to"] == b.user.id and data["from_device"] == a_dev
      assert data["generation"] == 1 and data["epoch"] == 1
      assert {:ok, %{kind: "delete", recipient_id: rid}} = Store.impl().get_message(mid2)
      assert rid == b.user.id
    end
  end

  defp collect_appended(acc \\ []) do
    receive do
      {:appended, u, e} -> collect_appended([{u, e} | acc])
    after
      100 -> Enum.reverse(acc)
    end
  end

  describe "deleted messages are gone everywhere (§15.8, server R5)" do
    test "the index tombstone keeps the remaining TTL", ctx do
      %{a: a, c: c, plain: plain} = ctx
      {m, _} = psend(a, c)
      # Shorten the row's TTL to 1000 s (a row written long ago).
      [row] =
        cql(
          "SELECT sender_id, recipient_id, client_msg_id, conversation_id, status FROM message_index WHERE message_id = ?",
          [{"timeuuid", m}]
        )

      cql(
        "INSERT INTO message_index (message_id, sender_id, recipient_id, client_msg_id, conversation_id, status) " <>
          "VALUES (?, ?, ?, ?, ?, ?) USING TTL 1000",
        [
          {"timeuuid", m},
          {"uuid", row["sender_id"]},
          {"uuid", row["recipient_id"]},
          {"uuid", row["client_msg_id"]},
          {"text", row["conversation_id"]},
          {"text", row["status"]}
        ]
      )

      assert {:ok, %{deleted: [^m]}} = del(a, nil, plain, [m])

      [ttls] =
        cql(
          "SELECT TTL(sender_id) AS s, TTL(deleted_at) AS d, TTL(deleted_by) AS b, deleted_by FROM message_index WHERE message_id = ?",
          [{"timeuuid", m}]
        )

      assert ttls["d"] <= 1000 and ttls["d"] >= 990
      assert abs(ttls["d"] - ttls["s"]) <= 2 and ttls["b"] == ttls["d"]
      assert ttls["deleted_by"] == a.user.id
      assert {:ok, %{deleted_at: %DateTime{}, deleted_by: by}} = Store.impl().get_message(m)
      assert by == a.user.id
    end

    test "acks, reactions, receipts and resends treat a deleted message as gone", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, id: id, plain: plain} = ctx
      {m, p} = psend(a, c)
      assert {:ok, %{message_id: mid}} = del(a, nil, plain, [m])

      # DM acks of a deleted message (and of the delete event) produce no status.
      :ok = Messaging.ack(c.user.id, [m, mid], "read")
      assert events(a.user.id, "status") == []

      # Reactions: unknown_target, for the message and for the delete event.
      for t <- [m, mid] do
        assert {:error, :unknown_target} =
                 Messaging.send(c.user.id, %{
                   "client_msg_id" => uuid(),
                   "to" => a.user.id,
                   "reaction" => %{"target" => t, "emoji" => "👍", "op" => "add"}
                 })
      end

      # A resend of the original returns its reply without re-delivering.
      assert {:ok, %{message_id: ^m}} = Messaging.send(a.user.id, p)
      refute has?(a, m) or has?(c, m)

      # Group: receipts 404, acks ignored.
      g = gsend(b, b_dev, id)
      {200, _} = api(:get, "/api/v1/groups/#{id}/messages/#{g}/receipts", b.token, nil, b_dev)
      assert {:ok, _} = del(b, b_dev, id, [g])
      {404, _} = api(:get, "/api/v1/groups/#{id}/messages/#{g}/receipts", b.token, nil, b_dev)
      :ok = Messaging.ack(a.user.id, [g], "read")
      assert Store.impl().list_group_receipts(g) == []
      _ = a_dev
    end

    test "a pending group_receipt timer emits nothing; the receipts partition is gone", ctx do
      %{a: a, b: b, b_dev: b_dev, id: id} = ctx
      prev = Application.get_env(:risime, :group_receipt_coalesce_ms)
      Application.put_env(:risime, :group_receipt_coalesce_ms, 300)
      on_exit(fn -> Application.put_env(:risime, :group_receipt_coalesce_ms, prev) end)

      g = gsend(b, b_dev, id)
      :ok = Messaging.ack(a.user.id, [g], "delivered")
      # The first emits at once; the second (no "all" flip) is coalesced behind a timer.
      :ok = Messaging.ack(a.user.id, [g], "read")
      assert length(events(b.user.id, "group_receipt")) == 1
      assert Store.impl().list_group_receipts(g) != []

      assert {:ok, _} = del(b, b_dev, id, [g])
      Process.sleep(600)
      assert length(events(b.user.id, "group_receipt")) == 1
      assert Store.impl().list_group_receipts(g) == []
    end

    test "plaintext reaction refs: the reaction events and their index rows go too", ctx do
      %{a: a, c: c, plain: plain} = ctx
      {m, _} = psend(a, c)

      {:ok, %{message_id: r}} =
        Messaging.send(c.user.id, %{
          "client_msg_id" => uuid(),
          "to" => a.user.id,
          "reaction" => %{"target" => m, "emoji" => "👍", "op" => "add"}
        })

      assert length(Store.impl().list_message_refs(m, nil)) == 2
      assert has?(a, r) and has?(c, r)

      assert {:ok, %{deleted: [^m]} = reply} = del(a, nil, plain, [m])
      refute has?(a, r) or has?(c, r)
      assert {:ok, %{deleted_at: %DateTime{}}} = Store.impl().get_message(r)
      assert Store.impl().list_message_refs(m, nil) == []

      # The finishing job was enqueued and is idempotent.
      assert_enqueued(worker: DeleteFinish, args: %{"message_id" => reply.message_id})
      [job] = all_enqueued(worker: DeleteFinish, args: %{"message_id" => reply.message_id})
      assert :ok = perform_job(DeleteFinish, job.args)
      assert [_] = Enum.filter(events(c.user.id, "delete"), &(&1["event_id"] == reply.message_id))
    end
  end

  describe "blobs (§15.2, §15.10)" do
    setup do
      dir = Path.join(System.tmp_dir!(), "risime-del-#{System.unique_integer([:positive])}")
      prev = Application.get_env(:risime, :blob_dir)
      Application.put_env(:risime, :blob_dir, dir)

      on_exit(fn ->
        Application.put_env(:risime, :blob_dir, prev)
        File.rm_rf(dir)
      end)

      :ok
    end

    defp upload!(user, conv) do
      q =
        URI.encode_query(%{
          "purpose" => "media",
          "conversation_id" => conv,
          "client_blob_id" => uuid()
        })

      {201, %{"blob_id" => blob}} =
        api_raw("/api/v1/blobs?" <> q, user.token, :crypto.strong_rand_bytes(100))

      blob
    end

    defp blob_status(user, blob) do
      Phoenix.ConnTest.build_conn()
      |> Plug.Conn.put_req_header("authorization", "Bearer " <> user.token)
      |> Phoenix.ConnTest.dispatch(@endpoint, :get, "/api/v1/blobs/#{blob}")
      |> Map.fetch!(:status)
    end

    test "the target sender's blob is removed; others are skipped and logged; a duplicate is all gone",
         ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, id: id} = ctx
      own = upload!(b, id)
      other = upload!(c, id)
      m = gsend(b, b_dev, id)
      assert blob_status(a, own) == 200

      log =
        capture_log(fn ->
          assert {:ok, %{deleted: [^m]}} =
                   del(b, b_dev, id, [m], %{"blob_ids" => [own, other, uuid()]})
        end)

      assert log =~ "skipped 2 blob id(s)"
      assert blob_status(a, own) == 404 and blob_status(c, own) == 404
      assert blob_status(a, other) == 200

      # A concurrent duplicate (another device, another client_msg_id) succeeds as all gone,
      # and an admin may name the blob of an already tombstoned target.
      assert {:ok, %{message_id: nil, deleted: [], gone: [^m]}} =
               del(b, b_dev, id, [m], %{"blob_ids" => [own]})

      assert {:ok, %{deleted: [], gone: [^m]}} =
               del(a, ctx.a_dev, id, [m], %{"blob_ids" => [own]})
    end
  end

  describe "idempotency and crash recovery (§15.8, server R2)" do
    test "a retry returns the first reply and stores nothing new", ctx do
      %{b: b, b_dev: b_dev, c: c, id: id} = ctx
      m = gsend(b, b_dev, id)
      req = payload(id, [m])
      assert {:ok, first} = Deletes.delete(b.user.id, req, device_id: b_dev)
      assert {:ok, ^first} = Deletes.delete(b.user.id, req, device_id: b_dev)
      assert Enum.count(events(c.user.id, "delete"), &(&1["event_id"] == first.message_id)) == 1
    end

    test "a crash between the claim and the tombstones: the retry finishes it", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, id: id} = ctx
      m = gsend(b, b_dev, id)
      req = payload(id, [m])
      planned = TimeUUID.generate()
      ts = DateTime.utc_now() |> DateTime.truncate(:millisecond)

      :ok =
        Store.impl().claim_send(b.user.id, req["client_msg_id"], %{
          message_id: planned,
          conversation_id: id,
          server_ts: ts,
          kind: "delete"
        })

      assert {:ok, %{message_id: ^planned, deleted: [^m], gone: []} = reply} =
               Deletes.delete(b.user.id, req, device_id: b_dev)

      assert reply.server_ts == Messaging.iso(ts)
      refute has?(a, m) or has?(c, m)
      assert has?(a, planned) and has?(c, planned)
      assert {:ok, ^reply} = Deletes.delete(b.user.id, req, device_id: b_dev)
    end

    test "the finishing job completes a delete the client never retried", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, id: id} = ctx
      m = gsend(b, b_dev, id)
      planned = TimeUUID.generate()

      plan = %{
        "conversation_id" => id,
        "kind" => "grp",
        "deleter" => b.user.id,
        "from_device" => b_dev,
        "client_msg_id" => uuid(),
        "message_id" => planned,
        "server_ts" => Messaging.iso(DateTime.utc_now()),
        "targets" => [m],
        "deleted" => [m],
        "members" => [b.user.id, a.user.id, c.user.id],
        "e2ee" => true,
        "ciphertext" => ct([m]),
        "generation" => 1,
        "epoch" => 1,
        "blob_ids" => []
      }

      assert :ok = perform_job(DeleteFinish, plan)
      refute has?(a, m) or has?(b, m) or has?(c, m)
      assert event(c, planned)["data"]["targets"] |> hd() |> Map.get("from") == b.user.id
      assert {:ok, %{deleted_by: by}} = Store.impl().get_message(m)
      assert by == b.user.id
      # Again: a no-op.
      assert :ok = perform_job(DeleteFinish, plan)
    end
  end

  describe "scope: me (§15.2)" do
    test "only the caller's partition and only message/reaction rows of the conversation", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, id: id, plain: plain} = ctx
      g = gsend(a, ctx.a_dev, id)
      RisiMe.Messaging.publish_mls(b.user.id, "mls_commit", %{"conversation_id" => id})
      [commit] = events(b.user.id, "mls_commit") |> Enum.map(& &1["event_id"]) |> Enum.take(-1)

      me = fn user, conv, targets ->
        Deletes.delete(user.user.id, %{
          "client_msg_id" => uuid(),
          "conversation_id" => conv,
          "scope" => "me",
          "targets" => targets
        })
      end

      assert {:ok, %{message_id: nil, server_ts: nil, deleted: [^g], gone: [^commit]}} =
               me.(b, id, [g, commit])

      refute has?(b, g)
      assert has?(a, g) and has?(c, g) and has?(b, commit)
      # Another conversation's id is gone and untouched.
      {m, _} = psend(a, c)
      assert {:ok, %{deleted: [], gone: [^m]}} = me.(a, id, [m])
      assert has?(a, m)

      # A plaintext reaction row, and the refs of a deleted message.
      {:ok, %{message_id: r}} =
        Messaging.send(c.user.id, %{
          "client_msg_id" => uuid(),
          "to" => a.user.id,
          "reaction" => %{"target" => m, "emoji" => "👍", "op" => "add"}
        })

      assert {:ok, %{deleted: [^m]}} = me.(a, plain, [m])
      refute has?(a, r)
      assert has?(c, r) and has?(c, m)
      assert [%{user_id: cu}] = Store.impl().list_message_refs(m, nil)
      assert cu == c.user.id
      # No event, no claim needed; ciphertext is refused.
      assert {:error, :bad_request} =
               Deletes.delete(
                 a.user.id,
                 %{
                   "client_msg_id" => uuid(),
                   "conversation_id" => id,
                   "scope" => "me",
                   "targets" => [g],
                   "ciphertext" => ct([g]),
                   "generation" => 1,
                   "epoch" => 1
                 },
                 device_id: b_dev
               )
    end
  end

  describe "chat:clear (§15.9)" do
    test "keeps mls_*/group_* events and events after upto, compares by time", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, id: id} = ctx
      m1 = gsend(b, ctx.b_dev, id)
      {other, _} = psend(a, c)
      RisiMe.Messaging.publish_mls(a.user.id, "mls_commit", %{"conversation_id" => id})
      Process.sleep(2)

      # Two events at exactly upto's time but other node bytes (string order differs).
      t = DateTime.utc_now()
      upto = TimeUUID.at(t)
      same = TimeUUID.at(t)
      later = TimeUUID.at(DateTime.add(t, 1, :microsecond))

      for e <- [same, later],
          do:
            :ok =
              Store.impl().append_event(a.user.id, %{
                event_id: e,
                kind: "message",
                data: %{"conversation_id" => id, "message_id" => e}
              })

      # A row written before v1.12 (no conversation_id column): the payload decides.
      legacy = TimeUUID.at(DateTime.add(t, -1, :millisecond))

      cql(
        "INSERT INTO inbox_events (user_id, event_id, kind, payload) VALUES (?, ?, ?, ?)",
        [
          {"uuid", a.user.id},
          {"timeuuid", legacy},
          {"text", "status"},
          {"text", Jason.encode!(%{"conversation_id" => id, "message_id" => m1})}
        ]
      )

      Process.sleep(2)
      m3 = gsend(b, ctx.b_dev, id)
      before = ids(a)

      group_events =
        for e <- events(a.user.id),
            e["kind"] in ~w(group_event mls_commit mls_welcome),
            do: e["event_id"]

      assert group_events != []

      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
      ref = push(chan, "chat:clear", %{"conversation_id" => id, "upto" => upto})
      assert_reply ref, :ok, %{}

      assert_enqueued(
        worker: ChatClear,
        args: %{"user_id" => a.user.id, "conversation_id" => id, "upto" => upto}
      )

      # Unique per (user, conversation, upto).
      ref = push(chan, "chat:clear", %{"conversation_id" => id, "upto" => upto})
      assert_reply ref, :ok, %{}
      assert length(all_enqueued(worker: ChatClear)) == 1

      assert :ok =
               perform_job(ChatClear, %{
                 "user_id" => a.user.id,
                 "conversation_id" => id,
                 "upto" => upto
               })

      after_ids = ids(a)
      refute m1 in after_ids or same in after_ids or legacy in after_ids
      assert later in after_ids and m3 in after_ids and other in after_ids
      assert Enum.all?(group_events, &(&1 in after_ids))
      assert has?(b, m1) and has?(c, m1)
      assert length(before) - length(after_ids) == 3

      # Malformed and rate limited.
      ref = push(chan, "chat:clear", %{"conversation_id" => id, "upto" => uuid()})
      assert_reply ref, :error, %{reason: "bad_request"}
      ref = push(chan, "chat:clear", %{"conversation_id" => "x", "upto" => upto})
      assert_reply ref, :error, %{reason: "bad_request"}

      results =
        for _ <- 1..10 do
          Deletes.clear(a.user.id, %{"conversation_id" => id, "upto" => TimeUUID.generate()})
        end

      assert {:error, :rate_limited} in results
    end
  end

  describe "deletes capability (§15.1)" do
    test "deletes_ready and missing_deletes, for groups and plaintext DMs", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, id: id, plain: plain} = ctx
      {200, view} = api(:get, "/api/v1/mls/groups/#{id}", a.token, nil, a_dev)
      assert {view["deletes_ready"], view["missing_deletes"]} == {true, []}
      {200, view} = api(:get, "/api/v1/mls/groups/#{plain}", a.token)
      assert {view["deletes_ready"], view["missing_deletes"]} == {true, []}
      assert view["images_ready"] == false

      c_old = device!(c, ["groups", "images"])
      {200, view} = api(:get, "/api/v1/mls/groups/#{id}", a.token, nil, a_dev)
      assert view["deletes_ready"] == false
      assert view["missing_deletes"] == [%{"user_id" => c.user.id, "device_id" => c_old}]
      assert view["images_ready"] == true
      _ = b
    end
  end

  test "no CQL statement uses ALLOW FILTERING" do
    root = Path.expand("../../..", __DIR__)

    for file <- Path.wildcard(Path.join(root, "lib/**/*.ex")),
        line <- File.read!(file) |> String.split("\n"),
        do: refute(line =~ ~r/"[^"]*ALLOW FILTERING/i, "#{file}: #{line}")

    for file <- Path.wildcard(Path.join(root, "priv/cql/*.cql")),
        line <- File.read!(file) |> String.split("\n"),
        not String.starts_with?(String.trim_leading(line), "--"),
        do: refute(line =~ ~r/ALLOW FILTERING|CREATE INDEX/i, "#{file}: #{line}")
  end
end
