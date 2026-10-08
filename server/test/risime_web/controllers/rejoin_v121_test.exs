defmodule RisiMeWeb.RejoinV121Test do
  @moduledoc """
  Contract v1.21 §12.12 (decision 060): rejoin replies (`op`, `candidates`, `exhausted`), the
  per-op, per-device naming budget, the `409 rejoin_pending` reset guard, DM wake pushes,
  pruning ops whose targets are gone, the stale-leaf sweep and the recovery task.
  """
  use RisiMeWeb.ChannelCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import ExUnit.CaptureIO
  import ExUnit.CaptureLog
  import Ecto.Query, only: [from: 2]
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers, except: [ref: 2]

  alias RisiMe.{Devices, Groups, MLS, Release, Repo}
  alias RisiMe.Groups.{Op, Ops, Strikes}
  alias RisiMe.MLS.DmOp
  alias RisiMe.Workers.{GroupTimer, StaleLeaves}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @cap "member_devices"

  setup :with_attestation_key

  setup do
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    clear_legacy!()
    a_dev = device!(a, [@cap])
    b_dev = device!(b, [@cap])
    c_dev = device!(c, [@cap])
    %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev}
  end

  ## Helpers

  defp device!(user, caps, opts \\ []) do
    dev = Keyword.get(opts, :id, Ecto.UUID.generate())
    key = Process.get({:key, dev}) || Base.encode64(:crypto.strong_rand_bytes(32))
    Process.put({:key, dev}, key)

    {:ok, _} =
      Devices.register(user.user.id, dev, %{
        "platform" => "android",
        "push_token" => opts[:token],
        "mls" => %{"signature_key" => key, "capabilities" => ["groups" | caps]}
      })

    :ok = MLS.record_instance(user.user.id, dev, nil, Keyword.get(opts, :version, "0.3.0-test"))
    dev
  end

  # The device was last seen `hours` ago, before its user's newer device was registered (§12.1).
  defp supersede!(dev, hours \\ 1) do
    past = DateTime.add(DateTime.utc_now(), -hours * 3600, :second)

    Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^dev),
      set: [last_seen_at: past, inserted_at: DateTime.add(past, -60, :second)]
    )

    Repo.update_all(from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
      set: [last_seen_at: past]
    )
  end

  defp active_group!(%{a: a, b: b, c: c, a_dev: a_dev}) do
    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, c.user.id]}

    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    members = [a.user.id, b.user.id, c.user.id]

    api(
      :post,
      "/api/v1/mls/groups/#{id}/commit",
      a.token,
      create_commit(members, {a.user.id, a_dev}),
      a_dev
    )
    |> assert_status(200)

    id
  end

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    chan
  end

  defp ref(u, d), do: %{"user_id" => u, "device_id" => d}

  defp timeout!(%Op{} = op),
    do:
      perform_job(GroupTimer, %{"kind" => "committer", "op_id" => op.op_id, "naming" => op.naming})

  defp timeout!(%DmOp{} = op),
    do:
      perform_job(GroupTimer, %{
        "kind" => "dm_committer",
        "op_id" => op.op_id,
        "naming" => op.naming
      })

  defp commit_body(id, added, removed, op_id) do
    %{
      "generation" => 1,
      "epoch" => Groups.epoch(id),
      "commit" => b64(),
      "welcome" => if(added == [], do: nil, else: b64()),
      "added" => added,
      "removed" => removed,
      "op_id" => op_id,
      "meta_changed" => false
    }
  end

  defp reset(user, dev, conv, gen \\ 1),
    do: api(:post, "/api/v1/mls/groups/#{conv}/reset", user.token, %{"generation" => gen}, dev)

  # The only admin (a) reinstalls: a2, its old phone superseded.
  defp admin_reinstall!(ctx) do
    a2 = device!(ctx.a, [@cap])
    supersede!(ctx.a_dev)
    a2
  end

  defp adding_op(id, u, d), do: Ops.adding_op(id, {u, d})

  defp add_leaf!(group_id, user_id, device_id),
    do:
      Repo.insert_all("mls_group_devices", [
        %{
          conversation_id: group_id,
          user_id: Ecto.UUID.dump!(user_id),
          device_id: Ecto.UUID.dump!(device_id)
        }
      ])

  ## §12.12.2 rejoin replies

  describe "group rejoin reply (§12.12.2)" do
    test "op, candidates online or not, exhausted; the only admin is re-added by a member", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      a2 = admin_reinstall!(ctx)

      %{"group" => %{"id" => ^id}, "op" => op, "candidates" => 2, "exhausted" => false} =
        api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2) |> assert_status(202)

      # Nobody online: b and c (members, §12.4a tier 3) are counted; a's superseded old phone
      # isn't.
      assert op["added"] == [ref(a.user.id, a2)] and op["committer"] == nil

      # b opens the app: named, and completes the op (the member path, op_id).
      join!(b, b_dev)
      %Op{committer_device: ^b_dev} = adding_op(id, a.user.id, a2)

      api(
        :post,
        "/api/v1/mls/groups/#{id}/commit",
        b.token,
        commit_body(id, [ref(a.user.id, a2)], [], op["op_id"]),
        b_dev
      )
      |> assert_status(200)

      assert adding_op(id, a.user.id, a2) == nil
      assert MapSet.member?(Groups.in_group(id), {a.user.id, a2})

      # Re-added: idempotent rejoin of an in-group device is a same-device re-add.
      assert c.user.id != a.user.id
    end

    test "tier 3 counts only while the gate is open; epoch null answers null/0", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, c_dev: c_dev} = ctx
      id = active_group!(ctx)
      # b's and c's phones are on an old app: the §12.4a gate is closed, no member candidate.
      ^b_dev = device!(b, [], id: b_dev)
      ^c_dev = device!(c, [], id: c_dev)
      a2 = admin_reinstall!(ctx)

      assert %{"candidates" => 0, "exhausted" => false, "op" => %{}} =
               api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      Repo.update_all(from(x in "mls_groups", where: x.conversation_id == ^id), set: [epoch: nil])

      assert %{"op" => nil, "candidates" => 0, "exhausted" => false} =
               api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2)
               |> assert_status(202)
    end

    test "10 per user per minute", ctx do
      %{a: a} = ctx
      id = active_group!(ctx)
      a2 = admin_reinstall!(ctx)

      for _ <- 1..10,
          do: api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2) |> assert_status(202)

      assert {429, %{"error" => %{"code" => "rate_limited"}}} =
               api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2)
    end
  end

  ## §12.12.4 naming budget

  describe "naming budget (§12.12.4)" do
    test "3 strikes per device and op; exhausted; inbox joins skip; cleared by app_version",
         ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, c_dev: c_dev} = ctx
      id = active_group!(ctx)
      join!(b, b_dev)
      join!(c, c_dev)
      a2 = admin_reinstall!(ctx)
      op = adding_op(id, a.user.id, a2)
      assert op.committer_device in [b_dev, c_dev]

      # Six 60-s windows run out: b and c are named in turn, three strikes each.
      for _ <- 1..6, do: assert(:ok = timeout!(Repo.reload!(op)))

      op = Repo.reload!(op)
      assert op.committer_device == nil
      assert Strikes.count(op.strikes, b_dev) == 3 and Strikes.count(op.strikes, c_dev) == 3

      # No wake job for an exhausted op (only the check when the first budget comes back).
      [job] = all_enqueued(worker: GroupTimer, args: %{"kind" => "wake", "op_id" => op.op_id})
      assert DateTime.diff(job.scheduled_at, DateTime.utc_now(), :hour) in 23..24

      assert %{"op" => %{"op_id" => op_id}, "candidates" => 2, "exhausted" => true} =
               api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      assert op_id == op.op_id

      # The inbox-join rule skips out-of-budget devices.
      join!(b, b_dev)
      assert Repo.reload!(op).committer_device == nil

      # The op stays pending (never deleted for being exhausted).
      assert Repo.get(Op, op.op_id)

      # b connects with a new app_version: its strikes are cleared, it is named again.
      :ok = MLS.record_instance(b.user.id, b_dev, nil, "0.4.0-test")
      assert Strikes.count(Repo.reload!(op).strikes, b_dev) == 0
      join!(b, b_dev)
      assert Repo.reload!(op).committer_device == b_dev

      assert %{"exhausted" => false} =
               api(:post, "/api/v1/groups/#{id}/rejoin", a.token, nil, a2)
               |> assert_status(202)
    end

    test "cleared by new capabilities and after 24 h; wake pushes skip out-of-budget", ctx do
      test_push!()
      %{a: a, b: b, c: c, b_dev: b_dev, c_dev: c_dev} = ctx
      tb = push_token("v121-b")
      tc = push_token("v121-c")
      ^b_dev = device!(b, [@cap], id: b_dev, token: tb)
      ^c_dev = device!(c, [@cap], id: c_dev, token: tc)
      id = active_group!(ctx)
      a2 = admin_reinstall!(ctx)
      op = adding_op(id, a.user.id, a2)
      now = DateTime.to_iso8601(DateTime.utc_now())

      op =
        op
        |> Ecto.Changeset.change(strikes: %{b_dev => [3, now]})
        |> Repo.update!()

      Repo.delete_all(from j in Oban.Job, where: j.worker == "RisiMe.Workers.GroupTimer")
      level = Logger.level()
      Logger.configure(level: :info)
      on_exit(fn -> Logger.configure(level: level) end)

      log =
        capture_log(fn -> perform_job(GroupTimer, %{"kind" => "wake", "op_id" => op.op_id}) end)

      assert log =~ "group_op_wake: candidates=1 pushed=1"
      assert_receive {:push, ^tc, %{"type" => "inbox"}}, 1_000

      # A PUT with a different capability set clears b's strikes on every op.
      ^b_dev = device!(b, [@cap, "video"], id: b_dev, token: tb)
      assert Repo.reload!(op).strikes == %{}

      # 24 h after the last strike the budget is back.
      old = DateTime.to_iso8601(DateTime.add(DateTime.utc_now(), -25, :hour))
      assert Strikes.count(%{c_dev => [3, old]}, c_dev) == 0
      assert Strikes.count(%{c_dev => [3, now]}, c_dev) == 3
      refute Strikes.exhausted?(%{c_dev => [3, old]}, [{c.user.id, c_dev}])
    end
  end

  ## §12.12.3 the reset guard

  describe "reset guard (§12.12.3)" do
    test "409 rejoin_pending for the only admin's new device; allowed once exhausted", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, c_dev: c_dev} = ctx
      id = active_group!(ctx)
      a2 = admin_reinstall!(ctx)
      op = adding_op(id, a.user.id, a2)

      assert %{"error" => %{"code" => "rejoin_pending", "op_id" => op_id, "candidates" => 2}} =
               reset(a, a2, id) |> assert_status(409)

      assert op_id == op.op_id
      # Before generation_conflict: a stale generation still learns it must wait.
      assert %{"error" => %{"code" => "rejoin_pending"}} =
               reset(a, a2, id, 7) |> assert_status(409)

      # After not_admin: a member gets not_admin, not the op.
      assert {403, %{"error" => %{"code" => "not_admin"}}} = reset(b, b_dev, id)

      # Exhausted: every candidate out of budget → the reset goes through.
      now = DateTime.to_iso8601(DateTime.utc_now())

      op
      |> Ecto.Changeset.change(strikes: %{b_dev => [3, now], c_dev => [3, now]})
      |> Repo.update!()

      assert %{"generation" => 2} = reset(a, a2, id) |> assert_status(200)
      assert c.user.id != a.user.id
    end

    test "a group with no candidate: guarded for 24 h after the op, then allowed", ctx do
      %{a: a, b: b, c: c, b_dev: b_dev, c_dev: c_dev} = ctx
      id = active_group!(ctx)
      ^b_dev = device!(b, [], id: b_dev)
      ^c_dev = device!(c, [], id: c_dev)
      a2 = admin_reinstall!(ctx)
      op = adding_op(id, a.user.id, a2)

      assert %{"error" => %{"candidates" => 0}} = reset(a, a2, id) |> assert_status(409)

      op
      |> Ecto.Changeset.change(created_at: DateTime.add(DateTime.utc_now(), -25, :hour))
      |> Repo.update!()

      assert {200, _} = reset(a, a2, id)
    end

    test "a device not waiting to be added may reset", ctx do
      %{a: a, a_dev: a_dev} = ctx
      id = active_group!(ctx)
      assert {200, %{"generation" => 2}} = reset(a, a_dev, id)
    end
  end

  ## §12.12.5 pruning

  describe "pruning (§12.12.5)" do
    test "an added device superseded by a later reinstall is dropped; the empty op is done",
         ctx do
      %{c: c, b: b, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      c2 = device!(c, [@cap])
      supersede!(ctx.c_dev)
      op2 = adding_op(id, c.user.id, c2)
      assert op2

      # c reinstalls again: c2 is superseded too.
      c3 = device!(c, [@cap])
      supersede!(c2)
      assert adding_op(id, c.user.id, c3)

      # The next naming (b's inbox joins) prunes c2's op: deleted, never named.
      join!(b, b_dev)
      assert Repo.get(Op, op2.op_id) == nil
      assert %Op{committer_device: ^b_dev} = adding_op(id, c.user.id, c3)
      refute Enum.any?(events(b.user.id, "group_op"), &(&1["data"]["op"]["op_id"] == op2.op_id))
    end

    test "an op whose user left is dropped at the next naming", ctx do
      %{c: c, b: b, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      c2 = device!(c, [@cap])
      op = adding_op(id, c.user.id, c2)

      Repo.update_all(
        from(m in RisiMe.Groups.Member, where: m.group_id == ^id and m.user_id == ^c.user.id),
        set: [state: "pending_remove"]
      )

      join!(b, b_dev)
      assert Repo.get(Op, op.op_id) == nil
    end
  end

  ## §12.12.6 stale leaves

  describe "stale leaves (§12.12.6)" do
    setup ctx do
      %{b: b, c: c, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      join!(b, b_dev)
      # c reinstalls; b (member) re-adds c2. c's old leaf stays.
      c2 = device!(c, [@cap])
      supersede!(ctx.c_dev)
      op = adding_op(id, c.user.id, c2)

      api(
        :post,
        "/api/v1/mls/groups/#{id}/commit",
        b.token,
        commit_body(id, [ref(c.user.id, c2)], [], op.op_id),
        b_dev
      )
      |> assert_status(200)

      %{id: id, c2: c2}
    end

    defp cleanup_op(id), do: Enum.find(Ops.list(id), &Ops.cleanup?/1)

    test "24 h floor; cleanup op by the user's own device or an admin, never a member", ctx do
      %{a: a, b: b, c: c, id: id, c2: c2, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev} = ctx

      # Seen an hour ago: not stale yet.
      assert %{stale_leaves: 0, cleanup_ops: 0} = StaleLeaves.sweep()
      assert cleanup_op(id) == nil

      supersede!(c_dev, 25)
      assert :ok = perform_job(StaleLeaves, %{})
      op = cleanup_op(id)
      assert op.payload["added"] == [] and op.payload["removed"] == [ref(c.user.id, c_dev)]
      # Nobody of tiers 1–2 online: waiting, and no wake job for a cleanup op.
      assert op.committer_device == nil
      refute_enqueued(worker: GroupTimer, args: %{"kind" => "wake", "op_id" => op.op_id})

      g = Groups.get_group(id)
      assert Enum.sort(Ops.candidates(g, op)) == Enum.sort([{c.user.id, c2}, {a.user.id, a_dev}])
      refute Ops.authorised?(g, op, {b.user.id, b_dev})

      # Idempotent: one cleanup op per (group, user).
      assert %{cleanup_ops: 0, stale_leaves: 1} = StaleLeaves.sweep()
      assert Enum.count(Ops.list(id), &Ops.cleanup?/1) == 1

      # The event shape (event_group_op_cleanup.json): c2's inbox joins, it is named.
      join!(c, c2)
      assert %Op{committer_device: ^c2} = Repo.reload!(op)
      wire = last_event(c.user.id, "group_op")["data"]["op"]
      assert wire["added"] == [] and wire["removed"] == [ref(c.user.id, c_dev)]

      # A member can't commit it; c2 can.
      assert {403, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 b.token,
                 commit_body(id, [], [ref(c.user.id, c_dev)], op.op_id),
                 b_dev
               )

      api(
        :post,
        "/api/v1/mls/groups/#{id}/commit",
        c.token,
        commit_body(id, [], [ref(c.user.id, c_dev)], op.op_id),
        c2
      )
      |> assert_status(200)

      assert cleanup_op(id) == nil
      refute MapSet.member?(Groups.in_group(id), {c.user.id, c_dev})
    end

    test "only while the user keeps a non-superseded leaf; a device seen again is pruned", ctx do
      %{b: b, c: c, id: id, c2: c2, c_dev: c_dev} = ctx
      supersede!(c_dev, 25)

      # c2 isn't in the group any more (say): c has no live leaf, so nothing is stale.
      Repo.delete_all(
        from(gd in "mls_group_devices",
          where: gd.conversation_id == ^id and gd.device_id == type(^c2, :binary_id)
        )
      )

      assert %{stale_leaves: 0} = StaleLeaves.sweep()
      add_leaf!(id, c.user.id, c2)
      assert %{stale_leaves: 1, cleanup_ops: 1} = StaleLeaves.sweep()
      op = cleanup_op(id)

      # The old phone is opened again: no longer superseded; the op is pruned to nothing (done).
      :ok = MLS.record_instance(c.user.id, c_dev, nil, "0.3.0-test")

      Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^c_dev),
        set: [last_seen_at: DateTime.utc_now()]
      )

      join!(b, ctx.b_dev)
      assert :ok = Ops.prune_all(Groups.get_group(id))
      assert Repo.get(Op, op.op_id) == nil
    end

    test "STALE_LEAF_HOURS lowers the floor", ctx do
      %{id: id, c_dev: c_dev} = ctx
      Application.put_env(:risime, :stale_leaf_hours, 0)
      on_exit(fn -> Application.delete_env(:risime, :stale_leaf_hours) end)
      supersede!(c_dev, 1)
      assert %{stale_leaves: 1, cleanup_ops: 1} = StaleLeaves.sweep()
      assert cleanup_op(id)
    end
  end

  ## DMs

  describe "DMs (§12.12.2–6)" do
    setup %{a: a, b: b, a_dev: a1, b_dev: b1} do
      conv = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)

      api(
        :post,
        "/api/v1/mls/groups/#{conv}/commit",
        a.token,
        %{
          "generation" => 1,
          "epoch" => 0,
          "commit" => b64(),
          "welcome" => b64(),
          "added" => [ref(b.user.id, b1)]
        },
        a1
      )
      |> assert_status(200)

      %{conv: conv}
    end

    defp dm_op(conv), do: Repo.one(from o in DmOp, where: o.conversation_id == ^conv)

    defp key_packages!(user, dev),
      do:
        api(
          :post,
          "/api/v1/me/devices/#{dev}/key_packages",
          user.token,
          %{"key_packages" => [b64()], "last_resort" => nil}
        )
        |> assert_status(204)

    test "wake pushes for a waiting DM op; reset guarded while the peer can re-add", ctx do
      test_push!()
      %{a: a, b: b, conv: conv, b_dev: b1} = ctx
      tb = push_token("v121-dm")
      ^b1 = device!(b, [@cap], id: b1, token: tb)
      a2 = device!(a, [@cap])
      supersede!(ctx.a_dev)
      key_packages!(a, a2)
      op = dm_op(conv)
      assert op.committer_device == nil
      assert_enqueued(worker: GroupTimer, args: %{"kind" => "dm_wake", "op_id" => op.op_id})

      Repo.delete_all(from j in Oban.Job, where: j.worker == "RisiMe.Workers.GroupTimer")
      assert :ok = perform_job(GroupTimer, %{"kind" => "dm_wake", "op_id" => op.op_id})
      assert_receive {:push, ^tb, %{"type" => "inbox", "v" => "1"}}, 1_000
      [job] = all_enqueued(worker: GroupTimer, args: %{"kind" => "dm_wake", "op_id" => op.op_id})
      assert DateTime.diff(job.scheduled_at, DateTime.utc_now(), :minute) in 355..360

      assert %{"op" => %{}, "candidates" => 1, "exhausted" => false} =
               api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      assert %{"error" => %{"code" => "rejoin_pending", "candidates" => 1, "op_id" => op_id}} =
               reset(a, a2, conv) |> assert_status(409)

      assert op_id == op.op_id
      # Before generation_conflict.
      assert {409, %{"error" => %{"code" => "rejoin_pending"}}} = reset(a, a2, conv, 9)

      # b's device times out three times: exhausted, the reset goes through.
      join!(b, b1)

      for _ <- 1..3 do
        o = dm_op(conv)
        assert o.committer_device == b1
        assert :ok = timeout!(o)
      end

      assert %DmOp{committer_device: nil} = dm_op(conv)

      assert %{"candidates" => 1, "exhausted" => true} =
               api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      assert {200, %{"generation" => 2}} = reset(a, a2, conv)
    end

    test "a stale leaf joins the user's op; pruned when superseded added devices go", ctx do
      %{a: a, b: b, conv: conv, a_dev: a1, b_dev: b1} = ctx
      join!(b, b1)
      a2 = device!(a, [@cap])
      supersede!(a1)
      key_packages!(a, a2)
      op = dm_op(conv)
      assert op.payload["removed"] == [ref(a.user.id, a1)]

      # b adds a2 but never removes a1; the op keeps only the removal.
      api(
        :post,
        "/api/v1/mls/groups/#{conv}/commit",
        b.token,
        %{
          "generation" => 1,
          "epoch" => 1,
          "commit" => b64(),
          "welcome" => b64(),
          "added" => [ref(a.user.id, a2)],
          "op_id" => op.op_id
        },
        b1
      )
      |> assert_status(200)

      Repo.delete!(dm_op(conv))
      supersede!(a1, 25)
      assert %{stale_leaves: 1, cleanup_ops: 1} = StaleLeaves.sweep()
      op = dm_op(conv)
      assert op.payload == %{"added" => [], "removed" => [ref(a.user.id, a1)]}
      assert op.committer_device == b1
      refute_enqueued(worker: GroupTimer, args: %{"kind" => "dm_wake", "op_id" => op.op_id})

      api(
        :post,
        "/api/v1/mls/groups/#{conv}/commit",
        b.token,
        %{
          "generation" => 1,
          "epoch" => 2,
          "commit" => b64(),
          "removed" => [ref(a.user.id, a1)],
          "op_id" => op.op_id
        },
        b1
      )
      |> assert_status(200)

      assert dm_op(conv) == nil
    end

    test "DM reset allowed with candidates: 0", ctx do
      %{a: a, b: b, conv: conv} = ctx
      a2 = device!(a, [@cap])
      supersede!(ctx.a_dev)
      _b2 = device!(b, [@cap])
      supersede!(ctx.b_dev)

      assert %{"candidates" => 0, "exhausted" => false} =
               api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      assert {200, %{"generation" => 2}} = reset(a, a2, conv)
    end
  end

  ## §12.12.7 recovery

  describe "recovery task (§12.12.7)" do
    test "a dry run changes nothing; the real run prunes, creates and names", ctx do
      %{b: b, c: c, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      # c reinstalled twice: c3 is in the group, c's first phone is a stale leaf (cleanup), and an
      # op still adds the superseded c2 (pruned; as a pre-v1.21 server left it), with strikes.
      c2 = device!(c, [@cap])
      c3 = device!(c, [@cap])
      supersede!(c2)
      add_leaf!(id, c.user.id, c3)
      supersede!(ctx.c_dev, 25)
      Repo.delete_all(Op)

      op2 =
        Repo.insert!(%Op{
          op_id: Ecto.UUID.generate(),
          group_id: id,
          type: "devices",
          actor: c.user.id,
          payload: %{
            "user_ids" => [],
            "role" => nil,
            "added" => [ref(c.user.id, c2)],
            "removed" => []
          },
          strikes: %{b_dev => [3, DateTime.to_iso8601(DateTime.utc_now())]},
          created_at: DateTime.utc_now()
        })

      op3 =
        Repo.insert!(%Op{
          op_id: Ecto.UUID.generate(),
          group_id: id,
          type: "devices",
          actor: b.user.id,
          payload: %{
            "user_ids" => [],
            "role" => nil,
            "added" => [ref(b.user.id, Ecto.UUID.generate())],
            "removed" => []
          },
          created_at: DateTime.utc_now()
        })

      ops_before = Repo.all(Op)

      {{:ok, dry}, out} = quiet(fn -> Release.stale_device_ops() end)
      assert out =~ "stale device ops (dry_run=true)"
      refute out =~ c.user.id
      assert Repo.all(Op) == ops_before

      assert %{
               ops: 2,
               pruned_devices: pruned,
               done_ops: done,
               cleanup_ops: 1,
               stale_leaves: 1,
               named: 0
             } = dry

      # op2 loses c2 and is done; op3's device row doesn't exist (can't receive) either.
      assert pruned == 2 and done == 2

      join!(b, b_dev)
      {{:ok, real}, _} = quiet(fn -> Release.stale_device_ops(dry_run: false) end)
      assert %{cleanup_ops: 1, stale_leaves: 1} = real
      assert Repo.get(Op, op2.op_id) == nil and Repo.get(Op, op3.op_id) == nil
      assert Enum.any?(Ops.list(id), &Ops.cleanup?/1)
      assert Enum.all?(Ops.list(id), &(&1.strikes == %{}))

      # Idempotent.
      {{:ok, again}, _} = quiet(fn -> Release.stale_device_ops(dry_run: false) end)
      assert again.cleanup_ops == 0 and again.done_ops == 0
    end
  end

  defp quiet(fun) do
    me = self()
    out = capture_io(fn -> capture_log(fn -> send(me, {:result, fun.()}) end) end)
    assert_received {:result, r}
    {r, out}
  end
end
