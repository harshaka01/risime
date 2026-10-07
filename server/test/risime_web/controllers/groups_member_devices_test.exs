defmodule RisiMeWeb.GroupsMemberDevicesTest do
  @moduledoc """
  Contract v1.14 §12.4a/§12.11: any active member restores an existing member's devices. The
  committer tiers, the `member_devices` gate, commit authorisation (op id + exact lists), the
  wake pushes and the pilot recovery task.
  """
  use RisiMeWeb.ChannelCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import ExUnit.CaptureLog
  import ExUnit.CaptureIO
  require Logger
  import Ecto.Query, only: [from: 2]
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers

  alias RisiMe.{Devices, Groups, Release, Repo}
  alias RisiMe.Groups.{Op, Ops}
  alias RisiMe.Workers.GroupTimer
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

  # A groups device with `caps` (plus `groups`); the signature key is kept for re-PUTs.
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

    :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
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

  # The device was last seen before another device of its user was first registered (§12.1).
  defp supersede!(dev) do
    past = DateTime.add(DateTime.utc_now(), -3600, :second)

    Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^dev),
      set: [last_seen_at: past]
    )

    Repo.update_all(from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
      set: [last_seen_at: past]
    )
  end

  # c reinstalls: a new device c2; the old c1 is superseded but still a leaf.
  defp reinstall_c!(ctx, caps \\ [@cap]) do
    c2 = device!(ctx.c, caps)
    supersede!(ctx.c_dev)
    c2
  end

  defp commit_body(op, added, removed, op_id \\ :op) do
    %{
      "generation" => 1,
      "epoch" => RisiMe.Groups.epoch(op.group_id),
      "commit" => b64(),
      "welcome" => if(added == [], do: nil, else: b64()),
      "added" => added,
      "removed" => removed,
      "op_id" => if(op_id == :op, do: op.op_id, else: op_id),
      "meta_changed" => false
    }
  end

  defp info_logs! do
    level = Logger.level()
    Logger.configure(level: :info)
    on_exit(fn -> Logger.configure(level: level) end)
  end

  defp waiting_op(id) do
    [op] = Ops.list(id)
    op
  end

  describe "committer naming and authorisation (§12.4a)" do
    test "a member's device is named for a reinstalled member's op and completes it", ctx do
      %{b: b, c: c, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      c2 = reinstall_c!(ctx)

      # Nobody online: the op waits; a wake job is queued.
      op = waiting_op(id)
      assert op.type == "devices" and op.committer_device == nil
      assert Ops.member_committable?(Groups.get_group(id), op)
      assert Ops.gate_open?(Groups.get_group(id), op)

      assert_enqueued(worker: GroupTimer, args: %{"kind" => "wake", "op_id" => op.op_id})

      # b (a plain member) opens the app: named by the join rule, as the third tier.
      join!(b, b_dev)
      op = Repo.get(Op, op.op_id)
      assert op.committer_device == b_dev
      assert last_event(b.user.id, "group_op")["data"]["op"]["committer"]["device_id"] == b_dev

      # Without op_id (list-only): refused for a non-admin on another user's leaves.
      assert {403, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 b.token,
                 commit_body(op, [ref(c.user.id, c2)], [], nil),
                 b_dev
               )

      # Lists that don't match the op exactly.
      assert {400, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 b.token,
                 commit_body(op, [ref(c.user.id, c2), ref(c.user.id, Ecto.UUID.generate())], []),
                 b_dev
               )

      info_logs!()

      log =
        capture_log(fn ->
          assert {200, %{"epoch" => 2}} =
                   api(
                     :post,
                     "/api/v1/mls/groups/#{id}/commit",
                     b.token,
                     commit_body(op, [ref(c.user.id, c2)], []),
                     b_dev
                   )
        end)

      assert log =~ "committer_role=member"
      assert Ops.list(id) == []
      assert MapSet.member?(Groups.in_group(id), {c.user.id, c2})
      assert last_event(c.user.id, "mls_welcome")["data"]["to_devices"] == [c2]
    end

    test "tier order: own devices, then admins, then members; agents never", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      g = Groups.get_group(id)
      c2 = reinstall_c!(ctx)
      op = waiting_op(id)

      # c's old (superseded, offline) phone is tier 1; a admin; b member.
      assert Ops.candidates(g, op) == [
               {c.user.id, ctx.c_dev},
               {a.user.id, a_dev},
               {b.user.id, b_dev}
             ]

      # Online admin wins over an online member.
      join!(b, b_dev)
      join!(a, a_dev)
      op = Ops.name_next(g, Repo.get(Op, op.op_id))
      assert op.committer_device in [a_dev, b_dev]
      op = Ops.name_next(g, Repo.reload!(op))
      # Each tier is tried in turn; both are authorised.
      assert Ops.authorised?(g, op, {a.user.id, a_dev})
      assert Ops.authorised?(g, op, {b.user.id, b_dev})
      refute Ops.authorised?(g, op, {c.user.id, c2})

      # An agent member is never a member-tier candidate nor authorised.
      Repo.update_all(
        from(m in RisiMe.Groups.Member, where: m.group_id == ^id and m.user_id == ^b.user.id),
        set: [kind: "agent"]
      )

      assert Ops.candidates(g, op) == [{c.user.id, ctx.c_dev}, {a.user.id, a_dev}]
      refute Ops.authorised?(g, op, {b.user.id, b_dev})
    end

    test "the gate: a non-superseded leaf without member_devices closes it", ctx do
      %{b: b, c: c, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      c2 = reinstall_c!(ctx)
      g = Groups.get_group(id)

      # An old app on b's second phone (in the group, seen recently): closed.
      b2 = device!(b, [])
      MLSFix.add_leaf!(id, b.user.id, b2)
      op = Enum.find(Ops.list(id), &(Ops.refs(&1, "added") == [{c.user.id, c2}]))
      refute Ops.gate_open?(g, op)
      refute Ops.member_path?(g, op)
      refute Ops.authorised?(g, op, {b.user.id, b_dev})
      refute {b.user.id, b_dev} in Ops.candidates(g, op)

      assert {403, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 b.token,
                 commit_body(op, [ref(c.user.id, c2)], []),
                 b_dev
               )

      # Superseded: it can't receive, so it doesn't block.
      supersede!(b2)
      assert Ops.gate_open?(g, op)

      # A leaf whose device row is gone doesn't block either.
      MLSFix.add_leaf!(id, b.user.id, Ecto.UUID.generate())
      assert Ops.gate_open?(g, op)

      # c's old phone without the capability, not superseded: closed (it would reject).
      Repo.update_all(
        from(d in RisiMe.Devices.Device, where: d.device_id == ^ctx.c_dev),
        set: [capabilities: ["groups"], last_seen_at: DateTime.utc_now()]
      )

      RisiMe.MLS.record_instance(c.user.id, ctx.c_dev, nil, "0.3.0-test")
      refute Ops.gate_open?(g, op)
    end

    test "not member-committable: no leaf left, removal only, another device swap", ctx do
      %{b: b, c: c, b_dev: b_dev, c_dev: c_dev} = ctx
      id = active_group!(ctx)
      g = Groups.get_group(id)
      join!(b, b_dev)

      # A rejoin of c's only leaf: committable (c holds a leaf, the same device is re-added).
      {202, _} = api(:post, "/api/v1/groups/#{id}/rejoin", c.token, nil, c_dev)
      op = waiting_op(id)
      assert Ops.member_committable?(g, op)
      assert Repo.get(Op, op.op_id).committer_device == b_dev

      # Its old leaf is gone (c has no leaf left): admin-only.
      Repo.delete_all(
        from(gd in "mls_group_devices",
          where: gd.conversation_id == ^id and gd.device_id == type(^c_dev, :binary_id)
        )
      )

      refute Ops.member_committable?(g, op)
      refute Ops.authorised?(g, op, {b.user.id, b_dev})
      Repo.delete!(op)

      # Removal only, and a swap for another device: never a member's.
      MLSFix.add_leaf!(id, c.user.id, c_dev)
      other = Ecto.UUID.generate()

      for {added, removed} <- [
            {[], [{c.user.id, c_dev}]},
            {[{c.user.id, other}], [{c.user.id, c_dev}]}
          ] do
        op = Ops.create(g, "devices", c.user.id, %{added: added, removed: removed}, nil)
        refute Ops.member_committable?(g, op)
        refute Ops.authorised?(g, op, {b.user.id, b_dev})
        Repo.delete!(op)
      end
    end

    test "a member can't use the path for adds of a user not in the group", ctx do
      %{a: a, b: b, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      g = Groups.get_group(id)
      d = logged_in_user(display_name: "Dinesh")
      befriend!(a, d)
      d_dev = device!(d, [@cap])

      op = Ops.create(g, "devices", d.user.id, %{added: [{d.user.id, d_dev}]}, nil)
      refute Ops.member_committable?(g, op)

      assert {403, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{id}/commit",
                 b.token,
                 commit_body(op, [ref(d.user.id, d_dev)], []),
                 b_dev
               )
    end
  end

  describe "wake pushes (§12.4a)" do
    setup :test_push!

    test "the first candidates' own tokens, skipping online devices; 4 per device per day", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev} = ctx
      ta = push_token("wake-a")
      tb = push_token("wake-b")
      ^a_dev = device!(a, [@cap], id: a_dev, token: ta)
      ^b_dev = device!(b, [@cap], id: b_dev, token: tb)
      id = active_group!(ctx)
      _c2 = reinstall_c!(ctx)
      op = waiting_op(id)
      assert_enqueued(worker: GroupTimer, args: %{"kind" => "wake", "op_id" => op.op_id})
      # Oban runs the queued (immediate) wake: in manual testing mode, take it off the queue.
      Repo.delete_all(from j in Oban.Job, where: j.worker == "RisiMe.Workers.GroupTimer")
      info_logs!()

      log =
        capture_log(fn ->
          assert :ok = perform_job(GroupTimer, %{"kind" => "wake", "op_id" => op.op_id})
        end)

      assert_receive {:push, ^ta, %{"type" => "inbox", "v" => "1"}}, 1_000
      assert_receive {:push, ^tb, %{"type" => "inbox", "v" => "1"}}, 1_000
      assert log =~ "group_op_wake: candidates=3 pushed=2"
      refute log =~ c.user.id

      # The next check is queued 6 h out (one per op).
      assert [job] =
               all_enqueued(worker: GroupTimer, args: %{"kind" => "wake", "op_id" => op.op_id})

      assert DateTime.diff(job.scheduled_at, DateTime.utc_now(), :minute) in 355..360

      # Three more reach each device today; the fifth doesn't.
      # (Other pushes, e.g. for the `created` event, may also arrive: count the wake log lines.)
      log =
        capture_log(fn ->
          for _ <- 1..4, do: perform_job(GroupTimer, %{"kind" => "wake", "op_id" => op.op_id})
        end)

      assert length(String.split(log, "candidates=3 pushed=2")) - 1 == 3
      assert log =~ "candidates=3 pushed=0"

      # An online candidate is skipped (and the op gets named on its join).
      join!(b, b_dev)
      assert Repo.get(Op, op.op_id).committer_device == b_dev

      log =
        capture_log(fn -> perform_job(GroupTimer, %{"kind" => "wake", "op_id" => op.op_id}) end)

      refute log =~ "group_op_wake"

      # Waiting again while b stays online: b is skipped (a and c's old phone remain).
      unname!(Repo.get(Op, op.op_id))

      log =
        capture_log(fn -> perform_job(GroupTimer, %{"kind" => "wake", "op_id" => op.op_id}) end)

      assert log =~ "group_op_wake: candidates=2 pushed=0"
    end
  end

  describe "pilot recovery (§12.11)" do
    test "dry run counts only; the real run names; a re-run is harmless", ctx do
      %{b: b, b_dev: b_dev} = ctx
      id = active_group!(ctx)
      join!(b, b_dev)
      _c2 = reinstall_c!(ctx)
      # A pre-deploy op that waits with nobody named (b is online).
      op = unname!(waiting_op(id))

      capture_io(fn ->
        capture_log(fn ->
          assert {:ok, counts} = Release.name_pending_device_ops()
          assert counts.devices_ops == 1 and counts.waiting == 1
          assert counts.member_committable == 1 and counts.gate_closed == 0
          assert counts.with_candidates == 1 and counts.named == 0
          assert Repo.get(Op, op.op_id).committer_device == nil

          assert {:ok, %{named: 1, still_waiting: 0}} =
                   Release.name_pending_device_ops(dry_run: false)

          assert Repo.get(Op, op.op_id).committer_device == b_dev
          assert {:ok, %{waiting: 0, named: 0}} = Release.name_pending_device_ops(dry_run: false)

          # The eval path's job (run by the server) does the same.
          assert :ok = perform_job(GroupTimer, %{"kind" => "name_pending"})
        end)
      end)
    end

    test "a PUT that newly adds member_devices re-runs naming for the user's groups", ctx do
      %{a: a, b: b, b_dev: b_dev} = ctx
      # b's phone is on an older app: the gate is closed.
      ^b_dev = device!(b, [], id: b_dev)
      id = active_group!(ctx)
      _c2 = reinstall_c!(ctx)
      op = waiting_op(id)
      join!(b, b_dev)
      refute Ops.gate_open?(Groups.get_group(id), op)
      assert Repo.get(Op, op.op_id).committer_device == nil

      # b updates: the PUT advertises member_devices, the gate opens, b (online) is named.
      ^b_dev = device!(b, [@cap], id: b_dev)
      assert Repo.get(Op, op.op_id).committer_device == b_dev
      refute a.user.id == b.user.id
    end
  end

  defp unname!(op) do
    op
    |> Ecto.Changeset.change(committer_user: nil, committer_device: nil, committer_until: nil)
    |> Repo.update!()
  end
end

defmodule MLSFix do
  @moduledoc false
  def add_leaf!(group_id, user_id, device_id) do
    RisiMe.Repo.insert_all("mls_group_devices", [
      %{
        conversation_id: group_id,
        user_id: Ecto.UUID.dump!(user_id),
        device_id: Ecto.UUID.dump!(device_id)
      }
    ])
  end
end
