defmodule RisiMe.Agent.CallTranscribeConsentTest do
  @moduledoc """
  Contract v1.39 §36.2–§36.3 (decision 080): the consent state machine of the call-transcription
  fast path. Every transition (request, allow, decline, stop, stop-ended, expiry at 30 s, `left`
  at 90 s, 4-h max, Official off, the kill switch, the speech service down), the `call_transcribe`
  signal on each, the consent lines, the push refusals (`transcribe_off`, `unknown_call`,
  `not_participant`, `not_official`, `invalid_device`, `peer_not_ready`, `busy`, `bad_state`,
  `rate_limited`, `transcribe_unavailable`) and `GET /calls/{call_id}/transcribe`. Timers are
  shortened; the speech service is `RisiMe.FakeSpeech`.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.CallTranscribeHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Agent.CallTranscribe
  alias RisiMe.Agent.CallTranscribe.Calls

  @moduletag capture_log: true

  setup do
    w = transcribe_world!()
    RisiMe.Messaging.subscribe(w.a.user.id)
    w
  end

  defp signal_state do
    receive do
      {:signal, %{kind: "call_transcribe", data: data}} -> data
    after
      2_000 -> raise "no call_transcribe signal"
    end
  end

  describe "the happy path" do
    test "request → requested (peer asked), allow → on, both signalled, the consent line", w do
      RisiMe.Messaging.subscribe(w.b.user.id)
      assert {:ok, %{session: s}} = tpush!(w, :a, "request")
      assert s["state"] == "requested"
      assert s["conversation_id"] == w.og
      assert s["chat_id"] == w.dm
      assert s["requested_by"] == w.a.user.id
      assert s["compare"] == false
      assert s["chunk"] == %{"min_ms" => 5000, "max_ms" => 20_000, "max_bytes" => 262_144}

      assert [
               %{"user_id" => a, "consent" => "allowed"},
               %{"user_id" => b, "consent" => "pending"}
             ] =
               s["participants"]

      assert {a, b} == {w.a.user.id, w.b.user.id}
      {:ok, req, _} = DateTime.from_iso8601(s["requested_at"])
      {:ok, exp, _} = DateTime.from_iso8601(s["expires_at"])
      assert DateTime.diff(exp, req, :millisecond) == 30_000

      # Both participants get the signal of the request (A's and B's inbox topics).
      assert signal_state()["state"] == "requested"
      assert signal_state()["state"] == "requested"

      assert {:ok, %{session: on}} = tpush!(w, :b, "allow", %{"session_id" => s["session_id"]})
      assert on["state"] == "on"
      assert on["expires_at"] == nil
      assert on["started_at"] != nil
      assert Enum.all?(on["participants"], &(&1["consent"] == "allowed"))
      assert signal_state()["state"] == "on"

      {body, risi} = await_post(w.og, "call_transcribe")
      assert risi["state"] == "on"
      assert risi["session_id"] == s["session_id"]
      assert risi["made_by"]["model"] == nil
      assert risi["notify"] == []

      assert body =~
               ~r/^Test User A asked Risi to transcribe this call; Test User B allowed \(\d\d:\d\d\)\. Audio is sent to Risi for transcription\.$/

      row = session!(s["session_id"])
      assert row.state == "on"
      # The bound devices: the requester's and the allower's.
      assert CallTranscribe.Session.bound_device(row, w.a.user.id) == w.da
      assert CallTranscribe.Session.bound_device(row, w.b.user.id) == w.db
    end

    test "a repeated allow, decline or stop on a final session is ok with its state", w do
      sid = start_on!(w)

      assert {:ok, %{session: %{"state" => "stopped"}}} =
               tpush!(w, :b, "stop", %{"session_id" => sid, "reason" => "stopped"})

      for {who, action} <- [{:b, "allow"}, {:b, "decline"}, {:a, "stop"}] do
        assert {:ok, %{session: %{"state" => "stopped", "session_id" => ^sid}}} =
                 tpush!(w, who, action, %{"session_id" => sid})
      end
    end
  end

  describe "final transitions" do
    test "decline: declined, a consent line naming who; uploads then get 409 stopped", w do
      {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")

      assert {:ok, %{session: s}} =
               tpush!(w, :b, "decline", %{"session_id" => sid, "reason" => nil})

      assert s["state"] == "declined"
      assert s["by"] == w.b.user.id
      assert s["reason"] == "declined"
      {body, risi} = await_post(w.og, "call_transcribe")
      assert risi["state"] == "declined"
      assert body =~ ~r/^Test User B declined transcription \(\d\d:\d\d\)\.$/

      assert {409, %{"error" => %{"code" => "stopped"}}} = upload!(w, :a, sid, "en:hello")
      refute_received {:fake_speech, _, _}
    end

    test "decline with private_call (a phone holding the call as Private)", w do
      {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")

      assert {:ok, %{session: %{"state" => "declined", "reason" => "private_call"}}} =
               tpush!(w, :b, "decline", %{"session_id" => sid, "reason" => "private_call"})
    end

    test "uploads while requested get 409 not_consented, and nothing is transcribed", w do
      {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")
      assert {409, %{"error" => %{"code" => "not_consented"}}} = upload!(w, :a, sid, "en:hi")
      refute_received {:fake_speech, _, _}
    end

    test "stop before start is a cancel (stopped, reason stopped)", w do
      {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")

      assert {:ok, %{session: %{"state" => "stopped", "reason" => "stopped", "by" => by}}} =
               tpush!(w, :b, "stop", %{"session_id" => sid, "reason" => "stopped"})

      assert by == w.b.user.id
    end

    test "stop for everyone by the peer: stopped, the line names them", w do
      sid = start_on!(w)
      _ = await_post(w.og, "call_transcribe")

      assert {:ok, %{session: s}} =
               tpush!(w, :b, "stop", %{
                 "session_id" => sid,
                 "reason" => "stopped",
                 "discard" => false
               })

      assert s["state"] == "stopped"
      assert s["by"] == w.b.user.id
      {body, risi} = await_post(w.og, "call_transcribe", &(&1["state"] == "stopped"))
      assert body =~ ~r/^Test User B stopped transcription \(\d\d:\d\d\)\.$/
      assert risi["by"] == w.b.user.id
      assert risi["discard"] == false
      assert {409, %{"error" => %{"code" => "stopped"}}} = upload!(w, :a, sid, "en:hi")
    end

    test "hang-up: stop with reason ended → ended", w do
      sid = start_on!(w)

      assert {:ok, %{session: %{"state" => "ended", "reason" => "ended"}}} =
               tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "ended"})

      {body, _} = await_post(w.og, "call_transcribe", &(&1["state"] == "ended"))
      assert body =~ ~r/^Transcription ended with the call \(\d\d:\d\d–\d\d:\d\d\)\.$/
    end

    test "a request expires after 30 s without full consent" do
      w = transcribe_world!(timing: [request_ttl_ms: 100])
      {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")
      eventually(fn -> session!(sid).state == "expired" end)
      assert session!(sid).reason == "expired"
      {body, _} = await_post(w.og, "call_transcribe", &(&1["state"] == "expired"))
      assert body =~ ~r/^No answer to the transcription request \(\d\d:\d\d\)\.$/

      assert {:ok, %{session: %{"state" => "expired"}}} =
               tpush!(w, :b, "allow", %{"session_id" => sid})
    end

    test "90 s with no chunk from a participant: ended (left)" do
      w = transcribe_world!(timing: [left_ms: 300, tick_ms: 50])
      sid = start_on!(w)
      eventually(fn -> session!(sid).state == "ended" end)
      assert session!(sid).reason == "left"
    end

    test "chunks keep a session alive past the `left` limit" do
      w = transcribe_world!(timing: [left_ms: 400, tick_ms: 50])
      sid = start_on!(w)

      for i <- 0..1 do
        Process.sleep(250)
        assert {202, _} = upload!(w, :a, sid, "silence", %{"seq" => i})
        assert {202, _} = upload!(w, :b, sid, "silence", %{"seq" => i})
      end

      assert session!(sid).state == "on"
    end

    test "4 h after start: ended (max_length)" do
      w = transcribe_world!(timing: [max_ms: 200, tick_ms: 50])
      sid = start_on!(w)
      eventually(fn -> session!(sid).state == "ended" end)
      assert session!(sid).reason == "max_length"
    end

    test "the speech service down for 60 s: stopped (unavailable)" do
      w = transcribe_world!(timing: [unavailable_ms: 100, tick_ms: 50])
      sid = start_on!(w)
      assert {202, _} = upload!(w, :a, sid, "fail")
      eventually(fn -> session!(sid).state == "stopped" end)
      assert session!(sid).reason == "unavailable"
    end

    test "Official off stops a live session (official_off)", w do
      sid = start_on!(w)
      RisiMe.Agent.CallTranscribe.official_off(w.og)
      assert session!(sid).state == "stopped"
      assert session!(sid).reason == "official_off"
      {body, _} = await_post(w.og, "call_transcribe", &(&1["state"] == "stopped"))
      assert body =~ "Official was turned off"
    end

    test "the kill switch: live sessions stop (switch_off); pushes and uploads are refused", w do
      sid = start_on!(w)
      Application.put_env(:risime, :risi_call_transcribe, false)
      :ok = CallTranscribe.switch_off()
      assert session!(sid).state == "stopped"
      assert session!(sid).reason == "switch_off"
      assert {:error, :transcribe_off} = tpush!(w, :a, "request")
      assert {503, %{"error" => %{"code" => "transcribe_off"}}} = upload!(w, :a, sid, "en:x")
    end

    test "the switch turned off is noticed by a live session's tick" do
      w = transcribe_world!(timing: [tick_ms: 50])
      sid = start_on!(w)
      Application.put_env(:risime, :risi_call_transcribe, false)
      eventually(fn -> session!(sid).state == "stopped" end)
      assert session!(sid).reason == "switch_off"
    end
  end

  describe "refusals" do
    test "transcribe_off while the switch is off", w do
      Application.put_env(:risime, :risi_call_transcribe, false)
      assert {:error, :transcribe_off} = tpush!(w, :a, "request")
    end

    test "unknown_call: no ring recorded in the last 4 h", w do
      w = %{w | call_id: Ecto.UUID.generate()}
      assert {:error, :unknown_call} = tpush!(w, :a, "request")
    end

    test "not_participant: a third user of the call id", w do
      c = RisiMe.Fixtures.logged_in_user(display_name: "C")
      dc = RisiMe.TabsHelpers.tabs_device!(c.user, caps: ~w(groups tabs call_transcribe))

      assert {:error, :not_participant} =
               CallTranscribe.push(c.user.id, dc, %{
                 "action" => "request",
                 "call_id" => w.call_id,
                 "conversation_id" => w.og
               })
    end

    test "not_official: a Private (non-Official) conversation, or one of another chat", w do
      private = "grp:" <> Ecto.UUID.generate()
      assert {:error, :not_official} = tpush!(w, :a, "request", %{"conversation_id" => private})

      other = RisiMe.RisiHelpers.risi_chat!([w.a.user, w.b.user])
      assert {:error, :not_official} = tpush!(w, :a, "request", %{"conversation_id" => other})
    end

    test "not_official: Official off (Risi not an active member)", w do
      RisiMe.Repo.update_all(
        from(m in RisiMe.Groups.Member,
          where: m.group_id == ^w.og and m.kind == "agent"
        ),
        set: [state: "removed"]
      )

      assert {:error, :not_official} = tpush!(w, :a, "request")
    end

    test "invalid_device: a device without call_transcribe, or not the bound one", w do
      old = RisiMe.TabsHelpers.tabs_device!(w.a.user, caps: ~w(groups member_devices tabs))
      assert {:error, :invalid_device} = tpush!(w, :a, "request", %{}, old)

      sid = start_on!(w)
      other = RisiMe.TabsHelpers.tabs_device!(w.a.user, caps: ~w(groups tabs call_transcribe))

      assert {:error, :invalid_device} =
               tpush!(w, :a, "stop", %{"session_id" => sid, "reason" => "stopped"}, other)

      assert {403, %{"error" => %{"code" => "invalid_device"}}} =
               upload!(w, :a, sid, "en:x", %{}, device: other)
    end

    test "invalid_device: an allow from a device without call_transcribe", w do
      {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")
      old = RisiMe.TabsHelpers.tabs_device!(w.b.user, caps: ~w(groups member_devices tabs))
      assert {:error, :invalid_device} = tpush!(w, :b, "allow", %{"session_id" => sid}, old)
    end

    test "peer_not_ready: the other participant has no live call_transcribe device" do
      w = transcribe_world!()
      c = RisiMe.Fixtures.logged_in_user(display_name: "C")
      _dc = RisiMe.TabsHelpers.tabs_device!(c.user, caps: ~w(groups tabs call_transcribe))
      dm = RisiMe.Messaging.conversation_id(w.a.user.id, c.user.id)
      og = RisiMe.RisiHelpers.risi_chat!([w.a.user, c.user], chat_id: dm)
      call_id = ring!(w.a.user.id, c.user.id, dm)

      assert {:error, :peer_not_ready} =
               CallTranscribe.push(w.a.user.id, w.da, %{
                 "action" => "request",
                 "call_id" => call_id,
                 "conversation_id" => og
               })
    end

    test "busy: a live session, or 3 sessions used", w do
      {:ok, %{session: %{"session_id" => s1}}} = tpush!(w, :a, "request")
      assert {:error, :busy} = tpush!(w, :b, "request")
      tpush!(w, :a, "stop", %{"session_id" => s1})

      for _ <- 1..2 do
        reset_request_gap!()
        {:ok, %{session: %{"session_id" => s}}} = tpush!(w, :a, "request")
        tpush!(w, :a, "stop", %{"session_id" => s})
      end

      reset_request_gap!()
      assert {:error, :busy} = tpush!(w, :a, "request")
    end

    test "rate_limited: a second request within 10 s of the user's last", w do
      {:ok, %{session: %{"session_id" => s1}}} = tpush!(w, :a, "request")
      tpush!(w, :a, "stop", %{"session_id" => s1})
      assert {:error, :rate_limited} = tpush!(w, :a, "request")
    end

    test "bad_state: allow from the requester, allow once on, an unknown session", w do
      {:ok, %{session: %{"session_id" => sid}}} = tpush!(w, :a, "request")
      assert {:error, :bad_state} = tpush!(w, :a, "decline", %{"session_id" => sid})
      tpush!(w, :b, "allow", %{"session_id" => sid})
      assert {:error, :bad_state} = tpush!(w, :b, "decline", %{"session_id" => sid})

      assert {:error, :bad_state} =
               tpush!(w, :b, "allow", %{"session_id" => Ecto.UUID.generate()})
    end

    test "transcribe_unavailable: the speech service unhealthy, or the live-session cap", w do
      Application.put_env(:risime, :fake_speech_healthy, false)
      assert {:error, :transcribe_unavailable} = tpush!(w, :a, "request")
      Application.put_env(:risime, :fake_speech_healthy, true)
      Application.put_env(:risime, :risi_call_transcribe_max_live, 0)
      assert {:error, :transcribe_unavailable} = tpush!(w, :a, "request")
    end

    test "bad_request: a malformed push", w do
      assert {:error, :bad_request} = tpush!(w, :a, "frobnicate")
      assert {:error, :bad_request} = tpush!(w, :a, "allow", %{"session_id" => "x"})

      assert {:error, :bad_request} =
               CallTranscribe.push(w.a.user.id, w.da, %{"action" => "request"})
    end

    test "a new call never inherits consent", w do
      start_on!(w)
      call2 = ring!(w.a.user.id, w.b.user.id, w.dm)
      reset_request_gap!()

      {:ok, %{session: s}} =
        CallTranscribe.push(w.a.user.id, w.da, %{
          "action" => "request",
          "call_id" => call2,
          "conversation_id" => w.og
        })

      assert s["state"] == "requested"
      assert Enum.find(s["participants"], &(&1["user_id"] == w.b.user.id))["consent"] == "pending"
    end
  end

  describe "the call record" do
    test "the first ring of a call id wins; records live 4 h in memory", w do
      :ok =
        CallTranscribe.note_ring(w.call_id, w.b.user.id, Ecto.UUID.generate(), "dm:x", "audio")

      assert %{from: from} = Calls.get(w.call_id)
      assert from == w.a.user.id
      assert Calls.get(Ecto.UUID.generate()) == nil
    end
  end

  describe "GET /api/v1/calls/{call_id}/transcribe" do
    test "null before any session; the newest session after; 404 to others", w do
      get = fn token, call_id ->
        build_conn()
        |> put_req_header("authorization", "Bearer " <> token)
        |> get("/api/v1/calls/#{call_id}/transcribe")
      end

      assert %{"session" => nil} = json_response(get.(w.a.token, w.call_id), 200)
      sid = start_on!(w)

      assert %{"session" => %{"session_id" => ^sid, "state" => "on"}} =
               json_response(get.(w.b.token, w.call_id), 200)

      c = RisiMe.Fixtures.logged_in_user(display_name: "C")
      assert %{"error" => %{"code" => "not_found"}} = json_response(get.(c.token, w.call_id), 404)

      assert %{"error" => %{"code" => "not_found"}} =
               json_response(get.(w.a.token, Ecto.UUID.generate()), 404)
    end
  end
end
