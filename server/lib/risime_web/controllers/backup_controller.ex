defmodule RisiMeWeb.BackupController do
  @moduledoc "Encrypted backups (contract v1.22 §22.3): `/backups` and `/backup_key`."
  use RisiMeWeb, :controller

  alias RisiMe.Backups
  alias RisiMeWeb.{ApiError, GroupController}

  defp me(conn), do: conn.assigns.current_user.id
  defp device(conn), do: List.first(get_req_header(conn, "x-device-id"))

  def create(conn, params) do
    case Backups.commit(me(conn), device(conn), params) do
      {:ok, :created, backup} -> conn |> put_status(201) |> json(%{backup: backup})
      {:ok, :replay, backup} -> json(conn, %{backup: backup})
      error -> error(conn, error)
    end
  end

  def index(conn, _params), do: json(conn, Backups.list(me(conn)))

  def delete(conn, _params) do
    :ok = Backups.delete_all(me(conn))
    send_resp(conn, 204, "")
  end

  def put_key(conn, params) do
    case Backups.put_key(me(conn), device(conn), params) do
      {:ok, key} -> json(conn, %{backup_key: key})
      error -> error(conn, error)
    end
  end

  def get_key(conn, _params) do
    case Backups.get_key(me(conn)) do
      {:ok, key} -> json(conn, %{backup_key: key})
      error -> error(conn, error)
    end
  end

  defp error(conn, {:error, :backup_unavailable}),
    do: ApiError.send_error(conn, 503, :backup_unavailable)

  defp error(%{method: "GET"} = conn, {:error, :no_backup_key}),
    do: ApiError.send_error(conn, 404, :no_backup_key)

  defp error(conn, {:error, :no_backup_key}), do: ApiError.send_error(conn, 409, :no_backup_key)

  defp error(conn, {:error, {:backup_key_conflict, bk_id}}),
    do: ApiError.send_error(conn, 409, :backup_key_conflict, extra: [bk_id: bk_id])

  defp error(conn, {:error, {:backup_device_mismatch, device_id, name}}),
    do:
      ApiError.send_error(conn, 409, :backup_device_mismatch,
        extra: [device_id: device_id, device_name: name]
      )

  defp error(conn, {:error, {:rate_limited, s}}),
    do: ApiError.send_error(conn, 429, :rate_limited, retry_after: s)

  defp error(conn, error), do: GroupController.error(conn, error)
end
