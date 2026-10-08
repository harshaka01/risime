defmodule RisiMeWeb.ChatsS3Test do
  @moduledoc """
  v1.24 §24.2/§24.5 (server S3): `POST /api/v1/chats/{chat_id}/official`, the Risi claim rule,
  epoch-0 validation, and `403 private_tab` on every §24.5 path.
  """
  use RisiMeWeb.ConnCase, async: false

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers

  alias RisiMe.{Groups, Repo}

  setup :with_attestation_key

  setup do
    risi = risi_on!()
    [a, b, c] = for n <- ~w(Harsha Kamal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    a_dev = tabs_device!(a)
    b_dev = tabs_device!(b)
    c_dev = tabs_device!(c)
    clear_legacy!()

    body = %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, c.user.id]}

    %{"group" => %{"id" => private}} =
      api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

    api(
      :post,
      "/api/v1/mls/groups/#{private}/commit",
      a.token,
      create_commit([a.user.id, b.user.id, c.user.id], {a.user.id, a_dev}),
      a_dev
    )
    |> assert_status(200)

    %{
      a: a,
      b: b,
      c: c,
      a_dev: a_dev,
      b_dev: b_dev,
      c_dev: c_dev,
      private: private,
      risi: risi
    }
  end

  defp official!(chat_id, user, dev),
    do: api(:post, "/api/v1/chats/#{chat_id}/official", user.token, %{}, dev)

  # The epoch-0 commit adding every tabs device of the humans plus Risi's device.
  defp official_commit(official, users, {me, dev}, extra \\ %{}) do
    added =
      for %{user_id: u, device_id: d} <- Groups.groups_devices(users, "tabs"),
          {u, d} != {me, dev},
          do: ref(u, d)

    create_commit([], {me, dev}, Map.merge(%{"added" => added}, extra))
    |> then(&{official, &1})
  end

  defp commit!({official, body}, token, dev),
    do: api(:post, "/api/v1/mls/groups/#{official}/commit", token, body, dev)

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  describe "POST /chats/{chat_id}/official for a group (§24.2)" do
    test "201 creating, the epoch-0 commit with every tabs device and Risi, then 200", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, private: private, risi: risi} = ctx

      %{"group" => g} = official!(private, a, a_dev) |> assert_status(201)
      ex = example("chat_official_create_reply.json")["group"]
      assert keys(g) == keys(ex)

      assert {g["state"], g["tab"], g["chat_id"], g["chat_kind"]} ==
               {"creating", "official", private, "group"}

      assert g["agents"] == [risi.user_id] and g["my_role"] == "admin" and g["epoch"] == nil
      r = Enum.find(g["members"], &(&1["kind"] == "agent"))

      assert {r["user_id"], r["role"], r["phone"], r["display_name"]} ==
               {risi.user_id, "member", nil, "Risi"}

      assert Enum.map(g["members"], &keys/1) |> Enum.uniq() == [keys(hd(ex["members"]))]
      # Roles mirror the Private group.
      assert Enum.find(g["members"], &(&1["user_id"] == b.user.id))["role"] == "member"

      # The creator claims Risi's key package for this Official id (§24.5).
      {200, %{"devices" => devs}} =
        api(
          :post,
          "/api/v1/mls/key_packages/claim",
          a.token,
          %{"user_ids" => [b.user.id, c.user.id, risi.user_id], "conversation_id" => g["id"]},
          a_dev
        )

      assert Enum.any?(devs, &(&1["device_id"] == risi.device_id and &1["key_package"]))

      # Epoch 0 must add Risi's device too.
      {400, _} =
        official_commit(g["id"], [a.user.id, b.user.id, c.user.id], {a.user.id, a_dev})
        |> commit!(a.token, a_dev)

      {200, %{"epoch" => 1}} =
        official_commit(
          g["id"],
          [a.user.id, b.user.id, c.user.id, risi.user_id],
          {a.user.id, a_dev}
        )
        |> commit!(a.token, a_dev)

      assert Groups.get_group(g["id"]).state == "active"

      # group_event created (with kinds) and chat_event official_created, to every human member.
      ev = last_event(b.user.id, "group_event")
      ex = example("event_group_created_official.json")
      assert keys(ev) == keys(ex) and keys(ev["data"]) == keys(ex["data"])

      assert {ev["data"]["tab"], ev["data"]["chat_id"], ev["data"]["chat_kind"]} ==
               {"official", private, "group"}

      assert risi.user_id in ev["data"]["targets"]
      assert Enum.any?(ev["data"]["members"], &(&1["kind"] == "agent"))

      for u <- [a, b, c] do
        ce = last_event(u.user.id, "chat_event")
        ex = example("event_chat_official_created.json")
        assert keys(ce) == keys(ex) and keys(ce["data"]) == keys(ex["data"])

        assert ce["data"] == %{
                 ex["data"]
                 | "chat_id" => private,
                   "actor" => a.user.id,
                   "official_conversation_id" => g["id"],
                   "server_ts" => ce["data"]["server_ts"]
               }
      end

      refute last_event(risi.user_id, "chat_event")
      assert Repo.get!(RisiMe.Groups.Chat, private).official_conversation_id == g["id"]

      # Another member's POST: 200 with the same group (group_reply_v124.json shape).
      %{"group" => again} = official!(private, b, ctx.b_dev) |> assert_status(200)
      assert again["id"] == g["id"] and again["state"] == "active"
      assert keys(again) == keys(example("group_reply_v124.json")["group"])

      # Once active, Risi's key package is claimable only through a pending add op.
      {403, %{"error" => %{"code" => "not_member"}}} =
        api(
          :post,
          "/api/v1/mls/key_packages/claim",
          a.token,
          %{"user_ids" => [risi.user_id], "conversation_id" => g["id"]},
          a_dev
        )
    end

    test "the unique (chat_id, tab) index resolves concurrent creates: one 201, the rest 200",
         ctx do
      %{a: a, b: b, c: c, private: private} = ctx
      {:ok, chat} = RisiMe.Chats.resolve(a.user.id, private)

      results =
        [a, b, c, a, b]
        |> Task.async_stream(fn u -> RisiMe.Chats.insert_official(u.user.id, chat) end)
        |> Enum.map(fn {:ok, {:ok, kind, g}} -> {kind, g.id} end)

      assert Enum.count(results, &(elem(&1, 0) == :created)) == 1
      assert results |> Enum.map(&elem(&1, 1)) |> Enum.uniq() |> length() == 1
      assert Repo.aggregate(from(g in Groups.Group, where: g.chat_id == ^private), :count) == 2
    end

    test "errors: invalid_device, not_found, agent_unavailable, not_ready (tab), official_off",
         ctx do
      %{a: a, a_dev: a_dev, private: private} = ctx

      # A device without tabs.
      old = old_device!(a)
      {403, %{"error" => %{"code" => "invalid_device"}}} = official!(private, a, old)
      {403, _} = official!(private, a, nil)
      Repo.delete_all(from d in RisiMe.Devices.Device, where: d.device_id == ^old)
      Repo.delete_all(from i in "app_instances", where: i.instance_key == ^"device:#{old}")

      # Not a member, or not a chat id.
      stranger = logged_in_user()
      s_dev = tabs_device!(stranger)
      {404, _} = official!(private, stranger, s_dev)
      {404, _} = official!("grp:" <> Ecto.UUID.generate(), a, a_dev)

      # Risi without a key package, then RISI off.
      Repo.delete_all("mls_key_packages")
      {503, %{"error" => %{"code" => "agent_unavailable"}}} = official!(private, a, a_dev)
      risi_on!()
      Application.put_env(:risime, :risi, false)
      {503, %{"error" => %{"code" => "agent_unavailable"}}} = official!(private, a, a_dev)
      Application.put_env(:risime, :risi, true)

      # A member who isn't tabs-ready.
      old_c = old_device!(ctx.c)
      :ok = RisiMe.MLS.record_instance(ctx.c.user.id, old_c, nil, "0.3.0")

      Repo.update_all(from(d in RisiMe.Devices.Device, where: d.device_id == ^ctx.c_dev),
        set: [capabilities: ["groups"]]
      )

      {409, %{"error" => e}} = official!(private, a, a_dev)
      assert e["code"] == "not_ready" and e["tab"] == "official"
      assert Enum.all?(e["missing"], &(&1["user_id"] == ctx.c.user.id))
      assert e["message"] == "Chamari needs to update RisiMe"

      # Turned off: official_off.
      RisiMe.Chats.upsert_row(private, "group", %{official: "off"})
      {409, %{"error" => %{"code" => "official_off"}}} = official!(private, a, a_dev)
    end
  end

  describe "POST /chats/{dm}/official (§24.1, §24.2)" do
    test "a 1:1 Official: both users admin, Risi a member; not_e2ee, not_friends", ctx do
      %{a: a, b: b, a_dev: a_dev, risi: risi} = ctx
      dm = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)

      {409, %{"error" => %{"code" => "not_e2ee"}}} = official!(dm, a, a_dev)

      now = DateTime.utc_now()

      Repo.insert_all("mls_groups", [
        %{conversation_id: dm, generation: 1, epoch: 1, e2ee_since: now, updated_at: now}
      ])

      RisiMe.Social.block(Repo.get!(RisiMe.Accounts.User, b.user.id), a.user.id)
      {403, %{"error" => %{"code" => "not_friends"}}} = official!(dm, a, a_dev)
      Repo.delete_all("blocks")
      befriend!(a, b)

      %{"group" => g} = official!(dm, a, a_dev) |> assert_status(201)
      ex = example("chat_official_create_reply.json")["group"]
      assert keys(g) == keys(ex)
      assert {g["chat_id"], g["chat_kind"], g["tab"]} == {dm, "dm", "official"}

      roles = Map.new(g["members"], &{&1["user_id"], {&1["role"], &1["kind"]}})

      assert roles == %{
               a.user.id => {"admin", "user"},
               b.user.id => {"admin", "user"},
               risi.user_id => {"member", "agent"}
             }

      # A third user is never a participant of the DM chat.
      {404, _} = official!(dm, ctx.c, ctx.c_dev)
    end
  end

  describe "403 private_tab on every §24.5 path" do
    test "REST adds, group_create, claims and commits naming an agent", ctx do
      %{a: a, b: b, a_dev: a_dev, private: private, risi: risi} = ctx
      ex = example("error_private_tab.json")

      # group_create naming an agent.
      {403, e} =
        api(
          :post,
          "/api/v1/groups",
          a.token,
          %{"client_group_id" => Ecto.UUID.generate(), "member_ids" => [b.user.id, risi.user_id]},
          a_dev
        )

      assert e == ex

      # REST add to a Private group.
      {403, ^ex} =
        api(
          :post,
          "/api/v1/groups/#{private}/members",
          a.token,
          %{"user_ids" => [risi.user_id]},
          a_dev
        )

      # A claim of the agent's key package against a Private id, a dm: id, or none (§10.2).
      dm = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)

      for conv <- [private, dm, nil] do
        body = %{"user_ids" => [risi.user_id]}
        body = if conv, do: Map.put(body, "conversation_id", conv), else: body
        {403, ^ex} = api(:post, "/api/v1/mls/key_packages/claim", a.token, body, a_dev)
      end

      # A commit adding the agent's device to the Private group (admin, op-less).
      {403, ^ex} =
        api(
          :post,
          "/api/v1/mls/groups/#{private}/commit",
          a.token,
          %{
            "generation" => 1,
            "epoch" => 1,
            "commit" => b64(),
            "welcome" => b64(),
            "added" => [ref(risi.user_id, risi.device_id)],
            "removed" => []
          },
          a_dev
        )

      # A DM commit adding the agent's device.
      {403, ^ex} =
        api(
          :post,
          "/api/v1/mls/groups/#{dm}/commit",
          a.token,
          %{
            "generation" => 1,
            "epoch" => 0,
            "commit" => b64(),
            "welcome" => b64(),
            "added" => [ref(risi.user_id, risi.device_id), ref(b.user.id, ctx.b_dev)]
          },
          a_dev
        )

      assert Groups.member(private, risi.user_id) == nil
    end

    test "Official: REST add of the agent is invalid_member; the agent commits only self-updates",
         ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, private: private, risi: risi} = ctx
      %{"group" => %{"id" => off}} = official!(private, a, a_dev) |> assert_status(201)

      {200, _} =
        official_commit(off, [a.user.id, b.user.id, c.user.id, risi.user_id], {a.user.id, a_dev})
        |> commit!(a.token, a_dev)

      {422, %{"error" => %{"code" => "invalid_member"}}} =
        api(
          :post,
          "/api/v1/groups/#{off}/members",
          a.token,
          %{"user_ids" => [risi.user_id]},
          a_dev
        )

      # The agent's device may self-update, nothing else.
      body = %{
        "generation" => 1,
        "epoch" => 1,
        "commit" => b64(),
        "added" => [],
        "removed" => [ref(c.user.id, ctx.c_dev)]
      }

      assert {:error, :not_admin} =
               Groups.Commit.commit(risi.user_id, risi.device_id, off, body)

      assert {:ok, 2} =
               Groups.Commit.commit(risi.user_id, risi.device_id, off, %{body | "removed" => []})
    end
  end
end
