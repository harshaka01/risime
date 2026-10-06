defmodule RisiMe.PushTest do
  @moduledoc "Contract v1.5 §8: when pushes go out, what they carry, FCM HTTP v1 (stubbed)."
  use RisiMeWeb.ChannelCase, async: false

  import ExUnit.CaptureLog
  import RisiMe.Fixtures
  import Ecto.Query, only: [from: 2]

  alias RisiMe.{Devices, Push, Repo}
  alias RisiMe.Devices.Device
  alias RisiMe.Push.FCM
  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup do
    Application.put_env(:risime, :push_sender, RisiMe.Push.Test)
    Application.put_env(:risime, :push_test_pid, self())

    on_exit(fn ->
      Application.put_env(:risime, :push_sender, nil)
      Application.delete_env(:risime, :push_test_pid)
    end)

    a = logged_in_user()
    b = logged_in_user()
    befriend!(a, b)
    {:ok, sock_a} = connect(UserSocket, %{"token" => a.token})
    {:ok, _, chan_a} = subscribe_and_join(sock_a, InboxChannel, "inbox:" <> a.user.id, %{})
    %{a: a, b: b, chan_a: chan_a}
  end

  defp device!(user, token),
    do:
      :ok =
        Devices.register(user.id, Ecto.UUID.generate(), %{
          "platform" => "android",
          "push_token" => token
        })

  defp send_to(chan, to) do
    ref =
      push(chan, "msg:send", %{
        "client_msg_id" => Uniq.UUID.uuid4(),
        "to" => to,
        "body" => "secret body"
      })

    assert_reply ref, :ok, _
  end

  describe "trigger" do
    test "an offline recipient gets exactly the content-free payload", %{b: b, chan_a: chan_a} do
      device!(b.user, "tok-b")
      send_to(chan_a, b.user.id)
      assert_receive {:push, "tok-b", payload}, 1_000
      assert payload == %{"type" => "inbox", "v" => "1"}
      assert payload == Push.payload()
    end

    test "no push while the recipient has a live inbox channel", %{b: b, chan_a: chan_a} do
      device!(b.user, "tok-b")
      {:ok, sock_b} = connect(UserSocket, %{"token" => b.token})
      {:ok, _, _} = subscribe_and_join(sock_b, InboxChannel, "inbox:" <> b.user.id, %{})
      send_to(chan_a, b.user.id)
      refute_receive {:push, _, _}, 500
    end

    test "coalesced: one now and one trailing push per 10 s window (300 ms in test)",
         %{b: b, chan_a: chan_a} do
      device!(b.user, "tok-b")
      for _ <- 1..5, do: send_to(chan_a, b.user.id)

      assert_receive {:push, "tok-b", _}, 1_000
      assert_receive {:push, "tok-b", _}, 1_000
      refute_receive {:push, _, _}, 600
    end

    test "status events (acks) wake an offline sender too" do
      c = logged_in_user()
      d = logged_in_user()
      befriend!(c, d)
      device!(c.user, "tok-c")

      {:ok, %{message_id: id}} =
        RisiMe.Messaging.send(c.user.id, %{
          "client_msg_id" => Uniq.UUID.uuid4(),
          "to" => d.user.id,
          "body" => "x"
        })

      :ok = RisiMe.Messaging.ack(d.user.id, [id], "read")
      assert_receive {:push, "tok-c", %{"type" => "inbox", "v" => "1"}}, 1_000
    end

    test "an unregistered token deletes the device; retryable errors are retried once",
         %{b: b, chan_a: chan_a} do
      device!(b.user, "unregistered-1")
      device!(b.user, "retry-1")
      send_to(chan_a, b.user.id)

      assert_receive {:push, "unregistered-1", _}, 1_000
      assert_receive {:push, "retry-1", _}, 1_000
      assert_receive {:push, "retry-1", _}, 1_000
      refute_receive {:push, "retry-1", _}, 300

      Process.sleep(50)
      tokens = Repo.all(from d in Device, where: d.user_id == ^b.user.id, select: d.push_token)
      assert tokens == ["retry-1"]
    end

    test "push off: nothing is sent", %{b: b, chan_a: chan_a} do
      Application.put_env(:risime, :push_sender, nil)
      device!(b.user, "tok-b")
      send_to(chan_a, b.user.id)
      refute_receive {:push, _, _}, 500
    end
  end
end
