defmodule RisiMeWeb.ChatsReviewTest do
  @moduledoc """
  v1.24 §24 privacy review fixes (S1–S4): membership changes while Official is `creating`
  (`members_changed`), no agent removal through the Private id, Official group rings only to
  `tabs` devices, no rejoin of a non-`tabs` device into Official, `PATCH /me` validating every
  field before writing, `GET /api/v1/chats` in a fixed number of queries, one §24.5 query per
  page for an agent socket.
  """
  use RisiMeWeb.ChannelCase, async: false

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers

  alias RisiMe.{Groups, Repo}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  setup do
    risi = risi_on!(20)
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

    {200, _} =
      api(
        :post,
        "/api/v1/mls/groups/#{private}/commit",
        a.token,
        create_commit([a.user.id, b.user.id, c.user.id], {a.user.id, a_dev}),
        a_dev
      )

    %{a: a, b: b, c: c, a_dev: a_dev, b_dev: b_dev, c_dev: c_dev, private: private, risi: risi}
  end

  defp official!(chat_id, user, dev) do
    %{"group" => %{"id" => id, "state" => "creating"}} =
      api(:post, "/api/v1/chats/#{chat_id}/official", user.token, %{}, dev)
      |> assert_status(201)

    id
  end

  # The epoch-0 commit of an Official adding every tabs device of `users` (Risi included).
  defp official_commit(official, users, user, dev) do
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

  defp humans(g),
    do:
      Repo.all(
        from m in Groups.Member,
          where: m.group_id == ^g and m.kind == "user",
          select: m.user_id
      )
      |> Enum.sort()

  defp leaf_users(g), do: g |> Groups.in_group() |> MapSet.new(&elem(&1, 0))

  describe "membership while Official is creating (§24.2/§24.3)" do
    test "a member removed during creation is never in Official: stale epoch 0 is members_changed",
         ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, private: private, risi: risi} = ctx
      official = official!(private, a, a_dev)

      {204, _} =
        api(:delete, "/api/v1/groups/#{private}/members/#{c.user.id}", a.token, nil, a_dev)

      refute Groups.member(official, c.user.id)

      # Epoch 0 built for the member list at creation time.
      {409, %{"error" => e}} =
        official_commit(official, [a.user.id, b.user.id, c.user.id, risi.user_id], a, a_dev)

      assert e["code"] == "members_changed"
      assert Groups.get_group(official).state == "creating"

      {200, %{"epoch" => 1}} =
        official_commit(official, [a.user.id, b.user.id, risi.user_id], a, a_dev)

      refute MapSet.member?(leaf_users(official), c.user.id)
      assert humans(official) == Enum.sort([a.user.id, b.user.id])
    end

    test "a member added or leaving during creation is mirrored; roles too", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, c_dev: c_dev, private: private, risi: risi} = ctx
      d = logged_in_user(display_name: "Dilan")
      befriend!(a, d)
      tabs_device!(d)
      clear_legacy!()
      official = official!(private, a, a_dev)

      {200, _} =
        api(
          :post,
          "/api/v1/groups/#{private}/members",
          a.token,
          %{"user_ids" => [d.user.id]},
          a_dev
        )

      assert %{state: "active"} = Groups.member(official, d.user.id)
      # No op on a group without an MLS state.
      assert Groups.Ops.list(official) == []

      {204, _} = api(:post, "/api/v1/groups/#{private}/leave", c.token, nil, c_dev)
      refute Groups.member(official, c.user.id)

      {200, _} =
        api(
          :patch,
          "/api/v1/groups/#{private}/members/#{b.user.id}",
          a.token,
          %{"role" => "admin"},
          a_dev
        )

      assert Groups.member(official, b.user.id).role == "admin"

      {409, %{"error" => %{"code" => "members_changed"}}} =
        official_commit(official, [a.user.id, b.user.id, c.user.id, risi.user_id], a, a_dev)

      {200, %{"epoch" => 1}} =
        official_commit(official, [a.user.id, b.user.id, d.user.id, risi.user_id], a, a_dev)

      assert humans(official) == Enum.sort([a.user.id, b.user.id, d.user.id])
    end

    test "drift that bypassed the chat lock is re-synced and kept, the commit refused", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, private: private, risi: risi} = ctx
      official = official!(private, a, a_dev)

      # c gone from Private behind the Official's back.
      Repo.delete_all(
        from m in Groups.Member, where: m.group_id == ^private and m.user_id == ^c.user.id
      )

      {409, %{"error" => %{"code" => "members_changed"}}} =
        official_commit(official, [a.user.id, b.user.id, c.user.id, risi.user_id], a, a_dev)

      # The re-sync survived the refused commit.
      assert humans(official) == Enum.sort([a.user.id, b.user.id])

      {200, _} = official_commit(official, [a.user.id, b.user.id, risi.user_id], a, a_dev)
    end
  end

  describe "no agent removal through either tab (§24.3/§24.4)" do
    setup ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, private: private, risi: risi} = ctx
      official = official!(private, a, a_dev)

      {200, _} =
        official_commit(official, [a.user.id, b.user.id, c.user.id, risi.user_id], a, a_dev)

      %{official: official}
    end

    test "DELETE via the Private id is risi_required while on, invalid_member when off", ctx do
      %{a: a, a_dev: a_dev, private: private, official: official, risi: risi} = ctx

      {409, %{"error" => %{"code" => "risi_required"}}} =
        api(:delete, "/api/v1/groups/#{private}/members/#{risi.user_id}", a.token, nil, a_dev)

      assert Groups.member(official, risi.user_id).state == "active"
      assert Groups.Ops.list(official) == []

      {200, _} =
        api(:patch, "/api/v1/chats/#{private}", a.token, %{"official" => "off"}, a_dev)

      for g <- [private, official] do
        {422, %{"error" => %{"code" => "invalid_member"}}} =
          api(:delete, "/api/v1/groups/#{g}/members/#{risi.user_id}", a.token, nil, a_dev)
      end
    end

    test "rejoin of a non-tabs device into Official is invalid_device", ctx do
      %{b: b, official: official, private: private} = ctx
      old = old_device!(b)

      {403, %{"error" => %{"code" => "invalid_device"}}} =
        api(:post, "/api/v1/groups/#{official}/rejoin", b.token, nil, old)

      assert Groups.Ops.list(official) == []

      # The Private group still takes it.
      {202, _} = api(:post, "/api/v1/groups/#{private}/rejoin", b.token, nil, old)
    end
  end

  describe "Official group rings (§24.5/§24.7)" do
    setup :test_push!

    test "a ring in an Official group pushes only to tabs devices", ctx do
      %{a: a, b: b} = ctx
      caps = ~w(groups member_devices calls group_calls)
      a_dev = tabs_device!(a, caps: caps ++ ["tabs"])
      new_tok = push_token("fcm-new")
      old_tok = push_token("fcm-old")
      _ = tabs_device!(b, caps: caps ++ ["tabs"], push_token: new_tok)
      _ = tabs_device!(b, caps: caps, push_token: old_tok)

      official =
        official_group!(
          "grp:" <> Ecto.UUID.generate(),
          [{a.user.id, "admin"}, {b.user.id, "member"}],
          leaves: [{a.user.id, a_dev}]
        )

      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
      {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})

      ref =
        push(chan, "call:signal", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "conversation_id" => official,
          "call_id" => Ecto.UUID.generate(),
          "ring" => true,
          "media" => "audio",
          "ciphertext" => b64(64),
          "generation" => 1,
          "epoch" => 1
        })

      assert_reply ref, :ok, _, 2000
      assert_receive {:push, ^new_tok, %{"type" => "call"}}, 1000
      refute_receive {:push, ^old_tok, _}, 800
    end
  end

  describe "PATCH /me (§24.11)" do
    test "a bad display_name writes nothing, not even a valid tz", %{a: a} do
      {422, %{"error" => %{"code" => "invalid_display_name"}}} =
        api(:patch, "/api/v1/me", a.token, %{"tz" => "Asia/Colombo", "display_name" => ""})

      assert Repo.get!(RisiMe.Accounts.User, a.user.id).tz == nil

      {200, %{"user" => u}} =
        api(:patch, "/api/v1/me", a.token, %{"tz" => "Asia/Colombo", "display_name" => "H"})

      assert {u["tz"], u["display_name"]} == {"Asia/Colombo", "H"}
    end
  end

  describe "query counts" do
    defp count_queries(fun) do
      me = self()
      id = "count-#{System.unique_integer([:positive])}"

      :telemetry.attach(
        id,
        RisiMe.Repo.config()[:telemetry_prefix] ++ [:query],
        fn _, _, _, _ -> if self() == me, do: send(me, :query) end,
        nil
      )

      try do
        result = fun.()
        {result, drain(0)}
      after
        :telemetry.detach(id)
      end
    end

    defp drain(n) do
      receive do
        :query -> drain(n + 1)
      after
        0 -> n
      end
    end

    test "GET /api/v1/chats: the same number of queries for 1 or 5 group chats", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev} = ctx

      {{200, %{"chats" => one}}, n1} =
        count_queries(fn -> api(:get, "/api/v1/chats", a.token, nil, a_dev) end)

      for _ <- 1..4 do
        body = %{
          "client_group_id" => Ecto.UUID.generate(),
          "member_ids" => [b.user.id, c.user.id]
        }

        %{"group" => %{"id" => id}} =
          api(:post, "/api/v1/groups", a.token, body, a_dev) |> assert_status(201)

        {200, _} =
          api(
            :post,
            "/api/v1/mls/groups/#{id}/commit",
            a.token,
            create_commit([a.user.id, b.user.id, c.user.id], {a.user.id, a_dev}),
            a_dev
          )
      end

      {{200, %{"chats" => five}}, n5} =
        count_queries(fn -> api(:get, "/api/v1/chats", a.token, nil, a_dev) end)

      assert length(one) == 1 and length(five) == 5
      assert n1 > 0 and n5 == n1
    end

    test "an agent's page of events is filtered with one query", ctx do
      %{a: a, b: b, private: private, risi: risi} = ctx

      on =
        official_group!(private, [{a.user.id, "admin"}, {b.user.id, "member"}],
          agents: [risi.user_id]
        )

      other = official_group!("grp:" <> Ecto.UUID.generate(), [{a.user.id, "admin"}])

      events =
        for conv <- [on, other, private, on, other],
            do: %{kind: "message", data: %{"conversation_id" => conv}}

      events =
        events ++ [%{kind: "chat_event", data: %{"chat_id" => private}}, %{kind: "x", data: %{}}]

      {keep, n} =
        count_queries(fn ->
          f = RisiMe.Groups.Tabs.agent_filter(risi.user_id, events)
          Enum.map(events, f)
        end)

      assert n == 1
      assert keep == [true, false, false, true, false, false, true]

      assert keep ==
               Enum.map(events, &RisiMe.Groups.Tabs.agent_may_see?(risi.user_id, &1))
    end
  end
end
