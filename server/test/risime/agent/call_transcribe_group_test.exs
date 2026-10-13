defmodule RisiMe.Agent.CallTranscribeGroupTest do
  @moduledoc """
  Contract v1.44 §39.9 (activates §36.9): transcription of Official **group** calls. The call
  record comes from the group ring; the participants are the human users in the LiveKit room
  (the fake LiveKit); a joiner is `pending` (a `requested` session waits for them, an `on` one is
  `paused` until they allow; decline stops for everyone; 30 s → `stopped`/`expired`); `left` only
  when nobody uploads; the room gone ends it; `peer_not_ready`, `busy` with the §27.7 listener,
  `not_participant`. The 1:1 behaviour is covered (unchanged) by the v1.39 tests.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.CallTranscribeHelpers

  alias RisiMe.Agent.CallTranscribe
  alias RisiMe.Calls.LiveKit
  alias RisiMe.FakeLiveKit

  @moduletag capture_log: true

  @caps ~w(groups member_devices calls tabs risi_tools risi_ledger call_transcribe)
  @dir Path.expand("../../../../contract/v1/examples", __DIR__)

  # A, B, C with `call_transcribe` devices (online), their group chat's Official conversation
  # `og` with Risi, and a group call rung by A whose room holds A and B (C not yet).
  defp group_world!(opts \\ []) do
    restore_transcribe_env!()
    Application.put_env(:risime, :risi_call_transcribe, true)
    Application.put_env(:risime, :risi_speech, RisiMe.FakeSpeech)
    Application.put_env(:risime, :risi_call_transcribe_timing, Keyword.get(opts, :timing, []))
    FakeLiveKit.setup()

    RisiMe.MLSHelpers.with_attestation_key(%{})

    users =
      for n <- ~w(A B C), do: RisiMe.Fixtures.logged_in_user(display_name: "Test User " <> n)

    [a, b, c] = users
    [da, db, dc] = for u <- users, do: RisiMe.TabsHelpers.tabs_device!(u.user, caps: @caps)

    for {u, d} <- [{a, da}, {b, db}, {c, dc}],
        Keyword.get(opts, :online, true) or u != b,
        do: online!(u.user.id, d)

    chat = "grp:" <> Ecto.UUID.generate()
    og = RisiMe.RisiHelpers.risi_chat!(Enum.map(users, & &1.user), chat_id: chat)
    call_id = Ecto.UUID.generate()
    :ok = CallTranscribe.note_group_ring(call_id, a.user.id, og, "audio")

    {:ok, config} = LiveKit.config()
    room = LiveKit.room_name(config.api_secret, og, call_id, "audio")
    meta = Jason.encode!(Map.merge(%{"c" => og, "m" => "audio"}, Keyword.get(opts, :meta, %{})))

    {:ok, _} =
      FakeLiveKit.create_room(config, room, %{
        max_participants: 32,
        empty_timeout: 60,
        departure_timeout: 20,
        metadata: meta
      })

    FakeLiveKit.join(room, LiveKit.identity(a.user.id, da))
    FakeLiveKit.join(room, LiveKit.identity(b.user.id, db))

    %{a: a, b: b, c: c, da: da, db: db, dc: dc, og: og, chat: chat, call_id: call_id, room: room}
  end

  defp dev(w, who), do: w[String.to_atom("d" <> Atom.to_string(who))]

  defp gpush(w, who, action, extra \\ %{}) do
    payload =
      Map.merge(%{"action" => action, "call_id" => w.call_id, "conversation_id" => w.og}, extra)

    CallTranscribe.push(w[who].user.id, dev(w, who), payload)
  end

  defp join_c(w), do: FakeLiveKit.join(w.room, LiveKit.identity(w.c.user.id, w.dc))

  defp consents(s), do: Map.new(s["participants"], &{&1["user_id"], &1["consent"]})

  defp on!(w) do
    {:ok, %{session: %{"session_id" => sid, "state" => "requested"}}} = gpush(w, :a, "request")
    {:ok, %{session: %{"state" => "on"}}} = gpush(w, :b, "allow", %{"session_id" => sid})
    sid
  end

  defp up(w, who, sid, seq),
    do: upload!(w, who, sid, "en:hello", %{"seq" => seq}, device: dev(w, who))

  test "participants are the humans in the room; the session's chat is the group's" do
    w = group_world!()
    assert {:ok, %{session: s}} = gpush(w, :a, "request")
    assert s["state"] == "requested" and s["conversation_id"] == w.og and s["chat_id"] == w.chat
    assert consents(s) == %{w.a.user.id => "allowed", w.b.user.id => "pending"}

    # C is a member but not in the room: not a participant.
    assert {:error, :not_participant} =
             gpush(w, :c, "allow", %{"session_id" => s["session_id"]})
  end

  test "a joiner while requested is waited for too" do
    w = group_world!()
    {:ok, %{session: %{"session_id" => sid}}} = gpush(w, :a, "request")
    join_c(w)
    assert {:ok, %{session: s}} = gpush(w, :b, "allow", %{"session_id" => sid})
    assert s["state"] == "requested" and consents(s)[w.c.user.id] == "pending"
    assert {:ok, %{session: %{"state" => "on"}}} = gpush(w, :c, "allow", %{"session_id" => sid})
  end

  test "a joiner while on pauses it (uploads 409) until they allow" do
    w = group_world!()
    sid = on!(w)
    assert {202, _} = up(w, :a, sid, 0)
    join_c(w)

    # Any push reads the room: here B's (idempotent) allow.
    assert {:ok, %{session: s}} = gpush(w, :b, "allow", %{"session_id" => sid})
    assert s["state"] == "paused" and is_binary(s["expires_at"])
    assert consents(s)[w.c.user.id] == "pending"
    assert {409, %{"error" => %{"code" => "not_consented"}}} = up(w, :a, sid, 1)

    # The paused Session matches the example (keys and value types).
    ex =
      (@dir
       |> Path.join("signal_call_transcribe_paused.json")
       |> File.read!()
       |> Jason.decode!())["data"]

    assert Enum.sort(Map.keys(s)) == Enum.sort(Map.keys(ex))

    assert Enum.sort(Map.keys(hd(s["participants"]))) ==
             Enum.sort(Map.keys(hd(ex["participants"])))

    assert {:ok, %{session: %{"state" => "on", "expires_at" => nil}}} =
             gpush(w, :c, "allow", %{"session_id" => sid})

    assert {202, _} = up(w, :a, sid, 2)
    assert {202, _} = up(w, :c, sid, 0)
  end

  test "the room tick pauses without any push; a joiner's decline stops it for everyone" do
    w = group_world!(timing: [tick_ms: 50])
    sid = on!(w)
    join_c(w)
    eventually(fn -> session!(sid).state == "paused" end)
    assert {:ok, %{session: s}} = gpush(w, :c, "decline", %{"session_id" => sid})
    assert s["state"] == "declined" and s["by"] == w.c.user.id
  end

  test "paused 30 s without an answer: stopped, reason expired" do
    w = group_world!(timing: [request_ttl_ms: 150])
    sid = on!(w)
    join_c(w)
    {:ok, %{session: %{"state" => "paused"}}} = gpush(w, :a, "allow", %{"session_id" => sid})
    eventually(fn -> session!(sid).state == "stopped" end)
    assert session!(sid).reason == "expired"
  end

  test "the room gone ends the session" do
    w = group_world!(timing: [tick_ms: 50])
    sid = on!(w)
    FakeLiveKit.close(w.room)
    eventually(fn -> session!(sid).state == "ended" end)
  end

  test "left: only when nobody uploads for 90 s" do
    w = group_world!(timing: [left_ms: 300, tick_ms: 50, chunk_burst: 10])
    sid = on!(w)

    # A keeps uploading, B is silent: the group session goes on past the limit.
    for seq <- 0..3 do
      assert {202, _} = up(w, :a, sid, seq)
      Process.sleep(150)
    end

    assert session!(sid).state == "on"
    eventually(fn -> session!(sid).state == "ended" end, 2_000)
    assert session!(sid).reason == "left"
  end

  test "peer_not_ready: someone else in the room has no live call_transcribe device" do
    w = group_world!(online: false)
    assert {:error, :peer_not_ready} = gpush(w, :a, "request")
  end

  test "busy while the §27.7 listener is on for the room" do
    w = group_world!(meta: %{"r" => 1})
    assert {:error, :busy} = gpush(w, :a, "request")
  end

  test "refusals: not_participant, unknown_call, not_official" do
    w = group_world!()
    assert {:error, :not_participant} = gpush(w, :c, "request")

    assert {:error, :unknown_call} =
             gpush(%{w | call_id: Ecto.UUID.generate()}, :a, "request")

    # Only the call's own conversation.
    assert {:error, :not_official} = gpush(%{w | og: w.chat}, :a, "request")
  end

  test "a 1:1 record is unchanged: a group record never needs a `to`" do
    w = group_world!()
    rec = RisiMe.Agent.CallTranscribe.Calls.get(w.call_id)
    assert rec.group == true and rec.conv == w.og and rec.from == w.a.user.id
    assert {:ok, nil} = CallTranscribe.room_users(%{rec | group: false}, w.call_id)
    assert {:ok, %{users: users, listen: false}} = CallTranscribe.room_users(rec, w.call_id)
    assert users == [w.a.user.id, w.b.user.id]
  end
end
