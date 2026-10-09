defmodule RisiMeWeb.RisiToolsS9Test do
  @moduledoc """
  v1.25 §25.8 (server S9): the `risi_tools` capability and readiness, `/auth/config`
  `risi_tools` from `RISI_TOOLS`, and the `risi_tools` delivery filter for `risi_progress` and
  `risi_tool_call` (live and replay, the tool call only to the device it names).
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]
  import RisiMe.TabsHelpers

  alias RisiMe.{Devices, Groups, Messaging, Repo, TimeUUID}
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  setup do
    a = logged_in_user(display_name: "Asha")
    %{a: a, tools: risi_tools_device!(a), tabs: tabs_device!(a), old: old_device!(a)}
  end

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    {chan, reply}
  end

  defp config do
    Phoenix.ConnTest.build_conn()
    |> Phoenix.ConnTest.dispatch(RisiMeWeb.Endpoint, :get, "/api/v1/auth/config")
    |> Map.fetch!(:resp_body)
    |> Jason.decode!()
  end

  test "/auth/config: risi_tools only while RISI_TOOLS=on (absent = off)" do
    refute Map.has_key?(config(), "risi_tools")
    risi_tools_on!()
    assert config()["risi_tools"] == "on"
  end

  test "the capability is stored and only with groups + tabs counts", %{a: a} = ctx do
    assert "risi_tools" in Repo.get_by!(Devices.Device, device_id: ctx.tools).capabilities
    assert Devices.risi_tools_device?(a.user.id, ctx.tools)
    refute Devices.risi_tools_device?(a.user.id, ctx.tabs)
    refute Devices.risi_tools_device?(a.user.id, nil)
    assert Devices.risi_tools_device_ids(a.user.id) == [ctx.tools]

    # risi_tools without tabs is not a risi_tools device.
    odd = tabs_device!(a, caps: ["groups", "risi_tools"])
    refute Devices.risi_tools_device?(a.user.id, odd)
  end

  test "risi_tools readiness", %{a: a} do
    b = logged_in_user(display_name: "Bimal")
    tabs_device!(b)
    {ready, missing} = Groups.risi_tools_readiness([a.user.id, b.user.id])
    assert MapSet.to_list(ready) == [a.user.id]
    assert [%{user_id: u, reason: "legacy_app"}] = missing
    assert u == b.user.id
  end

  test "risi_progress reaches only risi_tools sockets", %{a: a} = ctx do
    {_c1, _} = join!(a, ctx.tools)
    {_c2, _} = join!(a, ctx.tabs)
    {_c3, _} = join!(a, ctx.old)

    signal = %{
      kind: "risi_progress",
      data: %{"request_id" => Ecto.UUID.generate(), "state" => "working", "seq" => 1}
    }

    Messaging.signal(a.user.id, signal)
    assert_push "signal", %{kind: "risi_progress"}
    refute_push "signal", %{kind: "risi_progress"}, 200
  end

  test "risi_tool_call reaches only the device it names, live and on join", %{a: a} = ctx do
    other_tools = risi_tools_device!(a)
    {_c1, _} = join!(a, ctx.tools)
    {_c2, _} = join!(a, other_tools)
    {_c3, _} = join!(a, ctx.tabs)

    event = %{
      event_id: TimeUUID.generate(),
      kind: "risi_tool_call",
      data: %{
        "tool_call_id" => Ecto.UUID.generate(),
        "conversation_id" => "grp:" <> Ecto.UUID.generate(),
        "device_id" => ctx.tools,
        "tool" => "calendar_check",
        "args" => %{},
        "to_devices" => [ctx.tools]
      }
    }

    Messaging.publish_batch([{a.user.id, event, [push: false]}])
    assert_push "event", %{kind: "risi_tool_call"}
    refute_push "event", %{kind: "risi_tool_call"}, 200

    for {dev, n} <- [{ctx.tools, 1}, {other_tools, 0}, {ctx.tabs, 0}, {ctx.old, 0}] do
      {_chan, reply} = join!(a, dev)
      got = for e <- reply.events, e.kind == "risi_tool_call", do: e
      assert length(got) == n, "device #{dev}"
    end
  end
end
