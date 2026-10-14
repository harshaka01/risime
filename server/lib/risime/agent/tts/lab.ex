defmodule RisiMe.Agent.TTS.Lab do
  @moduledoc """
  The client of `risime-tts-lab` (`infra/docker-compose.tts-lab.yml`, loopback only,
  `RISI_TTS_LAB_URL`, default `http://127.0.0.1:8231`): the TTS candidates Harsha compares on the
  admin console's Voices page (2026-10-14): Chatterbox, IndicF5, Indic Parler-TTS and Orpheus.
  **Console tests only:** Call Risi keeps using Kokoro (`RisiMe.Agent.TTS.Kokoro`, its own
  resident container), and nothing here is on a call path.

  The service loads one engine on demand, unloads it when idle, refuses a load that would take the
  host's MemAvailable below its floor, and pauses while live speech needs the machine. On this
  side, while a Call Risi call is running a console test is refused (`:live_call`): live calls
  first.

  Each engine is its own module (`use RisiMe.Agent.TTS.Lab, id: "chatterbox", name: "Chatterbox"`)
  so it is registered separately in `:risi_tts_engines`. `voices/0` is `{:ok, voices}` only when
  the engine can run; `{:error, :downloading}` while its weights have not arrived,
  `{:error, :review_pending}` while its shipped code is not cleared (IndicF5), else
  `{:error, :unavailable}`. The text is never logged; the audio stays in memory.
  """

  @doc false
  defmacro __using__(opts) do
    id = Keyword.fetch!(opts, :id)
    name = Keyword.fetch!(opts, :name)

    quote do
      @behaviour RisiMe.Agent.TTS

      @impl true
      def engine, do: %{id: unquote(id), name: unquote(name)}

      @impl true
      def voices, do: RisiMe.Agent.TTS.Lab.voices(unquote(id))

      @impl true
      def synthesize(text, voice_id, opts \\ []),
        do: RisiMe.Agent.TTS.Lab.synthesize(unquote(id), text, voice_id, opts)
    end
  end

  @statuses %{
    "ready" => :ok,
    "loaded" => :ok,
    "downloading" => :downloading,
    "review_pending" => :review_pending
  }

  @doc "The engine `id`'s voices, by the service's own status (see the moduledoc)."
  def voices(id) do
    with {:ok, engines} <- engines(),
         %{} = e <- Enum.find(engines, &(&1["id"] == id)) do
      case Map.get(@statuses, e["status"], :unavailable) do
        :ok -> {:ok, parse_voices(e["voices"])}
        other -> {:error, other}
      end
    else
      _ -> {:error, :unavailable}
    end
  end

  defp parse_voices(vs) when is_list(vs) do
    for %{"id" => id} = v <- vs, is_binary(id) do
      %{id: id, name: v["name"] || id, languages: List.wrap(v["languages"])}
    end
  end

  defp parse_voices(_), do: []

  @doc "The service's engine list (`GET /v1/engines`), or `{:error, :unavailable}`."
  def engines do
    with {:ok, base} <- base_url(),
         {:ok, %{status: 200, body: %{"engines" => es}}} when is_list(es) <-
           Req.get(req(base <> "/v1/engines", 3_000)) do
      {:ok, es}
    else
      _ -> {:error, :unavailable}
    end
  end

  @doc """
  Speaks `text` with `voice_id` (one WAV): `{:ok, %{audio, content_type, first_audio_ms,
  total_ms, load_ms, audio_ms}}`. `first_audio_ms` is the time to the first sentence's audio after
  the model is loaded; `load_ms` the load this request caused (0 when it was resident). Refused
  with `{:error, :live_call}` while a Call Risi call is running.
  """
  def synthesize(id, text, voice_id, opts \\ []) when is_binary(text) and is_binary(voice_id) do
    cond do
      not String.starts_with?(voice_id, id <> ":") ->
        {:error, :failed}

      live_calls() > 0 ->
        {:error, :live_call}

      true ->
        post(text, voice_id, opts)
    end
  end

  defp post(text, voice_id, opts) do
    with {:ok, base} <- base_url() do
      body = %{"text" => text, "voice" => voice_id}
      body = if l = opts[:lang], do: Map.put(body, "lang", l), else: body
      t0 = System.monotonic_time(:millisecond)

      case Req.post(req(base <> "/v1/tts", Keyword.get(opts, :timeout, 180_000)),
             json: body,
             decode_body: false
           ) do
        {:ok, %{status: 200, body: wav} = resp} when is_binary(wav) ->
          total = System.monotonic_time(:millisecond) - t0

          {:ok,
           %{
             audio: wav,
             content_type: "audio/wav",
             first_audio_ms: header_int(resp, "x-first-audio-ms") || total,
             total_ms: total,
             load_ms: header_int(resp, "x-load-ms") || 0,
             audio_ms: header_int(resp, "x-audio-ms")
           }}

        {:ok, %{status: status, body: b}} ->
          {:error, error_of(status, b)}

        {:error, %{reason: :timeout}} ->
          {:error, :timeout}

        {:error, _} ->
          {:error, :unavailable}
      end
    end
  end

  @errors %{
    "no_memory" => :no_memory,
    "paused" => :paused,
    "busy" => :busy,
    "downloading" => :downloading,
    "review_pending" => :review_pending
  }

  defp error_of(status, body) do
    code =
      case body do
        b when is_binary(b) ->
          case Jason.decode(b) do
            {:ok, %{"error" => e}} -> e
            _ -> nil
          end

        %{"error" => e} ->
          e

        _ ->
          nil
      end

    Map.get(@errors, code) || if(status == 503, do: :unavailable, else: :failed)
  end

  defp header_int(resp, name) do
    with [v | _] <- Req.Response.get_header(resp, name),
         {n, ""} <- Integer.parse(v) do
      n
    else
      _ -> nil
    end
  end

  defp live_calls do
    case Application.get_env(:risime, :risi_tts_live_calls) do
      f when is_function(f, 0) -> f.()
      _ -> RisiMe.Agent.CallRisi.running()
    end
  end

  @doc "The service URL (loopback only) or `{:error, :unavailable}`."
  def base_url do
    case RisiMe.Agent.LLM.Local.check_url(Keyword.get(config(), :url, "http://127.0.0.1:8231")) do
      {:ok, u} -> {:ok, u}
      _ -> {:error, :unavailable}
    end
  end

  defp config, do: Application.get_env(:risime, :risi_tts_lab, [])

  defp req(url, timeout) do
    headers =
      case RisiMe.Agent.TTS.Kokoro.api_key() do
        k when is_binary(k) and k != "" -> [{"authorization", "Bearer " <> k}]
        _ -> []
      end

    Req.new(
      [
        url: url,
        headers: headers,
        receive_timeout: timeout,
        connect_options: [timeout: 2_000],
        retry: false,
        redirect: false
      ] ++ Keyword.get(config(), :req_options, [])
    )
  end
end

defmodule RisiMe.Agent.TTS.Chatterbox do
  @moduledoc "Chatterbox (Resemble AI, MIT; PerTh watermark) through `RisiMe.Agent.TTS.Lab`."
  use RisiMe.Agent.TTS.Lab, id: "chatterbox", name: "Chatterbox"
end

defmodule RisiMe.Agent.TTS.IndicF5 do
  @moduledoc "IndicF5 (AI4Bharat, MIT; reviewed custom code) through `RisiMe.Agent.TTS.Lab`."
  use RisiMe.Agent.TTS.Lab, id: "indicf5", name: "IndicF5"
end

defmodule RisiMe.Agent.TTS.IndicParler do
  @moduledoc "Indic Parler-TTS (AI4Bharat, Apache-2.0) through `RisiMe.Agent.TTS.Lab`."
  use RisiMe.Agent.TTS.Lab, id: "indic-parler", name: "Indic Parler-TTS"
end

defmodule RisiMe.Agent.TTS.Orpheus do
  @moduledoc "Orpheus 3B (Canopy Labs; Apache-2.0 + Llama 3.2 licence) through `RisiMe.Agent.TTS.Lab`."
  use RisiMe.Agent.TTS.Lab, id: "orpheus", name: "Orpheus"
end
