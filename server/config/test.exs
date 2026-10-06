import Config

# Configure your database
#
# The MIX_TEST_PARTITION environment variable can be used
# to provide built-in test partitioning in CI environment.
# Run `mix help test` for more information.
config :risime, RisiMe.Repo,
  username: "risime",
  hostname: "127.0.0.1",
  database: "risime_test#{System.get_env("MIX_TEST_PARTITION")}",
  pool: Ecto.Adapters.SQL.Sandbox,
  pool_size: System.schedulers_online() * 2

# We don't run a server during test. If one is required,
# you can enable the server option below.
config :risime, RisiMeWeb.Endpoint,
  http: [ip: {127, 0, 0, 1}, port: 4002],
  secret_key_base: "Lx6Q1pTc3qmAK21gSlZvRSSrKxsfEu27gxCOcQWjFk4hPhvmdrPn/SDI5tz99q7g",
  server: false

config :risime, :cassandra,
  nodes: ["127.0.0.1:9042"],
  keyspace: "risime_test",
  pool_size: 2,
  sync_connect: 10_000

config :risime, :otp_dev_log, false

# Short presence grace period so the offline path is testable (5 s in dev/prod).
config :risime, :presence_grace_ms, 150

# Jobs are only inserted (and asserted with Oban.Testing); no queues or plugins run.
config :risime, Oban, testing: :manual

# Both sign-in modes in test. Tests install locally generated keys as a static JWKS.
config :risime, :dev_local_auth, true

config :risime, :oidc,
  enabled: true,
  issuer: "https://risicloud.ai/realms/aoa",
  client_id: "risime",
  jwks: {:static, %{"keys" => []}},
  req_options: [plug: {Req.Test, RisiMe.Auth.JWKS}]

config :risime, :auth_expiry_grace_ms, 300

# SMS codes go to the test process; Notify.lk is only ever reached through Req.Test stubs.
config :risime, :sms_mode, :test
config :risime, :notifylk, req_options: [plug: {Req.Test, RisiMe.NotifyLk}]

# Push: off by default in tests; push tests switch to RisiMe.Push.Test. FCM is only ever
# reached through Req.Test stubs.
config :risime, :push_sender, nil
config :risime, :push_coalesce_ms, 300
config :risime, :push_retry_ms, 10
config :risime, :fcm, req_options: [plug: {Req.Test, RisiMe.FCM}]

# fail2ban auth log: a per-run temp file in tests.
config :risime, :auth_log_path, Path.join(System.tmp_dir!(), "risime-test-auth.log")

# In test we don't send emails
config :risime, RisiMe.Mailer, adapter: Swoosh.Adapters.Test

# Disable swoosh api client as it is only required for production adapters
config :swoosh, :api_client, false

# Print only warnings and errors during test
config :logger, level: :warning

# Initialize plugs at runtime for faster test compilation
config :phoenix, :plug_init_mode, :runtime

# Sort query params output of verified routes for robust url comparisons
config :phoenix,
  sort_verified_routes_query_params: true
