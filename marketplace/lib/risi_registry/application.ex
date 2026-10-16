defmodule RisiRegistry.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    children = [
      RisiRegistry.Repo,
      RisiRegistry.Cards.Cache,
      RisiRegistry.OIDC.Discovery,
      RisiRegistryWeb.Endpoint
    ]

    Supervisor.start_link(children, strategy: :one_for_one, name: RisiRegistry.Supervisor)
  end

  @impl true
  def config_change(changed, _new, removed) do
    RisiRegistryWeb.Endpoint.config_change(changed, removed)
    :ok
  end
end
