defmodule RisiMeWeb.RisiChatS10Test do
  @moduledoc """
  v1.25 §25.2 (server S10): the Risi chat. `POST /api/v1/risi/chat` (201/200, the create race,
  403/503), epoch 0 with every `risi_tools` device of the owner plus Risi's device, the `Chat`
  object, `422 risi_chat` on toggle/member/role calls, the `risi_tools` delivery filter
  (`GET /groups`, `GET /chats`, live events), devices ops for `risi_tools` devices only, and
  leaving (Risi's data for the chat deleted; a later POST creates a new chat).
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
    risi = risi_on!(10)
    tabs_on!()
    risi_tools_on!()
    a = logged_in_user(display_name: "Harsha")
    a_dev = risi_tools_device!(a)
    a_dev2 = risi_tools_device!(a)
    a_tabs = tabs_device!(a)
    clear_legacy!()
    %{a: a, a_dev: a_dev, a_dev2: a_dev2, a_tabs: a_tabs, risi: risi}
  end

  defp create!(user, dev), do: api(:post, "/api/v1/risi/chat", user.token, %{}, dev)

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  # Epoch 0: every risi_tools device of the owner plus Risi's device.
  defp epoch0!(%{a: a, a_dev: a_dev}, conv) do
    added =
      for %{user_id: u, device_id: d} <-
            Groups.groups_devices([a.user.id, RisiMe.Risi.user_id()], "risi_tools"),
          {u, d} != {a.user.id, a_dev},
          do: ref(u, d)

    api(
      :post,
      "/api/v1/mls/groups/#{conv}/commit",
      a.token,
      create_commit([], {a.user.id, a_dev}, %{"added" => added}),
      a_dev
    )
  end

  defp active_chat!(ctx) do
    {201, %{"chat" => %{"chat_id" => conv}}} = create!(ctx.a, ctx.a_dev)
    {200, %{"epoch" => 1}} = epoch0!(ctx, conv)
    conv
  end

  test "POST /risi/chat: 503 while off, 403 without risi_tools, 201 then 200", ctx do
    %{a: a, a_dev: a_dev, risi: risi} = ctx

    Application.put_env(:risime, :risi_tools, false)
    {503, %{"error" => %{"code" => "agent_unavailable"}}} = create!(a, a_dev)
    Application.put_env(:risime, :risi_tools, true)

    {403, %{"error" => %{"code" => "invalid_device"}}} = create!(a, ctx.a_tabs)
    {403, %{"error" => %{"code" => "invalid_device"}}} = create!(a, nil)

    {201, %{"chat" => chat, "group" => g} = reply} = create!(a, a_dev)
    ex = example("risi_chat_create_reply.json")
    assert keys(reply) == keys(ex)
    assert keys(chat) == keys(ex["chat"]) and keys(g) == keys(ex["group"])
    assert chat["chat_id"] == g["id"] and g["chat_id"] == g["id"]
    assert chat["kind"] == "risi" and chat["private"] == nil and chat["can_toggle"] == false

    assert chat["official"] == %{
             "state" => "none",
             "conversation_id" => g["id"],
             "changed_by" => nil,
             "changed_at" => nil
           }

    assert {g["state"], g["tab"], g["chat_kind"], g["my_role"]} ==
             {"creating", "official", "risi", "admin"}

    assert g["agents"] == [risi.user_id]

    assert Enum.map(g["members"], &{&1["user_id"], &1["role"], &1["kind"]}) ==
             [{a.user.id, "admin", "user"}, {risi.user_id, "member", "agent"}]

    {200, %{"chat" => %{"chat_id" => same}}} = create!(a, ctx.a_dev2)
    assert same == g["id"]
  end

  test "the create race: concurrent POSTs get one chat", %{a: a, a_dev: a_dev} do
    ids =
      1..4
      |> Enum.map(fn _ ->
        Task.async(fn ->
          Ecto.Adapters.SQL.Sandbox.allow(Repo, self(), self())
          {s, %{"chat" => %{"chat_id" => id}}} = create!(a, a_dev)
          {s, id}
        end)
      end)
      |> Enum.map(&Task.await/1)

    assert ids |> Enum.map(&elem(&1, 1)) |> Enum.uniq() |> length() == 1
    assert Enum.count(ids, &(elem(&1, 0) == 201)) == 1

    assert Repo.one(
             from r in "risi_chats",
               where: r.owner_id == type(^a.user.id, :binary_id),
               select: count()
           ) == 1
  end

  test "epoch 0 adds every risi_tools device of the owner and Risi; Chat on", ctx do
    %{a: a, a_dev: a_dev, risi: risi} = ctx
    {201, %{"chat" => %{"chat_id" => conv}}} = create!(a, a_dev)

    # Risi's key package may be claimed for its Risi chat; only risi_tools devices are listed.
    {200, %{"devices" => devs}} =
      api(
        :post,
        "/api/v1/mls/key_packages/claim",
        a.token,
        %{"user_ids" => [a.user.id, risi.user_id], "conversation_id" => conv},
        a_dev
      )

    assert Enum.sort(Enum.map(devs, & &1["device_id"])) ==
             Enum.sort([ctx.a_dev2, risi.device_id])

    # Without the second risi_tools device: bad_request.
    {400, _} =
      api(
        :post,
        "/api/v1/mls/groups/#{conv}/commit",
        a.token,
        create_commit([], {a.user.id, a_dev}, %{"added" => [ref(risi.user_id, risi.device_id)]}),
        a_dev
      )

    {200, %{"epoch" => 1}} = epoch0!(ctx, conv)
    assert Groups.get_group(conv).state == "active"
    assert RisiMe.RisiChat.active?(a.user.id)
    assert RisiMe.Groups.Tabs.agent_conversation?(risi.user_id, conv)

    {200, %{"chat" => chat}} = api(:get, "/api/v1/chats/#{conv}", a.token, nil, a_dev)
    ex = example("chat_reply_risi.json")["chat"]
    assert keys(chat) == keys(ex)

    assert chat == %{
             ex
             | "chat_id" => conv,
               "official" => %{ex["official"] | "conversation_id" => conv}
           }

    # Listed only to risi_tools devices; 404 elsewhere.
    {200, %{"chats" => chats}} = api(:get, "/api/v1/chats", a.token, nil, a_dev)
    assert Enum.any?(chats, &(&1["chat_id"] == conv and &1["kind"] == "risi"))
    {200, %{"chats" => chats}} = api(:get, "/api/v1/chats", a.token, nil, ctx.a_tabs)
    refute Enum.any?(chats, &(&1["chat_id"] == conv))
    {404, _} = api(:get, "/api/v1/chats/#{conv}", a.token, nil, ctx.a_tabs)

    {200, %{"groups" => gs}} = api(:get, "/api/v1/groups", a.token, nil, a_dev)
    assert Enum.any?(gs, &(&1["id"] == conv and &1["chat_kind"] == "risi"))
    {200, %{"groups" => gs}} = api(:get, "/api/v1/groups", a.token, nil, ctx.a_tabs)
    refute Enum.any?(gs, &(&1["id"] == conv))
    {404, _} = api(:get, "/api/v1/groups/#{conv}", a.token, nil, ctx.a_tabs)
    {200, _} = api(:get, "/api/v1/groups/#{conv}", a.token, nil, a_dev)

    # No chats row and no chat_event for a Risi chat.
    assert Repo.get(RisiMe.Groups.Chat, conv) == nil
  end

  test "422 risi_chat on toggle, …/official, member and role calls", ctx do
    %{a: a, a_dev: a_dev, risi: risi} = ctx
    conv = active_chat!(ctx)
    b = logged_in_user(display_name: "Kamal")
    befriend!(a, b)

    for {method, path, body} <- [
          {:patch, "/api/v1/chats/#{conv}", %{"official" => "off"}},
          {:post, "/api/v1/chats/#{conv}/official", %{}},
          {:post, "/api/v1/groups/#{conv}/members", %{"user_ids" => [b.user.id]}},
          {:patch, "/api/v1/groups/#{conv}/members/#{risi.user_id}", %{"role" => "admin"}},
          {:delete, "/api/v1/groups/#{conv}/members/#{risi.user_id}", nil}
        ] do
      assert {422, %{"error" => %{"code" => "risi_chat"}}} =
               api(method, path, a.token, body, a_dev),
             "#{method} #{path}"
    end

    # A non-member gets 404, not risi_chat.
    b_dev = risi_tools_device!(b)
    {404, _} = api(:get, "/api/v1/chats/#{conv}", b.token, nil, b_dev)
  end

  test "delivery: Risi-chat events only to risi_tools sockets", ctx do
    %{a: a} = ctx
    conv = active_chat!(ctx)

    join = fn dev ->
      {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => dev})
      {:ok, _, _} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
    end

    join.(ctx.a_dev)
    join.(ctx.a_tabs)

    g = Groups.get_group(conv)
    Messaging.publish_batch(Groups.group_events(g, "metadata_changed", a.user.id, []))
    assert_push "event", %{kind: "group_event", data: %{"group_id" => ^conv}}
    refute_push "event", %{kind: "group_event", data: %{"group_id" => ^conv}}, 200
  end

  test "devices ops: only a new risi_tools device of the owner joins the Risi chat", ctx do
    %{a: a} = ctx
    conv = active_chat!(ctx)
    _ = tabs_device!(a)
    assert RisiMe.Groups.Ops.list(conv) == []
    d = risi_tools_device!(a)
    [op] = RisiMe.Groups.Ops.list(conv)
    assert op.type == "devices" and inspect(op.payload) =~ d
  end

  test "leave: Risi's data for the chat is deleted; a later POST creates a new chat", ctx do
    %{a: a, a_dev: a_dev, risi: risi} = ctx
    conv = active_chat!(ctx)

    {:ok, note} =
      Repo.insert(%RisiMe.Agent.Fact{
        id: Ecto.UUID.generate(),
        subject_user_id: a.user.id,
        chat_id: conv,
        conversation_id: conv,
        kind: "note",
        text: "My dentist is Dr Swan",
        source_message_ids: []
      })

    {204, _} = api(:post, "/api/v1/groups/#{conv}/leave", a.token, %{}, a_dev)
    assert Repo.get(RisiMe.Agent.Fact, note.id) == nil
    assert Groups.member(conv, a.user.id).state == "pending_remove"
    assert Groups.member(conv, risi.user_id).state == "pending_remove"
    refute RisiMe.RisiChat.active?(a.user.id)
    refute RisiMe.Groups.Tabs.agent_conversation?(risi.user_id, conv)

    assert Repo.exists?(
             from j in Oban.Job,
               where:
                 j.worker == "RisiMe.Workers.Risi" and
                   fragment("?->>'kind' = 'forget' and ?->>'conv' = ?", j.args, j.args, ^conv)
           )

    ev = last_event(a.user.id, "group_event")
    assert ev["data"]["action"] == "removed" and a.user.id in ev["data"]["targets"]

    {201, %{"chat" => %{"chat_id" => new}}} = create!(a, a_dev)
    assert new != conv
  end
end
