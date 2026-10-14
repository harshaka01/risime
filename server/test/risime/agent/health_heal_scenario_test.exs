defmodule RisiMe.Agents.HealthHealScenarioTest do
  @moduledoc """
  Gate scenario of incident 2026-10-14 (`scripts/health-heal-test`, `HHTEST OK`): H reinstalls
  while one peer is on the old app with no push token and another is offline. Official heals
  without any peer (Risi re-adds H's new phone); the Private 1:1s wait, the peers are nudged once,
  the operator gets one plain report; the peers come back, their phones re-add H's phone, and the
  next report says fixed. Nothing is reported with an id.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  @moduletag capture_log: true
  @moduletag :health_heal

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers
  import RisiMe.RisiHelpers, only: [own_risi_chat!: 1, restore_on_exit: 1]

  alias RisiMe.Agents.Health
  alias RisiMe.{Devices, Groups, MLS, Repo}
  alias RisiMe.Groups.Ops
  alias RisiMe.MLS.{DmOp, DmOps}

  setup :with_attestation_key

  setup do
    restore_on_exit([:risi, :risi_test_pid, :risi_dev_users, :health_stuck_s, :risi_agent_flags])
    risi = risi_on!()
    Application.put_env(:risime, :risi_test_pid, self())
    Application.put_env(:risime, :health_stuck_s, 0)
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_HEALTH_NUDGE" => "canary"})
    :ok
    %{risi: risi}
  end

  defp old_app_device!(user) do
    dev = Ecto.UUID.generate()

    {:ok, _} =
      Devices.register(user.user.id, dev, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => ["groups", "member_devices"]
        }
      })

    :ok = MLS.record_instance(user.user.id, dev, nil, "0.2.0-nightly.43")
    dev
  end

  defp e2ee_dm!(h, h1, p, p1) do
    conv = RisiMe.Messaging.conversation_id(h.user.id, p.user.id)

    api(
      :post,
      "/api/v1/mls/groups/#{conv}/commit",
      h.token,
      %{
        "generation" => 1,
        "epoch" => 0,
        "commit" => b64(),
        "welcome" => b64(),
        "added" => [ref(p.user.id, p1)]
      },
      h1
    )
    |> assert_status(200)

    conv
  end

  defp supersede!(dev) do
    past = DateTime.add(DateTime.utc_now(), -3600, :second)

    Repo.update_all(from(d in Devices.Device, where: d.device_id == ^dev),
      set: [last_seen_at: past, inserted_at: DateTime.add(past, -60, :second)]
    )

    Repo.update_all(from(i in "app_instances", where: i.instance_key == ^("device:" <> dev)),
      set: [last_seen_at: past]
    )
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, _conv, body, risi} -> posts([{body, risi} | acc])
    after
      0 -> Enum.reverse(acc)
    end
  end

  test "a reinstall with an old-app peer and an offline peer: Official heals at once, Private heals when they come back",
       %{risi: risi} do
    h = logged_in_user(display_name: "Harsha")
    old = logged_in_user(display_name: "Olda")
    off = logged_in_user(display_name: "Offa")
    for p <- [old, off], do: befriend!(h, p)
    h1 = tabs_device!(h)
    old1 = old_app_device!(old)
    off1 = tabs_device!(off, push_token: "tok-" <> Ecto.UUID.generate())
    clear_legacy!()

    dm_old = e2ee_dm!(h, h1, old, old1)
    dm_off = e2ee_dm!(h, h1, off, off1)

    # The 1:1 with the offline peer has an Official (Risi a member).
    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [off.user.id]}

    %{"group" => %{"id" => private}} =
      api(:post, "/api/v1/groups", h.token, body, h1) |> assert_status(201)

    api(
      :post,
      "/api/v1/mls/groups/#{private}/commit",
      h.token,
      create_commit([h.user.id, off.user.id], {h.user.id, h1}),
      h1
    )
    |> assert_status(200)

    %{"group" => %{"id" => og}} =
      api(:post, "/api/v1/chats/#{private}/official", h.token, %{}, h1) |> assert_status(201)

    added =
      for %{user_id: u, device_id: d} <-
            Groups.groups_devices([h.user.id, off.user.id, risi.user_id], "tabs"),
          {u, d} != {h.user.id, h1},
          do: ref(u, d)

    api(
      :post,
      "/api/v1/mls/groups/#{og}/commit",
      h.token,
      create_commit([], {h.user.id, h1}, %{"added" => added}),
      h1
    )
    |> assert_status(200)

    rc = own_risi_chat!(h.user)
    Application.put_env(:risime, :risi_dev_users, [h.user.id])

    # H reinstalls (both peers offline; the old-app peer has no push token).
    h2 = tabs_device!(h)
    supersede!(h1)

    api(
      :post,
      "/api/v1/me/devices/#{h2}/key_packages",
      h.token,
      %{"key_packages" => [b64()], "last_resort" => nil}
    )
    |> assert_status(204)

    api(:post, "/api/v1/groups/#{og}/rejoin", h.token, nil, h2) |> assert_status(202)
    # The first naming ran out (Risi's tree isn't running in this test).
    Ops.adding_op(og, {h.user.id, h2})
    |> Ecto.Changeset.change(committer_device: nil, committer_user: nil)
    |> Repo.update!()

    assert %DmOp{} = DmOps.list(dm_old) |> hd()
    assert %DmOp{} = DmOps.list(dm_off) |> hd()

    # Sweep 1 (the shipped defaults: Health canary, Official repair on, nudge canary).
    r = Health.sweep(:canary)
    assert Enum.any?(r.found, &(&1.conv == og and &1.action == "renamed_risi"))
    assert r.nudged == 2
    assert [_] = events(old.user.id, "secure_nudge")
    assert [_] = events(off.user.id, "secure_nudge")

    # Official heals without any peer: Risi's commit of the op completes it.
    op = Ops.adding_op(og, {h.user.id, h2})
    assert op.committer_device == RisiMe.Risi.device_id()

    assert {:ok, _} =
             Groups.Commit.commit(RisiMe.Risi.user_id(), RisiMe.Risi.device_id(), og, %{
               "generation" => 1,
               "epoch" => Groups.epoch(og),
               "commit" => b64(),
               "welcome" => b64(),
               "added" => [ref(h.user.id, h2)],
               "removed" => [],
               "op_id" => op.op_id
             })

    assert MapSet.member?(Groups.in_group(og), {h.user.id, h2})

    [{report, risi_obj}] = posts()
    # The two DMs and the Private 1:1 group with the offline peer (Risi never commits in Private).
    assert report =~ "3 private chats are waiting for the other person to open RisiMe"
    assert report =~ "I've reminded Offa."
    assert report =~ "Olda can't get RisiMe notifications right now"
    refute report =~ ~r/grp:|dm:|[0-9a-f]{8}-/
    assert length(risi_obj["items"]) == 3
    _ = rc

    # The peers come back: their inbox joins name them, they commit the re-add with the op.
    for {p, p1, conv} <- [{old, old1, dm_old}, {off, off1, dm_off}] do
      DmOps.device_joined(p.user.id, p1)
      [dop] = DmOps.list(conv)
      assert dop.committer_device == p1

      api(
        :post,
        "/api/v1/mls/groups/#{conv}/commit",
        p.token,
        %{
          "generation" => 1,
          "epoch" => MLS.group(conv).epoch,
          "commit" => b64(),
          "welcome" => b64(),
          "added" => [ref(h.user.id, h2)],
          "op_id" => dop.op_id
        },
        p1
      )
      |> assert_status(200)

      # H's phone is in; the op lives on only to remove the old leaf: no longer stuck.
      assert MapSet.member?(DmOps.in_group(conv), {h.user.id, h2})
    end

    # The offline peer's phone also re-adds H's phone in their Private group (§12.4a member path).
    Ops.device_joined(off.user.id, off1)
    gop = Ops.adding_op(private, {h.user.id, h2})
    assert gop.committer_device == off1

    api(
      :post,
      "/api/v1/mls/groups/#{private}/commit",
      off.token,
      %{
        "generation" => 1,
        "epoch" => Groups.epoch(private),
        "commit" => b64(),
        "welcome" => b64(),
        "added" => gop.payload["added"],
        "removed" => gop.payload["removed"] || [],
        "op_id" => gop.op_id
      },
      off1
    )
    |> assert_status(200)

    r2 = Health.sweep(:canary)
    assert r2.found == []
    [{fixed, %{"items" => items}}] = posts()
    assert fixed =~ "were stuck: fixed."
    assert Enum.all?(items, &(&1["state"] == "fixed"))
    assert length(items) == 4
    IO.puts("HHTEST PASS scenario")
  end
end
