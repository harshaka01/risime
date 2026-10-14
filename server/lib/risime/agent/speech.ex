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
  * Both take `language_hint: ["si", ...]` (decision 071 amendment d): the languages expected on
    the call or note (`RisiMe.Agent.Lang.speech_hint/2`), sent as the service's `language_hint`
    field; it only makes a hinted si/ta more likely, never forces it. `hint_opts/1` builds it.

  Audio is passed as a binary and never written anywhere by this module.
  """

  @type result :: %{
          optional(:fallback) => nil | String.t(),
          optional(:fallback_reason) => nil | String.t(),
          optional(:source) => String.t(),
          optional(:romanised) => boolean,
          optional(:language_probability) => number | nil,
          optional(:script) => script | nil,
          optional(:segments) => [%{offset_ms: non_neg_integer, text: String.t()}],
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
  @doc "The fast fallback model (incident 2026-10-14: omniASR-CTC-1B, CPU int8) on one chunk."
  @callback fallback_transcribe(audio :: binary, opts :: keyword) ::
              {:ok, result} | {:error, :timeout | :unavailable | :failed}
  @optional_callbacks fallback_transcribe: 2

  require Logger

  @langs ~w(si ta en)
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

  @doc "The speech options for a hint (`[]` without one, so nothing is sent)."
  def hint_opts([_ | _] = hint), do: [language_hint: hint]
  def hint_opts(_), do: []

  @doc "The service's `language_hint` field for `opts` (`\"si,ta\"`), or nil."
  def hint_field(opts) do
    case opts |> Keyword.get(:language_hint) |> List.wrap() |> Enum.filter(&(&1 in @langs)) do
      [] -> nil
      hint -> hint |> Enum.uniq() |> Enum.join(",")
    end
  end

  def impl, do: Application.get_env(:risime, :risi_speech, RisiMe.Agent.Speech.Http)

  def healthy?, do: impl().healthy?()
  def transcribe(audio, opts \\ []), do: impl().transcribe(audio, opts)
  def compare(audio, opts \\ []), do: impl().compare(audio, opts)

  ## Voice notes: the live budget and the fallbacks (incident 2026-10-14, Sinhala slow)

  @min_budget_ms 15_000
  @phone_model "phone-on-device"

  @doc """
  The live time budget of a voice note (Harsha 2026-10-14): 3 × its duration, at least 15 s.
  """
  def budget_ms(duration_ms) when is_integer(duration_ms) and duration_ms > 0,
    do: max(@min_budget_ms, 3 * duration_ms)

  def budget_ms(_), do: @min_budget_ms

  @doc "The fast fallback's own timeout: the note's duration, at least 10 s (RTF ~0.05 on CPU)."
  def fallback_budget_ms(duration_ms) when is_integer(duration_ms) and duration_ms > 0,
    do: max(10_000, duration_ms)

  def fallback_budget_ms(_), do: 10_000

  @doc "The model alias of a transcript taken from the sender phone (proposal 2026-10-14)."
  def phone_model, do: @phone_model

  @doc """
  A voice note (§37.6) under the live budget, with the fallbacks (incident 2026-10-14,
  docs/status/incident-2026-10-14-sinhala-slow.md):

    1. the routed model (`transcribe/2`, `priority: "live"`, timeout `budget_ms/1`); a fast error
       (not a timeout, within half the budget) is retried once, as before;
    2. on a timeout or failure, the sender phone's own transcript (§37.10 `phone_transcript`),
       only when `opts[:phone_fallback]` (flag `RISI_VOICE_PHONE_FALLBACK`, proposal
       2026-10-14-phone-transcript-fallback) and the field is valid;
    3. else the fast CPU model (`fallback_transcribe/2`, omniASR-CTC-1B int8);
    4. else `{:error, :failed}`.

  `opts`: `:duration_ms`, `:language_hint`, `:phone_transcript` (the envelope's map or nil),
  `:phone_fallback`. A fallback result carries `fallback: "phone" | "ctc"` and
  `fallback_reason: "timeout" | "unavailable" | "failed"`; a phone one `source: "phone"`.
  Every note writes one `risi_agent_runs` row (agent `voice`, mode `stt`: the model, status,
  latency, budget, codes only) and a slow or failed one pauses evals (`RisiMe.Agent.SpeechGuard`).
  Never logs text.
  """
  def transcribe_note(audio, opts \\ []) when is_binary(audio) do
    dur = opts[:duration_ms]
    budget = budget_ms(dur)
    t0 = System.monotonic_time(:millisecond)
    primary_opts = Keyword.take(opts, [:language_hint]) ++ [timeout: budget, priority: "live"]

    phone_text =
      case phone_transcript(opts[:phone_transcript]) do
        {:ok, t, _} -> t
        _ -> nil
      end

    {result, why} =
      case primary(audio, primary_opts, t0, budget) do
        {:ok, r} ->
          case guard(r, dur, phone_text) do
            :ok ->
              {{:ok, Map.merge(r, %{fallback: nil, fallback_reason: nil})}, nil}

            {:reject, code} ->
              # The hallucination guard: the fallbacks get a chance; else the note is unclear.
              case fallback(audio, opts, "rejected_" <> code, dur, phone_text) do
                {:ok, _} = ok ->
                  {ok, "rejected_" <> code}

                {:error, _} ->
                  {{:ok, Map.merge(r, %{fallback: nil, fallback_reason: nil, rejected: code})},
                   nil}
              end
          end

        {:error, why} ->
          {fallback(audio, opts, why, dur, phone_text), why}
      end

    elapsed = System.monotonic_time(:millisecond) - t0
    RisiMe.Agent.SpeechGuard.observe_live(result, elapsed, dur)
    record_run(result, why, elapsed, budget, opts)
    result
  end

  defp primary(audio, opts, t0, budget) do
    case transcribe(audio, opts) do
      {:ok, _} = ok ->
        ok

      {:error, :timeout} = e ->
        e

      {:error, _} = e ->
        left = budget - (System.monotonic_time(:millisecond) - t0)

        if left > budget / 2,
          do: transcribe(audio, Keyword.put(opts, :timeout, left)),
          else: e
    end
  end

  defp fallback(audio, opts, why, dur, phone_text) do
    reason = to_string(why)

    # Each candidate passes the hallucination guard too (the phone's own text isn't compared
    # with itself).
    with :none <- guarded(phone_fallback(opts), dur, nil),
         :none <- guarded(ctc_fallback(audio, opts), dur, phone_text) do
      {:error, :failed}
    else
      {:ok, r} -> {:ok, Map.put(r, :fallback_reason, reason)}
    end
  end

  defp guarded({:ok, r} = ok, dur, phone_text) do
    case guard(r, dur, phone_text) do
      :ok -> ok
      {:reject, _} -> :none
    end
  end

  defp guarded(other, _dur, _phone_text), do: other

  # The hallucination guard (`RisiMe.Agent.SpeechQuality`; `RISI_SPEECH_HALLUCINATION_GUARD=off`
  # switches it off). Codes and numbers only in the log.
  defp guard(r, dur, phone_text) do
    if System.get_env("RISI_SPEECH_HALLUCINATION_GUARD") == "off" do
      :ok
    else
      case RisiMe.Agent.SpeechQuality.check(r, dur, phone_text) do
        :ok ->
          :ok

        {:reject, code, m} ->
          Logger.info(
            "speech guard: rejected #{code} model=#{r[:model]} tokens=#{m.tokens} " <>
              "max_run=#{m.max_run} ngram_cover=#{m.ngram_cover} unique=#{m.unique_ratio} " <>
              "compression=#{m.compression} chars_per_s=#{m.chars_per_s} " <>
              "logprob=#{m.avg_logprob} phone_cer=#{m.phone_cer}"
          )

          {:reject, code}
      end
    end
  end

  defp phone_fallback(opts) do
    with true <- opts[:phone_fallback] == true,
         {:ok, text, tag} <- phone_transcript(opts[:phone_transcript]) do
      lang = tag |> String.split(["-", "_"]) |> hd() |> String.downcase()
      language = if lang in @langs, do: lang, else: script_lang(text)

      {:ok,
       %{
         text: text,
         language: language,
         model: @phone_model,
         elapsed_ms: 0,
         speech: true,
         romanised: language == "si" and romanised?(text),
         language_probability: nil,
         script: if(language == "si", do: script_check(text)),
         segments: [],
         source: "phone",
         fallback: "phone"
       }}
    else
      _ -> :none
    end
  end

  @doc """
  A valid §37.10 `phone_transcript` (lenient: anything malformed is `:error`, the note is kept):
  `{:ok, trimmed_text, lang_tag}`.
  """
  def phone_transcript(%{"text" => t, "lang" => l, "engine" => e})
      when is_binary(t) and is_binary(l) and is_binary(e) do
    text = String.trim(t)
    len = String.length(text)

    if len in 1..4000 and byte_size(text) <= 12_000 and String.length(l) in 2..35 and
         String.length(e) in 1..64,
       do: {:ok, text, l},
       else: :error
  end

  def phone_transcript(_), do: :error

  defp ctc_fallback(audio, opts) do
    m = impl()

    if Code.ensure_loaded?(m) and function_exported?(m, :fallback_transcribe, 2) do
      case m.fallback_transcribe(audio,
             timeout: fallback_budget_ms(opts[:duration_ms]),
             priority: "live"
           ) do
        {:ok, r} ->
          # The CTC model is not language-conditioned: the language is its output's script.
          language = if r.speech, do: script_lang(r.text), else: r.language

          {:ok,
           Map.merge(r, %{
             language: language,
             romanised: false,
             language_probability: nil,
             script: if(language == "si", do: script_check(r.text)),
             fallback: "ctc"
           })}

        {:error, _} ->
          :none
      end
    else
      :none
    end
  end

  @doc "`si` | `ta` | `en` by the script of `text` (Sinhala or Tamil letters, else `en`)."
  def script_lang(text) when is_binary(text) do
    {si, ta} =
      text
      |> String.to_charlist()
      |> Enum.reduce({0, 0}, fn
        c, {si, ta} when c in 0x0D80..0x0DFF -> {si + 1, ta}
        c, {si, ta} when c in 0x0B80..0x0BFF -> {si, ta + 1}
        _, acc -> acc
      end)

    cond do
      si > 0 and si >= ta -> "si"
      ta > 0 -> "ta"
      true -> "en"
    end
  end

  def script_lang(_), do: "en"

  @doc "The §36.11 Sinhala script check of `text` (as the speech service computes it)."
  def script_check(text) do
    {si, la, letters} =
      text
      |> String.to_charlist()
      |> Enum.reduce({0, 0, 0}, fn c, {si, la, l} ->
        cond do
          c in 0x0D80..0x0DFF -> {si + 1, la, l}
          c in ?a..?z or c in ?A..?Z -> {si, la + 1, l + 1}
          letter?(c) -> {si, la, l + 1}
          true -> {si, la, l}
        end
      end)

    %{expected: "sinh", sinhala_chars: si, latin_chars: la, ok: not (si == 0 and letters > 0)}
  end

  defp romanised?(text), do: not script_check(text).ok

  defp letter?(c), do: String.match?(<<c::utf8>>, ~r/^\p{L}$/u)

  # One `risi_agent_runs` row per note (Voice agent scores, decision 084): codes and timings.
  defp record_run(result, why, elapsed, budget, opts) do
    {status, model, fell_back, reason, lang} =
      case result do
        {:ok, %{rejected: code} = r} when is_binary(code) ->
          {"rejected", r.model, false, "rejected_" <> code, r.language}

        {:ok, %{fallback: nil} = r} ->
          {"ok", r.model, false, nil, r.language}

        {:ok, r} ->
          {"ok", r.model, true, "#{r.fallback_reason}_#{r.fallback}", r.language}

        {:error, _} ->
          # Every fallback failed too: `<primary error>_none` (e.g. `timeout_none`).
          {"error", "none", true, "#{why || "failed"}_none", nil}
      end

    %{
      agent: "voice",
      mode: "stt",
      decision: model,
      status: status,
      fell_back: fell_back,
      reason: reason,
      latency_ms: elapsed,
      budget_ms: budget,
      lang: lang
    }
    |> then(fn row -> if opts[:sender], do: Map.put(row, :user, opts[:sender]), else: row end)
    |> RisiMe.Agents.Runs.record()
  end

  @doc """
  The extra compare service's models (compare only, never a route; `Speech.Http`):
  `{:ok, [%{id, streamed}]}` or `{:error, :off | :unavailable}`. An impl without it is off.
  """
  def extra_models do
    m = impl()

    if Code.ensure_loaded?(m) and function_exported?(m, :extra_models, 0),
      do: m.extra_models(),
      else: {:error, :off}
  end

  @doc "The extra compare models asked by default (`[]` when off)."
  def extra_default_models do
    m = impl()

    if Code.ensure_loaded?(m) and function_exported?(m, :extra_default_models, 0),
      do: m.extra_default_models(),
      else: []
  end
end
