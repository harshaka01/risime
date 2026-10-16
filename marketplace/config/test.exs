import Config

partition = System.get_env("MIX_TEST_PARTITION", "")

config :risi_registry, RisiRegistry.Repo,
  hostname: "127.0.0.1",
  database: "risi_registry_test#{partition}",
  pool: Ecto.Adapters.SQL.Sandbox,
  pool_size: 5

config :risi_registry, RisiRegistryWeb.Endpoint,
  http: [ip: {127, 0, 0, 1}, port: 4602],
  # Test-only key, never used outside `mix test`.
  secret_key_base: "test-only-0d3b9a6f2c4e8a1b7f5d3c9e6a2b8f4d1c7e5a3b9f6d2c8e4a1b7f5d3c9e6a2b8f",
  server: false

config :risi_registry, :http_client, RisiRegistry.HTTPStub

config :risi_registry, :sandbox,
  suite_version: "0.1.0",
  client: RisiRegistry.SandboxStubClient,
  timeout_ms: 100,
  max_answer_bytes: 32 * 1024

config :risi_registry, :oidc,
  issuer: "https://idp.test/realms/aoa",
  client_id: "risi-marketplace",
  redirect_uri: "https://risicloud.ai/marketplace/auth/callback",
  scopes: "openid email profile"

config :logger, level: :warning
config :phoenix, :plug_init_mode, :runtime
