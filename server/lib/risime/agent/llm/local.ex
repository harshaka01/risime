defmodule RisiMe.Agent.LLM.Local do
  @moduledoc """
  The `risi_l1` provider (decision 061): the self-hosted vLLM, OpenAI-compatible, on loopback.

  * **Base URL** from `config :risime, :risi_llm, url:` (`RISI_LLM_URL`, default
    `http://127.0.0.1:8100/v1`). Any host that isn't loopback (`127.0.0.0/8`, `::1`,
    `localhost`) is refused before a socket is opened (`{:error, :non_loopback}`), and redirects
    are never followed, so no chat text can leave the host through this module (§24.12).
  * `Authorization: Bearer $LLM_API_KEY` when the key is set (never logged).
  * One request per call; the router (`RisiMe.Agent.LLM`) retries once. 60-s receive timeout.
  * The real model id behind the alias comes from `GET /models` (`root`), cached for 10 min.

  Nothing here logs a request or a response body.
  """
  @behaviour RisiMe.Agent.LLM

  @default_url "http://127.0.0.1:8100/v1"
  @model_ttl_ms 600_000

  @impl true
  def name, do: "risi_l1"

  @impl true
  def chat(body, opts \\ []) do
    # Decision 084: a caller may name another loopback endpoint (checked the same way).
    base_result =
      case Keyword.get(opts, :base_url) do
        nil -> base_url()
        url -> check_url(url)
      end

    with {:ok, base} <- base_result do
      case Keyword.get(opts, :on_partial) do
        f when is_function(f, 1) -> stream_chat(base, body, opts, f)
        _ -> plain_chat(base, body, opts)
      end
    end
  end

  # Item 11: `on_partial` streams the completion (server-sent events) and is called with the
  # text so far after each piece, in this process; the result is the same as the plain call's.
  defp stream_chat(base, body, opts, on_partial) do
    key = {__MODULE__, make_ref()}
    Process.put(key, %{buf: "", text: "", model: nil, usage: %{}})

    into = fn {:data, data}, acc ->
      st = Process.get(key)
      {events, buf} = RisiMe.Agent.CallRisi.LLMStream.split_events(st.buf <> data)
      st = Enum.reduce(events, %{st | buf: buf}, &stream_event(&1, &2, on_partial))
      Process.put(key, st)
      {:cont, acc}
    end

    body =
      body
      |> Map.put("stream", true)
      |> Map.put("stream_options", %{"include_usage" => true})

    req =
      Req.new(
        [
          url: base <> "/chat/completions",
          json: body,
          receive_timeout: Keyword.get(opts, :timeout, timeout()),
          connect_options: [timeout: 5_000],
          retry: false,
          redirect: false,
          headers: auth_headers(),
          into: into
        ] ++ req_options()
      )

    result =
      case Req.post(req) do
        {:ok, %{status: 200}} ->
          st = Process.get(key)

          if st.text == "",
            do: {:error, :bad_response},
            else:
              {:ok,
               %{
                 content: st.text,
                 model: real_model(base, st.model),
                 prompt_tokens: st.usage["prompt_tokens"],
                 completion_tokens: st.usage["completion_tokens"]
               }}

        other ->
          status(other)
      end

    Process.delete(key)
    result
  end

  defp stream_event(ev, st, on_partial) do
    with "data: " <> json <-
           Enum.find(String.split(ev, "\n"), "", &String.starts_with?(&1, "data: {")),
         {:ok, m} <- Jason.decode(json) do
      delta =
        case m["choices"] do
          [%{"delta" => %{"content" => c}} | _] when is_binary(c) -> c
          _ -> ""
        end

      st = %{
        st
        | text: st.text <> delta,
          model: st.model || m["model"],
          usage: m["usage"] || st.usage
      }

      if delta != "", do: safe_partial(on_partial, st.text)
      st
    else
      _ -> st
    end
  end

  defp safe_partial(f, text) do
    f.(text)
  rescue
    _ -> :ok
  catch
    _, _ -> :ok
  end

  defp status({:ok, %{status: 429}}), do: {:error, :busy}
  defp status({:ok, %{status: s}}) when s >= 500, do: {:error, :http_5xx}
  defp status({:ok, %{status: _}}), do: {:error, :http_4xx}
  defp status({:error, %{reason: :timeout}}), do: {:error, :timeout}
  defp status(_), do: {:error, :transport}

  defp plain_chat(base, body, opts) do
    req =
      Req.new(
        [
          url: base <> "/chat/completions",
          json: body,
          receive_timeout: Keyword.get(opts, :timeout, timeout()),
          connect_options: [timeout: 5_000],
          retry: false,
          redirect: false,
          headers: auth_headers()
        ] ++ req_options()
      )

    case Req.post(req) do
      {:ok, %{status: 200, body: %{"choices" => [%{"message" => msg} | _]} = resp}} ->
        case msg do
          %{"content" => content} when is_binary(content) ->
            usage = resp["usage"] || %{}

            {:ok,
             %{
               content: content,
               model: real_model(base, resp["model"]),
               prompt_tokens: usage["prompt_tokens"],
               completion_tokens: usage["completion_tokens"]
             }}

          _ ->
            {:error, :bad_response}
        end

      {:ok, %{status: 200}} ->
        {:error, :bad_response}

      {:ok, %{status: 429}} ->
        {:error, :busy}

      {:ok, %{status: s}} when s >= 500 ->
        {:error, :http_5xx}

      {:ok, %{status: _}} ->
        {:error, :http_4xx}

      {:error, %{reason: :timeout}} ->
        {:error, :timeout}

      {:error, _} ->
        {:error, :transport}
    end
  end

  @doc """
  The configured base URL, or `{:error, :non_loopback}` / `{:error, :bad_url}`. Only `http`/`https`
  to a loopback host is accepted.
  """
  def base_url, do: check_url(config()[:url] || @default_url)

  @doc false
  def check_url(url) when is_binary(url) do
    case URI.parse(url) do
      %URI{scheme: scheme, host: host, userinfo: nil} = uri
      when scheme in ["http", "https"] and is_binary(host) and host != "" ->
        if loopback?(host),
          do: {:ok, String.trim_trailing(URI.to_string(%{uri | query: nil, fragment: nil}), "/")},
          else: {:error, :non_loopback}

      _ ->
        {:error, :bad_url}
    end
  end

  def check_url(_), do: {:error, :bad_url}

  @doc "True for `localhost`, `127.0.0.0/8` and `::1` (no DNS lookup is ever made)."
  def loopback?("localhost"), do: true

  def loopback?(host) do
    case :inet.parse_strict_address(
           String.to_charlist(String.trim(host, "[") |> String.trim("]"))
         ) do
      {:ok, {127, _, _, _}} -> true
      {:ok, {0, 0, 0, 0, 0, 0, 0, 1}} -> true
      _ -> false
    end
  end

  defp config, do: Application.get_env(:risime, :risi_llm, [])
  defp req_options, do: config()[:req_options] || []
  defp timeout, do: config()[:timeout_ms] || 60_000

  defp auth_headers do
    case config()[:api_key] do
      k when is_binary(k) and k != "" -> [{"authorization", "Bearer " <> k}]
      _ -> []
    end
  end

  # The served model behind the alias (vLLM `/models` `root`), cached; the alias otherwise.
  defp real_model(base, alias_name) do
    key = {__MODULE__, :model, base}
    now = System.monotonic_time(:millisecond)

    case :persistent_term.get(key, nil) do
      {model, at} when now - at < @model_ttl_ms ->
        model

      _ ->
        model = fetch_model(base) || alias_name || "unknown"
        :persistent_term.put(key, {model, now})
        model
    end
  end

  defp fetch_model(base) do
    req =
      Req.new(
        [
          url: base <> "/models",
          receive_timeout: 5_000,
          retry: false,
          redirect: false,
          headers: auth_headers()
        ] ++ req_options()
      )

    case Req.get(req) do
      {:ok, %{status: 200, body: %{"data" => models}}} when is_list(models) ->
        Enum.find_value(models, fn
          %{"id" => "risi-l1", "root" => root} when is_binary(root) -> Path.basename(root)
          _ -> nil
        end)

      _ ->
        nil
    end
  rescue
    _ -> nil
  end
end
