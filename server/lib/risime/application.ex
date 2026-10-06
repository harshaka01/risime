defmodule RisiMe.Application do
  # See https://elixir.hexdocs.pm/Application.html
  # for more information on OTP Applications
  @moduledoc false

  use Application

  require Logger

  @impl true
  def start(_type, _args) do
    if level = Application.get_env(:risime, :log_level), do: Logger.configure(level: level)
    if Application.get_env(:risime, :log_format) == :json, do: RisiMe.JSONLogFormatter.install()
    Logger.info("RisiMe server #{version()} starting")

    if Application.get_env(:risime, :push_sender) == RisiMe.Push.FCM and
         not File.regular?(Application.get_env(:risime, :fcm_service_account_file) || ""),
       do: Logger.error("FCM_ENABLED=true but FCM_SERVICE_ACCOUNT_FILE is missing or not a file")

    if Application.get_env(:risime, :env) == :prod and RisiMe.Auth.Config.dev_local_auth?(),
      do: Logger.warning("DEV_LOCAL_AUTH=true in prod: dev login and opaque tokens are enabled")

    RisiMe.TimeUUID.init()

    children = [
      RisiMeWeb.Telemetry,
      RisiMe.Repo,
      {Oban, Application.fetch_env!(:risime, Oban)},
      RisiMe.Messaging.Store.Cassandra,
      RisiMe.RateLimiter,
      {DNSCluster, query: Application.get_env(:risime, :dns_cluster_query) || :ignore},
      {Phoenix.PubSub, name: RisiMe.PubSub},
      RisiMe.Presence,
      RisiMe.SocketTracker,
      RisiMe.Auth.JWKS,
      RisiMe.MLS.Attestation,
      RisiMe.Auth,
      RisiMe.Accounts.ResetListener,
      RisiMe.Accounts.SmsStatus,
      {Task.Supervisor, name: RisiMe.Push.TaskSupervisor},
      RisiMe.Push.FCM,
      RisiMe.Push.Dispatcher,
      # Start a worker by calling: RisiMe.Worker.start_link(arg)
      # {RisiMe.Worker, arg},
      # Start to serve requests, typically the last entry
      RisiMeWeb.Endpoint
    ]

    # See https://elixir.hexdocs.pm/Supervisor.html
    # for other strategies and supported options
    opts = [strategy: :one_for_one, name: RisiMe.Supervisor]
    Supervisor.start_link(children, opts)
  end

  @doc false
  def version, do: :risime |> Application.spec(:vsn) |> to_string()

  # Tell Phoenix to update the endpoint configuration
  # whenever the application is updated.
  @impl true
  def config_change(changed, _new, removed) do
    RisiMeWeb.Endpoint.config_change(changed, removed)
    :ok
  end
end
