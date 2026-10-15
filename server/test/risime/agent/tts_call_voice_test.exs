defmodule RisiMe.Agent.TTS.CallVoiceTest do
  @moduledoc "Item C (2026-10-15): Call Risi's voice, rated and picked on the Voices page."
  use ExUnit.Case, async: true

  alias RisiMe.Agent.TTS.CallVoice

  defp ok(ms), do: {:ok, %{first_audio_ms: ms, total_ms: ms + 50, load_ms: 0, src: "data:"}}

  test "the lineup: today's voice first, then the candidates (never twice)" do
    [now | rest] = CallVoice.lineup("kokoro:af_heart")
    assert now.id == "kokoro:af_heart" and now.now?
    ids = Enum.map(rest, & &1.id)
    assert "kokoro:bf_emma" in ids and "chatterbox:default" in ids
    assert length(ids) >= 3
    refute Enum.any?(rest, & &1.now?)

    [now | rest] = CallVoice.lineup("kokoro:bf_emma")
    assert now.id == "kokoro:bf_emma"
    refute "kokoro:bf_emma" in Enum.map(rest, & &1.id)
  end

  test "the same realistic samples for every voice, with times and names" do
    samples = CallVoice.samples()
    assert length(samples) == 3
    assert Enum.any?(samples, &(&1 =~ "ten thirty"))
    assert Enum.any?(samples, &(&1 =~ "Ruwan"))
  end

  test "first audio is the p50 of the samples that played" do
    assert CallVoice.first_ms([nil, nil, nil]) == nil
    assert CallVoice.first_ms([ok(300), ok(100), ok(200)]) == 200
    assert CallVoice.first_ms([ok(300), {:error, :timeout}, nil]) == 300
  end

  test "live: only a measured Kokoro voice within the budget may speak live calls" do
    assert CallVoice.live("kokoro:bf_emma", [nil, nil, nil]) == :unmeasured
    assert CallVoice.live("kokoro:bf_emma", [ok(120), ok(110), ok(130)]) == :ok
    assert CallVoice.live("kokoro:bf_emma", [ok(1600), ok(1700), ok(1800)]) == {:too_slow, 1700}

    assert CallVoice.live("chatterbox:default", [ok(900), ok(800), ok(1000)]) ==
             {:not_streamed, 900}

    assert CallVoice.live("chatterbox:default", [ok(4000), ok(3000), ok(3500)]) ==
             {:too_slow, 3500}

    errs = [{:error, :no_memory}, {:error, :no_memory}, {:error, :no_memory}]
    assert CallVoice.live("chatterbox:default", errs) == {:cannot_run, :no_memory}
    # Still speaking: not decided yet.
    assert CallVoice.live("chatterbox:default", [{:error, :no_memory}, nil, nil]) == :unmeasured
  end

  test "pick refuses a voice that can't speak live calls (nothing saved)" do
    assert CallVoice.pick("chatterbox:default", [ok(900)], "admin", ["chatterbox:default"]) ==
             {:error, {:not_streamed, 900}}

    assert CallVoice.pick("kokoro:bf_emma", [nil], "admin", ["kokoro:bf_emma"]) ==
             {:error, :unmeasured}
  end
end
