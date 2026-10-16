defmodule RisiMe.Agent.TTS.Kokoro do
  @moduledoc """
  The Kokoro-82M engine of `RisiMe.Agent.TTS` (decision 090): the local `risime-tts` service
  (`infra/docker-compose.tts.yml`, loopback only, `RISI_TTS_URL`, default `http://127.0.0.1:8230`).

  English voices only in v1 (`kokoro:af_heart`, …; languages `["en"]`). `synthesize/3` asks the
  service's streaming endpoint and returns one WAV (24 kHz mono s16) with the time to the first
  audio chunk. The text is never logged; the audio stays in memory. During a call the media worker
  streams from the same service directly (`RisiMe.Agent.CallRisi`), so audio never crosses the BEAM.
  """
  @behaviour RisiMe.Agent.TTS

  @default_voice "kokoro:af_heart"
  @sr 24_000

  @impl true
  def engine, do: %{id: "kokoro", name: "Kokoro"}

  @doc "The voice Call Risi uses on Auto (`TTS.voice_for/1` = `:auto`)."
  def default_voice, do: @default_voice

  @impl true
  def voices do
    with {:ok, base} <- base_url(),
         {:ok, %{status: 200, body: %{"voices" => vs}}} when is_list(vs) <-
           Req.get(req(base <> "/v1/voices", 3_000)) do
      voices =
        for %{"id" => id, "name" => name} = v <- vs,
            is_binary(id),
            do: %{id: id, name: name, languages: List.wrap(v["languages"])}

      {:ok, voices}
    else
      _ -> {:error, :unavailable}
    end
  end

  @impl true
  def synthesize(text, voice_id, opts \\ []) when is_binary(text) and is_binary(voice_id) do
    with {:ok, base} <- base_url() do
      t0 = System.monotonic_time(:millisecond)
      parent = self()
      ref = make_ref()

      into = fn {:data, data}, {req, resp} ->
        if Process.get(ref) == nil do
          Process.put(ref, true)
          send(parent, {ref, :first, System.monotonic_time(:millisecond)})
        end

        # Newest chunk first (reversed below). Not `update_private/4`: it stores its default without
        # the chunk, so the first chunk was lost and later ones made an improper list.
        {:cont,
         {req,
          Req.Response.put_private(resp, :pcm, [data | Req.Response.get_private(resp, :pcm, [])])}}
      end

      body = %{"text" => text, "voice" => voice_id, "speed" => Keyword.get(opts, :speed, 1.0)}
      timeout = Keyword.get(opts, :timeout, 30_000)

      case Req.post(req(base <> "/v1/tts", timeout), json: body, into: into) do
        {:ok, %{status: 200} = resp} ->
          pcm = resp.private |> Map.get(:pcm, []) |> Enum.reverse() |> IO.iodata_to_binary()
          first = receive_first(ref, t0)
          total = System.monotonic_time(:millisecond) - t0

          {:ok,
           %{audio: wav(pcm), content_type: "audio/wav", first_audio_ms: first, total_ms: total}}

        {:ok, _} ->
          {:error, :failed}

        {:error, %{reason: :timeout}} ->
          {:error, :timeout}

        {:error, _} ->
          {:error, :unavailable}
      end
    end
  end

  @doc "True when the service answers `/health` with ok."
  def healthy? do
    with {:ok, base} <- base_url(),
         {:ok, %{status: 200, body: %{"ok" => true}}} <- Req.get(req(base <> "/health", 2_000)) do
      true
    else
      _ -> false
    end
  end

  defp receive_first(ref, t0) do
    receive do
      {^ref, :first, at} -> at - t0
    after
      0 -> System.monotonic_time(:millisecond) - t0
    end
  end

  @doc false
  def wav(pcm) do
    size = byte_size(pcm)

    <<"RIFF", 36 + size::little-32, "WAVEfmt ", 16::little-32, 1::little-16, 1::little-16,
      @sr::little-32, @sr * 2::little-32, 2::little-16, 16::little-16, "data", size::little-32,
      pcm::binary>>
  end

  @doc "The service URL (loopback only) or `{:error, :unavailable}`."
  def base_url do
    url = Keyword.get(config(), :tts_url, "http://127.0.0.1:8230")

    case RisiMe.Agent.LLM.Local.check_url(url) do
      {:ok, u} -> {:ok, u}
      _ -> {:error, :unavailable}
    end
  end

  @doc "The Bearer key for the service, or nil."
  def api_key, do: Keyword.get(config(), :tts_key)

  defp config, do: Application.get_env(:risime, :risi_call_risi_config, [])

  defp req(url, timeout) do
    headers =
      case api_key() do
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
