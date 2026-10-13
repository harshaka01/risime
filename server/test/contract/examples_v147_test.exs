defmodule RisiMe.Contract.ExamplesV147Test do
  @moduledoc "v1.47 §37.10: the sender phone's own transcript inside the voice envelope."
  use ExUnit.Case, async: true

  alias RisiMe.Agent.Voice

  @dir Path.expand("../../../contract/v1/examples", __DIR__)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  test "both examples are valid §37.2 voice envelopes; a malformed phone_transcript doesn't drop the note" do
    for f <- ~w(voice_payload_phone_transcript.json voice_payload_phone_transcript_bad.json) do
      assert {:ok, %{duration_ms: 12_400}} = Voice.validate(example(f)), f
    end

    assert %{"lang" => "si-LK", "engine" => "android-on-device"} =
             example("voice_payload_phone_transcript.json")["phone_transcript"]
  end

  test "Risi ignores the field: the validated note carries nothing of it" do
    {:ok, with} = Voice.validate(example("voice_payload_phone_transcript.json"))

    {:ok, without} =
      Voice.validate(
        Map.delete(example("voice_payload_phone_transcript.json"), "phone_transcript")
      )

    assert with == without
  end
end
