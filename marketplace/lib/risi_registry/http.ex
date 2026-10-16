defmodule RisiRegistry.HTTP do
  @moduledoc """
  The registry's only door to the network: GET a JSON document (agent card, owner key set,
  OIDC discovery and JWKS) and POST a form (the OIDC token endpoint).

  Bodies are returned raw (binary) so the caller decides how to parse them and can cap their size.
  The implementation is configurable (`config :risi_registry, :http_client`); tests use a stub.
  """

  @type response :: {:ok, %{status: non_neg_integer(), body: binary()}} | {:error, term()}

  @callback get(url :: String.t(), opts :: keyword()) :: response()
  @callback post_form(url :: String.t(), form :: keyword() | map(), opts :: keyword()) ::
              response()

  def client, do: Application.get_env(:risi_registry, :http_client, RisiRegistry.HTTP.ReqClient)

  def get(url, opts \\ []), do: client().get(url, opts)
  def post_form(url, form, opts \\ []), do: client().post_form(url, form, opts)
end

defmodule RisiRegistry.HTTP.ReqClient do
  @moduledoc "Req-backed client. No redirects, no retries, short timeouts, capped body size."
  @behaviour RisiRegistry.HTTP

  @impl true
  def get(url, opts) do
    url
    |> Req.get(base_opts(opts))
    |> wrap(opts)
  end

  @impl true
  def post_form(url, form, opts) do
    url
    |> Req.post(Keyword.merge(base_opts(opts), form: form))
    |> wrap(opts)
  end

  defp base_opts(opts) do
    [
      redirect: false,
      retry: false,
      decode_body: false,
      receive_timeout: Keyword.get(opts, :timeout_ms, 5_000),
      connect_options: [timeout: 3_000]
    ]
  end

  defp wrap({:ok, %Req.Response{status: status, body: body}}, opts) do
    max = Keyword.get(opts, :max_bytes, 64 * 1024)

    if is_binary(body) and byte_size(body) <= max,
      do: {:ok, %{status: status, body: body}},
      else: {:error, :too_large}
  end

  defp wrap({:error, exception}, _opts), do: {:error, {:transport, Exception.message(exception)}}
end
