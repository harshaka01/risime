import Config

# No force_ssl redirect (decision 018): TLS ends at Caddy on spark / `tailscale serve`, the
# server listens on loopback (or in a container published on loopback), and a redirect built
# here would drop the /risime prefix. Nothing trusts x-forwarded-* headers.

# Configure Swoosh API Client
config :swoosh, api_client: Swoosh.ApiClient.Req

# Disable Swoosh Local Memory Storage
config :swoosh, local: false

# Do not print debug messages in production
config :logger, level: :info

# Runtime production configuration, including reading
# of environment variables, is done on config/runtime.exs.
