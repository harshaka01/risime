defmodule RisiMe.Contract.ExamplesV149Test do
  @moduledoc """
  Contract v1.49 (one bundle of the 2026-10-14 proposals; v1.48 is reserved for Call Risi §41):
  §37.11 spoken @Risi (the chip's `request_id` on an Official note, `risi.voice_ref`), §37.10
  the phone transcript as a fallback (`source: "phone"`, `engine`), §37.12 the
  `voice_transcribing` signal and Stop / Try again, and the §35.4 morning brief example.
  The behaviour itself is covered in test/risime/agent/voice_test.exs and
  spoken_mention_test.exs; here every example is checked against the server's own code.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers
  import Plug.Conn, only: [put_req_header: 3]
  import Phoenix.ConnTest, except: [connect: 2, connect: 3]

  alias RisiMe.Agent.{Out, Speech, SpokenMention, Voice, VoiceJobs}

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(envelope_risi_answer_voice_ref.json voice_payload_official_chip.json
            envelope_risi_voice_transcript_phone.json envelope_risi_voice_transcript_google.json
            signal_voice_transcribing.json me_risi_voice_cancel.json
            me_risi_voice_cancel_reply.json me_risi_voice_retry_reply.json
            envelope_risi_digest_brief.json
            device_put_risi_send.json envelope_risi_confirm_send_message.json
            envelope_risi_action_confirm_write_send_edit.json
            event_risi_tool_call_send_message.json risi_tool_result_send_message.json
            envelope_risi_message_sent.json
            device_put_risi_fixit.json envelope_risi_needs_permission.json
            envelope_risi_action_permission_granted.json
            envelope_risi_action_transcript_language.json
            envelope_risi_voice_transcript_relang.json risi_translate_request.json
            risi_translate_reply.json me_risi_voice_put_v149.json me_risi_voice_reply_v149.json
            backup_entry_message_outbox.json)

  @doc "The v1.49 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  setup do
    old = Application.get_env(:risime, :risi_agent_flags)
    on_exit(fn -> Application.put_env(:risime, :risi_agent_flags, old) end)
    :ok
  end

  test "the files exist and are JSON" do
    for f <- @files, do: assert(is_map(example(f)), f)
  end

  describe "§37.11 spoken @Risi" do
    test "voice_payload_official_chip.json: a valid §37.2 note whose request_id is a chip" do
      ex = example("voice_payload_official_chip.json")
      assert {:ok, %{duration_ms: 6200}} = Voice.validate(ex)
      assert SpokenMention.chip?(ex["request_id"])
      refute SpokenMention.chip?(nil)
      # The same note without the chip is an ordinary Official note.
      assert {:ok, _} = Voice.validate(Map.delete(ex, "request_id"))
    end

    test "envelope_risi_answer_voice_ref.json: Out.risi/2 adds the note's voice_ref" do
      ex = example("envelope_risi_answer_voice_ref.json")
      r = ex["risi"]
      rid = r["request_id"]
      Process.put(:risi_voice_ref, {rid, r["voice_ref"]})

      built =
        Out.risi(Map.take(r, ~w(kind request_id answer refs confidence call_ref notify)))

      assert built["voice_ref"] == r["voice_ref"]
      assert r["voice_ref"]["message_id"] in r["refs"]
      assert Map.delete(built, "made_by") == r

      # Another request's post gets no voice_ref.
      other = Out.risi(%{"kind" => "answer", "request_id" => Ecto.UUID.generate()})
      refute Map.has_key?(other, "voice_ref")
    after
      Process.delete(:risi_voice_ref)
    end
  end

  describe "§37.10 the phone transcript as a fallback" do
    test "phone and Google transcripts: the §37.6 card plus source and engine" do
      base = example("envelope_risi_voice_transcript.json")["risi"]

      for {f, engine, model} <- [
            {"envelope_risi_voice_transcript_phone.json", "android-on-device", "phone-on-device"},
            {"envelope_risi_voice_transcript_google.json", "android-online-google",
             "phone-google"}
          ] do
        r = example(f)["risi"]
        assert r["source"] == "phone" and r["engine"] == engine, f
        assert r["model"] == model and Speech.phone_model_of(%{"engine" => engine}) == model, f
        assert %{"provider" => "phone", "model" => ^model} = r["made_by"]
        assert r["status"] == "ok" and is_binary(r["text"])

        assert (Map.keys(r) -- ["source", "engine"]) -- Map.keys(base) == [], f
        assert (Map.keys(base) -- ["script"]) -- Map.keys(r) == [], f
      end

      assert Speech.phone_model() == "phone-on-device"
    end
  end

  describe "§37.12 Stop / Try again" do
    defp signals(acc \\ []) do
      receive do
        {:signal, %{kind: "voice_transcribing", data: d}} -> signals([d | acc])
      after
        200 -> Enum.reverse(acc)
      end
    end

    defp running_job(user, conv, id) do
      test = self()

      {:ok, pid} =
        VoiceJobs.start(
          %{conv: conv, note_id: id, sender: user, note: nil, mode: :official},
          fn ->
            send(test, :started)
            Process.sleep(:infinity)
          end
        )

      assert_receive :started
      pid
    end

    test "signal_voice_transcribing.json and me_risi_voice_cancel*.json match the server" do
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_PROGRESS" => "on"})
      a = logged_in_user()
      req = example("me_risi_voice_cancel.json")
      sig = example("signal_voice_transcribing.json")
      conv = req["conversation_id"]
      id = req["message_id"]
      RisiMe.Messaging.subscribe(a.user.id)

      pid = running_job(a.user.id, conv, id)
      VoiceJobs.working(conv, id, a.user.id, ["si"])
      assert [d] = signals()
      assert Enum.sort(Map.keys(d)) == Enum.sort(Map.keys(sig["data"]))
      assert Map.delete(d, "server_ts") == Map.delete(sig["data"], "server_ts")

      conn = fn ->
        build_conn() |> put_req_header("authorization", "Bearer " <> a.token)
      end

      assert conn.()
             |> post("/api/v1/me/risi/voice/cancel", req)
             |> json_response(200) == example("me_risi_voice_cancel_reply.json")

      refute Process.alive?(pid)
      assert [%{"state" => "cancelled", "can_retry" => true, "seq" => 2}] = signals()

      # Nothing runs any more: Stop says so; Try again while held is the 202 path (voice_test).
      assert conn.() |> post("/api/v1/me/risi/voice/cancel", req) |> json_response(404) ==
               %{"error" => "not_running"}

      assert example("me_risi_voice_retry_reply.json") == %{"state" => "working"}

      # A note the server never held: 410 expired.
      other = %{req | "message_id" => "1e4f3a20-a95b-11f1-8000-0242ac120002"}

      assert conn.() |> post("/api/v1/me/risi/voice/retry", other) |> json_response(410) ==
               %{"error" => "expired"}

      # The flag off: both endpoints answer 404 and no signal goes out.
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_PROGRESS" => "off"})

      assert conn.() |> post("/api/v1/me/risi/voice/cancel", req) |> json_response(404) ==
               %{"error" => "not_running"}

      assert conn.() |> post("/api/v1/me/risi/voice/retry", req) |> json_response(404) ==
               %{"error" => "not_found"}

      assert signals() == []
    end
  end

  test "envelope_risi_digest_brief.json: the §35.4 brief's fields" do
    ex = example("envelope_risi_digest_brief.json")
    r = ex["risi"]
    assert %{"kind" => "digest", "scope" => "brief", "language" => "en"} = r
    for k <- ~w(events risi_items items decisions), do: assert(is_list(r[k]), k)
    assert [%{"text" => _, "conversation_id" => "grp:" <> _}] = r["decisions"]
    assert ex["body"] =~ ~r/\n\nChecked: /
    assert Out.risi(Map.delete(r, "made_by")) |> Map.delete("made_by") == Map.delete(r, "made_by")
  end

  # The bundle is client-side only (§22.5: the server stores ciphertext); checked against the rules.
  test "backup_entry_message_outbox.json: an unsent text line (§22.5 outbox)" do
    ex = example("backup_entry_message_outbox.json")
    assert %{"type" => "message", "message_id" => nil, "server_ts" => nil, "status" => nil} = ex
    assert ex["payload"]["type"] == "text" and is_binary(ex["payload"]["body"])
    assert %{"state" => s, "client_ts" => ts} = ex["outbox"]
    assert s in ~w(pending failed)
    assert {:ok, _, 0} = DateTime.from_iso8601(ts)
    assert ex["from"] in String.split(String.trim_leading(ex["conversation_id"], "dm:"), "_")
    refute Map.has_key?(ex["outbox"], "plain_ok")
  end

  defp put_device(a, body) do
    dev = Ecto.UUID.generate()
    body = put_in(body, ["mls", "signature_key"], Base.encode64(:crypto.strong_rand_bytes(32)))

    assert build_conn()
           |> put_req_header("authorization", "Bearer " <> a.token)
           |> put("/api/v1/me/devices/#{dev}", body)
           |> response(200)

    RisiMe.Devices.get(a.user.id, dev).capabilities
  end

  describe "§42 send a message for me" do
    setup :with_attestation_key

    test "device_put_risi_send.json: risi_send is kept only with risi_tools" do
      a = logged_in_user()
      ex = example("device_put_risi_send.json")
      assert "risi_send" in put_device(a, ex)

      refute "risi_send" in put_device(
               a,
               update_in(ex, ["mls", "capabilities"], &(&1 -- ["risi_tools"]))
             )
    end

    test "the card, the edit, the tool call, the result and the reply agree" do
      card = example("envelope_risi_confirm_send_message.json")["risi"]
      call = example("event_risi_tool_call_send_message.json")["data"]
      edit = example("envelope_risi_action_confirm_write_send_edit.json")
      sent = example("envelope_risi_message_sent.json")

      assert %{"kind" => "confirm", "tool" => "send_message", "when" => nil} = card
      assert card["buttons"] == ["send", "edit", "cancel"]
      # §26.5: the card's args are the tool call's args without write_id.
      assert Map.delete(call["args"], "write_id") == card["args"]
      assert call["tool"] == "send_message" and call["args"]["write_id"] == card["write_id"]

      r = %{name: "Upali Perera", user_id: card["send"]["to"]["user_id"], tab: "private"}
      assert RisiMe.Agent.SendForMe.send_display(r, "en") == card["send"]

      assert %{"action" => "confirm_write", "target" => t, "edit" => %{"text" => _} = e} = edit
      assert t == card["write_id"] and Map.keys(e) == ["text"]

      assert example("risi_tool_result_send_message.json") == %{
               "status" => "ok",
               "result" => %{"state" => "sent"}
             }

      r = sent["risi"]
      assert %{"kind" => "message_sent", "state" => "sent", "tab" => "private"} = r

      assert r["write_id"] == card["write_id"] and
               r["conversation_id"] == card["args"]["conversation_id"]

      refute Map.has_key?(r, "text")
      assert sent["body"] == "✓ Sent to Upali Perera"
    end
  end

  describe "§43 permission fix-it" do
    setup :with_attestation_key

    test "device_put_risi_fixit.json: risi_fixit is kept only with risi_skills" do
      a = logged_in_user()
      ex = example("device_put_risi_fixit.json")
      assert "risi_fixit" in put_device(a, ex)

      refute "risi_fixit" in put_device(
               a,
               update_in(ex, ["mls", "capabilities"], &(&1 -- ["risi_skills"]))
             )
    end

    test "the card and the answer" do
      r = example("envelope_risi_needs_permission.json")["risi"]

      assert Enum.sort(Map.keys(r)) ==
               Enum.sort(~w(v kind request_id resume_id permission skill_id turn_on text buttons
                            expires_at turn_ref call_ref made_by notify))

      assert %{"kind" => "needs_permission", "buttons" => ["allow"], "permission" => "calendar"} =
               r

      assert r["made_by"]["provider"] == "rule"

      assert %{"action" => "permission_granted", "edit" => nil, "target" => t} =
               example("envelope_risi_action_permission_granted.json")

      assert t == r["resume_id"]
    end
  end

  describe "§37.13 wrong language and Translate" do
    test "transcript_language carries the note's own blob/enc; relang is the §37.6 card plus relang" do
      a = example("envelope_risi_action_transcript_language.json")
      note = example("voice_payload.json")
      assert %{"action" => "transcript_language", "language" => "ta", "edit" => nil} = a
      assert a["voice"]["blob"] == note["blob"] and a["voice"]["enc"] == note["enc"]

      base = example("envelope_risi_voice_transcript.json")["risi"]
      r = example("envelope_risi_voice_transcript_relang.json")["risi"]
      assert %{"from" => "si", "to" => "ta", "by" => _} = r["relang"]
      assert r["language"] == r["relang"]["to"]
      assert (Map.keys(r) -- ["relang"]) -- Map.keys(base) == []
    end

    test "me_risi_voice_*_v149.json and POST /risi/translate against the server" do
      a = logged_in_user()
      conn = fn -> build_conn() |> put_req_header("authorization", "Bearer " <> a.token) end

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "on"})

      assert conn.()
             |> put("/api/v1/me/risi/voice", example("me_risi_voice_put_v149.json"))
             |> json_response(200) == example("me_risi_voice_reply_v149.json")

      assert conn.() |> get("/api/v1/me/risi/voice") |> json_response(200) ==
               example("me_risi_voice_reply_v149.json")

      # Not a conversation the caller is in: 403 not_available.
      assert conn.()
             |> post("/api/v1/risi/translate", example("risi_translate_request.json"))
             |> json_response(403) == %{"error" => "not_available"}

      # The flag off: the v1.41 shape, no translate fields.
      Application.put_env(:risime, :risi_agent_flags, %{"RISI_VOICE_TRANSLATE" => "off"})
      got = conn.() |> get("/api/v1/me/risi/voice") |> json_response(200)
      refute Map.has_key?(got, "translate_available") or Map.has_key?(got, "always_translate")

      reply = example("risi_translate_reply.json")
      req = example("risi_translate_request.json")
      assert Map.take(reply, ~w(conversation_id message_id target)) == req
      assert %{"label" => "Translated by Risi", "source_language" => "ta"} = reply
      assert reply["made_by"]["provider"] == "risime"
    end
  end
end
