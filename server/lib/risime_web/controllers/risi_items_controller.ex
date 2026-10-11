defmodule RisiMeWeb.RisiItemsController do
  @moduledoc """
  `/api/v1/risi/items…` (contract v1.35 §34.4, `RisiMe.Agent.RisiItems`): `403 invalid_device`
  unless `X-Device-Id` names a device of the caller; `404 not_found`, `422 bad_request`,
  `503 agent_unavailable`.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Agent.RisiItems
  alias RisiMeWeb.{ApiError, GroupController, MLSController}

  defp me(conn), do: conn.assigns.current_user.id

  defp gate(conn, fun) do
    device = MLSController.caller_device(conn)

    if is_binary(device) and RisiMe.Devices.get(me(conn), device) != nil,
      do: fun.(device),
      else: ApiError.send_error(conn, 403, :invalid_device)
  end

  def index(conn, params) do
    gate(conn, fn device ->
      case RisiItems.rest_list(me(conn), device, params) do
        {:ok, reply} -> json(conn, reply)
        error -> error(conn, error)
      end
    end)
  end

  def update(conn, %{"id" => id} = params) do
    gate(conn, fn device ->
      case RisiItems.rest_patch(me(conn), device, id, Map.delete(params, "id")) do
        {:ok, reply} -> json(conn, reply)
        error -> error(conn, error)
      end
    end)
  end

  def delete(conn, %{"id" => id}) do
    gate(conn, fn device ->
      case RisiItems.rest_delete(me(conn), device, id) do
        :ok -> send_resp(conn, 204, "")
        error -> error(conn, error)
      end
    end)
  end

  defp error(conn, {:error, :bad_request}), do: ApiError.send_error(conn, 422, :bad_request)
  defp error(conn, error), do: GroupController.error(conn, error)
end
