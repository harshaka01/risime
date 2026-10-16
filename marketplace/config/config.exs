import Config

config :risi_registry,
  ecto_repos: [RisiRegistry.Repo],
  generators: [timestamp_type: :utc_datetime_usec]

config :risi_registry, RisiRegistry.Repo,
  migration_timestamps: [type: :utc_datetime_usec],
  migration_primary_key: [type: :bigserial]

# Loopback only (RisiMe golden rule 6). The public route, if any, is a reverse proxy in front.
config :risi_registry, RisiRegistryWeb.Endpoint,
  adapter: Bandit.PhoenixAdapter,
  url: [host: "localhost"],
  http: [ip: {127, 0, 0, 1}, port: 4600],
  render_errors: [
    formats: [json: RisiRegistryWeb.ErrorJSON, html: RisiRegistryWeb.ErrorHTML],
    layout: false
  ]

config :risi_registry, :registry,
  # The caller re-checks status on every call; the verified card is cached for at most 5 minutes
  # (agent-connector.md section 4, review change 9).
  card_cache_ttl_ms: :timer.minutes(5),
  # Hard cap on a fetched card or key set, in bytes.
  max_document_bytes: 64 * 1024,
  # Signature algorithms accepted on cards (JWS `alg`).
  allowed_algs: ["ES256", "EdDSA"],
  # Only https card and key URLs in dev and prod.
  require_https: true

config :risi_registry, :http_client, RisiRegistry.HTTP.ReqClient

config :risi_registry, :sandbox,
  suite_version: "0.1.0",
  client: RisiRegistry.Sandbox.DisabledClient,
  timeout_ms: 10_000,
  max_answer_bytes: 32 * 1024

# OIDC (Keycloak realm aoa, client risi-marketplace; authorization code + PKCE, public client).
# The issuer comes from the environment (config/runtime.exs); nothing here is a secret.
config :risi_registry, :oidc,
  issuer: nil,
  client_id: "risi-marketplace",
  redirect_uri: "https://risicloud.ai/marketplace/auth/callback",
  scopes: "openid email profile"

config :logger, :default_formatter,
  format: "$time $metadata[$level] $message\n",
  metadata: [:request_id]

config :phoenix, :json_library, Jason

import_config "#{config_env()}.exs"
