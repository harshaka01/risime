defmodule RisiMe.Agents.HealthTest do
  @moduledoc """
  Decision 088 (contract v1.46 §12.12.9): the Health agent finds conversations stuck more than
  5 minutes, repairs only what is safe and only in Official (Risi named again; a reset when nobody
  can ever re-add), never touches Private groups or DMs, records every case and scores the day.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers

  alias RisiMe.Agents.Health
  alias RisiMe.{Groups, Repo}
  alias RisiMe.Groups.{Group, Ops}

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

    Application.put_env(:risime, :health_stuck_s, 0)
    on_exit(fn -> Application.delete_env(:risime, :health_stuck_s) end)

    %{a: a, b: b, a_dev: a_dev, b_dev: b_dev, private: private, risi: risi}
  end

  defp official!(ctx) do
    %{a: a, b: b, a_dev: a_dev, private: private, risi: risi} = ctx

    %{"group" => %{"id" => og}} =
      api(:post, "/api/v1/chats/#{private}/official", a.token, %{}, a_dev) |> assert_status(201)

    added =
      for %{user_id: u, device_id: d} <-
            Groups.groups_devices([a.user.id, b.user.id, risi.user_id], "tabs"),
          {u, d} != {a.user.id, a_dev},
          do: ref(u, d)

    api(
      :post,
      "/api/v1/mls/groups/#{og}/commit",
      a.token,
      create_commit([], {a.user.id, a_dev}, %{"added" => added}),
      a_dev
    )
    |> assert_status(200)

    og
  end

  defp supersede!(dev) do
    past = DateTime.add(DateTime.utc_now(), -3600, :second)

    Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^dev),
      set: [last_seen_at: past, inserted_at: DateTime.add(past, -60, :second)]
    )

    Repo.update_all(from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
      set: [last_seen_at: past]
    )
  end

  defp reinstall_a!(ctx, conv) do
    a2 = tabs_device!(ctx.a)
    supersede!(ctx.a_dev)
    api(:post, "/api/v1/groups/#{conv}/rejoin", ctx.a.token, nil, a2) |> assert_status(202)
    a2
  end

  defp found(result, conv), do: Enum.filter(result.found, &(&1.conv == conv))

  test "Official re-add Risi can commit: Risi is named again (repaired)", ctx do
    og = official!(ctx)
    a2 = reinstall_a!(ctx, og)
    op = Ops.adding_op(og, {ctx.a.user.id, a2})
    # The naming ran out (a strike-free wait): no committer now.
    op |> Ecto.Changeset.change(committer_device: nil, committer_user: nil) |> Repo.update!()

    r = Health.sweep(:on)
    assert [%{kind: "readd", action: "renamed_risi", repaired: true}] = found(r, og)
    assert Ops.adding_op(og, {ctx.a.user.id, a2}).committer_device == RisiMe.Risi.device_id()
  end

  test "Official re-add nobody can ever commit: reset with the waiting admin device", ctx do
    og = official!(ctx)
    a2 = reinstall_a!(ctx, og)
    Application.put_env(:risime, :risi, false)
    Repo.delete_all(from d in RisiMe.Devices.Device, where: d.device_id == ^ctx.b_dev)

    r = Health.sweep(:on)
    assert [%{action: "reset", repaired: true}] = found(r, og)
    assert Groups.get_group(og).generation == 2

    [rebuild] = Ops.list(og)
    assert rebuild.type == "rebuild" and rebuild.committer_device == a2
  end

  test "shadow and canary without a dev user repair nothing; a Private group is never touched",
       ctx do
    og = official!(ctx)
    a2 = reinstall_a!(ctx, og)
    a3 = tabs_device!(ctx.a)
    api(:post, "/api/v1/groups/#{ctx.private}/rejoin", ctx.a.token, nil, a3)

    Application.put_env(:risime, :risi_dev_users, [])
    on_exit(fn -> Application.delete_env(:risime, :risi_dev_users) end)

    for mode <- [:shadow, :canary] do
      r = Health.sweep(mode)
      assert Enum.all?(found(r, og), &(&1.action == "shadow" and not &1.repaired))
      assert Groups.get_group(og).generation == 1
    end

    r = Health.sweep(:on)
    assert [%{tab: "private", action: "alert", repaired: false} | _] = found(r, ctx.private)
    assert Groups.get_group(ctx.private).generation == 1
    assert Ops.adding_op(ctx.private, {ctx.a.user.id, a3}) != nil
    assert a2 != a3

    # Canary with Harsha as the dev user: his chats are repaired.
    Application.put_env(:risime, :risi_dev_users, [ctx.a.user.id])
    r = Health.sweep(:canary)
    assert Enum.any?(found(r, og), & &1.repaired)
  end

  test "a stale creating Official is expired; the day is recorded and scored", ctx do
    %{"group" => %{"id" => og}} =
      api(:post, "/api/v1/chats/#{ctx.private}/official", ctx.a.token, %{}, ctx.a_dev)
      |> assert_status(201)

    r = Health.sweep(:on)
    assert [%{kind: "creating", action: "expired_creating", repaired: true}] = found(r, og)
    assert Repo.get(Group, og) == nil

    s = Health.day_score(Date.utc_today())
    assert s.n >= 1 and s.repaired > 0
    assert is_float(s.reliability)

    rows = RisiMe.Agents.Scoreboard.compute(Date.utc_today())
    assert %{n: n} = Enum.find(rows, &(&1.agent == "health"))
    assert n >= 1
  end

  test "off does nothing" do
    assert %{stuck: 0, found: []} = Health.sweep(:off)
  end
end
