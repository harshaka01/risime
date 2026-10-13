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
    `omniasr-llm-1b`), and its results are appended (same §36.11 result shape; `chosen` and
    `language_detected` stay the speech service's). Its failure or timeout only marks those
    models `failed` / `timeout`. Off by default; `transcribe/1,2` never uses it.
  * **Health:** `GET /health`, cached 15 s.
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
      {:manual, model, lang} ->
        case post_transcription(audio, model, Keyword.put(opts, :language, lang)) do
          {:error, e} when e in [:not_served, :failed] -> transcribe_auto(audio, opts)
          other -> other
        end

      :auto ->
        transcribe_auto(audio, opts)
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

      fields = with_hint(fields, opts)

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
      segments: segments(b["segments"])
    }
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
        with_hint([file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"}], opts)

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
        models = Enum.reject(models, fn m -> Enum.any?(c.results, &(&1.model == m)) end)
        %{c | results: c.results ++ extra_results(base, models, c, audio, opts)}

      :off ->
        c
    end
  end

  defp extra_results(_base, [], _c, _audio, _opts), do: []

  defp extra_results(base, models, c, audio, opts) do
    timeout = Keyword.get(opts, :timeout, @timeout_ms)

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

  defp with_hint(fields, opts) do
    case Speech.hint_field(opts) do
      nil -> fields
      hint -> fields ++ [language_hint: hint]
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
