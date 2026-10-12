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
  * **Health:** `GET /health`, cached 15 s.

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
      romanised: b["romanised"] == true
    }
  end

  # v1.40 §36.11: the service's Sinhala script check of one compare result (absent: nil).
  defp script(%{"expected" => e, "sinhala_chars" => si, "latin_chars" => la, "ok" => ok})
       when is_binary(e) and is_integer(si) and is_integer(la) and is_boolean(ok),
       do: %{expected: e, sinhala_chars: si, latin_chars: la, ok: ok}

  defp script(_), do: nil

  defp ms(s, _fallback) when is_number(s), do: round(s * 1000)
  defp ms(_, fallback), do: fallback

  @impl true
  def compare(audio, opts \\ []) when is_binary(audio) do
    with {:ok, base} <- base_url() do
      timeout = Keyword.get(opts, :timeout, @timeout_ms)
      # All three models may run one after another on the service.
      fields = [file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"}]

      case Req.post(req(base <> "/v1/audio/compare", timeout * 3 + 5_000),
             form_multipart: fields
           ) do
        {:ok, %{status: 200, body: %{} = b}} ->
          {:ok, compare_result(b)}

        {:ok, %{status: 404}} ->
          compare_each(audio, timeout)

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
    results =
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

  # A service without `/v1/audio/compare`: each model by itself, in parallel.
  defp compare_each(audio, timeout) do
    results =
      Speech.models()
      |> Task.async_stream(
        fn m ->
          t0 = System.monotonic_time(:millisecond)

          case post_transcription(audio, m, timeout: timeout) do
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
