defmodule RisiMeWeb.ChatsS4Test do
  @moduledoc """
  v1.24 §24.3/§24.4/§24.8 (server S4): membership fan-out to both tabs, `dm_chat`,
  `risi_required`, the Official toggle (rights, immediate stop of delivery to Risi, the
  member-committable agent remove, on again), `official_off` on send, `chat_event`s,
  `GET /api/v1/chats[/id]`, the toggle rate limit.
  """
  use RisiMeWeb.ChannelCase, async: false

  import Ecto.Query
  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers

  alias RisiMe.{Groups, Messaging, Repo}
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

    official = start_official!(private, a, a_dev, [a.user.id, b.user.id, c.user.id, risi.user_id])

    %{
      a: a,
      b: b,
      c: c,
      a_dev: a_dev,
      b_dev: b_dev,
      c_dev: c_dev,
      private: private,
      official: official,
      risi: risi
    }
  end

  defp start_official!(chat_id, user, dev, all) do
    %{"group" => %{"id" => id}} =
      api(:post, "/api/v1/chats/#{chat_id}/official", user.token, %{}, dev)
      |> assert_status(201)

    added =
      for %{user_id: u, device_id: d} <- Groups.groups_devices(all, "tabs"),
          {u, d} != {user.user.id, dev},
          do: ref(u, d)

    {200, %{"epoch" => 1}} =
      api(
        :post,
        "/api/v1/mls/groups/#{id}/commit",
        user.token,
        create_commit([], {user.user.id, dev}, %{"added" => added}),
        dev
      )

    id
  end

  defp patch!(chat_id, user, dev, official),
    do: api(:patch, "/api/v1/chats/#{chat_id}", user.token, %{"official" => official}, dev)

  defp state(g, u), do: Groups.member(g, u) && Groups.member(g, u).state
  defp ops(g, type), do: g |> Groups.Ops.list() |> Enum.filter(&(&1.type == type))
  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  defp join!(token, user_id, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => token, "device_id" => device})
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user_id, %{})
    {chan, reply}
  end

  defp call(chan, event, payload) do
    ref = push(chan, event, payload)

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply")
    end
  end

  defp send_payload(conv, epoch),
    do: %{
      "client_msg_id" => Ecto.UUID.generate(),
      "conversation_id" => conv,
      "ciphertext" => b64(),
      "generation" => 1,
      "epoch" => epoch
    }

  describe "membership fan-out (§24.3)" do
    test "an add on either tab adds to both (one op per tab); not_ready rolls back both", ctx do
      %{a: a, a_dev: a_dev, private: private, official: official} = ctx
      d = logged_in_user(display_name: "Dilan")
      befriend!(a, d)
      old = old_device!(d)
      clear_legacy!()

      # Group-ready but not tabs-ready: refused for the whole chat, nothing added anywhere.
      {409, %{"error" => e}} =
        api(
          :post,
          "/api/v1/groups/#{private}/members",
          a.token,
          %{"user_ids" => [d.user.id]},
          a_dev
        )

      assert e["code"] == "not_ready" and e["tab"] == "official"
      assert state(private, d.user.id) == nil and state(official, d.user.id) == nil

      # Tabs-ready: one add op per tab; the reply is the targeted tab's Group.
      Repo.delete_all(from x in RisiMe.Devices.Device, where: x.device_id == ^old)
      Repo.delete_all(from i in "app_instances", where: i.instance_key == ^"device:#{old}")
      tabs_device!(d)

      {200, %{"group" => g}} =
        api(
          :post,
          "/api/v1/groups/#{official}/members",
          a.token,
          %{"user_ids" => [d.user.id]},
          a_dev
        )

      assert g["id"] == official and g["tab"] == "official"

      assert state(private, d.user.id) == "pending_add" and
               state(official, d.user.id) == "pending_add"

      assert [%{payload: %{"user_ids" => [_]}}] = ops(private, "add")
      assert [%{committer_device: ^a_dev}] = ops(official, "add")
    end

    test "remove, leave and role apply to both tabs; last_admin is checked once", ctx do
      %{a: a, b: b, c: c, a_dev: a_dev, private: private, official: official} = ctx

      {204, nil} =
        api(:delete, "/api/v1/groups/#{official}/members/#{c.user.id}", a.token, nil, a_dev)

      assert state(private, c.user.id) == "pending_remove"
      assert state(official, c.user.id) == "pending_remove"
      assert length(ops(private, "remove")) == 1 and length(ops(official, "remove")) == 1

      {200, _} =
        api(
          :patch,
          "/api/v1/groups/#{private}/members/#{b.user.id}",
          a.token,
          %{"role" => "admin"},
          a_dev
        )

      assert [%{payload: %{"role" => "admin"}}] = ops(private, "role")
      assert [%{payload: %{"role" => "admin"}}] = ops(official, "role")

      # a is the only admin (b's role op hasn't landed): last_admin, on either tab.
      {409, %{"error" => %{"code" => "last_admin"}}} =
        api(:post, "/api/v1/groups/#{official}/leave", a.token, nil, a_dev)

      {204, nil} = api(:post, "/api/v1/groups/#{private}/leave", b.token, nil, ctx.b_dev)
      assert state(private, b.user.id) == "pending_remove"
      assert state(official, b.user.id) == "pending_remove"
    end

    test "risi_required: removing the agent while Official is on", ctx do
      %{a: a, a_dev: a_dev, official: official, risi: risi} = ctx

      {409, %{"error" => %{"code" => "risi_required"}}} =
        api(:delete, "/api/v1/groups/#{official}/members/#{risi.user_id}", a.token, nil, a_dev)

      assert state(official, risi.user_id) == "active"
    end

    test "a 1:1 Official: member, leave and role calls are dm_chat", ctx do
      %{a: a, b: b, a_dev: a_dev, risi: risi} = ctx
      dm = Messaging.conversation_id(a.user.id, b.user.id)
      now = DateTime.utc_now()

      Repo.insert_all("mls_groups", [
        %{conversation_id: dm, generation: 1, epoch: 1, e2ee_since: now, updated_at: now}
      ])

      off = start_official!(dm, a, a_dev, [a.user.id, b.user.id, risi.user_id])
      c = ctx.c

      for {method, path, body} <- [
            {:post, "/api/v1/groups/#{off}/members", %{"user_ids" => [c.user.id]}},
            {:delete, "/api/v1/groups/#{off}/members/#{b.user.id}", nil},
            {:post, "/api/v1/groups/#{off}/leave", nil},
            {:patch, "/api/v1/groups/#{off}/members/#{b.user.id}", %{"role" => "member"}}
          ] do
        {422, %{"error" => %{"code" => "dm_chat"}}} = api(method, path, a.token, body, a_dev)
      end

      # Either person may toggle a 1:1 (§24.4).
      {200, %{"chat" => %{"official" => %{"state" => "off"}}}} = patch!(dm, b, ctx.b_dev, "off")
      {200, %{"chat" => %{"official" => %{"state" => "on"}}}} = patch!(dm, a, a_dev, "on")

      # Sends to a 1:1 Official while the two aren't friends: not_friends.
      RisiMe.Social.unfriend(Repo.get!(RisiMe.Accounts.User, a.user.id), b.user.id)
      assert {:error, :not_friends} = RisiMe.Chats.send_check(off)
    end
  end

  describe "the Official toggle (§24.4)" do
    test "rights: group admins only; tabs device; non-members 404", ctx do
      %{b: b, private: private} = ctx
      {403, %{"error" => %{"code" => "not_admin"}}} = patch!(private, b, ctx.b_dev, "off")

      {403, %{"error" => %{"code" => "invalid_device"}}} =
        patch!(private, ctx.a, old_device!(ctx.a), "off")

      s = logged_in_user()
      {404, _} = patch!(private, s, tabs_device!(s), "off")
      {400, _} = patch!(private, ctx.a, ctx.a_dev, "maybe")
    end

    test "off stops delivery to Risi at once; a member commits the agent removal; on again",
         ctx do
      %{
        a: a,
        b: b,
        c: c,
        a_dev: a_dev,
        b_dev: b_dev,
        private: private,
        official: official,
        risi: risi
      } =
        ctx

      {risi_chan, _} = join!(risi_token!(), risi.user_id, risi.device_id)
      {a_chan, _} = join!(a.token, a.user.id, a_dev)
      rt = "inbox:" <> risi.user_id

      # While on, Risi receives Official messages, and never anything of the Private group.
      {:ok, %{message_id: m1}} = call(a_chan, "msg:send", send_payload(official, 1))
      assert_receive %Phoenix.Socket.Message{topic: ^rt, payload: %{event_id: ^m1}}

      leak = %{
        event_id: RisiMe.TimeUUID.generate(),
        kind: "message",
        data: %{"conversation_id" => private, "from" => a.user.id}
      }

      Messaging.publish_batch([{risi.user_id, leak, [push: false]}])
      refute_receive %Phoenix.Socket.Message{topic: ^rt}, 150

      # Off (an admin's tabs device): chat_event to every human member, never to Risi.
      {200, %{"chat" => chat}} = patch!(private, a, a_dev, "off")
      assert chat["official"]["state"] == "off" and chat["official"]["changed_by"] == a.user.id
      assert chat["official"]["conversation_id"] == official
      assert state(official, risi.user_id) == "pending_remove"

      for u <- [a, b, c] do
        ce = last_event(u.user.id, "chat_event")
        ex = example("event_chat_official_off.json")
        assert keys(ce["data"]) == keys(ex["data"])

        assert {ce["data"]["action"], ce["data"]["official_conversation_id"]} ==
                 {"official_off", official}
      end

      refute last_event(risi.user_id, "chat_event")

      # Nothing of the chat reaches Risi any more: live (even an event put in its inbox by a
      # bug) or in a replay.
      Messaging.publish_batch([
        {risi.user_id,
         %{
           leak
           | data: %{leak.data | "conversation_id" => official},
             event_id: RisiMe.TimeUUID.generate()
         }, [push: false]}
      ])

      refute_receive %Phoenix.Socket.Message{topic: ^rt}, 150
      {_, replay} = join!(risi_token!(), risi.user_id, risi.device_id)

      refute Enum.any?(
               replay.events,
               &((&1.data["conversation_id"] || &1.data["group_id"]) in [official, private])
             )

      _ = risi_chan

      # Sends: official_off (error_official_off.json), checked before the e2ee checks.
      {:error, reply} = call(a_chan, "msg:send", send_payload(official, 99))
      assert Jason.decode!(Jason.encode!(reply)) == example("error_official_off.json")

      {409, %{"error" => %{"code" => "official_off"}}} =
        api(:post, "/api/v1/chats/#{private}/official", a.token, %{}, a_dev)

      # The agent remove op: the toggler's device first; any member's in-group device may commit.
      [op] = ops(official, "remove")
      assert {op.committer_user, op.committer_device} == {a.user.id, a_dev}

      {200, %{"epoch" => 2}} =
        api(
          :post,
          "/api/v1/mls/groups/#{official}/commit",
          b.token,
          %{
            "generation" => 1,
            "epoch" => 1,
            "commit" => b64(),
            "op_id" => op.op_id,
            "added" => [],
            "removed" => [ref(risi.user_id, risi.device_id)]
          },
          b_dev
        )

      assert Groups.member(official, risi.user_id) == nil
      ev = last_event(c.user.id, "group_event")
      ex = example("event_group_agent_removed.json")
      assert keys(ev["data"]) == keys(ex["data"])

      assert {ev["data"]["action"], ev["data"]["targets"], ev["data"]["actor"]} ==
               {"removed", [risi.user_id], a.user.id}

      # The Private tab is untouched; Official stays (read-only).
      assert Groups.get_group(official).state == "active"
      assert state(private, a.user.id) == "active"

      # On again: the same Official conversation; an add op for Risi (admin device first).
      {200, %{"chat" => chat}} = patch!(private, a, a_dev, "on")

      assert chat["official"] == %{
               chat["official"]
               | "state" => "on",
                 "conversation_id" => official
             }

      ce = last_event(b.user.id, "chat_event")

      assert ce["data"]["action"] == "official_on" and
               ce["data"]["official_conversation_id"] == official

      [add] = ops(official, "add")
      assert add.payload["user_ids"] == [risi.user_id]
      assert add.committer_device == a_dev

      # Risi's key package is claimable for the pending add.
      {200, %{"devices" => [%{"device_id" => rd}]}} =
        api(
          :post,
          "/api/v1/mls/key_packages/claim",
          a.token,
          %{"user_ids" => [risi.user_id], "conversation_id" => official},
          a_dev
        )

      assert rd == risi.device_id

      # A member can't complete the agent add; an admin can.
      add_body = %{
        "generation" => 1,
        "epoch" => 2,
        "commit" => b64(),
        "welcome" => b64(),
        "op_id" => add.op_id,
        "added" => [ref(risi.user_id, risi.device_id)],
        "removed" => []
      }

      {403, _} = api(:post, "/api/v1/mls/groups/#{official}/commit", b.token, add_body, b_dev)

      {200, %{"epoch" => 3}} =
        api(:post, "/api/v1/mls/groups/#{official}/commit", a.token, add_body, a_dev)

      ev = last_event(b.user.id, "group_event")
      ex = example("event_group_agent_added.json")
      assert keys(ev["data"]) == keys(ex["data"])
      assert [%{"kind" => "agent", "user_id" => r}] = ev["data"]["members"]
      assert r == risi.user_id
      assert state(official, risi.user_id) == "active"

      # Sends work again; Risi receives them.
      {:ok, %{message_id: m3}} = call(a_chan, "msg:send", send_payload(official, 3))
      assert m3
    end

    test "idempotent: the current state is 200 with no change; 6 toggles per chat per day", ctx do
      %{a: a, a_dev: a_dev, private: private} = ctx
      before = length(events(ctx.b.user.id, "chat_event"))
      {200, %{"chat" => %{"official" => %{"state" => "on"}}}} = patch!(private, a, a_dev, "on")
      assert length(events(ctx.b.user.id, "chat_event")) == before

      for i <- 1..6 do
        {200, _} = patch!(private, a, a_dev, if(rem(i, 2) == 1, do: "off", else: "on"))
      end

      {429, %{"error" => %{"code" => "rate_limited"}}} = patch!(private, a, a_dev, "off")
    end

    test "a chat that never had Official: on/off only set the state", ctx do
      %{a: a, b: b} = ctx
      dm = Messaging.conversation_id(a.user.id, b.user.id)
      {200, %{"chat" => c1}} = patch!(dm, a, ctx.a_dev, "off")
      assert c1["official"]["state"] == "off" and c1["official"]["conversation_id"] == nil
      {200, %{"chat" => c2}} = patch!(dm, b, ctx.b_dev, "on")
      assert c2["official"]["state"] == "none"
    end
  end

  describe "GET /api/v1/chats[/id] (§24.8)" do
    test "the Chat objects (chat_reply.json, chats_reply.json); tabs devices only", ctx do
      %{a: a, b: b, a_dev: a_dev, private: private, official: official} = ctx

      {200, %{"chat" => chat}} = api(:get, "/api/v1/chats/#{private}", a.token, nil, a_dev)
      ex = example("chat_reply.json")["chat"]
      assert keys(chat) == keys(ex) and keys(chat["official"]) == keys(ex["official"])

      assert chat == %{
               "chat_id" => private,
               "kind" => "group",
               "private" => %{"conversation_id" => private},
               "official" => %{
                 "state" => "on",
                 "conversation_id" => official,
                 "changed_by" => nil,
                 "changed_at" => nil
               },
               "official_ready" => true,
               "missing" => [],
               "can_toggle" => true
             }

      {200, %{"chat" => bc}} = api(:get, "/api/v1/chats/#{private}", b.token, nil, ctx.b_dev)
      refute bc["can_toggle"]

      # A DM chat (e2ee) and the group chat, for the caller.
      dm = Messaging.conversation_id(a.user.id, b.user.id)
      now = DateTime.utc_now()

      Repo.insert_all("mls_groups", [
        %{conversation_id: dm, generation: 1, epoch: 1, e2ee_since: now, updated_at: now}
      ])

      {200, %{"chats" => chats}} = api(:get, "/api/v1/chats", a.token, nil, a_dev)
      assert Enum.map(chats, & &1["chat_id"]) |> Enum.sort() == Enum.sort([dm, private])
      d = Enum.find(chats, &(&1["chat_id"] == dm))
      assert {d["kind"], d["official"]["state"], d["can_toggle"]} == {"dm", "none", true}
      assert keys(d) == keys(hd(example("chats_reply.json")["chats"]))

      # Risi off: official_ready false with the agent_unavailable entry.
      Application.put_env(:risime, :risi, false)
      {200, %{"chat" => off}} = api(:get, "/api/v1/chats/#{private}", a.token, nil, a_dev)

      assert {off["official_ready"], off["missing"]} ==
               {false, [%{"user_id" => nil, "device_id" => nil, "reason" => "agent_unavailable"}]}

      # Not for a non-tabs device; 404 for a non-member.
      {404, _} = api(:get, "/api/v1/chats", a.token, nil, old_device!(a))
      {404, _} = api(:get, "/api/v1/chats/#{private}", a.token, nil, nil)
      s = logged_in_user()
      {404, _} = api(:get, "/api/v1/chats/#{private}", s.token, nil, tabs_device!(s))
    end
  end
end
