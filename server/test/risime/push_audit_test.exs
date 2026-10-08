defmodule RisiMe.PushAuditTest do
  @moduledoc "Push audit log lines: per-device result, skipped-online, no-token (never a token)."
  use RisiMeWeb.ChannelCase, async: false

  import ExUnit.CaptureLog
  import RisiMe.Fixtures

  alias RisiMe.{Devices, Push}
  alias RisiMe.Push.Dispatcher
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup do
    test_push!()
    old = Logger.level()
    Logger.configure(level: :info)
    on_exit(fn -> Logger.configure(level: old) end)
    %{u: logged_in_user()}
  end

  defp device!(user, prefix) do
    token = push_token(prefix)
    device_id = Ecto.UUID.generate()

    {:ok, nil} =
      Devices.register(user.id, device_id, %{"platform" => "android", "push_token" => token})

    {device_id, token}
  end

  # Some lines are written by async tasks: capture while they finish.
  defp logs(fun) do
    capture_log(fn ->
      fun.()
      Process.sleep(400)
    end)
  end

  test "inbox push logs one line per device with a hashed user and a short device id", %{u: u} do
    {device_id, token} = device!(u.user, "tok")
    hash = Dispatcher.user_hash(u.user.id)
    dev = String.slice(device_id, 0, 8)

    log = logs(fn -> Dispatcher.push_now(u.user.id) end)

    assert log =~ ~r/push: kind=inbox user=#{hash} device=#{dev} result=ok ms=\d+/
    refute log =~ token
    refute log =~ u.user.id
  end

  test "an unregistered token logs result=unregistered", %{u: u} do
    {_, token} = device!(u.user, "unregistered")
    log = logs(fn -> Dispatcher.push_now(u.user.id) end)
    assert log =~ ~r/push: kind=inbox user=\w{8} device=\w{8} result=unregistered ms=\d+/
    refute log =~ token
  end

  test "a call push logs kind=call", %{u: u} do
    {_, token} = device!(u.user, "tok")
    log = logs(fn -> Dispatcher.push_call([token]) end)
    assert log =~ ~r/push: kind=call user=\w{8} device=\w{8} result=ok ms=\d+/
    refute log =~ token
  end

  test "an inbox push to explicit tokens logs kind=inbox", %{u: u} do
    {_, token} = device!(u.user, "tok")
    log = logs(fn -> Dispatcher.push_inbox([token]) end)
    assert log =~ ~r/push: kind=inbox user=\w{8} device=\w{8} result=ok/
    refute log =~ token
  end

  test "a user without a push token logs push: none", %{u: u} do
    hash = Dispatcher.user_hash(u.user.id)
    log = logs(fn -> Dispatcher.push_now(u.user.id) end)
    assert log =~ "push: none kind=inbox user=#{hash} reason=no_token"
  end

  test "an online user logs skipped reason=online with the device count", %{u: u} do
    device!(u.user, "tok")
    hash = Dispatcher.user_hash(u.user.id)
    {:ok, sock} = connect(UserSocket, %{"token" => u.token})
    {:ok, _, _} = subscribe_and_join(sock, InboxChannel, "inbox:" <> u.user.id, %{})

    log = logs(fn -> Push.notify(u.user.id) end)
    assert log =~ ~r/push: skipped kind=inbox user=#{hash} reason=online devices_online=\d+/
  end
end
