defmodule RisiMe.Contract.ExamplesV139Test do
  @moduledoc """
  Contract v1.39 §36 (decision 080, the call-transcription fast path): every new example file
  is decoded and checked against the §36 shapes. The behaviour is covered in
  test/risime/agent/call_transcribe_*_test.exs.
  """
  use ExUnit.Case, async: true

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  @doc false
  def files,
    do: ~w(auth_config_v139.json call_offer_official_payload.json call_transcribe_allow.json
           call_transcribe_chunk_reply.json call_transcribe_decline.json call_transcribe_reply.json
           call_transcribe_request.json call_transcribe_stop.json device_put_call_transcribe.json
           envelope_risi_call_transcribe_on.json envelope_risi_call_transcribe_stopped.json
           envelope_risi_call_transcript.json envelope_risi_call_transcript_compare.json
           envelope_risi_discussion_card_transcribed.json error_not_consented.json
           error_not_official.json error_transcribe_off.json me_reply_risi_dev.json
           me_risi_dev_put.json me_risi_dev_reply.json signal_call_transcribe.json)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  @session_keys ~w(session_id call_id conversation_id chat_id state requested_by requested_at
                   expires_at participants compare started_at ended_at by reason discard chunk
                   server_ts)

  test "every listed file exists and decodes" do
    for f <- files(), do: assert(is_map(example(f)), f)
  end

  test "the Session shape (reply and signal)" do
    s = example("call_transcribe_reply.json")["session"]
    assert Enum.sort(Map.keys(s)) == Enum.sort(@session_keys)
    assert s["chunk"] == %{"min_ms" => 5000, "max_ms" => 20000, "max_bytes" => 262_144}
    sig = example("signal_call_transcribe.json")
    assert sig["kind"] == "call_transcribe"
    assert Enum.sort(Map.keys(sig["data"])) == Enum.sort(@session_keys)
  end

  test "the pushes" do
    for {f, action} <- [
          {"call_transcribe_request.json", "request"},
          {"call_transcribe_allow.json", "allow"},
          {"call_transcribe_decline.json", "decline"},
          {"call_transcribe_stop.json", "stop"}
        ] do
      ex = example(f)
      assert ex["action"] == action
      assert String.starts_with?(ex["conversation_id"], "grp:")
    end
  end

  test "errors, switch, developer flag, capability, chunk reply" do
    for {f, code} <- [
          {"error_not_consented.json", "not_consented"},
          {"error_not_official.json", "not_official"},
          {"error_transcribe_off.json", "transcribe_off"}
        ],
        do: assert(example(f)["error"]["code"] == code)

    assert example("auth_config_v139.json")["call_transcribe"] in ~w(on off)
    assert example("me_reply_risi_dev.json")["user"]["risi_dev"] == true
    assert example("me_risi_dev_put.json") == %{"transcribe_compare" => true}
    assert example("me_risi_dev_reply.json") == %{"transcribe_compare" => true}
    assert "call_transcribe" in example("device_put_call_transcribe.json")["mls"]["capabilities"]

    assert example("call_transcribe_chunk_reply.json") == %{
             "seq" => 3,
             "accepted" => true,
             "duplicate" => false
           }

    assert example("call_offer_official_payload.json")["tab"] == "official"
  end

  test "the Risi kinds" do
    for f <- ~w(envelope_risi_call_transcribe_on.json envelope_risi_call_transcribe_stopped.json
                envelope_risi_call_transcript.json envelope_risi_call_transcript_compare.json
                envelope_risi_discussion_card_transcribed.json) do
      ex = example(f)
      assert ex["type"] == "text" and is_binary(ex["body"]), f
      assert ex["risi"]["v"] == 1
    end

    assert example("envelope_risi_discussion_card_transcribed.json")["risi"]["lang"] == "en"
  end
end
