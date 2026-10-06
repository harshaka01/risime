defmodule RisiMe.Auth do
  @moduledoc """
  Bearer-token authentication for REST and the socket (contract v1.3 §6, decision 018).

  * A JWT is a Keycloak access token. It is accepted only when OIDC is enabled, verified by
    `RisiMe.Auth.JWT`, then mapped to a user (`Accounts.map_identity/1`).
  * An opaque token is a dev token from `/auth/verify`. It is accepted only with
    `DEV_LOCAL_AUTH=true`.

  Successful JWT mappings are cached per token (by SHA-256) until the token's `exp`, so a
  request costs one ETS lookup plus loading the user.
  """
  use GenServer

  require Logger

  alias RisiMe.Accounts
  alias RisiMe.Accounts.User
  alias RisiMe.Auth.{Config, JWT}

  @table __MODULE__.Cache
  @sweep_every :timer.minutes(1)

  @type auth :: %{
          user: %User{},
          kind: :jwt | :dev,
          exp: integer | nil,
          token_record: struct | nil
        }
  @type error :: :invalid_token | :not_allowlisted | :identity_conflict

  @spec authenticate(term) :: {:ok, auth} | {:error, error}
  def authenticate(token) when is_binary(token) and token != "" do
    cond do
      JWT.jwt_shape?(token) and Config.oidc_enabled?() -> authenticate_jwt(token)
      JWT.jwt_shape?(token) -> {:error, :invalid_token}
      Config.dev_local_auth?() -> authenticate_dev(token)
      true -> {:error, :invalid_token}
    end
  end

  def authenticate(_), do: {:error, :invalid_token}

  defp authenticate_dev(token) do
    case Accounts.fetch_by_token(token) do
      {user, record} -> {:ok, %{user: user, kind: :dev, exp: nil, token_record: record}}
      nil -> {:error, :invalid_token}
    end
  end

  defp authenticate_jwt(token) do
    key = :crypto.hash(:sha256, token)
    now = System.os_time(:second)

    with {:cached, nil} <- {:cached, cached(key, now)},
         {:ok, identity} <- verify(token),
         {:ok, user} <- Accounts.map_identity(identity) do
      :ets.insert(@table, {key, user.id, identity.exp})
      {:ok, %{user: user, kind: :jwt, exp: identity.exp, token_record: nil}}
    else
      {:cached, {user, exp}} -> {:ok, %{user: user, kind: :jwt, exp: exp, token_record: nil}}
      {:error, _} = error -> error
    end
  end

  defp verify(token) do
    case JWT.verify(token) do
      {:ok, identity} ->
        {:ok, identity}

      {:error, reason} ->
        Logger.info("rejected access token: #{reason}")
        {:error, :invalid_token}
    end
  end

  # The cache entry outlives exp by the verification leeway, like the token itself.
  defp cached(key, now) do
    leeway = Config.oidc(:leeway_s)

    case :ets.lookup(@table, key) do
      [{^key, user_id, exp}] when now <= exp + leeway ->
        case Accounts.get_user(user_id) do
          nil -> nil
          user -> {user, exp}
        end

      _ ->
        nil
    end
  rescue
    ArgumentError -> nil
  end

  @doc "Forgets cached mappings (tests, and after an admin re-bind on this node)."
  def clear_cache, do: :ets.delete_all_objects(@table)

  ## Cache owner

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @impl true
  def init(:ok) do
    :ets.new(@table, [
      :named_table,
      :public,
      :set,
      read_concurrency: true,
      write_concurrency: true
    ])

    Process.send_after(self(), :sweep, @sweep_every)
    {:ok, nil}
  end

  @impl true
  def handle_info(:sweep, state) do
    cutoff = System.os_time(:second) - 60
    :ets.select_delete(@table, [{{:_, :_, :"$1"}, [{:<, :"$1", cutoff}], [true]}])
    Process.send_after(self(), :sweep, @sweep_every)
    {:noreply, state}
  end
end
