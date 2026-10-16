defmodule RisiRegistry.OIDC.Discovery do
  @moduledoc """
  Caches the issuer's discovery document and JWKS for an hour. An unknown `kid` forces one
  JWKS refresh (Keycloak key rotation).
  """
  use GenServer
  alias RisiRegistry.HTTP

  @table __MODULE__
  @ttl_ms :timer.hours(1)

  def start_link(_), do: GenServer.start_link(__MODULE__, nil, name: __MODULE__)

  @impl true
  def init(nil) do
    :ets.new(@table, [:named_table, :public, :set, read_concurrency: true])
    {:ok, nil}
  end

  def config(issuer) do
    cached({:config, issuer}, fn ->
      with {:ok, doc} <-
             get_json(String.trim_trailing(issuer, "/") <> "/.well-known/openid-configuration"),
           true <- doc["issuer"] == issuer || {:error, :issuer_mismatch},
           true <- is_binary(doc["authorization_endpoint"]) || {:error, :bad_discovery},
           true <- is_binary(doc["token_endpoint"]) || {:error, :bad_discovery},
           true <- is_binary(doc["jwks_uri"]) || {:error, :bad_discovery} do
        {:ok, doc}
      end
    end)
  end

  def jwks(issuer, opts \\ []) do
    if Keyword.get(opts, :refresh, false), do: :ets.delete(@table, {:jwks, issuer})

    cached({:jwks, issuer}, fn ->
      with {:ok, doc} <- config(issuer),
           {:ok, %{"keys" => keys}} when is_list(keys) <- get_json(doc["jwks_uri"]) do
        {:ok, keys}
      else
        {:ok, _} -> {:error, :bad_jwks}
        err -> err
      end
    end)
  end

  def clear, do: :ets.delete_all_objects(@table)

  defp cached(key, fun) do
    now = System.monotonic_time(:millisecond)

    case :ets.lookup(@table, key) do
      [{^key, value, expires}] when expires > now ->
        {:ok, value}

      _ ->
        with {:ok, value} <- fun.() do
          :ets.insert(@table, {key, value, now + @ttl_ms})
          {:ok, value}
        end
    end
  end

  defp get_json(url) do
    case HTTP.get(url, max_bytes: 256 * 1024) do
      {:ok, %{status: 200, body: body}} ->
        case Jason.decode(body) do
          {:ok, %{} = doc} -> {:ok, doc}
          _ -> {:error, :not_json}
        end

      {:ok, %{status: status}} ->
        {:error, {:http_status, status}}

      {:error, _} ->
        {:error, :idp_unreachable}
    end
  end
end
