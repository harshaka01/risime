import Config

config :risi_registry, RisiRegistryWeb.Endpoint, server: true
config :logger, level: :info

# The page is served only over https through the reverse proxy.
config :risi_registry, :secure_cookie, true
