defmodule RisiMe.Contract.ExamplesV147Test do
  @moduledoc "v1.47 §37.10: the sender phone's own transcript inside the voice envelope."
  use ExUnit.Case, async: true

  alias RisiMe.Agent.Voice

  @dir Path.expand("../../../contract/v1/examples", __DIR__)
  @files ~w(voice_payload_phone_transcript.json voice_payload_phone_transcript_bad.json)

  @doc "The v1.47 examples (for the coverage test in examples_test.exs)."
  def files, do: @files

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  test "both examples are valid §37.2 voice envelopes; a malformed phone_transcript doesn't drop the note" do
    for f <- @files do
      assert {:ok, %{duration_ms: 12_400}} = Voice.validate(example(f)), f
    end

    assert %{"lang" => "si-LK", "engine" => "android-on-device"} =
             example("voice_payload_phone_transcript.json")["phone_transcript"]
  end

  # v1.49 §37.10 (proposal 2026-10-14-phone-transcript-fallback): the validated note
  # may carry a valid phone transcript ONLY as a speech-fallback input (RisiMe.Agent.Speech.
  # transcribe_note/2, behind RISI_VOICE_PHONE_FALLBACK, default off). It is never posted, never
  # used for extraction and never logged while Risi's own model answers (voice_test.exs
  # "incident 2026-10-14 … the phone transcript is ignored").
  test "Risi carries the field only as a fallback input; everything else is unchanged" do
    {:ok, with} = Voice.validate(example("voice_payload_phone_transcript.json"))

    {:ok, without} =
      Voice.validate(
        Map.delete(example("voice_payload_phone_transcript.json"), "phone_transcript")
      )

    assert Map.delete(with, :phone_transcript) == Map.delete(without, :phone_transcript)
    assert without.phone_transcript == nil

    assert with.phone_transcript ==
             example("voice_payload_phone_transcript.json")["phone_transcript"]

    # A malformed field (no lang) is ignored entirely: not even a fallback input.
    {:ok, bad} = Voice.validate(example("voice_payload_phone_transcript_bad.json"))
    assert bad.phone_transcript == nil
  end
end
