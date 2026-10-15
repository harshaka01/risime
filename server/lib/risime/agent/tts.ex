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
  # `load_ms` (the model load this request caused) and `audio_ms` (the audio's length) are
  # optional: an engine that is always resident leaves them out.
  @type audio :: %{
          required(:audio) => binary,
          required(:content_type) => String.t(),
          required(:first_audio_ms) => non_neg_integer,
          required(:total_ms) => non_neg_integer,
          optional(:load_ms) => non_neg_integer,
          optional(:audio_ms) => non_neg_integer | nil
        }

  @callback engine() :: %{id: String.t(), name: String.t()}
  @callback voices() :: {:ok, [voice]} | {:error, :unavailable | :downloading | :review_pending}
  @callback synthesize(text :: String.t(), voice_id :: String.t(), opts :: keyword) ::
              {:ok, audio} | {:error, atom}

  @langs ~w(si mixed ta en)

  # Display catalogue (Part 12; candidates 2026-10-14): every engine Harsha compares, installed or
  # not, with its licence and whether it may be Risi's production voice. `production`: `:yes`,
  # `:conditional` (allowed with the stated condition) or `:no`. Risi's voice is always a built-in
  # or stock voice of the engine, never a clone of a real person from a recording.
  @catalogue [
    %{
      id: "kokoro",
      name: "Kokoro",
      languages: ~w(en),
      licence: "Apache-2.0 (Kokoro-82M weights), MIT (misaki G2P)",
      production: :yes,
      note: "Built-in voices. Call Risi's voice, resident for live calls."
    },
    %{
      id: "orpheus",
      name: "Orpheus",
      languages: ~w(en),
      licence:
        "Apache-2.0 (Canopy Labs) + Llama 3.2 Community Licence (fine-tuned from Llama 3.2 3B)",
      production: :conditional,
      note:
        "Only under the Llama 3.2 licence as well: \"Built with Llama\" attribution, Meta's acceptable " <>
          "use policy, the 700 M monthly-user clause. Stock voices (tara, leah, …). Console test only."
    },
    %{
      id: "chatterbox",
      name: "Chatterbox",
      languages: ~w(en),
      licence: "MIT (Resemble AI)",
      production: :yes,
      note:
        "Every output carries Resemble's PerTh watermark: inaudible, survives MP3 and edits, and marks " <>
          "the audio as AI-made for anyone with Resemble's open detector. Built-in default voice only " <>
          "(no cloning). No Sinhala or Tamil among its 23 languages."
    },
    %{
      id: "chatterbox-turbo",
      name: "Chatterbox-Turbo",
      languages: ~w(en),
      licence: "MIT (Resemble AI)",
      production: :yes,
      note:
        "Chatterbox's fast English model (350M GPT-2 backbone, 2-step decoder). PerTh watermark on every " <>
          "output, as Chatterbox. Its built-in stock voice only (no cloning). Console test only."
    },
    %{
      id: "dia2",
      name: "Dia2",
      languages: ~w(en),
      licence: "Apache-2.0 (Nari Labs); Mimi codec CC-BY-4.0 (Kyutai)",
      production: :yes,
      note:
        "Streaming dialogue model (1B). No named voices: the stock speaker is the model's own voice for a " <>
          "fixed seed, made at load, no recording of a person. CC-BY needs \"Mimi by Kyutai\" attribution. " <>
          "Console test only."
    },
    %{
      id: "indicf5",
      name: "IndicF5",
      languages: ~w(ta),
      licence: "MIT (AI4Bharat); its custom code is reviewed before it runs",
      production: :no,
      note:
        "No built-in voice: every sentence copies the voice of a reference recording (its stock prompts " <>
          "are dataset speakers). Not Risi's voice until a licensed or synthetic reference voice exists. " <>
          "No Sinhala."
    },
    %{
      id: "indic-parler",
      name: "Indic Parler-TTS",
      languages: ~w(ta en),
      licence: "Apache-2.0 (AI4Bharat)",
      production: :yes,
      note: "Stock named speakers or a described voice (no cloning). Tamil yes; no Sinhala."
    },
    %{
      id: "mms",
      name: "MMS",
      languages: ~w(ta),
      licence: "CC-BY-NC-4.0 (Meta MMS-TTS)",
      production: :no,
      note: "Non-commercial only, and there is no Sinhala model.",
      unavailable: "not available (licence)"
    },
    %{
      id: "phone",
      name: "Phone TTS (on the phone, not a server voice)",
      languages: ~w(si ta en),
      licence: "The phone's own engine (Google / Samsung), on-device voices only",
      production: :conditional,
      note:
        "Phone-side only: on-device voices, never network synthesis. Sinhala and Tamil are checked on " <>
          "Harsha's phone (docs/status/phone-tts.md).",
      unavailable: "on the phone only, not a server voice"
    }
  ]

  @doc "The languages with a choice."
  def languages, do: @langs

  @doc "The engines Harsha expects (`{id, name}`), installed or not."
  def catalogue, do: Enum.map(@catalogue, &{&1.id, &1.name})

  @doc """
  Every catalogue entry: `%{id, name, languages, licence, production, note}`, plus `unavailable`
  for an engine that is never a server voice (MMS, Phone).
  """
  def catalogue_info, do: @catalogue

  @doc "The catalogue entry of engine `id`, or nil."
  def info(id), do: Enum.find(@catalogue, &(&1.id == id))

  @doc "The registered engine modules."
  def engines, do: Application.get_env(:risime, :risi_tts_engines, [])

  @doc """
  Every registered engine with its voices: `[%{id, name, module, status, voices: [voice]}]`.
  `status` is `:ok`, `:downloading` (its weights haven't arrived), `:review_pending` (its shipped
  code isn't cleared yet) or `:unavailable` (it raises or doesn't answer).
  """
  def installed do
    for mod <- engines() do
      %{id: id, name: name} = mod.engine()

      {status, voices} =
        try do
          case mod.voices() do
            {:ok, vs} when is_list(vs) -> {:ok, vs}
            {:error, r} when r in [:downloading, :review_pending] -> {r, []}
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
