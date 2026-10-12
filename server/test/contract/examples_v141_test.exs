defmodule RisiMe.Contract.ExamplesV141Test do
  @moduledoc """
  Contract v1.41 §37 (decision 081, voice notes): every new example file. Server-produced
  examples are compared against real server output, here or in the behaviour tests:

    * `msg_ack_played.json`, `event_status_played.json`, `event_group_receipt_v141.json`,
      `group_receipts_reply_v141.json`: test/risime_web/channels/voice_played_v141_test.exs;
    * `envelope_risi_voice_transcript*.json`, `envelope_risi_action_transcript_delete.json`,
      `me_risi_voice_*.json`, `auth_config_v141.json`: test/risime/agent/voice_test.exs.

  Here: the device capability through `PUT /me/devices`, `voice_ready` / `missing_voice` on
  `GET /mls/groups/{id}`, the voice envelopes through the server's own §37.2 validator
  (`RisiMe.Agent.Voice.validate/1`), and the client-only backup entry.
  """
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1, e2ee_group!: 2]

  alias RisiMe.Agent.Voice
  alias RisiMe.GroupHelpers

  @moduletag capture_log: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(auth_config_v141.json backup_entry_message_v141.json device_put_voice.json
           envelope_risi_action_transcript_delete.json envelope_risi_voice_transcript.json
           envelope_risi_voice_transcript_unclear.json event_group_receipt_v141.json
           event_status_played.json group_receipts_reply_v141.json me_risi_voice_put.json
           me_risi_voice_reply.json msg_ack_played.json voice_payload.json
           voice_payload_bad_waveform.json voice_payload_forwarded.json
           voice_payload_reply_no_transcribe.json voice_payload_risi_chat.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  test "every v1.41 example exists and is JSON" do
    for f <- files(), do: assert(is_map(example(f)), f)
  end

  test "voice envelopes through the server's §37.2 validator; the bad waveform is dropped" do
    for f <-
          ~w(voice_payload.json voice_payload_forwarded.json voice_payload_reply_no_transcribe.json
                voice_payload_risi_chat.json),
        do: assert({:ok, %{duration_ms: d}} = Voice.validate(example(f)), f) && d >= 500

    assert Voice.validate(example("voice_payload_bad_waveform.json")) == :error
    assert example("voice_payload_reply_no_transcribe.json")["transcribe"] == false
    assert %{"hops" => 1} = example("voice_payload_forwarded.json")["forwarded"]
    assert is_binary(example("voice_payload_risi_chat.json")["request_id"])

    # Forward = a fresh upload per target (§33.5): never the same blob.
    refute example("voice_payload_forwarded.json")["blob"]["blob_id"] ==
             example("voice_payload.json")["blob"]["blob_id"]
  end

  describe "capability and readiness (§37.1)" do
    setup :with_attestation_key

    test "device_put_voice.json keeps `voice`; voice_ready / missing_voice like files_ready" do
      a = logged_in_user(display_name: "A")
      b = logged_in_user(display_name: "B")
      ex = example("device_put_voice.json")
      a_dev = Ecto.UUID.generate()
      b_dev = Ecto.UUID.generate()

      {200, %{"attestation" => _}} =
        GroupHelpers.api(:put, "/api/v1/me/devices/#{a_dev}", a.token, ex)

      {200, _} = GroupHelpers.api(:put, "/api/v1/me/devices/#{b_dev}", b.token, ex)
      dev = RisiMe.Repo.get_by(RisiMe.Devices.Device, device_id: a_dev)
      assert "voice" in dev.capabilities
      :ok = RisiMe.MLS.record_instance(a.user.id, a_dev, nil, "0.3.0")
      :ok = RisiMe.MLS.record_instance(b.user.id, b_dev, nil, "0.3.0")

      GroupHelpers.clear_legacy!()
      conv = e2ee_group!(a, b)
      {200, view} = GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", a.token)
      assert view["voice_ready"] == true and view["missing_voice"] == []

      # b's device drops `voice`: not ready, b's device listed (the §33.1 shape).
      no_voice = put_in(ex, ["mls", "capabilities"], ex["mls"]["capabilities"] -- ["voice"])
      {200, _} = GroupHelpers.api(:put, "/api/v1/me/devices/#{b_dev}", b.token, no_voice)
      {200, view} = GroupHelpers.api(:get, "/api/v1/mls/groups/#{conv}", a.token)
      assert view["voice_ready"] == false
      assert view["missing_voice"] == [%{"user_id" => b.user.id, "device_id" => b_dev}]
      # Same shape as files readiness.
      assert view["files_ready"] == true
    end
  end

  test "transcript_delete is a §24.11 risi_action; the backup entry (client-only)" do
    assert %{"type" => "risi_action", "action" => "transcript_delete", "edit" => nil} =
             example("envelope_risi_action_transcript_delete.json")

    e = example("backup_entry_message_v141.json")
    assert {:ok, _} = Voice.validate(e["payload"])
    assert e["played_at"] =~ ~r/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/
  end
end
