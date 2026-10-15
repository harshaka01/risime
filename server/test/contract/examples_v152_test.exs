defmodule RisiMe.Contract.ExamplesV152Test do
  @moduledoc """
  Contract v1.52 §41.8 (proposal 2026-10-15-call-risi-calendar-titles): meeting titles from the
  phone over the Call Risi room's data channel. Every new example against what the server really
  accepts, mints, sends, reads or posts. The helpers are covered in
  test/risime/agent/call_risi/phone_calendar_test.exs; the tokens of a real `start` in
  test/risime_web/controllers/call_risi_rooms_test.exs.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.Agent.CallRisi
  alias RisiMe.Agent.CallRisi.PhoneCalendar
  alias RisiMe.Calls.LiveKit

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(device_put_call_risi_cal.json livekit_token_claims_call_risi_v152.json
            call_risi_cal_request.json call_risi_cal_reply.json call_risi_cal_reply_error.json
            envelope_risi_call_risi_transcript_titles.json)

  @doc "The v1.52 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp caps_of(user_id, device_id),
    do:
      RisiMe.Repo.get_by!(RisiMe.Devices.Device, user_id: user_id, device_id: device_id).capabilities

  defp assert_shape(ours, theirs, path \\ "$")

  defp assert_shape(ours, theirs, path) when is_map(theirs) do
    assert is_map(ours), "#{path}: expected an object, got #{inspect(ours)}"
    assert Enum.sort(Map.keys(ours)) == Enum.sort(Map.keys(theirs)), "#{path}: keys"
    for {k, v} <- theirs, do: assert_shape(ours[k], v, "#{path}.#{k}")
  end

  defp assert_shape(ours, [_ | _] = theirs, path) do
    assert is_list(ours) and length(ours) == length(theirs), "#{path}: list"
    for {o, x} <- Enum.zip(ours, theirs), do: assert_shape(o, x, path <> "[]")
  end

  defp assert_shape(ours, [], path), do: assert(is_list(ours), path)
  defp assert_shape(_ours, nil, _path), do: :ok
  defp assert_shape(ours, t, path) when is_binary(t), do: assert(is_binary(ours), path)
  defp assert_shape(ours, t, path) when is_boolean(t), do: assert(is_boolean(ours), path)
  defp assert_shape(ours, t, path) when is_integer(t), do: assert(is_integer(ours), path)

  setup :with_attestation_key

  setup do
    keys = [:risi_user_id, :risi_device_id]
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

  test "device_put_call_risi_cal.json: call_risi_cal is kept only with call_risi" do
    ex = example("device_put_call_risi_cal.json")
    caps = ex["mls"]["capabilities"]
    assert "call_risi_cal" in caps and "call_risi" in caps

    u = logged_in_user(display_name: "Cal Caps")
    dev = Ecto.UUID.generate()
    {:ok, _} = RisiMe.Devices.register(u.user.id, dev, ex)
    assert "call_risi_cal" in caps_of(u.user.id, dev)
    assert PhoneCalendar.capable?(u.user.id, dev)

    # Without call_risi (here: without risi_tools, so call_risi goes too) it is dropped.
    dev2 = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(
        u.user.id,
        dev2,
        put_in(ex, ["mls", "capabilities"], caps -- ["risi_tools"])
      )

    refute "call_risi_cal" in caps_of(u.user.id, dev2)
    refute PhoneCalendar.capable?(u.user.id, dev2)

    # The v1.48 device (no call_risi_cal) is never asked.
    dev3 = Ecto.UUID.generate()
    {:ok, _} = RisiMe.Devices.register(u.user.id, dev3, example("device_put_call_risi.json"))
    refute PhoneCalendar.capable?(u.user.id, dev3)
  end

  test "livekit_token_claims_call_risi_v152.json: Risi's claims exactly; the caller's grant the same" do
    ex = example("livekit_token_claims_call_risi_v152.json")
    [u, d] = String.split(ex["sub"], "/")
    Application.put_env(:risime, :risi_user_id, u)
    Application.put_env(:risime, :risi_device_id, d)

    assert CallRisi.risi_claims(ex["iss"], ex["video"]["room"], ex["nbf"]) == ex
    assert ex["video"]["canPublishData"] == true

    # The caller's token in a k: "risi" room: §20.2 with canPublishData true (the same grant).
    caller = Ecto.UUID.generate() <> "/" <> Ecto.UUID.generate()

    mine =
      LiveKit.participant_claims(
        ex["iss"],
        caller,
        ex["video"]["room"],
        "audio",
        ex["nbf"],
        false,
        true
      )

    assert mine["video"] == ex["video"]

    # Every other room keeps data off (parity).
    plain = LiveKit.participant_claims(ex["iss"], caller, ex["video"]["room"], "audio", ex["nbf"])
    assert plain["video"]["canPublishData"] == false
  end

  test "call_risi_cal_request.json: the worker's packet of the server's cal_req command" do
    ex = example("call_risi_cal_request.json")
    assert Enum.sort(Map.keys(ex)) == ~w(from id t to)
    assert ex["t"] == "cal_req" and ex["id"] =~ @uuid

    {:ok, from, 19_800} = DateTime.from_iso8601(ex["from"])
    {:ok, to, _} = DateTime.from_iso8601(ex["to"])
    assert DateTime.diff(to, from) <= 8 * 86_400

    {:ok, cmd} = PhoneCalendar.request(from, to, offset_s: 19_800, id: ex["id"])
    # infra/voice/risi_voice_worker.py turns {"cmd": "cal_req", …} into {"t": "cal_req", …}.
    assert cmd |> Map.delete("cmd") |> Map.put("t", "cal_req") == ex
  end

  test "call_risi_cal_reply.json and call_risi_cal_reply_error.json: what the server accepts" do
    {:ok, cmd} =
      with ex <- example("call_risi_cal_request.json"),
           {:ok, f, _} <- DateTime.from_iso8601(ex["from"]),
           {:ok, t, _} <- DateTime.from_iso8601(ex["to"]),
           do: PhoneCalendar.request(f, t, offset_s: 19_800, id: ex["id"])

    ok = example("call_risi_cal_reply.json")
    assert ok["t"] == "cal" and ok["id"] == cmd["id"] and ok["ok"] == true
    assert length(ok["events"]) <= 100

    for e <- ok["events"] do
      assert Enum.sort(Map.keys(e)) == ~w(all_day busy end start title)
      assert is_nil(e["title"]) or String.length(e["title"]) <= 200
    end

    # The worker forwards the reply's fields as {"ev": "cal", …}.
    ev = ok |> Map.delete("t") |> Map.put("ev", "cal")
    assert {:ok, events} = PhoneCalendar.match(ev, cmd)
    assert length(events) == length(ok["events"])

    err = example("call_risi_cal_reply_error.json")
    assert Enum.sort(Map.keys(err)) == ~w(id ok reason t)
    assert err["reason"] in ~w(no_permission no_calendars off error)

    assert {:error, "no_permission"} =
             PhoneCalendar.match(err |> Map.delete("t") |> Map.put("ev", "cal"), cmd)
  end

  test "envelope_risi_call_risi_transcript_titles.json: the posted transcript, no title stored" do
    ex = example("envelope_risi_call_risi_transcript_titles.json")
    risi = ex["risi"]
    [u, f, a] = risi["lines"]

    assert a["titles_spoken"] == true and is_nil(u["titles_spoken"]) and
             is_nil(f["titles_spoken"])

    {:ok, cmd} =
      with r <- example("call_risi_cal_request.json"),
           {:ok, fr, _} <- DateTime.from_iso8601(r["from"]),
           {:ok, to, _} <- DateTime.from_iso8601(r["to"]),
           do: PhoneCalendar.request(fr, to, offset_s: 19_800, id: r["id"])

    {:ok, events} =
      example("call_risi_cal_reply.json") |> Map.put("ev", "cal") |> PhoneCalendar.match(cmd)

    # The spoken answer named the meeting; the stored line has the time-only wording.
    spoken =
      Map.put(
        a,
        "text",
        "Tomorrow you have one meeting, Call with Shakya, and Friday is free all day."
      )

    spoken = Map.delete(spoken, "titles_spoken")
    stored = PhoneCalendar.transcript_line(spoken, events)
    assert stored["titles_spoken"] == true
    refute stored["text"] =~ "Shakya"
    assert stored["text"] =~ "10:00–10:30 busy"

    {:ok, started, _} = DateTime.from_iso8601(risi["started_at"])
    {:ok, ended, _} = DateTime.from_iso8601(risi["ended_at"])
    args = %{call_id: risi["call_id"], user_id: hd(risi["notify"])}
    {ours, body} = CallRisi.transcript(args, [u, f, stored], started, ended, :hangup)

    assert_shape(
      ours |> Jason.encode!() |> Jason.decode!(),
      Map.drop(risi, ~w(v made_by call_ref))
    )

    refute body =~ "Shakya"
    refute Jason.encode!(ours) =~ "Shakya"
    refute Jason.encode!(ex) =~ "Shakya"
  end
end
