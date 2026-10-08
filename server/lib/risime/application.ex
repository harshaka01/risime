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

    # v1.13 §16.7: a short TURN_SECRET counts as unset (never logs the value).
    RisiMe.Calls.Turn.boot_check()

    RisiMe.TimeUUID.init()
    # v1.24 §24.7: the Official-conversation cache of the `tabs` filter.
    RisiMe.Groups.Tabs.init()
    # v1.24 §24.13: the global Risi queue (16 model calls in flight).
    RisiMe.Agent.LLM.init_gate()
    # v1.11 §14.8: temp files of uploads killed with the previous run.
    RisiMe.Blobs.sweep_tmp()

    children = [
      RisiMeWeb.Telemetry,
      RisiMe.Repo,
      {Oban, Application.fetch_env!(:risime, Oban)},
      RisiMe.Messaging.Store.Cassandra,
      RisiMe.RateLimiter,
      RisiMe.Blobs.Slots,
      RisiMe.Blobs.DiskGuard,
      {DNSCluster, query: Application.get_env(:risime, :dns_cluster_query) || :ignore},
      {Phoenix.PubSub, name: RisiMe.PubSub},
      RisiMe.Presence,
      RisiMe.Messaging.GroupReceipts,
      RisiMe.SocketTracker,
      RisiMe.Auth.JWKS,
      RisiMe.MLS.Attestation,
      RisiMe.Auth,
      RisiMe.Accounts.ResetListener,
      RisiMe.Accounts.SmsStatus,
      {Task.Supervisor, name: RisiMe.Push.TaskSupervisor},
      RisiMe.Push.FCM,
      RisiMe.Push.Dispatcher,
      RisiMe.Calls.State,
      # v1.23 §23.6: per-room locks and the tokens minted in the last 600 s.
      RisiMe.Calls.RoomMemory,
      # v1.19 §20.2: LiveKit RemoveParticipant calls off the group-lock path.
      {Task.Supervisor, name: RisiMe.Calls.TaskSupervisor}
    ]

    # v1.24 S5: the Risi agent tree, only with RISI=on, the MLS NIF and its keys. Started after
    # the endpoint by RisiMe.Agent.Starter, which can't fail: a tree that fails to start or
    # keeps crashing only makes Risi unavailable (/health checks.risi), never stops the boot or
    # the server (P0 2026-10-08).
    children = children ++ [RisiMeWeb.Endpoint] ++ RisiMe.Agent.children()

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
