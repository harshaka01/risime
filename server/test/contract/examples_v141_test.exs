defmodule RisiMe.Contract.ExamplesV141Test do
  @moduledoc """
  Contract v1.41 §37 (decision 081, voice notes): shape-only coverage of the new example files,
  added by root with the contract bump (integration). The server behaviour (`played`, the
  receipt fields, the Risi audio path, `/me/risi/voice`) replaces these checks with
  server-output comparisons when it is built.
  """
  use ExUnit.Case, async: true

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

  defp valid_voice?(%{"type" => "voice"} = v) do
    with %{"alg" => "A256GCM-S64K", "key" => key, "plain_size" => ps} when ps >= 1 <- v["enc"],
         {:ok, k} when byte_size(k) == 32 <- Base.decode64(key),
         %{"blob_id" => _, "size" => size, "sha256" => sha} when is_integer(size) <- v["blob"],
         {:ok, s} when byte_size(s) == 32 <- Base.decode64(sha),
         true <- v["mime"] in ["audio/ogg; codecs=opus", "audio/mp4"],
         d when is_integer(d) and d >= 500 and d <= 1_800_000 <- v["duration_ms"],
         {:ok, w} when byte_size(w) == 64 <- Base.decode64(v["waveform"] || "") do
      true
    else
      _ -> false
    end
  end

  test "every v1.41 example exists and is JSON" do
    for f <- files(), do: assert(is_map(example(f)), f)
  end

  test "voice envelopes: valid ones pass the §37.2 checks, the bad waveform is dropped" do
    for f <-
          ~w(voice_payload.json voice_payload_forwarded.json voice_payload_reply_no_transcribe.json
                voice_payload_risi_chat.json),
        do: assert(valid_voice?(example(f)), f)

    refute valid_voice?(example("voice_payload_bad_waveform.json"))
    assert example("voice_payload_reply_no_transcribe.json")["transcribe"] == false
    assert %{"hops" => 1} = example("voice_payload_forwarded.json")["forwarded"]
    assert is_binary(example("voice_payload_risi_chat.json")["request_id"])

    refute example("voice_payload_forwarded.json")["blob"]["blob_id"] ==
             example("voice_payload.json")["blob"]["blob_id"]
  end

  test "the played receipt" do
    assert %{"status" => "played", "message_ids" => [_]} = example("msg_ack_played.json")

    assert %{"kind" => "status", "data" => %{"status" => "played"}} =
             example("event_status_played.json")

    assert %{"kind" => "group_receipt", "data" => %{"played" => 1, "all_played" => false}} =
             example("event_group_receipt_v141.json")

    assert [%{"played_at" => p}, %{"played_at" => nil}] =
             example("group_receipts_reply_v141.json")["receipts"]

    assert is_binary(p)
  end

  test "voice_transcript, transcript_delete, the setting, the switch, capability, backup" do
    %{"risi" => ok} = example("envelope_risi_voice_transcript.json")
    assert %{"kind" => "voice_transcript", "status" => "ok", "language" => "si"} = ok
    assert is_binary(ok["text"]) and ok["script"]["ok"] == true
    %{"risi" => bad} = example("envelope_risi_voice_transcript_unclear.json")
    assert %{"status" => "unclear", "text" => nil, "romanised" => true} = bad

    assert %{"type" => "risi_action", "action" => "transcript_delete", "edit" => nil} =
             example("envelope_risi_action_transcript_delete.json")

    for f <- ~w(me_risi_voice_put.json me_risi_voice_reply.json) do
      assert %{"transcribe" => t, "action_items" => a} = example(f)
      assert is_boolean(t) and is_boolean(a)
    end

    assert example("auth_config_v141.json")["voice_transcribe"] == "on"
    assert "voice" in example("device_put_voice.json")["mls"]["capabilities"]
    e = example("backup_entry_message_v141.json")
    assert valid_voice?(e["payload"]) and is_binary(e["played_at"])
  end
end
