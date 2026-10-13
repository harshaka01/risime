defmodule RisiMe.Agent.TTSTest do
  @moduledoc """
  Decision 089 / Part 12 items 2–3: the TTS behaviour and registry, the per-language voice choice
  (parity: nothing set → `voice_for/1` is `:auto` everywhere, today's route), the STT model
  catalogue, and what Auto means (`RisiMe.Agent.VoiceAuto`).
  """
  use RisiMe.DataCase, async: false

  alias RisiMe.Admin.Settings
  alias RisiMe.Agent.{SpeechRoutes, TTS, VoiceAuto}

  defmodule FakeKokoro do
    @behaviour RisiMe.Agent.TTS
    def engine, do: %{id: "kokoro", name: "Kokoro"}

    def voices,
      do:
        {:ok,
         [
           %{id: "kokoro:en1", name: "Kokoro en", languages: ["en"]},
           %{id: "kokoro:si1", name: "Kokoro si", languages: ["si"]}
         ]}

    def synthesize(text, voice, _opts) do
      send(self(), {:spoke, voice, byte_size(text)})
      {:ok, %{audio: "RIFF", content_type: "audio/wav", first_audio_ms: 120, total_ms: 400}}
    end
  end

  defmodule Broken do
    @behaviour RisiMe.Agent.TTS
    def engine, do: %{id: "orpheus", name: "Orpheus"}
    def voices, do: raise("down")
    def synthesize(_, _, _), do: {:error, :unavailable}
  end

  setup do
    Settings.refresh()
    old = Application.get_env(:risime, :risi_tts_engines)

    on_exit(fn ->
      Application.put_env(:risime, :risi_tts_engines, old)
      Settings.refresh()
    end)

    :ok
  end

  defp admin do
    entry = RisiMe.Fixtures.allowlist_entry()
    {:ok, u} = RisiMe.Accounts.map_identity(%{sub: Ecto.UUID.generate(), email: entry.email})
    u
  end

  test "parity: no engines, nothing set → nothing installed, Auto everywhere" do
    Application.put_env(:risime, :risi_tts_engines, [])
    assert TTS.installed() == []
    assert TTS.voices() == []
    for lang <- ~w(si mixed ta en), do: assert(TTS.voice_for(lang) == :auto)
    assert TTS.voice_for("fr") == :auto
    assert TTS.synthesize("hi", "kokoro:en1") == {:error, :not_installed}
  end

  test "registered engines: voices, candidates, a broken engine is unavailable" do
    Application.put_env(:risime, :risi_tts_engines, [FakeKokoro, Broken])
    [k, b] = TTS.installed()
    assert k.status == :ok and length(k.voices) == 2
    assert b.status == :unavailable and b.voices == []

    voices = TTS.voices()
    assert Enum.map(TTS.candidates(voices, "si"), & &1.id) == ["kokoro:si1"]
    assert Enum.map(TTS.candidates(voices, "mixed"), & &1.id) == ["kokoro:en1", "kokoro:si1"]
    assert TTS.candidates(voices, "ta") == []

    assert {:ok, %{first_audio_ms: 120}} = TTS.synthesize("hello", "kokoro:si1")
    assert_received {:spoke, "kokoro:si1", 5}
  end

  test "a manual voice beats Auto until set back; only installed voices" do
    u = admin()
    assert TTS.put("si", "kokoro:si1", u.id, []) == {:error, :not_installed}
    assert TTS.put("xx", "a", u.id, ["a"]) == {:error, :invalid}
    assert TTS.put("si", "kokoro:si1", u.id, ["kokoro:si1"]) == :ok
    assert TTS.voice_for("si") == {:manual, "kokoro:si1"}
    assert TTS.voice_for("en") == :auto
    assert TTS.put("si", "auto", u.id) == :ok
    assert TTS.voice_for("si") == :auto
  end

  test "the Voices page speaks the sentence in every installed voice" do
    Application.put_env(:risime, :risi_tts_engines, [FakeKokoro])
    installed = TTS.installed()

    [a, b] = RisiMeWeb.Admin.VoicesLive.speak_all("hello", TTS.voices(installed), installed)
    assert {:ok, %{first_audio_ms: 120, src: "data:audio/wav;base64," <> _}} = a.result
    assert b.voice.id == "kokoro:si1"
  end

  test "STT catalogue: only served aliases count as installed; phone is never a server model" do
    models =
      for id <-
            ~w(faster-whisper-large-v3 whisper-small-sinhala qwen3-asr-0.6b-sinhala
               omnilingual-ctc-1b tamil-asr-qwen3 whisper-tamil-medium),
          do: %{id: id, language: nil, role: "compare", loaded: false}

    cat = Map.new(SpeechRoutes.catalogue(models), &{&1.family, &1})
    assert cat["Qwen-Sinhala"].installed == ["qwen3-asr-0.6b-sinhala"]
    assert cat["omniASR 1B"].installed == ["omnilingual-ctc-1b"]
    assert cat["omniASR 3B"].installed == []
    assert cat["omniASR 7B"].installed == []
    assert cat["Whisper"].installed == ["faster-whisper-large-v3", "whisper-small-sinhala"]
    assert cat["Tamil models"].installed == ["tamil-asr-qwen3", "whisper-tamil-medium"]
    assert cat["Phone (on-device)"].phone

    assert SpeechRoutes.omni_7b?("omnilingual-llm-7b")
    assert SpeechRoutes.omni_3b?("omniASR-CTC-3B")
  end

  test "Auto: no Index rows → today's route; rows present still never switch by themselves" do
    assert %{index?: false, basis: :todays_route} = VoiceAuto.status(:stt)
    assert %{index?: false} = VoiceAuto.status(:tts)

    Repo.insert_all("risi_agent_scores", [
      %{
        day: ~D[2026-10-13],
        agent: "voice",
        source: "index_stt",
        n: 1,
        updated_at: DateTime.utc_now()
      }
    ])

    assert %{index?: true, basis: :todays_route} = VoiceAuto.status(:stt)
    assert %{index?: false} = VoiceAuto.status(:tts)
  end
end
