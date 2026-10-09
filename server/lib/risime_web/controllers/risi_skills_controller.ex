defmodule RisiMeWeb.RisiSkillsController do
  @moduledoc """
  `/api/v1/risi/skills…` (contract v1.26 §26.2, §26.4): the skills, their per-user state and the
  device's reported permission, the activity log and undo (`RisiMe.Agent.Skills`). Every call
  answers `503 agent_unavailable` while the skills are off (`RISI_SKILLS` off, or no
  `RISI_MEMORY_KEY`).
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agent.Skills
  alias RisiMeWeb.{ApiError, GroupController, MLSController}

  defp me(conn), do: conn.assigns.current_user.id

  defp on(conn, fun) do
    if Skills.on?(), do: fun.(), else: GroupController.error(conn, {:error, :agent_unavailable})
  end

  def index(conn, _params) do
    on(conn, fn ->
      json(conn, %{skills: Skills.list(me(conn), MLSController.caller_device(conn))})
    end)
  end

  def update(conn, _params) do
    on(conn, fn ->
      case Skills.patch(me(conn), MLSController.caller_device(conn), conn.body_params) do
        {:ok, skills} -> json(conn, %{skills: skills})
        error -> error(conn, error)
      end
    end)
  end

  def activity(conn, %{"id" => id} = params) do
    on(conn, fn ->
      case Skills.activity(me(conn), id, Map.take(params, ["before", "limit"])) do
        {:ok, reply} -> json(conn, reply)
        error -> error(conn, error)
      end
    end)
  end

  def clear(conn, %{"id" => id}) do
    on(conn, fn ->
      case Skills.clear(me(conn), id) do
        :ok -> send_resp(conn, 204, "")
        error -> error(conn, error)
      end
    end)
  end

  def undo(conn, %{"id" => id, "entry_id" => entry_id}) do
    on(conn, fn ->
      case Skills.undo(me(conn), id, entry_id, conn.body_params) do
        {:ok, status, entry} -> conn |> put_status(status) |> json(%{entry: entry})
        error -> error(conn, error)
      end
    end)
  end

  defp error(conn, {:error, :bad_request}), do: ApiError.send_error(conn, 422, :bad_request)
  defp error(conn, error), do: GroupController.error(conn, error)
end
