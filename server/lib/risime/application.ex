defmodule RisiMe.Application do
  # See https://elixir.hexdocs.pm/Application.html
  # for more information on OTP Applications
  @moduledoc false

  use Application

  require Logger

  @impl true
  def start(_type, _args) do
    Logger.info("RisiMe server #{version()} starting")
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
