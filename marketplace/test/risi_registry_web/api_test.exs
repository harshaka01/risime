defmodule RisiRegistryWeb.ApiTest do
  use RisiRegistryWeb.ConnCase, async: false
  alias RisiRegistry.Registry

  test "discover lists only active, verified agents and never card bodies", %{conn: conn} do
    {a, _} = active_agent("a.test", "good")
    active_agent("b.test", "pending", approve: false)

    body = conn |> get("/api/v1/agents?skill=test.ask") |> json_response(200)
    assert [%{"agent_id" => id, "card_url" => _, "tier" => "listed"}] = body["agents"]
    assert id == a.agent_id
    refute Map.has_key?(hd(body["agents"]), "signatures")
  end

  test "get returns a record, 404 for unknown", %{conn: conn} do
    {a, _} = active_agent("a.test", "good")

    assert %{"agent" => %{"agent_id" => "a.test/good"}} =
             conn |> get("/api/v1/agents/a.test/good") |> json_response(200)

    assert a.agent_id == "a.test/good"

    assert %{"error" => "not_found"} =
             conn |> get("/api/v1/agents/a.test/nope") |> json_response(404)
  end

  test "status: callable for an active verified agent, not after delisting", %{conn: conn} do
    {a, _} = active_agent("a.test", "good")

    assert %{"callable" => true, "status" => "active"} =
             conn |> get("/api/v1/agents/a.test/good/status") |> json_response(200)

    {:ok, _} = Registry.delist(a.agent_id, "admin:test", "test")

    assert %{"callable" => false, "status" => "delisted", "reason" => "not_active:delisted"} =
             conn |> get("/api/v1/agents/a.test/good/status") |> json_response(200)
  end

  test "health", %{conn: conn} do
    assert %{"ok" => true} = conn |> get("/health") |> json_response(200)
  end
end
