defmodule RisiMe.Agent.Speech do
  @moduledoc """
  Speech-to-text for the call-transcription fast path (contract v1.39 §36.5, decisions 071 and
  080): the local speech service on loopback, behind a behaviour so tests use a fake
  (`config :risime, :risi_speech`).

  * `transcribe/2`: one chunk, routed by language (`si` → `whisper-small-sinhala`, `ta` →
    `tamil-asr-qwen3`, `en` and unsure → `faster-whisper-large-v3`). The result's `speech`
    is false when VAD found no speech (nothing is posted for it).
  * `compare/2`: every candidate model on the same chunk (developer compare mode), each with a
    30-s timeout.
  * v1.40 §36.11: `romanised` (transcribe) and `script` / `romanised` (each compare result of a
    Sinhala chunk) are the service's Sinhala script check, passed through to the Risi posts.
  * `healthy?/0`: the service answers `/health` (cached briefly).

  Audio is passed as a binary and never written anywhere by this module.
  """

  @type result :: %{
          optional(:romanised) => boolean,
          optional(:language_probability) => number | nil,
          optional(:script) => script | nil,
          text: String.t(),
          language: String.t(),
          model: String.t(),
          elapsed_ms: non_neg_integer,
          speech: boolean
        }

  @typedoc "v1.40 §36.11: the speech service's Sinhala script check of one text."
  @type script :: %{
          expected: String.t(),
          sinhala_chars: non_neg_integer,
          latin_chars: non_neg_integer,
          ok: boolean
        }

  @type compare_result :: %{
          language_detected: String.t(),
          chosen: String.t(),
          results: [
            %{
              optional(:script) => script | nil,
              optional(:romanised) => boolean | nil,
              model: String.t(),
              text: String.t() | nil,
              language: String.t() | nil,
              elapsed_ms: non_neg_integer,
              error: nil | String.t()
            }
          ]
        }

  @callback healthy?() :: boolean
  @callback transcribe(audio :: binary, opts :: keyword) ::
              {:ok, result} | {:error, :timeout | :unavailable | :failed}
  @callback compare(audio :: binary, opts :: keyword) ::
              {:ok, compare_result} | {:error, :timeout | :unavailable | :failed}

  @models ~w(whisper-small-sinhala tamil-asr-qwen3 faster-whisper-large-v3)

  @doc "The routed model aliases (§36.5)."
  def models, do: @models

  @doc "The alias a language routes to (§36.5)."
  def route("si"), do: "whisper-small-sinhala"
  def route("ta"), do: "tamil-asr-qwen3"
  def route(_), do: "faster-whisper-large-v3"

  @doc "A language code normalised to `si` | `ta` | `en` (anything else and unsure → `en`)."
  def lang(l) when l in ["si", "ta", "en"], do: l
  def lang(_), do: "en"

  def impl, do: Application.get_env(:risime, :risi_speech, RisiMe.Agent.Speech.Http)

  def healthy?, do: impl().healthy?()
  def transcribe(audio, opts \\ []), do: impl().transcribe(audio, opts)
  def compare(audio, opts \\ []), do: impl().compare(audio, opts)
end
