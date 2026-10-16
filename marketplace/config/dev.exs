import Config

# The password comes from marketplace/.env (config/runtime.exs).
config :risi_registry, RisiRegistry.Repo,
  hostname: "127.0.0.1",
  database: "risi_registry_dev",
  pool_size: 5,
  show_sensitive_data_on_connection_error: false

config :risi_registry, RisiRegistryWeb.Endpoint,
  check_origin: false,
  debug_errors: true,
  server: true

config :risi_registry, :oidc, redirect_uri: "http://127.0.0.1:4600/marketplace/auth/callback"

config :phoenix, :plug_init_mode, :runtime
config :phoenix, :stacktrace_depth, 20
