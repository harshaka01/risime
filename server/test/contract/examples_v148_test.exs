defmodule RisiMe.Contract.ExamplesV148Test do
  @moduledoc """
  Contract v1.48 §41 (decision 090, Call Risi): every new example against what the server really
  accepts, mints, answers or posts. The behaviour (refusal order, the 2-seat room, `join` refused,
  `max_s` from the admin setting, parity) is in test/risime_web/controllers/call_risi_rooms_test.exs
  and test/risime/agent/call_risi/.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.Agent.CallRisi

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(device_put_call_risi.json me_reply_call_risi.json calls_room_request_call_risi.json
            calls_room_reply_call_risi.json error_call_risi_off.json error_risi_busy.json
            error_risi_unavailable.json error_not_risi_chat.json
            livekit_token_claims_call_risi.json group_call_started_call_risi_payload.json
            group_call_ended_call_risi_payload.json envelope_risi_call_risi_transcript.json
            envelope_risi_answer_call.json)

  @doc "The v1.48 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

  defp caps_of(user_id, device_id),
    do:
      RisiMe.Repo.get_by!(RisiMe.Devices.Device, user_id: user_id, device_id: device_id).capabilities

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  setup :with_attestation_key

  setup do
    keys = [:risi_agent_flags, :risi_dev_users, :risi_user_id, :risi_device_id]
    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}

    on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v,
              do: Application.put_env(:risime, k, v),
              else: Application.delete_env(:risime, k)
            )
    end)

    :ok
  end

  test "device_put_call_risi.json: the server keeps call_risi (with group_calls and risi_tools)" do
    ex = example("device_put_call_risi.json")
    caps = ex["mls"]["capabilities"]
    assert "call_risi" in caps and "group_calls" in caps and "risi_tools" in caps

    u = logged_in_user(display_name: "Caps")
    dev = Ecto.UUID.generate()
    {:ok, _} = RisiMe.Devices.register(u.user.id, dev, ex)
    assert "call_risi" in caps_of(u.user.id, dev)

    # Without risi_tools the server drops it (§41.1).
    dev2 = Ecto.UUID.generate()
    ex2 = put_in(ex, ["mls", "capabilities"], caps -- ["risi_tools"])
    {:ok, _} = RisiMe.Devices.register(u.user.id, dev2, ex2)
    refute "call_risi" in caps_of(u.user.id, dev2)
  end

  test "me_reply_call_risi.json: GET /me carries call_risi (and risi_dev) for a canary dev user",
       %{conn: conn} do
    u = logged_in_user(display_name: "Test User A", company: "Risi")
    Application.put_env(:risime, :risi_dev_users, [u.user.id])
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_CALL_RISI" => "canary"})

    me =
      conn
      |> put_req_header("authorization", "Bearer " <> u.token)
      |> get("/api/v1/me")
      |> json_response(200)

    ex = example("me_reply_call_risi.json")["user"]
    assert me["user"]["call_risi"] == ex["call_risi"] and me["user"]["risi_dev"] == ex["risi_dev"]
    assert Map.keys(ex) -- Map.keys(me["user"]) == []

    Application.put_env(:risime, :risi_agent_flags, %{"RISI_CALL_RISI" => "off"})

    me =
      conn
      |> put_req_header("authorization", "Bearer " <> u.token)
      |> get("/api/v1/me")
      |> json_response(200)

    refute Map.has_key?(me["user"], "call_risi")
  end

  test "calls_room_request_call_risi.json and calls_room_reply_call_risi.json" do
    req = example("calls_room_request_call_risi.json")
    assert req["risi_call"] == true and req["action"] == "start" and req["media"] == "audio"
    assert req["conversation_id"] =~ ~r/^grp:/ and req["call_id"] =~ @uuid

    reply = example("calls_room_reply_call_risi.json")
    assert reply["max_participants"] == 2 and reply["media"] == "audio"

    assert reply["risi_call"] == %{
             "state" => "joining",
             "e2ee" => false,
             "risi_identity" => reply["risi_call"]["risi_identity"],
             "max_s" => 1800,
             "lang" => "en"
           }

    [u, d] = String.split(reply["risi_call"]["risi_identity"], "/")
    assert u =~ @uuid and d =~ @uuid
    # The server's real reply is checked key for key in call_risi_rooms_test.exs.
    {lo, hi} = CallRisi.max_s_bounds()
    assert reply["risi_call"]["max_s"] in lo..hi
  end

  test "the four error examples are exactly the server's error replies", %{conn: conn} do
    for {name, status, code} <- [
          {"error_call_risi_off.json", 403, :call_risi_off},
          {"error_not_risi_chat.json", 422, :not_risi_chat},
          {"error_risi_busy.json", 409, :risi_busy},
          {"error_risi_unavailable.json", 503, :risi_unavailable}
        ] do
      assert RisiMeWeb.ApiError.send_error(conn, status, code) |> json_response(status) ==
               example(name),
             name
    end
  end

  test "livekit_token_claims_call_risi.json is exactly Risi's minted claims" do
    ex = example("livekit_token_claims_call_risi.json")
    [u, d] = String.split(ex["sub"], "/")
    Application.put_env(:risime, :risi_user_id, u)
    Application.put_env(:risime, :risi_device_id, d)

    assert CallRisi.risi_claims(ex["iss"], ex["video"]["room"], ex["nbf"]) == ex
  end

  test "the group_call payloads carry with: risi (§41.3)" do
    s = example("group_call_started_call_risi_payload.json")
    e = example("group_call_ended_call_risi_payload.json")

    assert Map.delete(s, "with") ==
             Map.merge(example("group_call_started_payload.json"), %{
               "call_id" => s["call_id"]
             })

    assert s["with"] == "risi" and e["with"] == "risi"

    assert Enum.sort(Map.keys(Map.delete(e, "with"))) ==
             Enum.sort(Map.keys(example("group_call_ended_payload.json")))

    assert e["reason"] in ~w(hangup timeout) and is_integer(e["duration_s"])
  end

  test "envelope_risi_call_risi_transcript.json is the server's transcript post" do
    ex = example("envelope_risi_call_risi_transcript.json")
    r = ex["risi"]
    {:ok, started, _} = DateTime.from_iso8601(r["started_at"])
    {:ok, ended, _} = DateTime.from_iso8601(r["ended_at"])
    [user] = r["notify"]

    {risi, body} =
      CallRisi.transcript(
        %{call_id: r["call_id"], user_id: user},
        r["lines"],
        started,
        ended,
        :hangup
      )

    ours = RisiMe.Agent.Out.risi(risi)
    assert put_in(ours, ["made_by", "at"], r["made_by"]["at"]) == r
    assert body == ex["body"]
    assert ex["type"] == "text" and ex["v"] == 1
  end

  test "envelope_risi_answer_call.json: a call's answer card carries call_id (Turn.voice_sink)" do
    ex = example("envelope_risi_answer_call.json")
    r = ex["risi"]
    Process.put(:risi_voice_sink, {self(), r["call_id"]})

    try do
      assert RisiMe.Agent.Turn.voice_sink(r["answer"], Map.delete(r, "call_id")) == r
      assert_received {:risi_voice_answer, req, answer}
      assert req == r["request_id"] and answer == r["answer"]
    after
      Process.delete(:risi_voice_sink)
    end

    # Outside a call nothing changes (parity).
    plain = Map.delete(r, "call_id")
    assert RisiMe.Agent.Turn.voice_sink(r["answer"], plain) == plain
  end
end
