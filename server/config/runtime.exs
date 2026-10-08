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
end

# OTP_DEV_LOG=true writes login/verification codes to the server log only ([DEV OTP] lines),
# never to an HTTP response or /health. Allowed in the prod pilot while DEV_LOCAL_AUTH is on.
if config_env() in [:dev, :prod] do
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

# Phone verification (decision 022). PHONE_VERIFICATION=required turns the gate on; SMS_MODE is
# notifylk | log (default notifylk in prod, log elsewhere).
if config_env() != :test do
  config :risime,
         :phone_verification,
         if(System.get_env("PHONE_VERIFICATION") == "required", do: :required, else: :off)

  default_sms = if config_env() == :prod, do: "notifylk", else: "log"

  if warn = System.get_env("SMS_BALANCE_WARN") do
    config :risime, :sms_balance_warn, String.to_integer(warn)
  end

  config :risime,
         :sms_mode,
         if(System.get_env("SMS_MODE", default_sms) == "notifylk", do: :notifylk, else: :log)
end

# Push (decision 028). FCM_SERVICE_ACCOUNT_FILE is a path outside the repo
# (~/risime-keys/fcm-service-account.json, mode 600); its contents are read at use only.
if config_env() != :test do
  if System.get_env("FCM_ENABLED") == "true", do: config(:risime, :push_sender, RisiMe.Push.FCM)

  config :risime,
         :fcm_service_account_file,
         System.get_env(
           "FCM_SERVICE_ACCOUNT_FILE",
           Path.expand("~/risime-keys/fcm-service-account.json")
         )
end

# E2EE attestation key (decision 034). Without the file, E2EE stays off (503 mls_unavailable).
if config_env() != :test do
  config :risime,
         :attestation_key_file,
         System.get_env(
           "ATTESTATION_KEY_FILE",
           Path.expand("~/risime-keys/attestation_ed25519.jwk")
         )

  if prev = System.get_env("ATTESTATION_PREVIOUS_KEYS"),
    do: config(:risime, :attestation_previous_keys, prev)
end

# v1.9 blobs (§12.6): bytes on local disk outside the repo. Default ~/risime-blobs/<env>.
if config_env() != :test do
  if dir = System.get_env("BLOB_DIR"), do: config(:risime, :blob_dir, Path.expand(dir))

  # v1.11 (decision 042): the server-wide live `media` cap in bytes (default 200 GiB).
  if max = System.get_env("BLOB_MEDIA_MAX"),
    do: config(:risime, :blob_guard, media_max: String.to_integer(max))
end

# v1.13 §16.7 (decision 046): TURN REST credentials for coturn `use-auth-secret`. TURN_SECRET
# (>= 32 bytes, the coturn static-auth-secret) and TURN_URLS (comma-separated) from .env /
# pilot.env; without both, GET /calls/turn answers 503 calls_unavailable. Never logged.
# Tests configure their own secret.
if config_env() != :test do
  config :risime, :turn,
    secret: if(System.get_env("TURN_SECRET", "") != "", do: System.get_env("TURN_SECRET")),
    urls:
      (System.get_env("TURN_URLS") || "")
      |> String.split(",", trim: true)
      |> Enum.map(&String.trim/1)
      |> Enum.reject(&(&1 == "")),
    ttl: String.to_integer(System.get_env("TURN_TTL") || "18000")
end

# v1.19 §20.2 (decision 056): LiveKit for group calls. LIVEKIT_URL is the public wss URL clients
# connect to (wss://risime.risicloud.ai/livekit), LIVEKIT_API_URL LiveKit's loopback server API
# (default http://127.0.0.1:7880), LIVEKIT_API_KEY / LIVEKIT_API_SECRET the key pair LiveKit is
# configured with. Any missing → POST /calls/rooms answers 503 calls_unavailable. Never logged.
# Tests configure their own (and a fake API client).
if config_env() != :test do
  config :risime, :livekit,
    url: System.get_env("LIVEKIT_URL"),
    api_url: System.get_env("LIVEKIT_API_URL", "http://127.0.0.1:7880"),
    api_key: System.get_env("LIVEKIT_API_KEY"),
    api_secret: System.get_env("LIVEKIT_API_SECRET")
end

# Invite link in v1.6 invites (decision 030).
if link = System.get_env("INVITE_LINK"), do: config(:risime, :invite_link, link)

# fail2ban auth log (decision 024); default ~/risime-logs/auth.log.
if config_env() != :test do
  if path = System.get_env("RISIME_AUTH_LOG"), do: config(:risime, :auth_log_path, path)
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
  # Prod release (decision 024; the pilot of decision 023 runs at https://risime.risicloud.ai
  # behind Caddy on spark2). Everything comes from the environment; nothing here is a secret.

  # Cassandra.
  config :risime, :cassandra,
    nodes: "CASSANDRA_NODES" |> System.get_env("127.0.0.1:9042") |> String.split(",", trim: true),
    keyspace: System.get_env("CASSANDRA_KEYSPACE", "risime_prod"),
    pool_size: String.to_integer(System.get_env("CASSANDRA_POOL_SIZE", "16"))

  # Postgres: DATABASE_URL, or POSTGRES_PASSWORD plus POSTGRES_HOST/PORT/DB/USER.
  repo_conn =
    case System.get_env("DATABASE_URL") do
      url when is_binary(url) and url != "" ->
        [url: url]

      _ ->
        [
          hostname: System.get_env("POSTGRES_HOST", "127.0.0.1"),
          port: String.to_integer(System.get_env("POSTGRES_PORT", "5432")),
          database: System.get_env("POSTGRES_DB", "risime_prod"),
          username: System.get_env("POSTGRES_USER", "risime"),
          password:
            System.get_env("POSTGRES_PASSWORD") ||
              raise("set DATABASE_URL, or POSTGRES_PASSWORD (+ POSTGRES_HOST/PORT/DB/USER)")
        ]
    end

  config :risime,
         RisiMe.Repo,
         repo_conn ++ [pool_size: String.to_integer(System.get_env("POOL_SIZE", "10"))]

  secret_key_base =
    System.get_env("SECRET_KEY_BASE") ||
      raise "environment variable SECRET_KEY_BASE is missing (mix phx.gen.secret)"

  # Public URL and origins. The server only ever binds loopback: Caddy on the same host
  # terminates TLS (golden rule 6). PHX_BIND may name another loopback address, nothing else.
  host = System.get_env("PHX_HOST", "risime.risicloud.ai")
  url_path = System.get_env("PHX_PATH", "/")

  origins =
    "PHX_ORIGINS"
    |> System.get_env("https://" <> host)
    |> String.split(",", trim: true)
    |> Enum.map(&String.trim/1)

  bind =
    case :inet.parse_address(String.to_charlist(System.get_env("PHX_BIND", "127.0.0.1"))) do
      {:ok, {127, _, _, _} = ip} -> ip
      {:ok, {0, 0, 0, 0, 0, 0, 0, 1} = ip} -> ip
      _ -> raise "PHX_BIND must be a loopback address (127.x.x.x or ::1)"
    end

  config :risime, :dns_cluster_query, System.get_env("DNS_CLUSTER_QUERY")

  config :risime, RisiMeWeb.Endpoint,
    # A prod release serves HTTP unless PHX_SERVER=false (bin/risime eval never starts it).
    server: System.get_env("PHX_SERVER", "true") != "false",
    url: [host: host, port: 443, scheme: "https", path: url_path],
    # Browsers only from the public origin(s); OkHttp sends no Origin header, which is allowed.
    check_origin: origins,
    http: [ip: bind, port: String.to_integer(System.get_env("PORT", "4000"))],
    secret_key_base: secret_key_base
end

# Invite and friend-request limits (lib/risime/social.ex): server config, changed by env + restart.
int_env = fn name, default ->
  case System.get_env(name) do
    nil -> default
    "" -> default
    v -> String.to_integer(v)
  end
end

# Open sign-up (contract v1.20 §21, decision 058): OPEN_SIGNUP=true turns it on (env + restart).
config :risime, :signup,
  open: System.get_env("OPEN_SIGNUP") == "true",
  per_ip_hour: int_env.("SIGNUP_PER_IP_HOUR", 5),
  per_sub_day: int_env.("SIGNUP_PER_SUB_DAY", 3),
  global_per_day: int_env.("SIGNUP_GLOBAL_PER_DAY", 200)

config :risime, :social,
  requests_per_day: int_env.("FRIEND_REQUESTS_PER_DAY", 50),
  invites_per_user_per_day: int_env.("INVITES_PER_USER_PER_DAY", 20),
  invites_global_per_day: int_env.("INVITES_GLOBAL_PER_DAY", 200),
  invites_pending: int_env.("INVITES_PENDING", 50),
  invite_admins:
    (System.get_env("INVITE_ADMINS") || "")
    |> String.split(",", trim: true)
    |> Enum.map(&String.trim/1)
