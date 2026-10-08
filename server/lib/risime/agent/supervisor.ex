defmodule RisiMe.Agent.Supervisor do
  @moduledoc """
  The Risi agent tree (`rest_for_one`, see `RisiMe.Agent`). A crash of `Agent.Mls` restarts
  everything after it, so no process ever holds a handle that is behind the database.
  """
  use Supervisor

  def start_link(opts \\ []), do: Supervisor.start_link(__MODULE__, opts, name: __MODULE__)

  @impl true
  def init(_opts) do
    children = [
      {Registry, keys: :unique, name: RisiMe.Agent.Registry},
      RisiMe.Agent.Mls,
      RisiMe.Agent.KeyPackages,
      RisiMe.Agent.ConversationSup,
      RisiMe.Agent.Inbox
    ]

    Supervisor.init(children, strategy: :rest_for_one, max_restarts: 5, max_seconds: 60)
  end
end
