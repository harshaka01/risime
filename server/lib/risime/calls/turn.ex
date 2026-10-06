defmodule RisiMe.Calls.Turn do
  @moduledoc """
  TURN REST credentials for coturn's `use-auth-secret` (contract v1.13 §16.7, decision 046).

    * `username = "<unix expiry>:<16 random hex>"`: no user id or phone (coturn logs it);
    * `credential = base64(HMAC-SHA1(TURN_SECRET, username))` (coturn's scheme);
    * TTL 18 000 s (5 h, server R1) so a credential outlives the 4-h maximum call.

  Configuration (`config :risime, :turn`, from `TURN_SECRET`, `TURN_URLS` (comma-separated) and
  an optional `TURN_TTL` in `config/runtime.exs`). A secret shorter than 32 bytes counts as unset.
  The secret, and the username → user mapping, are never logged.
  """
  require Logger

  @default_ttl 18_000
  @min_secret_bytes 32

  @doc "`{:ok, reply}` (the `calls_turn_reply.json` shape) or `{:error, :calls_unavailable}`."
  def credentials(now \\ System.os_time(:second)) do
    with {:ok, secret, urls, ttl} <- config() do
      expiry = now + ttl
      username = "#{expiry}:#{Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)}"
      {stun, turn} = Enum.split_with(urls, &String.starts_with?(&1, "stun:"))

      servers =
        if(stun != [], do: [%{urls: stun}], else: []) ++
          if turn != [],
            do: [%{urls: turn, username: username, credential: credential(secret, username)}],
            else: []

      {:ok,
       %{
         ice_servers: servers,
         ttl: ttl,
         expires_at: expiry |> DateTime.from_unix!() |> RisiMe.Messaging.iso()
       }}
    end
  end

  @doc "coturn's REST credential for `username`."
  def credential(secret, username),
    do: :hmac |> :crypto.mac(:sha, secret, username) |> Base.encode64()

  @doc false
  def config do
    cfg = Application.get_env(:risime, :turn, [])
    secret = cfg[:secret]
    urls = cfg[:urls] || []
    ttl = cfg[:ttl] || @default_ttl

    if is_binary(secret) and byte_size(secret) >= @min_secret_bytes and urls != [],
      do: {:ok, secret, urls, ttl},
      else: {:error, :calls_unavailable}
  end

  @doc "Boot check: warns (without the value) when calls are off or the secret is too short."
  def boot_check do
    cfg = Application.get_env(:risime, :turn, [])

    cond do
      is_binary(cfg[:secret]) and byte_size(cfg[:secret]) < @min_secret_bytes ->
        Logger.warning(
          "TURN_SECRET is shorter than #{@min_secret_bytes} bytes and counts as unset: " <>
            "GET /api/v1/calls/turn answers 503 calls_unavailable"
        )

      config() == {:error, :calls_unavailable} ->
        Logger.info("TURN_SECRET or TURN_URLS unset: GET /api/v1/calls/turn answers 503")

      true ->
        :ok
    end
  end
end
