defmodule RisiMeWeb.MLSDmReaddTest do
  @moduledoc """
  v1.16 (proposal 2026-10-07-dm-device-readd): DMs re-add a member's new device. DM `devices` ops
  (creation on key-package upload and rejoin, naming, `mls_dm_op`), the DM commit rule with
  `op_id`, settling, the DM reset and rebuild, and the pilot recovery task.
  """
  use RisiMeWeb.ChannelCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import ExUnit.CaptureIO
  import ExUnit.CaptureLog
  import Ecto.Query, only: [from: 2]
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers, except: [ref: 2]

  alias RisiMe.{Devices, MLS, Release, Repo}
  alias RisiMe.MLS.{DmOp, DmOps}
  alias RisiMe.Workers.GroupTimer
  alias RisiMeWeb.{InboxChannel, UserSocket}

  @cap "member_devices"

  setup :with_attestation_key

  setup do
    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    befriend!(a, b)
    clear_legacy!()
    a1 = device!(a, [@cap])
    b1 = device!(b, [@cap])
    conv = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)
    %{a: a, b: b, a1: a1, b1: b1, conv: conv}
  end

  defp device!(user, caps) do
    dev = Ecto.UUID.generate()

    {:ok, _} =
      Devices.register(user.user.id, dev, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => ["groups" | caps]
        }
      })

    :ok = MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end

  # A later install of the same user: the earlier ones become superseded (§12.1).
  defp reinstall!(user, caps \\ [@cap]) do
    Process.sleep(10)
    device!(user, caps)
  end

  defp key_packages!(user, dev),
    do:
      api(
        :post,
        "/api/v1/me/devices/#{dev}/key_packages",
        user.token,
        %{"key_packages" => [b64()], "last_resort" => nil}
      )
      |> assert_status(204)

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    chan
  end

  defp ref(user, dev), do: %{"user_id" => user.user.id, "device_id" => dev}

  defp e2ee!(%{a: a, b: b, a1: a1, b1: b1, conv: conv}) do
    api(
      :post,
      "/api/v1/mls/groups/#{conv}/commit",
      a.token,
      %{
        "generation" => 1,
        "epoch" => 0,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [ref(b, b1)]
      },
      a1
    )
    |> assert_status(200)

    conv
  end

  defp commit(who, dev, conv, epoch, body, generation \\ 1),
    do:
      api(
        :post,
        "/api/v1/mls/groups/#{conv}/commit",
        who.token,
        Map.merge(%{"generation" => generation, "epoch" => epoch, "commit" => b64()}, body),
        dev
      )

  defp leaves(who, conv) do
    %{"devices" => devices} =
      api(:get, "/api/v1/mls/groups/#{conv}", who.token) |> assert_status(200)

    devices |> Enum.map(& &1["device_id"]) |> Enum.sort()
  end

  defp op(conv), do: Repo.one(from o in DmOp, where: o.conversation_id == ^conv)

  describe "a reinstall is re-added by the peer (§2)" do
    test "key packages create the op; the peer adds the new device, then drops the superseded leaf",
         %{a: a, b: b, a1: a1, b1: b1} = ctx do
      conv = e2ee!(ctx)
      join!(b, b1)
      a2 = reinstall!(a)
      key_packages!(a, a2)

      op = op(conv)
      assert op.user_id == a.user.id
      assert op.payload == %{"added" => [ref(a, a2)], "removed" => [ref(a, a1)]}
      assert {op.committer_user, op.committer_device} == {b.user.id, b1}

      assert %{"data" => %{"conversation_id" => ^conv, "generation" => 1, "op" => wire}} =
               last_event(b.user.id, "mls_dm_op")

      assert wire["type"] == "devices" and wire["actor"] == a.user.id
      assert wire["committer"] == ref(b, b1) and wire["op_id"] == op.op_id

      assert_enqueued(
        worker: GroupTimer,
        args: %{"kind" => "dm_committer", "op_id" => op.op_id, "naming" => op.naming}
      )

      # The superseded leaf may go only with the op, and only once A has a live leaf.
      rm = %{"removed" => [ref(a, a1)]}
      assert {400, _} = commit(b, b1, conv, 1, rm)
      assert {400, _} = commit(b, b1, conv, 1, Map.put(rm, "op_id", op.op_id))
      assert {400, _} = commit(b, b1, conv, 1, Map.put(rm, "op_id", Ecto.UUID.generate()))

      add = %{"added" => [ref(a, a2)], "welcome" => b64(), "op_id" => op.op_id}
      assert %{"epoch" => 2} = commit(b, b1, conv, 1, add) |> assert_status(200)
      assert %DmOp{payload: %{"removed" => [_]}} = op(conv)

      assert %{"epoch" => 3} =
               commit(b, b1, conv, 2, Map.put(rm, "op_id", op.op_id)) |> assert_status(200)

      assert op(conv) == nil
      assert leaves(a, conv) == Enum.sort([a2, b1])
      assert [%{"data" => %{"to_devices" => [^a2]}}] = events(a.user.id, "mls_welcome")
    end

    test "an old peer adds the device without the op; the op then only removes the old leaf",
         %{a: a, b: b, a1: a1, b1: b1} = ctx do
      conv = e2ee!(ctx)
      a2 = reinstall!(a)
      key_packages!(a, a2)
      %DmOp{op_id: op_id} = op(conv)

      # The §10.3 mls_membership path of a pre-v1.16 app: no op_id.
      commit(b, b1, conv, 1, %{"added" => [ref(a, a2)], "welcome" => b64()}) |> assert_status(200)
      assert %DmOp{payload: %{"added" => [_], "removed" => [_]}} = op(conv)

      commit(b, b1, conv, 2, %{"removed" => [ref(a, a1)], "op_id" => op_id})
      |> assert_status(200)

      assert op(conv) == nil
    end

    test "never the other user's live (non-superseded) leaf, even with an op",
         %{a: a, b: b} = ctx do
      conv = e2ee!(ctx)
      # A second phone still in use: a1 is seen again, so it isn't superseded.
      a2 = reinstall!(a)
      :ok = MLS.record_instance(a.user.id, ctx.a1, nil, "0.3.0-test")
      key_packages!(a, a2)
      op = op(conv)
      assert op.payload == %{"added" => [ref(a, a2)], "removed" => []}

      commit(b, ctx.b1, conv, 1, %{
        "added" => [ref(a, a2)],
        "welcome" => b64(),
        "op_id" => op.op_id
      })
      |> assert_status(200)

      assert {400, _} =
               commit(b, ctx.b1, conv, 2, %{"removed" => [ref(a, ctx.a1)], "op_id" => op.op_id})

      assert op(conv) == nil
    end
  end

  describe "rejoin (§4)" do
    test "the new device asks; idempotent; candidates count offline devices",
         %{a: a, b: b} = ctx do
      conv = e2ee!(ctx)
      a2 = reinstall!(a)

      assert %{"op" => op1, "candidates" => 1} =
               api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      # B's device is offline: nobody named yet.
      assert op1["added"] == [ref(a, a2)] and op1["committer"] == nil

      assert %{"op" => %{"op_id" => same}} =
               api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      assert same == op1["op_id"]

      # B's inbox joins: it is named.
      join!(b, ctx.b1)
      assert %DmOp{committer_device: b1} = op(conv)
      assert b1 == ctx.b1
      assert %{"data" => %{"op" => %{"op_id" => ^same}}} = last_event(b.user.id, "mls_dm_op")

      # Errors: not a participant, no device header, a stranger's DM.
      c = logged_in_user()
      assert {404, _} = api(:post, "/api/v1/mls/groups/#{conv}/rejoin", c.token, nil, a2)
      assert {400, _} = api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, nil)
    end

    test "a device that lost its state is removed and re-added (same device)",
         %{a: a, b: b} = ctx do
      conv = e2ee!(ctx)
      join!(b, ctx.b1)

      %{"op" => %{"op_id" => op_id} = op} =
        api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, ctx.a1)
        |> assert_status(202)

      assert op["added"] == [ref(a, ctx.a1)] and op["removed"] == [ref(a, ctx.a1)]

      commit(b, ctx.b1, conv, 1, %{"removed" => [ref(a, ctx.a1)], "op_id" => op_id})
      |> assert_status(200)

      assert %DmOp{payload: %{"removed" => []}} = op(conv)

      commit(b, ctx.b1, conv, 2, %{
        "added" => [ref(a, ctx.a1)],
        "welcome" => b64(),
        "op_id" => op_id
      })
      |> assert_status(200)

      assert op(conv) == nil
      assert leaves(a, conv) == Enum.sort([ctx.a1, ctx.b1])
    end

    test "naming: member_devices required; own devices first; the timer names the next",
         %{a: a, b: b} = ctx do
      conv = e2ee!(ctx)
      # B's second phone (in the group) lacks member_devices; b1 stays live.
      b_old = reinstall!(b, [])
      :ok = MLS.record_instance(b.user.id, ctx.b1, nil, "0.3.0-test")

      commit(b, ctx.b1, conv, 1, %{"added" => [ref(b, b_old)], "welcome" => b64()})
      |> assert_status(200)

      join!(b, b_old)
      join!(b, ctx.b1)
      a2 = reinstall!(a)
      key_packages!(a, a2)
      op = op(conv)
      assert op.committer_device == ctx.b1
      assert DmOps.candidates(op) == [{b.user.id, ctx.b1}]

      # 60 s pass with no commit: b1 again (the only candidate), then waiting after the cap.
      for _ <- 1..8 do
        o = op(conv)

        assert :ok =
                 perform_job(GroupTimer, %{
                   "kind" => "dm_committer",
                   "op_id" => o.op_id,
                   "naming" => o.naming
                 })
      end

      assert %DmOp{committer_device: nil} = op(conv)
      # A stale timer is a no-op.
      assert :ok =
               perform_job(GroupTimer, %{
                 "kind" => "dm_committer",
                 "op_id" => op.op_id,
                 "naming" => 0
               })
    end
  end

  describe "reset and rebuild (§3)" do
    test "both reinstalled: no candidate → reset → rebuild at generation 2 without superseded installs",
         %{a: a, b: b} = ctx do
      conv = e2ee!(ctx)
      a2 = reinstall!(a)
      b2 = reinstall!(b)

      assert %{"candidates" => 0} =
               api(:post, "/api/v1/mls/groups/#{conv}/rejoin", a.token, nil, a2)
               |> assert_status(202)

      assert %{"generation" => 2} =
               api(:post, "/api/v1/mls/groups/#{conv}/reset", a.token, %{"generation" => 1}, a2)
               |> assert_status(200)

      assert op(conv) == nil
      assert MLS.e2ee?(conv)

      assert %{"e2ee" => true, "generation" => 2, "epoch" => nil, "devices" => []} =
               api(:get, "/api/v1/mls/groups/#{conv}", b.token) |> assert_status(200)

      assert %{"error" => %{"code" => "generation_conflict", "generation" => 2}} =
               api(:post, "/api/v1/mls/groups/#{conv}/reset", b.token, %{"generation" => 1}, b2)
               |> assert_status(409)

      assert %{"op" => nil, "candidates" => 0} =
               api(:post, "/api/v1/mls/groups/#{conv}/rejoin", b.token, nil, b2)
               |> assert_status(202)

      # Ciphertext at the old generation is stale; plaintext stays refused (never downgraded).
      assert {409, _} = commit(a, a2, conv, 1, %{"added" => [ref(b, b2)], "welcome" => b64()})

      # The rebuild must add every non-superseded device (b2), may skip superseded ones.
      assert {400, _} =
               commit(a, a2, conv, 0, %{"added" => [ref(b, ctx.b1)], "welcome" => b64()}, 2)

      assert %{"epoch" => 1} =
               commit(a, a2, conv, 0, %{"added" => [ref(b, b2)], "welcome" => b64()}, 2)
               |> assert_status(200)

      assert %{"error" => %{"code" => "epoch_conflict"}} =
               commit(b, b2, conv, 0, %{"added" => [ref(a, a2)], "welcome" => b64()}, 2)
               |> assert_status(409)

      assert leaves(b, conv) == Enum.sort([a2, b2])

      assert [%{"data" => %{"generation" => 2, "to_devices" => [^b2]}} | _] =
               Enum.reverse(events(b.user.id, "mls_welcome"))

      assert %{"commits" => [%{"epoch" => 0}]} =
               api(:get, "/api/v1/mls/groups/#{conv}/commits?since_epoch=0", b.token)
               |> assert_status(200)
    end

    test "errors and the rate limit", %{a: a, b: b} = ctx do
      conv = e2ee!(ctx)
      c = logged_in_user()
      befriend!(a, c)
      plain = RisiMe.Messaging.conversation_id(a.user.id, c.user.id)
      path = "/api/v1/mls/groups/#{conv}/reset"

      assert {404, _} =
               api(
                 :post,
                 "/api/v1/mls/groups/#{plain}/reset",
                 a.token,
                 %{"generation" => 1},
                 ctx.a1
               )

      assert {400, _} = api(:post, path, a.token, %{"generation" => "1"}, ctx.a1)
      assert {400, _} = api(:post, path, a.token, %{"generation" => 1}, ctx.b1)

      for g <- 1..3 do
        assert %{"generation" => n} =
                 api(:post, path, a.token, %{"generation" => g}, ctx.a1) |> assert_status(200)

        assert n == g + 1
      end

      assert {429, _} = api(:post, path, b.token, %{"generation" => 4}, ctx.b1)
    end
  end

  describe "pilot recovery (§7)" do
    test "dry run counts and changes nothing; the real run creates and names",
         %{a: a, b: b} = ctx do
      conv = e2ee!(ctx)
      join!(b, ctx.b1)
      # A reinstall that never uploaded key packages here (no op yet), and a DM with no candidate.
      _a2 = reinstall!(a)
      assert op(conv) == nil

      {dry, out} = quiet(fn -> Release.dm_device_ops() end)
      assert out =~ "dm device ops (dry_run=true)"
      refute out =~ a.user.id
      assert op(conv) == nil
      assert {:ok, dry} = dry

      assert %{
               dms: 1,
               affected_dms: 1,
               missing_devices: 1,
               superseded_leaves: 1,
               with_candidates: 1,
               without_candidates: 0,
               ops: 0,
               named: 0
             } = dry

      {{:ok, real}, _} = quiet(fn -> Release.dm_device_ops(dry_run: false) end)
      assert %{affected_dms: 1, ops: 1, named: 1} = real
      assert %DmOp{committer_device: b1} = op(conv)
      assert b1 == ctx.b1

      # Idempotent.
      {{:ok, again}, _} = quiet(fn -> Release.dm_device_ops(dry_run: false) end)
      assert again.ops == 1
      assert Repo.aggregate(DmOp, :count) == 1
    end
  end

  defp quiet(fun) do
    me = self()
    out = capture_io(fn -> capture_log(fn -> send(me, {:result, fun.()}) end) end)
    assert_received {:result, r}
    {r, out}
  end
end
