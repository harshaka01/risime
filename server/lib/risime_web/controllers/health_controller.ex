defmodule RisiMeWeb.HealthController do
  @moduledoc """
  `GET /health` (ops, not part of the wire protocol; no auth). Checks Postgres and Cassandra;
  `checks.sms` is informational (`RisiMe.Accounts.SmsStatus`).
  200 `{"status": "ok", ...}` when both answer, otherwise 503 `{"status": "error", ...}`.
  Only says "ok"/"error" per dependency, never error details.
  """
  use RisiMeWeb, :controller

  require Logger

  def show(conn, _params) do
    checks = %{
      "postgres" => check(:postgres, &postgres/0),
      "cassandra" => check(:cassandra, &RisiMe.Messaging.store_health/0)
    }

    healthy = Enum.all?(checks, fn {_, v} -> v == "ok" end)

    # SMS is a detail for ops, never a reason for 503 (decision 022); never the balance.
    checks = Map.put(checks, "sms", RisiMe.Accounts.SmsStatus.check())
    # Blob disk space (v1.11, decision 042): "low" under the warning level; never a 503.
    checks = Map.put(checks, "blob_storage", RisiMe.Blobs.DiskGuard.health())
    # Risi (P0 2026-10-08): "off" | "ok" | "unavailable: <reason>"; never a 503, never a secret.
    checks = Map.put(checks, "risi", risi())

    conn
    |> put_status(if healthy, do: 200, else: 503)
    |> json(%{
      status: if(healthy, do: "ok", else: "error"),
      version: RisiMe.Application.version(),
      checks: checks
    })
  end

  defp risi do
    RisiMe.Agent.health()
  rescue
    _ -> "unavailable: error"
  end

  defp postgres do
    case Ecto.Adapters.SQL.query(RisiMe.Repo, "SELECT 1", [], timeout: 2_000) do
      {:ok, _} -> :ok
      {:error, e} -> {:error, e}
    end
  end

  defp check(name, fun) do
    case fun.() do
      :ok ->
        "ok"

      {:error, reason} ->
        Logger.warning("health: #{name} failed: #{inspect(reason, limit: 5)}")
        "error"
    end
  rescue
    e ->
      Logger.warning("health: #{name} raised #{inspect(e.__struct__)}")
      "error"
  catch
    :exit, _ ->
      Logger.warning("health: #{name} exited")
      "error"
  end
end
