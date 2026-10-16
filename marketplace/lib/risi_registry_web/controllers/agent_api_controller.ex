defmodule RisiRegistryWeb.AgentApiController do
  @moduledoc """
  The read-only registry API: discover, get, status (agent-connector.md section 6, review
  change 1). It never returns card bodies, only registry records and card URLs.
  """
  use Phoenix.Controller, formats: [:json]
  import Plug.Conn
  alias RisiRegistry.Registry

  # GET /api/v1/agents?skill=&tier=&q=  (only active, verified agents)
  def index(conn, params) do
    agents = Registry.discover(Map.take(params, ["skill", "tier", "q"]))
    json(conn, %{agents: agents})
  end

  # GET /api/v1/agents/:domain/:slug
  def show(conn, %{"domain" => domain, "slug" => slug}) do
    case Registry.get(domain <> "/" <> slug) do
      nil -> not_found(conn)
      agent -> json(conn, %{agent: agent})
    end
  end

  # GET /api/v1/agents/:domain/:slug/status  (the check a caller runs before every call)
  def status(conn, %{"domain" => domain, "slug" => slug}) do
    agent_id = domain <> "/" <> slug
    checked_at = DateTime.utc_now() |> DateTime.to_iso8601()

    case Registry.check(agent_id) do
      {:ok, result} ->
        json(conn, %{
          agent_id: agent_id,
          callable: true,
          status: result.status,
          verification: result.verification,
          checked_at: checked_at
        })

      {:error, :not_found} ->
        not_found(conn)

      {:error, reason} ->
        agent = Registry.get(agent_id)

        json(conn, %{
          agent_id: agent_id,
          callable: false,
          status: agent && agent.status,
          verification: agent && agent.verification,
          reason: reason_code(reason),
          checked_at: checked_at
        })
    end
  end

  defp reason_code({:not_active, status}), do: "not_active:" <> status
  defp reason_code(reason), do: to_string(reason)

  defp not_found(conn), do: conn |> put_status(404) |> json(%{error: "not_found"})
end
