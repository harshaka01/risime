defmodule RisiMeWeb.CallTranscribeChannelTest do
  @moduledoc """
  Contract v1.39 §36.3 on the wire: the `call:transcribe` push on the inbox channel and its
  replies, and the `call_transcribe` signal reaching only `call_transcribe` devices.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.CallTranscribeHelpers

  alias RisiMeWeb.{InboxChannel, UserSocket}

  @moduletag capture_log: true

  setup do
    transcribe_world!()
  end

  defp join!(user, device) do
    {:ok, sock} = connect(UserSocket, %{"token" => user.token, "device_id" => device})
    {:ok, _, sock} = subscribe_and_join(sock, InboxChannel, "inbox:" <> user.user.id, %{})
    sock
  end

  test "request and allow over the socket; the signal reaches call_transcribe devices only", w do
    sa = join!(w.a, w.da)
    sb = join!(w.b, w.db)
    old = RisiMe.TabsHelpers.tabs_device!(w.b.user, caps: ~w(groups member_devices tabs))
    _sb_old = join!(w.b, old)

    ref =
      push(sa, "call:transcribe", %{
        "action" => "request",
        "call_id" => w.call_id,
        "conversation_id" => w.og
      })

    assert_reply ref, :ok, %{session: %{"state" => "requested", "session_id" => sid}}

    # Every live `call_transcribe` socket of both users gets it; the old device's doesn't.
    assert_push "signal", %{kind: "call_transcribe", data: %{"state" => "requested"}}
    assert_push "signal", %{kind: "call_transcribe", data: %{"state" => "requested"}}
    refute_push "signal", %{kind: "call_transcribe"}, 200

    ref =
      push(sb, "call:transcribe", %{
        "action" => "allow",
        "call_id" => w.call_id,
        "conversation_id" => w.og,
        "session_id" => sid
      })

    assert_reply ref, :ok, %{session: %{"state" => "on"}}
  end

  test "errors come back as {reason}", w do
    sa = join!(w.a, w.da)

    ref =
      push(sa, "call:transcribe", %{
        "action" => "request",
        "call_id" => Ecto.UUID.generate(),
        "conversation_id" => w.og
      })

    assert_reply ref, :error, %{reason: "unknown_call"}

    Application.put_env(:risime, :risi_call_transcribe, false)

    ref =
      push(sa, "call:transcribe", %{
        "action" => "request",
        "call_id" => w.call_id,
        "conversation_id" => w.og
      })

    assert_reply ref, :error, %{reason: "transcribe_off"}
  end

  test "a 1:1 ring through call:signal records the call's participants (ring: false doesn't)" do
    import RisiMe.Fixtures
    import RisiMe.MLSHelpers

    test_push!(%{})
    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    befriend!(a, b)
    caps = ~w(groups images deletes calls)
    a_dev = RisiMe.TabsHelpers.tabs_device!(a.user, caps: caps)
    _b_dev = RisiMe.TabsHelpers.tabs_device!(b.user, caps: caps)
    RisiMe.GroupHelpers.clear_legacy!()
    conv = e2ee_group!(a, b)
    chan = join!(a, a_dev)

    signal = fn call_id, ring ->
      ref =
        push(chan, "call:signal", %{
          "client_msg_id" => Ecto.UUID.generate(),
          "to" => b.user.id,
          "call_id" => call_id,
          "ring" => ring,
          "ciphertext" => b64(64),
          "generation" => 1,
          "epoch" => 1
        })

      assert_reply ref, :ok, _
    end

    call_id = Ecto.UUID.generate()
    signal.(call_id, true)
    rec = RisiMe.Agent.CallTranscribe.Calls.get(call_id)
    assert rec.from == a.user.id and rec.to == b.user.id and rec.conv == conv

    other = Ecto.UUID.generate()
    signal.(other, false)
    assert RisiMe.Agent.CallTranscribe.Calls.get(other) == nil
  end
end
