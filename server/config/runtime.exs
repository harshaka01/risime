import Config

# Dev and test read secrets from the repo-level `.env` (gitignored). Variables already set in
# the environment win. Prod gets its environment from its own deployment.
if config_env() in [:dev, :test] do
  env_file = Path.expand("../../.env", __DIR__)

  if File.exists?(env_file) do
    env_file
    |> File.read!()
    |> String.split("\n")
    |> Enum.each(fn line ->
      line = String.trim(line)

      with false <- line == "" or String.starts_with?(line, "#"),
           [key, value] <- String.split(line, "=", parts: 2) do
        key = key |> String.trim() |> String.trim_leading("export ")
        value = value |> String.trim() |> String.trim("\"") |> String.trim("'")
        if System.get_env(key) == nil, do: System.put_env(key, value)
      end
    end)
  end

  config :risime, RisiMe.Repo, password: System.get_env("POSTGRES_PASSWORD")
end

if config_env() == :dev do
  config :risime, RisiMeWeb.Endpoint,
    secret_key_base:
      System.get_env("SECRET_KEY_BASE") ||
        raise("SECRET_KEY_BASE is missing from .env (generate one with: mix phx.gen.secret)")

  config :risime, :otp_dev_log, System.get_env("OTP_DEV_LOG") == "true"
end

# Log format (docs/decisions/008-observability.md): JSON lines when LOG_FORMAT=json, and by
# default in prod (LOG_FORMAT=text turns it off). Dev and test keep the readable console.
log_format = System.get_env("LOG_FORMAT", if(config_env() == :prod, do: "json", else: "text"))
config :risime, :log_format, if(log_format == "json", do: :json, else: :text)

if pool = System.get_env("CASSANDRA_POOL_SIZE") do
  config :risime, :cassandra, pool_size: String.to_integer(pool)
end

# Authentication (decision 018). OIDC_ENABLED=true once the `risime` Keycloak client exists.
if config_env() != :test do
  oidc =
    [
      enabled: System.get_env("OIDC_ENABLED") == "true",
      issuer: System.get_env("OIDC_ISSUER", "https://risicloud.ai/realms/aoa"),
      client_id: System.get_env("OIDC_CLIENT_ID", "risime")
    ] ++
      if(url = System.get_env("OIDC_JWKS_URL"), do: [jwks: {:url, url}], else: [])

  config :risime, :oidc, oidc

  case System.get_env("DEV_LOCAL_AUTH") do
    nil -> :ok
    value -> config :risime, :dev_local_auth, value == "true"
  end
end

# Phone verification (decision 021). PHONE_VERIFICATION=required turns the gate on; SMS_MODE is
# notifylk | log (default notifylk in prod, log elsewhere).
if config_env() != :test do
  config :risime,
         :phone_verification,
         if(System.get_env("PHONE_VERIFICATION") == "required", do: :required, else: :off)

  default_sms = if config_env() == :prod, do: "notifylk", else: "log"

  config :risime,
         :sms_mode,
         if(System.get_env("SMS_MODE", default_sms) == "notifylk", do: :notifylk, else: :log)
end

# LOG_LEVEL=info|warning|… overrides the env's level (e.g. a dev server for a load test).
if level = System.get_env("LOG_LEVEL") do
  config :risime, :log_level, String.to_existing_atom(level)
end

# SMTP is wired up but only used when RISIME_MAILER=smtp (Release 0.2). Dev uses the local
# mailbox at /dev/mailbox plus the `[DEV OTP]` log line.
config :risime, :mail_from, System.get_env("SMTP_FROM", "RisiMe <no-reply@example.com>")

if System.get_env("RISIME_MAILER") == "smtp" do
  config :risime, RisiMe.Mailer,
    adapter: Swoosh.Adapters.SMTP,
    relay: System.fetch_env!("SMTP_HOST"),
    port: String.to_integer(System.get_env("SMTP_PORT", "587")),
    username: System.get_env("SMTP_USERNAME"),
    password: System.get_env("SMTP_PASSWORD"),
    tls: :always,
    auth: :always
end

# config/runtime.exs is executed for all environments, including
# during releases. It is executed after compilation and before the
# system starts, so it is typically used to load production configuration
# and secrets from environment variables or elsewhere. Do not define
# any compile-time configuration in here, as it won't be applied.
# The block below contains prod specific runtime configuration.

# ## Using releases
#
# If you use `mix release`, you need to explicitly enable the server
# by passing the PHX_SERVER=true when you start it:
#
#     PHX_SERVER=true bin/risime start
#
# Alternatively, you can use `mix phx.gen.release` to generate a `bin/server`
# script that automatically sets the env var above.
if System.get_env("PHX_SERVER") do
  config :risime, RisiMeWeb.Endpoint, server: true
end

if config_env() != :test do
  config :risime, RisiMeWeb.Endpoint,
    http: [port: String.to_integer(System.get_env("PORT", "4000"))]
end

if config_env() == :prod do
  # Cassandra for prod (docs/PROD.md). Without this, a release would use the dev defaults.
  config :risime, :cassandra,
    nodes: "CASSANDRA_NODES" |> System.get_env("127.0.0.1:9042") |> String.split(",", trim: true),
    keyspace: System.get_env("CASSANDRA_KEYSPACE", "risime_prod"),
    pool_size: String.to_integer(System.get_env("CASSANDRA_POOL_SIZE", "16"))

  database_url =
    System.get_env("DATABASE_URL") ||
      raise """
      environment variable DATABASE_URL is missing.
      For example: ecto://USER:PASS@HOST/DATABASE
      """

  maybe_ipv6 = if System.get_env("ECTO_IPV6") in ~w(true 1), do: [:inet6], else: []

  config :risime, RisiMe.Repo,
    # ssl: true,
    url: database_url,
    pool_size: String.to_integer(System.get_env("POOL_SIZE") || "10"),
    # For machines with several cores, consider starting multiple pools of `pool_size`
    # pool_count: 4,
    socket_options: maybe_ipv6

  # The secret key base is used to sign/encrypt cookies and other secrets.
  # A default value is used in config/dev.exs and config/test.exs but you
  # want to use a different value for prod and you most likely don't want
  # to check this value into version control, so we use an environment
  # variable instead.
  secret_key_base =
    System.get_env("SECRET_KEY_BASE") ||
      raise """
      environment variable SECRET_KEY_BASE is missing.
      You can generate one by calling: mix phx.gen.secret
      """

  # Public URL: https://risicloud.ai/risime/ via Caddy (decision 017, option 2b).
  host = System.get_env("PHX_HOST") || "risicloud.ai"
  url_path = System.get_env("PHX_PATH", "/risime")

  config :risime, :dns_cluster_query, System.get_env("DNS_CLUSTER_QUERY")

  config :risime, RisiMeWeb.Endpoint,
    url: [host: host, port: 443, scheme: "https", path: url_path],
    # Browsers only from the public origin; OkHttp sends no Origin header, which Phoenix allows.
    check_origin: ["https://" <> host],
    http: [
      # Enable IPv6 and bind on all interfaces.
      # Set it to  {0, 0, 0, 0, 0, 0, 0, 1} for local network only access.
      # See the documentation on https://bandit.hexdocs.pm/Bandit.html#t:options/0
      # for details about using IPv6 vs IPv4 and loopback vs public addresses.
      ip: {0, 0, 0, 0, 0, 0, 0, 0}
    ],
    secret_key_base: secret_key_base

  # ## SSL Support
  #
  # To get SSL working, you will need to add the `https` key
  # to your endpoint configuration:
  #
  #     config :risime, RisiMeWeb.Endpoint,
  #       https: [
  #         ...,
  #         port: 443,
  #         cipher_suite: :strong,
  #         keyfile: System.get_env("SOME_APP_SSL_KEY_PATH"),
  #         certfile: System.get_env("SOME_APP_SSL_CERT_PATH")
  #       ]
  #
  # The `cipher_suite` is set to `:strong` to support only the
  # latest and more secure SSL ciphers. This means old browsers
  # and clients may not be supported. You can set it to
  # `:compatible` for wider support.
  #
  # `:keyfile` and `:certfile` expect an absolute path to the key
  # and cert in disk or a relative path inside priv, for example
  # "priv/ssl/server.key". For all supported SSL configuration
  # options, see https://plug.hexdocs.pm/Plug.SSL.html#configure/1
  #
  # We also recommend setting `force_ssl` in your config/prod.exs,
  # ensuring no data is ever sent via http, always redirecting to https:
  #
  #     config :risime, RisiMeWeb.Endpoint,
  #       force_ssl: [hsts: true]
  #
  # Check `Plug.SSL` for all available options in `force_ssl`.

  # ## Configuring the mailer
  #
  # In production you need to configure the mailer to use a different adapter.
  # Here is an example configuration for Mailgun:
  #
  #     config :risime, RisiMe.Mailer,
  #       adapter: Swoosh.Adapters.Mailgun,
  #       api_key: System.get_env("MAILGUN_API_KEY"),
  #       domain: System.get_env("MAILGUN_DOMAIN")
  #
  # Most non-SMTP adapters require an API client. Swoosh supports Req, Hackney,
  # and Finch out-of-the-box. This configuration is typically done at
  # compile-time in your config/prod.exs:
  #
  #     config :swoosh, :api_client, Swoosh.ApiClient.Req
  #
  # See https://swoosh.hexdocs.pm/Swoosh.html#module-installation for details.
end
