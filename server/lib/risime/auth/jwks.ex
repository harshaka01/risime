defmodule RisiMe.Auth.JWKS do
  @moduledoc """
  Signing keys of the Keycloak realm (contract v1.3 §6.0, decision 018).

  * Keys live in a protected ETS table, keyed by `kid`, so lookups don't go through this process.
  * A background refresh runs every 10 min (`:refresh_ms`).
  * An unknown `kid` triggers an immediate refetch, single-flight because it goes through this
    process, and at most once per 60 s (`:min_refetch_ms`, counted from the last fetch attempt).
  * A failed fetch keeps the cached keys. With no keys, every token is rejected (fail closed).
  * JWKs whose `use` isn't `"sig"`, or without a `kid`, are ignored.

  Key source (`config :risime, :oidc, jwks:`):
    * `:discovery` (default): `jwks_uri` from `<issuer>/.well-known/openid-configuration`,
      falling back to `<issuer>/protocol/openid-connect/certs`;
    * `{:url, url}`;
    * `{:static, %{"keys" => [...]}}` (tests).
  """
  use GenServer

  require Logger

  alias RisiMe.Auth.Config

  @table __MODULE__

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "The JWK (`{jose_jwk, map}`) for `kid`, refetching once if unknown. `:error` if none."
  @spec get(String.t()) :: {:ok, {JOSE.JWK.t(), map}} | :error
  def get(kid) when is_binary(kid) do
    case lookup(kid) do
      {:ok, _} = hit -> hit
      :error -> GenServer.call(__MODULE__, {:unknown_kid, kid}, 15_000)
    end
  catch
    :exit, _ -> :error
  end

  def get(_), do: :error

  @doc "Fetches now, ignoring the rate limit (tests and operators)."
  def refresh_now, do: GenServer.call(__MODULE__, :refresh_now, 15_000)

  @doc "Number of cached keys."
  def size, do: :ets.select_count(@table, [{{:_, :_}, [], [true]}])

  defp lookup(kid) do
    case :ets.lookup(@table, kid) do
      [{^kid, key}] -> {:ok, key}
      [] -> :error
    end
  rescue
    ArgumentError -> :error
  end

  ## Server

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :protected, :set, read_concurrency: true])
    if Config.oidc_enabled?(), do: send(self(), :refresh)
    {:ok, %{last_fetch: nil, jwks_uri: nil}}
  end

  @impl true
  def handle_call({:unknown_kid, kid}, _from, state) do
    case lookup(kid) do
      {:ok, _} = hit ->
        {:reply, hit, state}

      :error ->
        if rate_limited?(state) do
          {:reply, :error, state}
        else
          state = fetch(state)
          {:reply, lookup(kid), state}
        end
    end
  end

  def handle_call(:refresh_now, _from, state) do
    state = fetch(state)
    {:reply, size(), state}
  end

  @impl true
  def handle_info(:refresh, state) do
    Process.send_after(self(), :refresh, Config.oidc(:refresh_ms))
    {:noreply, fetch(state)}
  end

  defp rate_limited?(%{last_fetch: nil}), do: false

  defp rate_limited?(%{last_fetch: t}),
    do: System.monotonic_time(:millisecond) - t < Config.oidc(:min_refetch_ms)

  defp fetch(state) do
    state = %{state | last_fetch: System.monotonic_time(:millisecond)}

    case load(state) do
      {:ok, %{"keys" => keys}, state} when is_list(keys) ->
        store(keys)
        state

      {:ok, _other, state} ->
        Logger.warning("JWKS: response has no keys list; keeping #{size()} cached key(s)")
        state

      {:error, reason, state} ->
        Logger.warning("JWKS fetch failed (#{reason}); keeping #{size()} cached key(s)")
        state
    end
  end

  defp load(state) do
    case Config.oidc(:jwks) do
      {:static, jwks} -> {:ok, jwks, state}
      {:url, url} -> with_state(get_json(url), state)
      :discovery -> load_discovered(state)
    end
  end

  defp load_discovered(%{jwks_uri: nil} = state) do
    issuer = Config.oidc(:issuer)

    uri =
      case get_json(issuer <> "/.well-known/openid-configuration") do
        {:ok, %{"jwks_uri" => uri}} when is_binary(uri) -> uri
        _ -> issuer <> "/protocol/openid-connect/certs"
      end

    load_discovered(%{state | jwks_uri: uri})
  end

  defp load_discovered(%{jwks_uri: uri} = state), do: with_state(get_json(uri), state)

  defp with_state({:ok, body}, state), do: {:ok, body, state}
  defp with_state({:error, reason}, state), do: {:error, reason, state}

  defp get_json(url) do
    opts = [retry: false, receive_timeout: 5_000] ++ Config.oidc(:req_options)

    case Req.get(url, opts) do
      {:ok, %Req.Response{status: 200, body: body}} when is_map(body) ->
        {:ok, body}

      # Some servers send JSON without an application/json content type.
      {:ok, %Req.Response{status: 200, body: body}} when is_binary(body) ->
        case Jason.decode(body) do
          {:ok, map} when is_map(map) -> {:ok, map}
          _ -> {:error, "HTTP 200 without a JSON object"}
        end

      {:ok, %Req.Response{status: status}} ->
        {:error, "HTTP #{status}"}

      {:error, e} ->
        {:error, Exception.message(e)}
    end
  rescue
    e -> {:error, Exception.message(e)}
  end

  defp store(keys) do
    fresh =
      for %{"kid" => kid, "use" => "sig"} = map <- keys, is_binary(kid), into: %{} do
        {kid, {JOSE.JWK.from_map(map), map}}
      end

    for {kid, key} <- fresh, do: :ets.insert(@table, {kid, key})

    for {kid, _} <- :ets.tab2list(@table),
        not Map.has_key?(fresh, kid),
        do: :ets.delete(@table, kid)

    :ok
  end
end
