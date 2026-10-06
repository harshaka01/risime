defmodule RisiMeWeb.E2EESendTest do
  @moduledoc "Contract v1.7 §10.3: msg:send with ciphertext, the census on connect."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMeWeb.{InboxChannel, UserSocket}

  setup :with_attestation_key

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
    a_dev = mls_device!(a)
    _b_dev = mls_device!(b)

    {:ok, sock} =
      connect(UserSocket, %{"token" => a.token, "device_id" => a_dev, "app_version" => "0.3.0"})

    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
    %{a: a, b: b, a_dev: a_dev, chan: chan}
  end

  defp send_msg(chan, payload) do
    ref = push(chan, "msg:send", Map.merge(%{"client_msg_id" => Uniq.UUID.uuid4()}, payload))

    receive do
      %Phoenix.Socket.Reply{ref: ^ref, status: s, payload: p} -> {s, p}
    after
      2000 -> flunk("no reply")
    end
  end

  defp cipher(to, gen \\ 1, epoch \\ 1, bytes \\ 64),
    do: %{"to" => to, "ciphertext" => b64(bytes), "generation" => gen, "epoch" => epoch}

  test "plaintext works until the conversation is e2ee; then e2ee_required", %{
    a: a,
    b: b,
    chan: chan
  } do
    assert {:ok, _} = send_msg(chan, %{"to" => b.user.id, "body" => "plain"})
    # Ciphertext to a plaintext conversation is a bad_request.
    assert {:error, %{reason: "bad_request"}} = send_msg(chan, cipher(b.user.id))

    e2ee_group!(a, b)

    assert {:error, %{reason: "e2ee_required"}} =
             send_msg(chan, %{"to" => b.user.id, "body" => "plain"})
  end

  test "ciphertext: stale_epoch, too_long, and both inboxes get the body-less event", %{
    a: a,
    b: b,
    a_dev: a_dev,
    chan: chan
  } do
    e2ee_group!(a, b)
    assert {:error, %{reason: "stale_epoch"}} = send_msg(chan, cipher(b.user.id, 1, 0))
    assert {:error, %{reason: "stale_epoch"}} = send_msg(chan, cipher(b.user.id, 2, 1))

    # The cap is 24 KiB of decoded ciphertext: exactly 24 KiB is accepted, one byte more isn't.
    assert {:error, %{reason: "too_long"}} =
             send_msg(chan, cipher(b.user.id, 1, 1, 24 * 1024 + 1))

    assert {:ok, %{message_id: _}} = send_msg(chan, cipher(b.user.id, 1, 1, 24 * 1024))

    {:ok, nil} =
      RisiMe.Devices.register(b.user.id, Ecto.UUID.generate(), %{
        "platform" => "android",
        "push_token" => "fcm-b"
      })

    payload = cipher(b.user.id)
    assert {:ok, %{message_id: id}} = send_msg(chan, payload)

    for u <- [b.user.id, a.user.id] do
      {:ok, events, _} = RisiMe.Messaging.fetch_events(u, nil)
      assert [%{data: data}] = Enum.filter(events, &(&1.event_id == id))
      refute Map.has_key?(data, "body")
      assert data["ciphertext"] == payload["ciphertext"]
      assert data["from_device"] == a_dev and data["generation"] == 1 and data["epoch"] == 1
    end

    # A message wakes the recipient; MLS events never push.
    assert_receive {:push, _, %{"type" => "inbox"}}, 1_000
    RisiMe.Messaging.publish_mls(b.user.id, "mls_membership", %{"x" => 1})
    refute_receive {:push, _, _}, 500
  end

  test "a socket without device_id can't send ciphertext", %{a: a, b: b} do
    e2ee_group!(a, b)
    {:ok, sock} = connect(UserSocket, %{"token" => a.token})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
    assert {:error, %{reason: "bad_request"}} = send_msg(chan, cipher(b.user.id))
  end

  test "the census: device_id and app_version per connect; pre-v1.7 apps as legacy", %{
    a: a,
    a_dev: a_dev
  } do
    rows = fn ->
      RisiMe.Repo.all(
        from i in "app_instances",
          where: i.user_id == type(^a.user.id, :binary_id),
          select: {i.instance_key, i.app_version}
      )
    end

    assert {"device:" <> ^a_dev, "0.3.0"} =
             Enum.find(rows.(), &match?({"device:" <> _, "0.3.0"}, &1))

    {:ok, _} = connect(UserSocket, %{"token" => a.token})
    assert Enum.any?(rows.(), &match?({"legacy:token:" <> _, nil}, &1))
  end
end
