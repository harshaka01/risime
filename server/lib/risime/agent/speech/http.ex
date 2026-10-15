defmodule RisiMe.Agent.Speech.Http do
  @moduledoc """
  `RisiMe.Agent.Speech` over the local speech service (`infra/whisper/server.py`, decisions 071
  and 080), loopback only (`RISI_SPEECH_URL`, default `http://127.0.0.1:8200`; any other host is
  refused before a socket opens), Bearer `WHISPER_API_KEY`.

  * **Transcribe:** `POST /v1/audio/transcriptions` (multipart; the chunk is sent from memory,
    never a file) with `model=auto` and `response_format=verbose_json`: the service detects the
    language with `faster-whisper-large-v3` and routes (§36.5). A service that doesn't serve
    `auto` yet (404) is asked for `faster-whisper-large-v3` instead (remembered for 5 min).
  * **Compare:** `POST /v1/audio/compare` (per-model `{model, text, language, processing_s}`,
    plus `script` and `romanised` for a Sinhala chunk, v1.40 §36.11);
    without it (404) each model is asked through `/v1/audio/transcriptions` in parallel. Every
    model has a 30-s timeout.
  * **Extra compare candidates** (2026-10-14, developer compare mode only): when
    `compare_extra_url` (else `RISI_SPEECH_COMPARE_EXTRA_URL`) names a second loopback service
    with the same `/v1/audio/compare` (the omniASR eval service, `infra/omniasr`), it is asked
    after the speech service, with the detected language and `models` =
    `compare_extra_models` (else `RISI_SPEECH_COMPARE_EXTRA_MODELS`, default
    `omniasr-llm-1b`; `opts[:extra_models]` overrides it per call, `[]` = none; the console
    can tick the streamed omniasr-llm-7b),
    and its results are appended (same §36.11 result shape; `chosen` and `language_detected`
    stay the speech service's). Each extra model gets `compare_extra_timeout_ms` (else
    `RISI_SPEECH_COMPARE_EXTRA_TIMEOUT_MS`, default 180 s: omniasr-llm-7b streams its encoder
    per request and is slow). Its failure or timeout only marks those models `failed` /
    `timeout`. Off by default; `transcribe/1,2` never uses it. `extra_models/0` lists what the
    extra service serves (the console's compare-only candidates).
  * **Health:** `GET /health`, cached 15 s.
  * **Priority** (incident 2026-10-14): `opts[:priority]` `"live"` (voice notes,
    `Speech.transcribe_note/2`) or `"batch"` (compare) is sent as the multipart field
    `priority`; call chunks send none (`normal`), except a fast session's (`RISI_LIVE_FAST`,
    `transcribe_fast/2`: `live`). The service's priority gate serves `live` first; an older
    service ignores the field.
  * **Fallback** (incident 2026-10-14): `fallback_transcribe/2` asks the fast CPU model
    (omniASR-CTC-1B int8) by name; `Speech.transcribe_note/2` uses it after a timeout.
  * **Language hint** (decision 071 amendment d): `opts[:language_hint]` (`["si", "en"]`) is
    sent as the multipart field `language_hint=si,en` on transcribe and compare (and the
    per-model fallback); omitted when empty.

  Nothing about the audio or the text is logged.
  """
  @behaviour RisiMe.Agent.Speech

  require Logger

  alias RisiMe.Agent.LLM.Local
  alias RisiMe.Agent.Speech

  @timeout_ms 30_000
  @health_ttl_ms 15_000
  @no_auto_ttl_ms 300_000
  @default_url "http://127.0.0.1:8200"

  @impl true
  def healthy? do
    now = System.monotonic_time(:millisecond)

    case :persistent_term.get({__MODULE__, :health}, nil) do
      {at, ok?} when now - at < @health_ttl_ms ->
        ok?

      _ ->
        ok? = probe()
        :persistent_term.put({__MODULE__, :health}, {now, ok?})
        ok?
    end
  end

  @doc false
  def reset_health, do: :persistent_term.erase({__MODULE__, :health})

  defp probe do
    with {:ok, base} <- base_url() do
      case Req.get(req(base <> "/health", 2_000)) do
        {:ok, %{status: 200}} -> true
        _ -> false
      end
    else
      _ -> false
    end
  end

  @impl true
  def transcribe(audio, opts \\ []) when is_binary(audio) do
    # Decision 089: an admin's manual model for the chunk's language (Auto: no I/O, unchanged).
    case RisiMe.Agent.SpeechRoutes.pick(audio, opts) do
      # A per-user "show nothing" (SpeechCompare's route decision): no model is good enough yet.
      {:manual, "none", lang} ->
        {:ok,
         %{
           text: "",
           language: lang || "si",
           model: "none",
           elapsed_ms: 0,
           speech: true,
           romanised: false,
           language_probability: nil,
           script: nil,
           segments: [],
           rejected: "route_none"
         }}

      {:manual, model, lang} ->
        case post_transcription(audio, model, Keyword.put(opts, :language, lang)) do
          {:error, e} when e in [:not_served, :failed] -> transcribe_auto(audio, opts)
          other -> other
        end

      :auto ->
        if opts[:fast], do: transcribe_fast(audio, opts), else: transcribe_auto(audio, opts)
    end
  end

  @fast_en_default "faster-whisper-large-v3-turbo"
  @fast_backoff_ms 300_000

  @doc """
  Proposal 2026-10-15-live-call-latency (`RISI_LIVE_FAST`): a live call chunk (`opts[:fast]`,
  sent at `priority: "live"`). While the fast English model (`fast_en_model/0`) can be served:
  the language is detected first (`/v1/audio/detect`, with the call's hint), an English chunk
  goes to that model with `language=en`, and Sinhala and Tamil keep their current route
  (`model=auto` with the detected `language`, so the service doesn't detect twice). When the
  service can't load it (`503`: not enough memory; `404`: not served), every chunk goes to
  `model=auto` as before (one request) for 5 min before the model is tried again.

  v1.50 §36.12: `opts[:defer_langs]` (`["si", "ta"]` in a fast session) are detected only: the
  result is `speech: true, deferred: true` with the language and no text, and the caller
  transcribes the speaker's joined chunks later (`language` forced, one request).
  """
  def transcribe_fast(audio, opts) do
    en = if fast_backoff?(), do: nil, else: fast_en_model()
    defer = Keyword.get(opts, :defer_langs, [])

    if en == nil and defer == [] do
      transcribe_auto(audio, opts)
    else
      case detect_routed(audio, opts) do
        {:ok, %{"reason" => "no_speech"}} ->
          {:ok, no_speech()}

        {:ok, %{} = d} ->
          lang = if d["language"] in ["si", "ta", "en"], do: d["language"]
          p = d["probability"]

          r =
            cond do
              # v1.50 §36.12: detected only; the caller joins it with the speaker's next chunks.
              lang != nil and lang in defer ->
                {:ok,
                 %{
                   no_speech()
                   | speech: true,
                     language: lang,
                     model: Speech.route(lang),
                     language_probability: p
                 }
                 |> Map.put(:deferred, true)}

              lang == "en" and en != nil ->
                fast_en(audio, en, opts)

              true ->
                post_transcription(audio, "auto", Keyword.put(opts, :language, lang))
            end

          with {:ok, res} <- r,
               do: {:ok, %{res | language_probability: res.language_probability || p}}

        {:error, :not_served} ->
          transcribe_auto(audio, opts)

        {:error, _} = e ->
          e
      end
    end
  end

  defp fast_en(audio, en, opts) do
    case post_transcription(audio, en, Keyword.put(opts, :language, "en")) do
      {:error, e} when e in [:unavailable, :not_served] ->
        :persistent_term.put({__MODULE__, :fast_backoff}, System.monotonic_time(:millisecond))
        Logger.info("speech: fast English model unavailable (#{e}), auto route for 5 min")
        post_transcription(audio, "auto", Keyword.put(opts, :language, "en"))

      other ->
        other
    end
  end

  @doc "The fast English model alias (`RISI_SPEECH_FAST_EN`; `\"\"` or `\"none\"`: none)."
  def fast_en_model do
    case Keyword.get(config(), :fast_en_model, System.get_env("RISI_SPEECH_FAST_EN")) do
      nil -> @fast_en_default
      v when v in ["", "none"] -> nil
      v -> v
    end
  end

  defp fast_backoff? do
    case :persistent_term.get({__MODULE__, :fast_backoff}, nil) do
      nil -> false
      at -> System.monotonic_time(:millisecond) - at < @fast_backoff_ms
    end
  end

  @doc false
  def reset_fast_backoff, do: :persistent_term.erase({__MODULE__, :fast_backoff})

  defp no_speech do
    %{
      text: "",
      language: "en",
      model: Speech.route("en"),
      elapsed_ms: 0,
      speech: false,
      romanised: false,
      language_probability: nil,
      script: nil,
      segments: [],
      avg_logprob: nil,
      no_speech_prob: nil
    }
  end

  # The routing decision with the call's hint (the same decision `model=auto` makes).
  defp detect_routed(audio, opts) do
    with {:ok, base} <- base_url() do
      fields =
        [file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"}]
        |> with_hint(Keyword.delete(opts, :hotwords))
        |> with_priority(opts)

      case Req.post(req(base <> "/v1/audio/detect", Keyword.get(opts, :timeout, @timeout_ms)),
             form_multipart: fields
           ) do
        {:ok, %{status: 200, body: %{} = b}} -> {:ok, b}
        {:ok, %{status: 404}} -> {:error, :not_served}
        {:ok, %{status: s}} when s in [429, 503] -> {:error, :unavailable}
        {:ok, %{status: _}} -> {:error, :failed}
        {:error, %{reason: :timeout}} -> {:error, :timeout}
        {:error, _} -> {:error, :unavailable}
      end
    else
      _ -> {:error, :unavailable}
    end
  end

  defp transcribe_auto(audio, opts) do
    model = if no_auto?(), do: Speech.route("en"), else: "auto"

    case post_transcription(audio, model, opts) do
      {:error, :not_served} when model == "auto" ->
        :persistent_term.put({__MODULE__, :no_auto}, System.monotonic_time(:millisecond))
        post_transcription(audio, Speech.route("en"), opts)

      {:error, :not_served} ->
        {:error, :failed}

      other ->
        other
    end
  end

  defp no_auto? do
    case :persistent_term.get({__MODULE__, :no_auto}, nil) do
      nil -> false
      at -> System.monotonic_time(:millisecond) - at < @no_auto_ttl_ms
    end
  end

  defp post_transcription(audio, model, opts) do
    with {:ok, base} <- base_url() do
      t0 = System.monotonic_time(:millisecond)
      timeout = Keyword.get(opts, :timeout, @timeout_ms)

      fields = [
        file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"},
        model: model,
        response_format: "verbose_json"
      ]

      fields = fields |> with_hint(opts) |> with_priority(opts)

      fields =
        if is_binary(opts[:language]), do: fields ++ [language: opts[:language]], else: fields

      case Req.post(req(base <> "/v1/audio/transcriptions", timeout), form_multipart: fields) do
        {:ok, %{status: 200, body: %{} = b}} ->
          {:ok, result(b, model, System.monotonic_time(:millisecond) - t0)}

        {:ok, %{status: 404}} ->
          {:error, :not_served}

        {:ok, %{status: s}} when s in [429, 503] ->
          {:error, :unavailable}

        {:ok, %{status: _}} ->
          {:error, :failed}

        {:error, %{reason: :timeout}} ->
          {:error, :timeout}

        {:error, _} ->
          {:error, :unavailable}
      end
    else
      _ -> {:error, :unavailable}
    end
  end

  defp result(b, asked, elapsed) do
    text = (b["text"] || "") |> to_string() |> String.trim()
    language = Speech.lang(b["language"])

    model =
      case b["model"] do
        m when is_binary(m) and m != "" and m != "auto" -> m
        _ -> if asked == "auto", do: Speech.route(language), else: asked
      end

    speech =
      text != "" and
        case b["duration_after_vad"] do
          d when is_number(d) -> d > 0
          _ -> true
        end

    %{
      text: text,
      language: language,
      model: model,
      elapsed_ms: ms(b["processing_s"], elapsed),
      speech: speech,
      # v1.40 §36.11: the final text is still not in Sinhala script after the service's retry.
      romanised: b["romanised"] == true,
      # v1.41 §37.6: the routing's detection probability (else the model's), and the script
      # check of a Sinhala text (nil otherwise), for `voice_transcript`.
      language_probability: probability(b),
      script: script(b["script"]),
      # v1.42 §38.3: the segments' offsets in the audio (an item's `offset_ms`).
      segments: segments(b["segments"]),
      # The hallucination guard (`RisiMe.Agent.SpeechQuality`): Whisper's own confidences.
      avg_logprob: seg_mean(b["segments"], "avg_logprob"),
      no_speech_prob: seg_max(b["segments"], "no_speech_prob")
    }
  end

  defp seg_values(list, key) when is_list(list),
    do: for(%{^key => v} <- list, is_number(v), do: v)

  defp seg_values(_, _), do: []

  defp seg_mean(list, key) do
    case seg_values(list, key) do
      [] -> nil
      vs -> Float.round(Enum.sum(vs) / length(vs), 4)
    end
  end

  defp seg_max(list, key) do
    case seg_values(list, key) do
      [] -> nil
      vs -> Enum.max(vs)
    end
  end

  defp segments(list) when is_list(list) do
    for %{"start" => s, "text" => t} <- list,
        is_number(s),
        is_binary(t),
        String.trim(t) != "",
        do: %{offset_ms: round(s * 1000), text: String.trim(t)}
  end

  defp segments(_), do: []

  defp probability(%{"routing" => %{"probability" => p}}) when is_number(p), do: p
  defp probability(%{"language_probability" => p}) when is_number(p), do: p
  defp probability(_), do: nil

  # v1.40 §36.11: the service's Sinhala script check of one compare result (absent: nil).
  defp script(%{"expected" => e, "sinhala_chars" => si, "latin_chars" => la, "ok" => ok})
       when is_binary(e) and is_integer(si) and is_integer(la) and is_boolean(ok),
       do: %{expected: e, sinhala_chars: si, latin_chars: la, ok: ok}

  defp script(_), do: nil

  defp ms(s, _fallback) when is_number(s), do: round(s * 1000)
  defp ms(_, fallback), do: fallback

  @impl true
  def compare(audio, opts \\ []) when is_binary(audio) do
    case compare_main(audio, opts) do
      {:ok, c} -> {:ok, with_extra(c, audio, opts)}
      other -> other
    end
  end

  defp compare_main(audio, opts) do
    with {:ok, base} <- base_url() do
      timeout = Keyword.get(opts, :timeout, @timeout_ms)
      # All three models may run one after another on the service.
      fields =
        [file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"}]
        |> with_hint(opts)
        |> with_priority(priority: "batch")

      case Req.post(req(base <> "/v1/audio/compare", timeout * 3 + 5_000),
             form_multipart: fields
           ) do
        {:ok, %{status: 200, body: %{} = b}} ->
          {:ok, compare_result(b)}

        {:ok, %{status: 404}} ->
          compare_each(audio, timeout, opts)

        {:ok, %{status: s}} when s in [429, 503] ->
          {:error, :unavailable}

        {:ok, _} ->
          {:error, :failed}

        {:error, %{reason: :timeout}} ->
          {:error, :timeout}

        {:error, _} ->
          {:error, :unavailable}
      end
    else
      _ -> {:error, :unavailable}
    end
  end

  defp compare_result(b) do
    results = compare_results(b)

    detected =
      Speech.lang(
        get_in(b, ["detected", "language"]) || b["language_detected"] || b["language"] ||
          Enum.find_value(results, &(&1.model == Speech.route("en") && &1.language))
      )

    %{
      language_detected: detected,
      chosen: b["routed_model"] || b["chosen"] || Speech.route(detected),
      results: results
    }
  end

  defp compare_results(b) do
    (b["results"] || [])
    |> Enum.filter(&is_map/1)
    |> Enum.map(fn r ->
      error =
        case r["error"] do
          nil -> nil
          "timeout" -> "timeout"
          _ -> "failed"
        end

      %{
        model: to_string(r["model"]),
        text: if(error, do: nil, else: r["text"] && String.trim(to_string(r["text"]))),
        language: if(error, do: nil, else: r["language"]),
        elapsed_ms: ms(r["processing_s"], r["elapsed_ms"] || 0),
        error: error,
        script: if(error, do: nil, else: script(r["script"])),
        romanised: if(error || not is_boolean(r["romanised"]), do: nil, else: r["romanised"])
      }
    end)
  end

  # The extra compare-only candidates (moduledoc), appended after the speech service's own.
  defp with_extra(c, audio, opts) do
    case extra() do
      {:ok, base, models} ->
        models = Keyword.get(opts, :extra_models, models)
        models = Enum.reject(models, fn m -> Enum.any?(c.results, &(&1.model == m)) end)
        %{c | results: c.results ++ extra_results(base, models, c, audio, opts)}

      :off ->
        c
    end
  end

  defp extra_results(_base, [], _c, _audio, _opts), do: []

  defp extra_results(base, models, c, audio, _opts) do
    timeout = extra_timeout()

    fields = [
      file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"},
      language: c.language_detected,
      models: Enum.join(models, ",")
    ]

    case Req.post(req(base <> "/v1/audio/compare", timeout * length(models) + 5_000),
           form_multipart: fields
         ) do
      {:ok, %{status: 200, body: %{} = b}} -> compare_results(b)
      {:error, %{reason: :timeout}} -> Enum.map(models, &missing(&1, "timeout", timeout))
      _ -> Enum.map(models, &missing(&1, "failed", 0))
    end
  end

  defp missing(model, error, ms),
    do: %{model: model, text: nil, language: nil, elapsed_ms: ms, error: error}

  @doc """
  The models the extra compare service serves: `{:ok, [%{id, streamed}]}`, or
  `{:error, :off | :unavailable}`. Compare-only: never a production route.
  """
  def extra_models do
    with {:ok, base, _} <- extra(),
         {:ok, %{status: 200, body: %{"data" => data}}} when is_list(data) <-
           Req.get(req(base <> "/v1/models", 5_000)) do
      {:ok,
       for(%{"id" => id} = m <- data, do: %{id: to_string(id), streamed: m["streamed"] == true})}
    else
      :off -> {:error, :off}
      _ -> {:error, :unavailable}
    end
  end

  @doc "The default extra compare models (configured list)."
  def extra_default_models do
    case extra() do
      {:ok, _, models} -> models
      :off -> []
    end
  end

  defp extra_timeout do
    case config()[:compare_extra_timeout_ms] ||
           System.get_env("RISI_SPEECH_COMPARE_EXTRA_TIMEOUT_MS") do
      ms when is_integer(ms) and ms > 0 ->
        ms

      ms when is_binary(ms) ->
        case Integer.parse(ms) do
          {n, ""} when n > 0 -> n
          _ -> 180_000
        end

      _ ->
        180_000
    end
  end

  defp extra do
    cfg = config()

    url =
      if Keyword.has_key?(cfg, :compare_extra_url),
        do: cfg[:compare_extra_url],
        else: System.get_env("RISI_SPEECH_COMPARE_EXTRA_URL")

    models =
      (cfg[:compare_extra_models] || System.get_env("RISI_SPEECH_COMPARE_EXTRA_MODELS") ||
         "omniasr-llm-1b")
      |> List.wrap()
      |> Enum.flat_map(&String.split(&1, [",", " "], trim: true))

    with true <- is_binary(url) and url != "" and models != [],
         {:ok, base} <- Local.check_url(url) do
      {:ok, base, models}
    else
      _ -> :off
    end
  end

  # A service without `/v1/audio/compare`: each model by itself, in parallel.
  defp compare_each(audio, timeout, opts) do
    hint = Keyword.take(opts, [:language_hint])

    results =
      Speech.models()
      |> Task.async_stream(
        fn m ->
          t0 = System.monotonic_time(:millisecond)

          case post_transcription(audio, m, [timeout: timeout] ++ hint) do
            {:ok, r} ->
              %{
                model: m,
                text: r.text,
                language: r.language,
                elapsed_ms: r.elapsed_ms,
                error: nil
              }

            {:error, :timeout} ->
              %{model: m, text: nil, language: nil, elapsed_ms: timeout, error: "timeout"}

            {:error, _} ->
              elapsed = System.monotonic_time(:millisecond) - t0
              %{model: m, text: nil, language: nil, elapsed_ms: elapsed, error: "failed"}
          end
        end,
        timeout: timeout + 5_000,
        on_timeout: :kill_task
      )
      |> Enum.zip(Speech.models())
      |> Enum.map(fn
        {{:ok, r}, _m} ->
          r

        {{:exit, _}, m} ->
          %{model: m, text: nil, language: nil, elapsed_ms: timeout, error: "timeout"}
      end)

    if Enum.all?(results, &(&1.error != nil)) do
      {:error, :unavailable}
    else
      detected =
        Speech.lang(Enum.find_value(results, &(&1.model == Speech.route("en") && &1.language)))

      {:ok, %{language_detected: detected, chosen: Speech.route(detected), results: results}}
    end
  end

  # Incident 2026-10-14: `live` (a voice note), none (call chunks: `normal`) or `batch` (compare,
  # evals). A service with the priority gate (infra/whisper) serves `live` first; an older one
  # ignores the field.
  defp with_priority(fields, opts) do
    case opts[:priority] do
      p when p in ["live", "batch"] -> fields ++ [priority: p]
      _ -> fields
    end
  end

  @doc """
  The service's language detection only (`POST /v1/audio/detect`, no hint: large-v3's own
  probabilities, incident 2026-10-14-tamil-script): `{:ok, body}` (`probs`, `raw_probs`,
  `whisper_top`, `reason`) or `{:error, :unavailable}`. `opts[:timeout]` (default 5 s),
  `opts[:priority]`.
  """
  @impl true
  def detect(audio, opts \\ []) when is_binary(audio) do
    fields =
      with_priority([file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"}], opts)

    with {:ok, base} <- base_url(),
         {:ok, %{status: 200, body: %{} = b}} <-
           Req.post(req(base <> "/v1/audio/detect", Keyword.get(opts, :timeout, 5_000)),
             form_multipart: fields
           ) do
      {:ok, b}
    else
      _ -> {:error, :unavailable}
    end
  end

  @doc """
  The fast fallback (incident 2026-10-14): `fallback_model` (else `RISI_SPEECH_FALLBACK_MODEL`,
  default `omnilingual-ctc-1b`: omniASR-CTC-1B, ONNX int8 on the CPU) by name, no language, no
  hint; `opts[:timeout]` (default 10 s), `priority` live.
  """
  @impl true
  def fallback_transcribe(audio, opts \\ []) when is_binary(audio) do
    model =
      config()[:fallback_model] || System.get_env("RISI_SPEECH_FALLBACK_MODEL") ||
        "omnilingual-ctc-1b"

    case post_transcription(audio, model,
           timeout: Keyword.get(opts, :timeout, 10_000),
           priority: Keyword.get(opts, :priority, "live")
         ) do
      {:error, :not_served} -> {:error, :unavailable}
      other -> other
    end
  end

  @doc """
  One model by name on the speech service (Harsha's Sinhala compare mode): `opts` `language`,
  `timeout`, `priority` (default batch). A 503 (no memory) is `{:error, :unavailable}`.
  """
  def transcribe_model(audio, model, opts \\ []) when is_binary(audio) and is_binary(model) do
    case post_transcription(audio, model, Keyword.put_new(opts, :priority, "batch")) do
      {:error, :not_served} -> {:error, :not_served}
      other -> other
    end
  end

  @doc """
  One model on the extra compare service (omniASR, `compare_extra_url`): `{:ok, result}`,
  `{:error, :off}` when none is configured, or an error.
  """
  def extra_transcribe(audio, model, opts \\ []) when is_binary(audio) do
    case extra() do
      {:ok, base, _} ->
        fields =
          [
            file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"},
            model: model,
            response_format: "verbose_json"
          ] ++ if(is_binary(opts[:language]), do: [language: opts[:language]], else: [])

        t0 = System.monotonic_time(:millisecond)
        timeout = Keyword.get(opts, :timeout, extra_timeout())

        case Req.post(req(base <> "/v1/audio/transcriptions", timeout), form_multipart: fields) do
          {:ok, %{status: 200, body: %{} = b}} ->
            {:ok, result(b, model, System.monotonic_time(:millisecond) - t0)}

          {:ok, %{status: 503}} ->
            {:error, :unavailable}

          {:error, %{reason: :timeout}} ->
            {:error, :timeout}

          _ ->
            {:error, :failed}
        end

      :off ->
        {:error, :off}
    end
  end

  defp with_hint(fields, opts) do
    fields =
      case Speech.hint_field(opts) do
        nil -> fields
        hint -> fields ++ [language_hint: hint]
      end

    # Proposal 2026-10-14-send-for-me §42.6: names to spell right (Risi, the sender's own
    # contacts in their Risi chat); an older service ignores the field. Never logged.
    case Speech.hotwords_field(opts) do
      nil -> fields
      words -> fields ++ [hotwords: words]
    end
  end

  defp req(url, timeout) do
    Req.new(
      [
        url: url,
        receive_timeout: timeout,
        connect_options: [timeout: 2_000],
        retry: false,
        redirect: false,
        headers: auth_headers()
      ] ++ (config()[:req_options] || [])
    )
  end

  defp base_url, do: Local.check_url(config()[:url] || @default_url)

  defp config, do: Application.get_env(:risime, :risi_speech_http, [])

  defp auth_headers do
    case config()[:api_key] do
      k when is_binary(k) and k != "" -> [{"authorization", "Bearer " <> k}]
      _ -> []
    end
  end
end
