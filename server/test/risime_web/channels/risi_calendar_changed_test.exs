defmodule RisiMeWeb.RisiCalendarChangedTest do
  @moduledoc """
  v1.29 §29.6: the stored, content-free `risi_calendar_changed` reaches only the user's
  `risi_events` devices (live and on join) and never wakes a phone.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.Messaging
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

  setup do
    RisiMe.CalendarHelpers.events_on!()
    a = logged_in_user(display_name: "Asha")

    %{
      a: a,
      cal: RisiMe.CalendarHelpers.calendar_device!(a.user),
      tools: RisiMe.TabsHelpers.risi_tools_device!(a.user)
    }
  end

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})
    {:ok, reply, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    {chan, reply}
  end

  test "only risi_events sockets get it, live and on join", %{a: a} = ctx do
    {_c1, _} = join!(a, ctx.cal)
    {_c2, _} = join!(a, ctx.tools)

    data = %{"cursor" => "rc1.x", "server_ts" => "2026-10-09T05:00:00.000Z"}
    Messaging.publish_quiet(a.user.id, "risi_calendar_changed", data)
    assert_push "event", %{kind: "risi_calendar_changed", data: ^data}
    refute_push "event", %{kind: "risi_calendar_changed"}, 200

    {_c3, reply} = join!(a, ctx.cal)
    assert Enum.any?(reply.events, &(&1.kind == "risi_calendar_changed"))
    {_c4, reply} = join!(a, ctx.tools)
    refute Enum.any?(reply.events, &(&1.kind == "risi_calendar_changed"))
  end
end
