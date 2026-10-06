defmodule RisiMe.Auth.Config do
  @moduledoc """
  Authentication settings (contract v1.3 §6, decision 018).

      config :risime, :dev_local_auth, true | false   # DEV_LOCAL_AUTH
      config :risime, :oidc, enabled: …, issuer: …, client_id: …, jwks: …   # OIDC_*
  """

  @oidc_defaults [
    enabled: false,
    issuer: "https://risicloud.ai/realms/aoa",
    client_id: "risime",
    jwks: :discovery,
    leeway_s: 60,
    refresh_ms: :timer.minutes(10),
    min_refetch_ms: :timer.seconds(60),
    req_options: []
  ]

  def oidc(key) do
    Application.get_env(:risime, :oidc, [])
    |> Keyword.get_lazy(key, fn -> Keyword.fetch!(@oidc_defaults, key) end)
  end

  def oidc_enabled?, do: oidc(:enabled) == true

  def dev_local_auth?, do: Application.get_env(:risime, :dev_local_auth, false) == true

  @doc "How long after `exp` a JWT socket is disconnected (default 60 s)."
  def expiry_grace_ms, do: Application.get_env(:risime, :auth_expiry_grace_ms, 60_000)

  @doc "`modes` for `GET /api/v1/auth/config`."
  def modes do
    for {mode, on} <- [{"oidc", oidc_enabled?()}, {"dev", dev_local_auth?()}], on, do: mode
  end
end
