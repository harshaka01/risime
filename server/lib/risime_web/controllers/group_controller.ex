defmodule RisiMeWeb.GroupController do
  @moduledoc """
  Groups REST (contract v1.9 §12.3, §12.7). Mutating calls need `X-Device-Id` naming a
  groups-capable MLS device of the caller (`403 invalid_device`). A group the caller isn't a
  member of is always `404 not_found`.
  """
  use RisiMeWeb, :controller

  alias RisiMe.Groups
  alias RisiMe.Messaging.GroupReceipts
  alias RisiMeWeb.{ApiError, MLSController}

  defp me(conn), do: conn.assigns.current_user.id
  defp device(conn), do: MLSController.caller_device(conn)

  def create(conn, params) do
    case Groups.create(me(conn), device(conn), params) do
      {:ok, :created, group} -> conn |> put_status(201) |> json(%{group: group})
      {:ok, :existing, group} -> json(conn, %{group: group})
      error -> error(conn, error)
    end
  end

  # v1.24 §24.7: Official groups only for an `X-Device-Id` naming a `tabs` device.
  defp tabs?(conn), do: RisiMe.Devices.tabs_device?(me(conn), device(conn))

  def index(conn, _params), do: json(conn, %{groups: Groups.list(me(conn), tabs?(conn))})

  def show(conn, %{"id" => id}) do
    case Groups.show(me(conn), id, tabs?(conn)) do
      {:ok, group} -> json(conn, %{group: group})
      error -> error(conn, error)
    end
  end

  def add_members(conn, %{"id" => id} = params) do
    case Groups.add_members(me(conn), device(conn), id, params) do
      {:ok, group} -> json(conn, %{group: group})
      error -> error(conn, error)
    end
  end

  def remove_member(conn, %{"id" => id, "user_id" => user_id}) do
    case Groups.remove_member(me(conn), device(conn), id, user_id) do
      :ok -> send_resp(conn, 204, "")
      error -> error(conn, error)
    end
  end

  def leave(conn, %{"id" => id}) do
    case Groups.leave(me(conn), device(conn), id) do
      :ok -> send_resp(conn, 204, "")
      error -> error(conn, error)
    end
  end

  def set_role(conn, %{"id" => id, "user_id" => user_id} = params) do
    case Groups.set_role(me(conn), device(conn), id, user_id, params) do
      {:ok, group} -> json(conn, %{group: group})
      error -> error(conn, error)
    end
  end

  def rejoin(conn, %{"id" => id}) do
    case Groups.rejoin(me(conn), device(conn), id) do
      {:ok, reply} -> conn |> put_status(202) |> json(reply)
      error -> error(conn, error)
    end
  end

  def receipts(conn, %{"id" => id, "message_id" => message_id}) do
    case GroupReceipts.list(me(conn), id, String.downcase(message_id)) do
      {:ok, reply} -> json(conn, reply)
      error -> error(conn, error)
    end
  end

  @doc "Error replies for group calls (also used by `MLSController` for `grp:` conversations)."
  def error(conn, {:error, :invalid_device}), do: ApiError.send_error(conn, 403, :invalid_device)
  def error(conn, {:error, :not_admin}), do: ApiError.send_error(conn, 403, :not_admin)
  def error(conn, {:error, :not_member}), do: ApiError.send_error(conn, 403, :not_member)
  def error(conn, {:error, :not_friends}), do: ApiError.send_error(conn, 403, :not_friends)
  def error(conn, {:error, :not_found}), do: ApiError.send_error(conn, 404, :not_found)
  def error(conn, {:error, :last_admin}), do: ApiError.send_error(conn, 409, :last_admin)
  def error(conn, {:error, :log_expired}), do: ApiError.send_error(conn, 410, :log_expired)
  def error(conn, {:error, :too_large}), do: ApiError.send_error(conn, 413, :too_large)

  # v1.10 §13.4.
  def error(conn, {:error, {:quota_exceeded, used, limit}}),
    do: ApiError.send_error(conn, 413, :quota_exceeded, extra: [used: used, limit: limit])

  def error(conn, {:error, :invalid_role}), do: ApiError.send_error(conn, 422, :invalid_role)

  def error(conn, {:error, :too_many_members}),
    do: ApiError.send_error(conn, 422, :too_many_members)

  def error(conn, {:error, :too_many_devices}),
    do: ApiError.send_error(conn, 422, :too_many_devices)

  def error(conn, {:error, :mls_unavailable}),
    do: ApiError.send_error(conn, 503, :mls_unavailable)

  def error(conn, {:error, :rate_limited}),
    do: ApiError.send_error(conn, 429, :rate_limited, retry_after: 60)

  def error(conn, {:error, {:epoch_conflict, epoch}}),
    do: ApiError.send_error(conn, 409, :epoch_conflict, extra: [epoch: epoch])

  # v1.21 §12.12.3.
  def error(conn, {:error, {:rejoin_pending, op_id, n}}),
    do: ApiError.send_error(conn, 409, :rejoin_pending, extra: [op_id: op_id, candidates: n])

  def error(conn, {:error, {:generation_conflict, g}}),
    do: ApiError.send_error(conn, 409, :generation_conflict, extra: [generation: g])

  # §12.1: "<name> needs to update RisiMe" (the first missing user), with every missing entry.
  def error(conn, {:error, {:not_ready, missing}}) do
    name =
      case missing do
        [%{user_id: u} | _] ->
          case RisiMe.Repo.get(RisiMe.Accounts.User, u) do
            %{display_name: n} when is_binary(n) -> n
            _ -> "Someone"
          end

        _ ->
          "Someone"
      end

    ApiError.send_error(conn, 409, :not_ready,
      extra: [missing: missing],
      message: "#{name} needs to update RisiMe"
    )
  end

  def error(conn, _), do: ApiError.send_error(conn, 400, :bad_request)
end
