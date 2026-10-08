defmodule RisiMeWeb.TabsS2Test do
  @moduledoc """
  v1.24 §24.7 (server S2): the `tabs` capability and readiness, the delivery filter (live and
  replay), `GET /groups[/id]` by `X-Device-Id`, the push filter, `/auth/config` `tabs`.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.GroupHelpers
  import RisiMe.TabsHelpers

  alias RisiMe.{Groups, Messaging, TimeUUID}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  setup do
    [a, b, c] = for n <- ~w(Asha Bimal Chamari), do: logged_in_user(display_name: n)
    befriend!(a, b)
    befriend!(a, c)
    a_dev = tabs_device!(a)
    b_old = old_device!(b)
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

    official =
      official_group!(
        private,
        [{a.user.id, "admin"}, {b.user.id, "member"}, {c.user.id, "member"}],
        leaves: [{a.user.id, a_dev}, {c.user.id, c_dev}]
      )

    %{
      a: a,
      b: b,
      c: c,
      a_dev: a_dev,
      b_old: b_old,
      c_dev: c_dev,
      private: private,
      official: official
    }
  end

  defp join!(user, device) do
    params = %{"token" => user.token, "device_id" => device}
    {:ok, sock} = connect(UserSocket, params)
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
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

  defp send_payload(conv),
    do: %{
      "client_msg_id" => Ecto.UUID.generate(),
      "conversation_id" => conv,
      "ciphertext" => b64(),
      "generation" => 1,
      "epoch" => 1
    }

  defp chat_event(chat_id, actor, official) do
    %{
      event_id: TimeUUID.generate(),
      kind: "chat_event",
      data: %{
        "chat_id" => chat_id,
        "action" => "official_on",
        "actor" => actor,
        "official_conversation_id" => official,
        "server_ts" => Messaging.iso(DateTime.utc_now())
      }
    }
  end

  defp official_event?(e, official),
    do: e.kind == "chat_event" or (e.data["conversation_id"] || e.data["group_id"]) == official

  describe "delivery filter (§24.7)" do
    test "an old socket never receives an Official event or a chat_event; a tabs socket does",
         ctx do
      %{a: a, b: b, c: c, official: official, private: private} = ctx
      {_b_chan, _} = join!(b, ctx.b_old)
      {_c_chan, _} = join!(c, ctx.c_dev)
      {a_chan, _} = join!(a, ctx.a_dev)
      bt = "inbox:" <> b.user.id
      ct = "inbox:" <> c.user.id

      # A message, typing, a group_event and a chat_event of the Official conversation.
      {:ok, %{message_id: mid}} = call(a_chan, "msg:send", send_payload(official))
      {:ok, _} = call(a_chan, "typing", %{"conversation_id" => official, "typing" => true})
      g = Groups.get_group(official)
      Messaging.publish_batch(Groups.group_events(g, "metadata_changed", a.user.id, []))

      Messaging.publish_batch(
        for u <- [b.user.id, c.user.id],
            do: {u, chat_event(private, a.user.id, official), []}
      )

      assert_receive %Phoenix.Socket.Message{topic: ^ct, payload: %{event_id: ^mid}}

      assert_receive %Phoenix.Socket.Message{
        topic: ^ct,
        event: "signal",
        payload: %{kind: "typing"}
      }

      assert_receive %Phoenix.Socket.Message{
        topic: ^ct,
        payload: %{kind: "group_event", data: %{"tab" => "official"}}
      }

      assert_receive %Phoenix.Socket.Message{topic: ^ct, payload: %{kind: "chat_event"}}
      refute_receive %Phoenix.Socket.Message{topic: ^bt}, 200

      # Private traffic still reaches the old socket.
      {:ok, %{message_id: pmid}} =
        call(a_chan, "msg:send", send_payload(private))

      assert_receive %Phoenix.Socket.Message{topic: ^bt, payload: %{event_id: ^pmid}}

      # Replays: the old device's join reply holds no Official event; the tabs one does.
      {_, b_reply} = join!(b, ctx.b_old)
      refute Enum.any?(b_reply.events, &official_event?(&1, official))
      assert Enum.any?(b_reply.events, &(&1.event_id == pmid))

      {_, c_reply} = join!(c, ctx.c_dev)
      assert Enum.count(c_reply.events, &official_event?(&1, official)) == 3
    end

    test "a device that gains tabs while connected starts receiving at once", ctx do
      %{a: a, b: b, official: official} = ctx
      {_chan, _} = join!(b, ctx.b_old)
      {a_chan, _} = join!(a, ctx.a_dev)
      bt = "inbox:" <> b.user.id

      {:ok, %{message_id: m1}} = call(a_chan, "msg:send", send_payload(official))
      refute_receive %Phoenix.Socket.Message{topic: ^bt, payload: %{event_id: ^m1}}, 150

      # Same key, now with `tabs`.
      d = RisiMe.Repo.get_by!(RisiMe.Devices.Device, device_id: ctx.b_old)

      {:ok, _} =
        RisiMe.Devices.register(b.user.id, ctx.b_old, %{
          "platform" => "android",
          "mls" => %{
            "signature_key" => Base.encode64(d.mls_signature_key),
            "capabilities" => ["groups", "tabs"]
          }
        })

      {:ok, %{message_id: m2}} = call(a_chan, "msg:send", send_payload(official))
      assert_receive %Phoenix.Socket.Message{topic: ^bt, payload: %{event_id: ^m2}}
    end
  end

  describe "GET /groups by X-Device-Id (§24.7)" do
    test "Official groups only for a tabs device; 404 for one by id otherwise", ctx do
      %{b: b, c: c, official: official, private: private} = ctx

      for dev <- [ctx.b_old, nil] do
        {200, %{"groups" => gs}} = api(:get, "/api/v1/groups", b.token, nil, dev)
        assert Enum.map(gs, & &1["id"]) == [private]
        # A Private group's JSON is exactly v1.23 (no tab fields).
        refute Map.has_key?(hd(gs), "tab") or Map.has_key?(hd(gs), "chat_id")
        {404, _} = api(:get, "/api/v1/groups/#{official}", b.token, nil, dev)
      end

      {200, %{"groups" => gs}} = api(:get, "/api/v1/groups", c.token, nil, ctx.c_dev)
      assert Enum.sort(Enum.map(gs, & &1["id"])) == Enum.sort([private, official])

      {200, %{"group" => g}} = api(:get, "/api/v1/groups/#{official}", c.token, nil, ctx.c_dev)

      assert {g["tab"], g["chat_id"], g["chat_kind"], g["agents"]} ==
               {"official", private, "group", []}

      # c's tabs device header on b's request does not count (not b's device).
      {200, %{"groups" => gs}} = api(:get, "/api/v1/groups", b.token, nil, ctx.c_dev)
      assert Enum.map(gs, & &1["id"]) == [private]
    end
  end

  describe "push filter (§24.7)" do
    setup :test_push!

    test "an Official event wakes only tabs devices; a Private one wakes every device", ctx do
      %{a: a, official: official, private: private} = ctx
      d = logged_in_user(display_name: "Dilan")
      old_tok = push_token("old")
      tabs_tok = push_token("tabs")
      old_device!(d, push_token: old_tok)
      tabs_device!(d, push_token: tabs_tok)

      ev = fn conv ->
        %{
          event_id: TimeUUID.generate(),
          kind: "message",
          data: %{"conversation_id" => conv, "from" => a.user.id}
        }
      end

      Messaging.publish_batch([{d.user.id, ev.(official), []}])
      assert_receive {:push, ^tabs_tok, _}, 1_000
      refute_receive {:push, ^old_tok, _}, 500

      # Outside the window: a Private event wakes both.
      Messaging.publish_batch([{d.user.id, ev.(private), []}])
      assert_receive {:push, ^tabs_tok, _}, 1_000
      assert_receive {:push, ^old_tok, _}, 1_000

      # A chat_event is tabs-only too, and an online old socket does not hold it.
      {_chan, _} =
        join!(d, RisiMe.Repo.get_by!(RisiMe.Devices.Device, push_token: old_tok).device_id)

      Process.sleep(350)
      Messaging.publish_batch([{d.user.id, chat_event(private, a.user.id, official), []}])
      assert_receive {:push, ^tabs_tok, _}, 1_000
      refute_receive {:push, ^old_tok, _}, 500
    end

    test "coalesced: a Private event in the window widens the trailing push to every device",
         ctx do
      %{a: a, official: official, private: private} = ctx
      d = logged_in_user(display_name: "Dilan")
      old_tok = push_token("old")
      tabs_tok = push_token("tabs")
      old_device!(d, push_token: old_tok)
      tabs_device!(d, push_token: tabs_tok)

      ev = fn conv ->
        %{event_id: TimeUUID.generate(), kind: "message", data: %{"conversation_id" => conv}}
      end

      Messaging.publish_batch([{d.user.id, ev.(private), []}])
      assert_receive {:push, ^old_tok, _}, 1_000
      assert_receive {:push, ^tabs_tok, _}, 1_000
      # In the window: an Official event alone → a trailing push to the tabs device only.
      Messaging.publish_batch([{d.user.id, ev.(official), []}])
      assert_receive {:push, ^tabs_tok, _}, 1_000
      refute_receive {:push, ^old_tok, _}, 400
      _ = a
    end
  end

  describe "tabs readiness (§24.7)" do
    test "the §12.1 rule with tabs in place of groups", ctx do
      %{b: b, c: c} = ctx
      {ready, missing} = Groups.tabs_readiness([b.user.id, c.user.id])
      assert MapSet.to_list(ready) == [c.user.id]

      assert [%{user_id: u, device_id: d, reason: "legacy_app"}] = missing
      assert {u, d} == {b.user.id, ctx.b_old}

      # Still group-ready (§12.1 unchanged).
      {ready, []} = Groups.readiness([b.user.id, c.user.id])
      assert MapSet.size(ready) == 2

      # A newer tabs registration supersedes the old install (the v1.21 §12.1 rule applies).
      tabs_device!(b)
      {ready, []} = Groups.tabs_readiness([b.user.id])
      assert MapSet.size(ready) == 1
    end

    test "the tabs capability is kept in mls.capabilities", ctx do
      d = RisiMe.Repo.get_by!(RisiMe.Devices.Device, device_id: ctx.c_dev)
      assert "tabs" in d.capabilities
      assert RisiMe.Devices.tabs?(d)

      refute RisiMe.Devices.tabs?(
               RisiMe.Repo.get_by!(RisiMe.Devices.Device, device_id: ctx.b_old)
             )
    end
  end

  describe "/auth/config tabs (§24.15)" do
    test "absent while TABS is off; on with TABS=on (auth_config_v124.json)" do
      {200, off} = api_noauth("/api/v1/auth/config")
      refute Map.has_key?(off, "tabs")

      tabs_on!()
      {200, on} = api_noauth("/api/v1/auth/config")
      assert on["tabs"] == "on"
      ex = example("auth_config_v124.json")
      assert Enum.sort(Map.keys(on)) == Enum.sort(Map.keys(ex))
    end
  end

  defp api_noauth(path) do
    conn = Phoenix.ConnTest.dispatch(Phoenix.ConnTest.build_conn(), @endpoint, :get, path, nil)
    {conn.status, Jason.decode!(conn.resp_body)}
  end
end
