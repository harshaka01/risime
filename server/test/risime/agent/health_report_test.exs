defmodule RisiMe.Agents.HealthReportTest do
  @moduledoc """
  Incident 2026-10-14 (decision 088 amendment, proposal 2026-10-14-health-nudge): a Private 1:1
  that waits for the other person's phone is never repaired by Risi; the other person gets one
  `secure_nudge` a day (stored event + the content-free wake), and the operator's Risi chat gets a
  plain report with names and actions, never ids or codes, each chat an item it can open. The
  v1.46 id summary stays behind `RISI_HEALTH_PLAIN=off`.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  @moduletag capture_log: true

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.RisiHelpers, only: [own_risi_chat!: 1, restore_on_exit: 1]

  alias RisiMe.Agents.Health
  alias RisiMe.{Devices, MLS, Repo}
  alias RisiMe.MLS.DmOp

  @cap "member_devices"

  setup :with_attestation_key

  setup do
    restore_on_exit([:risi, :risi_test_pid, :risi_dev_users, :health_stuck_s, :risi_agent_flags])
    RisiMe.TabsHelpers.risi_on!()
    Application.put_env(:risime, :risi_test_pid, self())
    Application.put_env(:risime, :health_stuck_s, 0)

    h = logged_in_user(display_name: "Harsha")
    p = logged_in_user(display_name: "Kamal")
    befriend!(h, p)
    clear_legacy!()
    h1 = device!(h)
    p1 = device!(p, "tok-" <> Ecto.UUID.generate())
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
        "added" => [%{"user_id" => p.user.id, "device_id" => p1}]
      },
      h1
    )
    |> assert_status(200)

    rc = own_risi_chat!(h.user)
    Application.put_env(:risime, :risi_dev_users, [h.user.id])

    # H reinstalls; the peer's phone is closed, so the DM op waits for it.
    Process.sleep(10)
    h2 = device!(h)

    api(
      :post,
      "/api/v1/me/devices/#{h2}/key_packages",
      h.token,
      %{"key_packages" => [b64()], "last_resort" => nil}
    )
    |> assert_status(204)

    assert %DmOp{} = Repo.one(from o in DmOp, where: o.conversation_id == ^conv)
    %{h: h, p: p, h2: h2, p1: p1, conv: conv, rc: rc}
  end

  defp device!(user, token \\ nil) do
    dev = Ecto.UUID.generate()

    params = %{
      "platform" => "android",
      "mls" => %{
        "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
        "capabilities" => ["groups", @cap]
      }
    }

    params = if token, do: Map.put(params, "push_token", token), else: params
    {:ok, _} = Devices.register(user.user.id, dev, params)
    :ok = MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end

  defp flags!(m), do: Application.put_env(:risime, :risi_agent_flags, m)

  defp drain_posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> drain_posts([{conv, body, risi} | acc])
    after
      0 -> Enum.reverse(acc)
    end
  end

  test "a waiting Private 1:1: never repaired; the other person nudged once a day; a plain report",
       %{h: h, p: p, conv: conv, rc: rc} do
    flags!(%{"RISI_HEALTH_NUDGE" => "canary"})

    r = Health.sweep(:canary)

    assert [%{kind: "dm_readd", tab: "dm", action: "alert", repaired: false}] =
             Enum.filter(r.found, &(&1.conv == conv))

    assert r.nudged == 1

    assert [%{"data" => %{"conversation_id" => ^conv} = d}] = events(p.user.id, "secure_nudge")
    assert d["waiting_user_id"] == h.user.id and d["reason"] == "device_readd"
    assert events(h.user.id, "secure_nudge") == []

    assert [{^rc, body, risi}] = drain_posts()
    assert body =~ "1 private chat is waiting for the other person to open RisiMe: Kamal."
    assert body =~ "I've reminded Kamal."
    refute body =~ conv
    refute body =~ "dm_readd"
    refute body =~ ~r/[0-9a-f]{8}-[0-9a-f]{4}/
    assert risi["kind"] == "ops_alert" and risi["check"] == "conversation_stuck"
    refute risi["detail"] =~ ~r/[0-9a-f]{8}/

    assert [%{"conversation_id" => ^conv, "chat_id" => ^conv, "label" => "Kamal"} = item] =
             risi["items"]

    assert item["state"] == "waiting_other" and item["open"] == true

    # The next sweep: no second nudge (once a day), no second report (same state).
    r2 = Health.sweep(:canary)
    assert r2.nudged == 0
    assert length(events(p.user.id, "secure_nudge")) == 1
    assert drain_posts() == []

    # The peer comes back and re-adds H's phone: the case is gone and reported fixed.
    Repo.delete_all(from o in DmOp, where: o.conversation_id == ^conv)
    Health.sweep(:canary)
    assert [{^rc, fixed, %{"items" => [%{"state" => "fixed"}]}}] = drain_posts()
    assert fixed =~ "1 private chat (Kamal) was stuck: fixed."
    assert drain_posts() == []
  end

  test "nudge off or canary without a dev member: nobody is nudged; a peer without push is named",
       %{p: p, conv: conv} do
    _ = conv
    flags!(%{"RISI_HEALTH_NUDGE" => "off"})
    assert Health.sweep(:canary).nudged == 0
    assert events(p.user.id, "secure_nudge") == []
    [{_, body, _}] = drain_posts()
    refute body =~ "reminded"

    # Shadow never nudges either.
    flags!(%{"RISI_HEALTH_NUDGE" => "on"})
    assert Health.sweep(:shadow).nudged == 0

    # Canary: H is no dev user any more.
    flags!(%{"RISI_HEALTH_NUDGE" => "canary"})
    Application.put_env(:risime, :risi_dev_users, [Ecto.UUID.generate()])
    assert Health.sweep(:canary).nudged == 0
    assert events(p.user.id, "secure_nudge") == []

    # On, and the peer has no push token: nudged (stored for its next open), named in the report.
    Application.put_env(:risime, :risi_dev_users, [])

    Repo.update_all(from(d in Devices.Device, where: d.user_id == ^p.user.id),
      set: [push_token: nil]
    )

    flags!(%{"RISI_HEALTH_NUDGE" => "on"})
    assert Health.sweep(:canary).nudged == 1
    assert [_] = events(p.user.id, "secure_nudge")
  end

  test "the report names the peer as unreachable when it has no push token", %{p: p, h: h} do
    Repo.update_all(from(d in Devices.Device, where: d.user_id == ^p.user.id),
      set: [push_token: nil]
    )

    flags!(%{"RISI_HEALTH_NUDGE" => "on"})
    Health.sweep(:canary)
    [{_, body, _}] = drain_posts()

    assert body =~
             "Kamal can't get RisiMe notifications right now, so please ask them to open RisiMe."

    refute body =~ h.user.id
  end

  test "RISI_HEALTH_PLAIN=off keeps the v1.46 id summary", %{conv: _conv} do
    flags!(%{"RISI_HEALTH_PLAIN" => "off", "RISI_HEALTH_NUDGE" => "off"})
    Health.sweep(:canary)
    [{_, body, risi}] = drain_posts()
    assert body =~ "conversation(s) stuck"
    assert body =~ "dm dm_readd dm (alert)"
    refute Map.has_key?(risi, "items")
  end

  test "labels: Risi chats by owner, groups by the other members, never an invented name", %{
    h: h,
    p: p
  } do
    q = logged_in_user(display_name: "Nameless")
    # q has no name in `names`: never invented.
    names = %{h.user.id => "Harsha", p.user.id => "Kamal"}

    risi = %RisiMe.Groups.Group{id: "grp:x", chat_kind: "risi"}
    rc_p = own_risi_chat!(p.user)

    assert Health.label(%{conv: rc_p, group: %{risi | id: rc_p}}, h.user.id, names) ==
             "Kamal's Risi chat"

    assert Health.label(%{conv: rc_p, group: %{risi | id: rc_p}}, p.user.id, names) ==
             "your Risi chat"

    g =
      RisiMe.TabsHelpers.official_group!(
        "grp:" <> Ecto.UUID.generate(),
        [{h.user.id, "admin"}, {p.user.id, "member"}, {q.user.id, "member"}],
        agents: [RisiMe.Risi.user_id()]
      )

    grp = Repo.get(RisiMe.Groups.Group, g)

    assert Health.label(%{conv: g, group: grp}, h.user.id, names) ==
             "the group with Kamal, a contact"
  end
end
