import Config

# No force_ssl redirect (decision 018): TLS ends at Caddy on spark / `tailscale serve`, the
# server listens on loopback (or in a container published on loopback), and a redirect built
# here would drop the /risime prefix. Nothing trusts x-forwarded-* headers.

# No SMTP unless RISIME_MAILER=smtp (runtime.exs): send nothing, log nothing of the email.
config :risime, RisiMe.Mailer, adapter: RisiMe.Mailer.Disabled

# Explicitly off in prod (dev.exs turns them on): no /dev routes, no debug error pages.
config :risime, dev_routes: false
config :risime, RisiMeWeb.Endpoint, debug_errors: false, code_reloader: false

# Configure Swoosh API Client
config :swoosh, api_client: Swoosh.ApiClient.Req

# Disable Swoosh Local Memory Storage
config :swoosh, local: false

# Do not print debug messages in production
config :logger, level: :info

# Runtime production configuration, including reading
# of environment variables, is done on config/runtime.exs.
