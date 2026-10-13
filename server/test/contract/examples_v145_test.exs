defmodule RisiMe.Contract.ExamplesV145Test do
  @moduledoc """
  Contract v1.45 §40 (decision 086, adding people to a call): every new example file. The
  server-side ones (`add_to_call` kept, `Friend.group_calls`, the paused group Session) are
  checked against real server output; the client envelopes (`call_escalate`, `call_end` `moved`,
  `ring_users`, `features` `add`) against their shape rules, and a 1:1 `call:signal` carrying a
  `call_escalate` is an ordinary `ring: false` signal. Group transcription (§40.9) is covered in
  test/risime/agent/call_transcribe_group_test.exs.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers
  import Plug.Conn, only: [put_req_header: 3]
  import Phoenix.ConnTest, except: [connect: 2, connect: 3]

  alias RisiMeWeb.{InboxChannel, UserSocket}

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
  @grp ~r/^grp:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

  @doc false
  def files,
    do: ~w(device_put_add_to_call.json call_offer_features_add_payload.json
           call_answer_features_add_payload.json call_escalate_move_payload.json
           call_escalate_failed_payload.json call_end_moved_payload.json
           call_offer_sfu_ring_users_payload.json friends_reply_v145.json
           signal_call_transcribe_paused.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  setup :with_attestation_key

  test "the files exist and are JSON" do
    for f <- files(), do: assert(is_map(example(f)))
  end

  test "device_put_add_to_call.json: add_to_call is kept with group_calls and call_switch" do
    a = logged_in_user()
    ex = example("device_put_add_to_call.json")
    dev = Ecto.UUID.generate()

    assert build_conn()
           |> put_req_header("authorization", "Bearer " <> a.token)
           |> put("/api/v1/me/devices/#{dev}", ex)
           |> response(200)

    caps = RisiMe.Devices.get(a.user.id, dev).capabilities
    assert "add_to_call" in caps and "group_calls" in caps and "call_switch" in caps

    # Without group_calls or call_switch it is dropped (§40.1: "together with").
    for drop <- ~w(group_calls call_switch) do
      dev = Ecto.UUID.generate()
      body = update_in(ex, ["mls", "capabilities"], &(&1 -- [drop]))
      body = put_in(body, ["mls", "signature_key"], Base.encode64(:crypto.strong_rand_bytes(32)))

      assert build_conn()
             |> put_req_header("authorization", "Bearer " <> a.token)
             |> put("/api/v1/me/devices/#{dev}", body)
             |> response(200)

      refute "add_to_call" in RisiMe.Devices.get(a.user.id, dev).capabilities
    end
  end

  test "friends_reply_v145.json: Friend.group_calls from the friend's devices" do
    a = logged_in_user(display_name: "Asha")
    k = logged_in_user(display_name: "Kamal")
    s = logged_in_user(display_name: "Sunil")
    befriend!(a, k)
    befriend!(a, s)
    _ = device!(k, ~w(groups calls video group_calls call_switch add_to_call))
    _ = device!(s, ~w(groups calls video))

    %{"friends" => fs} =
      reply =
      build_conn()
      |> put_req_header("authorization", "Bearer " <> a.token)
      |> get("/api/v1/friends")
      |> json_response(200)

    ex = example("friends_reply_v145.json")
    assert Enum.sort(Map.keys(reply)) == Enum.sort(Map.keys(ex))

    for f <- fs,
        do: assert(Enum.sort(Map.keys(f)) == Enum.sort(Map.keys(hd(ex["friends"]))))

    by = Map.new(fs, &{&1["display_name"], &1["group_calls"]})
    assert by == %{"Kamal" => true, "Sunil" => false}
    assert Enum.map(ex["friends"], & &1["group_calls"]) |> Enum.sort() == [false, true]
  end

  test "call_escalate: move and failed (shape rules of §40.5)" do
    m = example("call_escalate_move_payload.json")
    assert %{"v" => 1, "type" => "call_escalate", "state" => "move"} = m
    assert m["call_id"] =~ @uuid and m["to_device"] =~ ~r/^[0-9a-f-]{36}$/
    assert m["conversation_id"] =~ @grp
    assert m["room_call_id"] =~ @uuid and m["room_call_id"] != m["call_id"]
    assert m["media"] in ~w(audio video)

    f = example("call_escalate_failed_payload.json")
    assert %{"v" => 1, "type" => "call_escalate", "state" => "failed"} = f
    assert f["reason"] in ~w(no_group room keys busy cancelled)
    refute Map.has_key?(f, "room_call_id")
  end

  test "call_end moved, ring_users, features add" do
    e = example("call_end_moved_payload.json")
    assert %{"type" => "call_end", "reason" => "moved", "duration_s" => d} = e
    assert is_integer(d) and e["moved_to"]["conversation_id"] =~ @grp
    assert e["moved_to"]["call_id"] =~ @uuid

    o = example("call_offer_sfu_ring_users_payload.json")
    assert %{"type" => "call_offer", "mode" => "sfu", "ring_users" => [_ | _] = us} = o
    assert length(us) <= 31 and Enum.all?(us, &(&1 =~ @uuid))

    for f <- ~w(call_offer_features_add_payload.json call_answer_features_add_payload.json),
        do: assert("add" in example(f)["features"])
  end

  test "a 1:1 call:signal carrying a call_escalate is an ordinary ring: false signal" do
    a = logged_in_user(display_name: "Asha")
    b = logged_in_user(display_name: "Bimal")
    befriend!(a, b)
    a_dev = device!(a, ~w(groups images deletes calls))
    _ = device!(b, ~w(groups images deletes calls))
    RisiMe.GroupHelpers.clear_legacy!()
    _conv = e2ee_group!(a, b)
    {:ok, sock} = connect(UserSocket, %{"token" => a.token, "device_id" => a_dev})
    {:ok, _, chan} = subscribe_and_join(sock, InboxChannel, "inbox:" <> a.user.id, %{})
    m = example("call_escalate_move_payload.json")

    ref =
      push(chan, "call:signal", %{
        "client_msg_id" => Ecto.UUID.generate(),
        "to" => b.user.id,
        "call_id" => m["call_id"],
        "ring" => false,
        "media" => m["media"],
        "ciphertext" => Base.encode64(:crypto.strong_rand_bytes(64)),
        "generation" => 1,
        "epoch" => 1,
        "client_ts" => "2026-10-13T08:17:50.140Z"
      })

    assert_reply ref, :ok, %{message_id: _}, 2000
  end

  test "signal_call_transcribe_paused.json: a paused group Session" do
    %{"kind" => "call_transcribe", "data" => d} = example("signal_call_transcribe_paused.json")

    assert d["state"] == "paused" and
             d["state"] not in RisiMe.Agent.CallTranscribe.Session.final_states()

    assert d["conversation_id"] =~ @grp and d["chat_id"] =~ @grp
    assert Enum.count(d["participants"], &(&1["consent"] == "pending")) == 1
    assert is_binary(d["expires_at"]) and is_binary(d["started_at"])
  end

  defp device!(user, caps) do
    dev = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(user.user.id, dev, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => caps
        }
      })

    :ok = RisiMe.MLS.record_instance(user.user.id, dev, nil, "0.3.0-test")
    dev
  end
end
