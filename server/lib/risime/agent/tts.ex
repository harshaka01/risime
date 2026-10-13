defmodule RisiMe.Agent.TTS do
  @moduledoc """
  Text-to-speech for Risi (Call Risi, spoken answers): the behaviour every voice engine
  implements, the engine registry, and the per-language voice choice of the admin console's
  Voices page (decision 089, Part 12 item 2). **No engine is built here.** A voice service (e.g.
  the Kokoro service for Call Risi) plugs in by implementing this behaviour and being listed in

      config :risime, :risi_tts_engines, [RisiMe.Agent.TTS.Kokoro]

  (default `[]`: nothing installed, every catalogue engine shows "not installed").

  * **Engines:** `c:engine/0` (`%{id: "kokoro", name: "Kokoro"}`), `c:voices/0` (the voices it
    serves now, each `%{id, name, languages}`, ids globally unique, e.g. `"kokoro:af_heart"`),
    `c:synthesize/3` (audio from memory, never written to disk, with the time to first audio).
  * **Choice per language** (`si`, `mixed` = Singlish and other code-switching, `ta`, `en`):
    `"auto"` or a voice id, in `RisiMe.Admin.Settings` (key `tts_voice_<lang>`: table > env
    `RISI_TTS_VOICE_<LANG>` > `"auto"`, 5-s cache): live within 5 s, no deploy.
  * **The hook for callers:** `voice_for/1` → `:auto` (the caller keeps today's route: its own
    default voice; byte-identical behaviour) or `{:manual, voice_id}`. A manual choice beats Auto
    until set back. What Auto will become: `RisiMe.Agent.VoiceAuto`.

  The text to speak is never logged or stored by this module.
  """
  alias RisiMe.Admin.Settings

  @type voice :: %{id: String.t(), name: String.t(), languages: [String.t()]}
  @type audio :: %{
          audio: binary,
          content_type: String.t(),
          first_audio_ms: non_neg_integer,
          total_ms: non_neg_integer
        }

  @callback engine() :: %{id: String.t(), name: String.t()}
  @callback voices() :: {:ok, [voice]} | {:error, :unavailable}
  @callback synthesize(text :: String.t(), voice_id :: String.t(), opts :: keyword) ::
              {:ok, audio} | {:error, :unavailable | :timeout | :failed}

  @langs ~w(si mixed ta en)

  # Display catalogue (Part 12): engines Harsha expects; only registered engines are installed.
  @catalogue [
    {"kokoro", "Kokoro"},
    {"orpheus", "Orpheus"},
    {"chatterbox", "Chatterbox"},
    {"mms", "MMS"},
    {"indic-parler", "Indic Parler"},
    {"phone", "Phone TTS (on the phone, not a server voice)"}
  ]

  @doc "The languages with a choice."
  def languages, do: @langs

  @doc "The engines Harsha expects (`{id, name}`), installed or not."
  def catalogue, do: @catalogue

  @doc "The registered engine modules."
  def engines, do: Application.get_env(:risime, :risi_tts_engines, [])

  @doc """
  Every registered engine with its voices: `[%{id, name, module, status: :ok | :unavailable,
  voices: [voice]}]`. An engine that raises or doesn't answer is `:unavailable`.
  """
  def installed do
    for mod <- engines() do
      %{id: id, name: name} = mod.engine()

      {status, voices} =
        try do
          case mod.voices() do
            {:ok, vs} when is_list(vs) -> {:ok, vs}
            _ -> {:unavailable, []}
          end
        rescue
          _ -> {:unavailable, []}
        catch
          :exit, _ -> {:unavailable, []}
        end

      %{id: id, name: name, module: mod, status: status, voices: voices}
    end
  end

  @doc "All installed voices (flat), each with its `engine` id."
  def voices(installed \\ installed()) do
    for e <- installed, v <- e.voices, do: Map.put(v, :engine, e.id)
  end

  @doc "The voices offered for `lang` (those that list it; `mixed` also takes si and en voices)."
  def candidates(voices, lang) when lang in @langs do
    accept = if lang == "mixed", do: ["mixed", "si", "en"], else: [lang]
    Enum.filter(voices, fn v -> Enum.any?(List.wrap(v[:languages]), &(&1 in accept)) end)
  end

  @doc "The settings key of `lang`."
  def setting_key(lang) when lang in @langs, do: "tts_voice_" <> lang

  @doc "The choice for `lang`: `\"auto\"` or a voice id."
  def choice(lang) when lang in @langs do
    case Settings.get(setting_key(lang), "auto") do
      v when is_binary(v) and v != "" -> v
      _ -> "auto"
    end
  end

  @doc "Every language's choice."
  def choices, do: Map.new(@langs, &{&1, choice(&1)})

  @doc """
  **The hook** for a caller about to speak in `lang`: `:auto` (keep today's route) or
  `{:manual, voice_id}`. Unknown languages are `:auto`.
  """
  def voice_for(lang) when lang in @langs do
    case choice(lang) do
      "auto" -> :auto
      id -> {:manual, id}
    end
  end

  def voice_for(_), do: :auto

  @doc "Sets `lang`'s choice by admin `by` (audit-logged); a voice must be one of `installed_ids`."
  def put(lang, value, by, installed_ids \\ [])

  def put(lang, "auto", by, _) when lang in @langs, do: Settings.clear(setting_key(lang), by)

  def put(lang, voice, by, installed_ids) when lang in @langs and is_binary(voice) do
    if voice in installed_ids,
      do: Settings.put(setting_key(lang), voice, by),
      else: {:error, :not_installed}
  end

  def put(_, _, _, _), do: {:error, :invalid}

  @doc """
  Speaks `text` with `voice_id` through the engine that serves it. `{:error, :not_installed}`
  when no registered engine serves the voice.
  """
  def synthesize(text, voice_id, opts \\ []) when is_binary(text) and is_binary(voice_id) do
    installed = Keyword.get_lazy(opts, :installed, &installed/0)

    case Enum.find(installed, fn e -> Enum.any?(e.voices, &(&1.id == voice_id)) end) do
      nil ->
        {:error, :not_installed}

      e ->
        try do
          e.module.synthesize(text, voice_id, Keyword.delete(opts, :installed))
        rescue
          _ -> {:error, :failed}
        catch
          :exit, _ -> {:error, :timeout}
        end
    end
  end
end
