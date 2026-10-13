defmodule RisiMe.Groups.OfficialNeverWaitsV146Test do
  @moduledoc """
  Contract v1.46 §12.12.9 (incident 2026-10-13, decision 088): Risi's device commits the re-add of
  a member's new device in Official only; Official candidates leave out devices that can never
  commit and an Official admin may reset at `candidates: 0` at once; the creation hand-over after
  60 s; concurrent `POST …/official`. Parity: Private groups keep today's rules.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers

  alias RisiMe.{Groups, Repo}
  alias RisiMe.Groups.{Group, Op, Ops}

  setup :with_attestation_key

  setup do
    risi = risi_on!()
    [a, b] = for n <- ~w(Harsha Kamal), do: logged_in_user(display_name: n)
    befriend!(a, b)
    a_dev = tabs_device!(a)
    b_dev = tabs_device!(b)
    clear_legacy!()

    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id]}

    %{"group" => %{"id" => private}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    api(
      :post,
      "/api/v1/mls/groups/#{private}/commit",
      a.token,
      create_commit([a.user.id, b.user.id], {a.user.id, a_dev}),
      a_dev
    )
    |> assert_status(200)

    %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, private: private, risi: risi}
  end

  ## Helpers

  defp official!(chat_id, user, dev),
    do: api(:post, "/api/v1/chats/#{chat_id}/official", user.token, %{}, dev)

  defp commit_epoch0(official, user, dev, users) do
    added =
      for %{user_id: u, device_id: d} <- Groups.groups_devices(users, "tabs"),
          {u, d} != {user.user.id, dev},
          do: ref(u, d)

    api(
      :post,
      "/api/v1/mls/groups/#{official}/commit",
      user.token,
      create_commit([], {user.user.id, dev}, %{"added" => added}),
      dev
    )
  end

  defp active_official!(ctx) do
    %{a: a, b: b, a_dev: a_dev, private: private, risi: risi} = ctx
    %{"group" => %{"id" => og}} = official!(private, a, a_dev) |> assert_status(201)
    commit_epoch0(og, a, a_dev, [a.user.id, b.user.id, risi.user_id]) |> assert_status(200)
    og
  end

  # The device was last seen an hour ago, before its user's newer device (§12.1).
  defp supersede!(dev) do
    past = DateTime.add(DateTime.utc_now(), -3600, :second)

    Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^dev),
      set: [last_seen_at: past, inserted_at: DateTime.add(past, -60, :second)]
    )

    Repo.update_all(from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
      set: [last_seen_at: past]
    )
  end

  defp reinstall!(ctx, who) do
    {user, old} = if who == :a, do: {ctx.a, ctx.a_dev}, else: {ctx.b, ctx.b_dev}
    new = tabs_device!(user)
    supersede!(old)
    new
  end

  defp rejoin(user, dev, conv),
    do: api(:post, "/api/v1/groups/#{conv}/rejoin", user.token, nil, dev) |> assert_status(202)

  defp reset(user, dev, conv),
    do: api(:post, "/api/v1/mls/groups/#{conv}/reset", user.token, %{"generation" => 1}, dev)

  defp risi_commit(conv, op_id, added, removed) do
    Groups.Commit.commit(RisiMe.Risi.user_id(), RisiMe.Risi.device_id(), conv, %{
      "generation" => 1,
      "epoch" => Groups.epoch(conv),
      "commit" => b64(),
      "welcome" => if(added == [], do: nil, else: b64()),
      "added" => added,
      "removed" => removed,
      "op_id" => op_id
    })
  end

  ## Risi re-adds (§12.12.9)

  describe "Risi re-adds a member's new device in Official" do
    test "Risi is the first candidate and named; its commit with the op completes it", ctx do
      og = active_official!(ctx)
      b2 = reinstall!(ctx, :b)

      %{"op" => op, "candidates" => n, "exhausted" => false} = rejoin(ctx.b, b2, og)
      assert n >= 1
      assert op["added"] == [ref(ctx.b.user.id, b2)]
      assert op["committer"] == ref(RisiMe.Risi.user_id(), RisiMe.Risi.device_id())

      %Op{} = o = Ops.adding_op(og, {ctx.b.user.id, b2})
      assert hd(Ops.candidates(Groups.get_group(og), o)) == Ops.risi_ref()

      # Without the op_id: refused (an agent commits nothing but its own self-updates).
      assert {:error, :not_admin} = risi_commit(og, nil, [ref(ctx.b.user.id, b2)], [])
      # Lists that don't match the op: bad_request.
      assert {:error, _} = risi_commit(og, o.op_id, [ref(ctx.a.user.id, ctx.a_dev)], [])

      assert {:ok, 2} = risi_commit(og, o.op_id, [ref(ctx.b.user.id, b2)], [])
      assert Ops.adding_op(og, {ctx.b.user.id, b2}) == nil
      assert MapSet.member?(Groups.in_group(og), {ctx.b.user.id, b2})
    end

    test "never a cleanup op, never when Risi is unavailable", ctx do
      og = active_official!(ctx)
      g = Groups.get_group(og)

      cleanup = %Op{
        type: "devices",
        payload: %{"added" => [], "removed" => [ref(ctx.a.user.id, ctx.a_dev)], "cleanup" => true}
      }

      refute Ops.risi_can_commit?(g, cleanup)

      b2 = reinstall!(ctx, :b)
      rejoin(ctx.b, b2, og)
      op = Ops.adding_op(og, {ctx.b.user.id, b2})
      assert Ops.risi_can_commit?(g, op)

      Application.put_env(:risime, :risi, false)
      refute Ops.risi_can_commit?(g, op)
      Application.put_env(:risime, :risi, true)
    end

    test "a 1:1-style both-sides reinstall: Risi re-adds each side, no human needed", ctx do
      og = active_official!(ctx)
      a2 = reinstall!(ctx, :a)
      b2 = reinstall!(ctx, :b)

      for {user, dev} <- [{ctx.a, a2}, {ctx.b, b2}] do
        %{"op" => op} = rejoin(user, dev, og)
        assert op["committer"]["device_id"] == RisiMe.Risi.device_id()
        o = Ops.adding_op(og, {user.user.id, dev})
        assert {:ok, _} = risi_commit(og, o.op_id, [ref(user.user.id, dev)], [])
      end

      leaves = Groups.in_group(og)
      assert MapSet.member?(leaves, {ctx.a.user.id, a2})
      assert MapSet.member?(leaves, {ctx.b.user.id, b2})
    end
  end

  describe "parity: Private groups are unchanged (never Risi)" do
    test "a Private group's op never names Risi and Risi can't commit there", ctx do
      b2 = reinstall!(ctx, :b)
      %{"op" => op} = rejoin(ctx.b, b2, ctx.private)
      refute op["committer"] && op["committer"]["device_id"] == RisiMe.Risi.device_id()

      o = Ops.adding_op(ctx.private, {ctx.b.user.id, b2})
      refute Ops.risi_can_commit?(Groups.get_group(ctx.private), o)
      refute Ops.risi_ref() in Ops.candidates(Groups.get_group(ctx.private), o)
      assert {:error, _} = risi_commit(ctx.private, o.op_id, [ref(ctx.b.user.id, b2)], [])
    end

    test "a Private group with no candidate stays guarded for 24 h (v1.21)", ctx do
      # b's phone on an old app: the §12.4a gate is closed, no member candidate for a.
      _ = tabs_device!(ctx.b, device_id: ctx.b_dev, caps: ["groups"])
      a2 = reinstall!(ctx, :a)
      rejoin(ctx.a, a2, ctx.private)

      assert %{"error" => %{"code" => "rejoin_pending", "candidates" => 0}} =
               reset(ctx.a, a2, ctx.private) |> assert_status(409)
    end
  end

  describe "Official reset at candidates 0 (§12.12.3)" do
    test "nobody can ever re-add: the admin device may reset at once", ctx do
      og = active_official!(ctx)
      a2 = reinstall!(ctx, :a)
      # Risi is unavailable and b's phone is gone (row deleted): nobody can commit.
      Application.put_env(:risime, :risi, false)
      Repo.delete_all(from d in RisiMe.Devices.Device, where: d.device_id == ^ctx.b_dev)

      %{"candidates" => 0} = rejoin(ctx.a, a2, og)
      assert %{"generation" => 2} = reset(ctx.a, a2, og) |> assert_status(200)
    end

    test "with Risi able to re-add, the reset is still guarded", ctx do
      og = active_official!(ctx)
      a2 = reinstall!(ctx, :a)
      %{"candidates" => n} = rejoin(ctx.a, a2, og)
      assert n >= 1

      assert %{"error" => %{"code" => "rejoin_pending"}} =
               reset(ctx.a, a2, og) |> assert_status(409)
    end
  end

  ## Creation (§24.2, §12.12.9)

  describe "creation hand-over and races" do
    test "a creating Official is handed over after 60 s, not before", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, private: private, risi: risi} = ctx
      %{"group" => %{"id" => og}} = official!(private, a, a_dev) |> assert_status(201)

      %{"group" => g} = official!(private, b, b_dev) |> assert_status(200)
      assert g["created_by"] == a.user.id

      Repo.update_all(from(x in Group, where: x.id == ^og),
        set: [created_at: DateTime.add(DateTime.utc_now(), -61, :second)]
      )

      %{"group" => g} = official!(private, b, b_dev) |> assert_status(200)
      assert g["id"] == og and g["created_by"] == b.user.id

      users = [a.user.id, b.user.id, risi.user_id]
      assert {404, _} = commit_epoch0(og, a, a_dev, users)
      commit_epoch0(og, b, b_dev, users) |> assert_status(200)
      assert Groups.get_group(og).state == "active"

      # Active: no further hand-over.
      %{"group" => g} = official!(private, a, a_dev) |> assert_status(200)
      assert g["created_by"] == b.user.id
    end

    test "both users POST at the same moment: one Official group", ctx do
      %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, private: private} = ctx

      results =
        [{a, a_dev}, {b, b_dev}]
        |> Enum.map(fn {u, d} -> Task.async(fn -> official!(private, u, d) end) end)
        |> Enum.map(&Task.await(&1, 15_000))

      ids = for {status, %{"group" => %{"id" => id}}} <- results, status in [200, 201], do: id
      assert length(ids) == 2 and length(Enum.uniq(ids)) == 1
      assert Enum.sort(Enum.map(results, &elem(&1, 0))) == [200, 201]

      assert Repo.aggregate(
               from(g in Group, where: g.chat_id == ^private and g.tab == "official"),
               :count
             ) == 1
    end
  end
end
