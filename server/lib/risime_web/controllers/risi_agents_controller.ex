defmodule RisiMeWeb.RisiAgentsController do
  @moduledoc """
  Decision 084 (deep agents): a read-only admin view for Risi developers. Not wire protocol for
  apps: `404` for anyone not in `RISI_DEV_USERS` (the same group as §36.1 compare mode).

    * `GET /api/v1/risi/agents` → `{"agents": [spec…], "flags": [{name, value, from}],
      "scores": [row…] (7 days), "incidents": [row…] (latest 50)}`.

  Counts, codes and timings only: no chat text.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agents.{Flags, Runs, Scoreboard}
  alias RisiMeWeb.ApiError

  def show(conn, params) do
    if Flags.dev_user?(conn.assigns.current_user.id) do
      days = min(max(String.to_integer(params["days"] || "7"), 1), 90)

      json(conn, %{
        "agents" =>
          for s <- RisiMe.Agents.specs() do
            %{
              "name" => s.name,
              "type" => s.type,
              "goal" => s.goal,
              "status" => s.status,
              "tools" => s.tools,
              "budget" => s.budget,
              "learned_version" => s.learned_version,
              "mode" => Flags.mode(s.name)
            }
          end,
        "flags" => Flags.all(),
        "scores" => Scoreboard.rows(days),
        "incidents" => Runs.incidents(50)
      })
    else
      ApiError.send_error(conn, 404, :not_found)
    end
  rescue
    ArgumentError -> ApiError.send_error(conn, 422, :bad_request)
  end
end
