# This file is responsible for configuring your application
# and its dependencies with the aid of the Config module.
#
# This configuration file is loaded before any dependency and
# is restricted to this project.

# General application configuration
import Config

config :risime,
  namespace: RisiMe,
  ecto_repos: [RisiMe.Repo],
  generators: [timestamp_type: :utc_datetime, binary_id: true]

# Configure the endpoint
config :risime, RisiMeWeb.Endpoint,
  url: [host: "localhost"],
  adapter: Bandit.PhoenixAdapter,
  render_errors: [
    formats: [json: RisiMeWeb.ErrorJSON],
    layout: false
  ],
  pubsub_server: RisiMe.PubSub,
  live_view: [signing_salt: "9jWWlwaT"]

config :risime, :otp_dev_log, false

config :risime, :cassandra,
  nodes: ["127.0.0.1:9042"],
  keyspace: "risime_dev",
  pool_size: 4

# Background jobs (docs/decisions/004-oban-background-jobs.md). Cron times are UTC.
config :risime, Oban,
  engine: Oban.Engines.Basic,
  repo: RisiMe.Repo,
  queues: [maintenance: 1],
  plugins: [
    # Completed/cancelled/discarded jobs are deleted after 7 days.
    {Oban.Plugins.Pruner, max_age: 7 * 24 * 60 * 60},
    {Oban.Plugins.Lifeline, rescue_after: :timer.minutes(30)},
    {Oban.Plugins.Cron,
     crontab: [
       {"17 3 * * *", RisiMe.Workers.PruneAccounts}
     ]}
  ]

# Configure the mailer
#
# By default it uses the "Local" adapter which stores the emails
# locally. You can see the emails in your browser, at "/dev/mailbox".
#
# For production it's recommended to configure a different adapter
# at the `config/runtime.exs`.
config :risime, RisiMe.Mailer, adapter: Swoosh.Adapters.Local

# Configure Elixir's Logger
config :logger, :default_formatter,
  format: "$time $metadata[$level] $message\n",
  metadata: [:request_id]

# Use Jason for JSON parsing in Phoenix
config :phoenix, :json_library, Jason

# Import environment specific config. This must remain at the bottom
# of this file so it overrides the configuration defined above.
import_config "#{config_env()}.exs"
