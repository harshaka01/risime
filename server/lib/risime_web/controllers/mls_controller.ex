defmodule RisiMeWeb.MLSController do
  @moduledoc """
  E2EE endpoints (contract v1.7 §10). The calling device is identified by the `X-Device-Id`
  header (required for commits, optional for claims; decision 034).
  """
  use RisiMeWeb, :controller

  alias RisiMe.MLS
  alias RisiMe.MLS.Attestation
  alias RisiMeWeb.ApiError

  defp me(conn), do: conn.assigns.current_user.id

  defp caller_device(conn) do
    case get_req_header(conn, "x-device-id") do
      [id] ->
        case Ecto.UUID.cast(id),
          do: (
            {:ok, id} -> id
            :error -> nil
          )

      _ ->
        nil
    end
  end

  def attestation_keys(conn, _params) do
    if Attestation.available?(),
      do: json(conn, %{keys: Attestation.public_keys()}),
      else: ApiError.send_error(conn, 503, :mls_unavailable)
  end

  def upload_key_packages(conn, %{"device_id" => device_id} = params) do
    case MLS.upload_key_packages(me(conn), device_id, params) do
      :ok -> send_resp(conn, 204, "")
      error -> error(conn, error)
    end
  end

  def key_package_count(conn, %{"device_id" => device_id}) do
    case MLS.key_package_count(me(conn), device_id) do
      {:ok, n} -> json(conn, %{count: n})
      error -> error(conn, error)
    end
  end

  def claim(conn, params) do
    case MLS.claim(me(conn), params["user_ids"], caller_device(conn)) do
      {:ok, devices} -> json(conn, %{devices: devices})
      error -> error(conn, error)
    end
  end

  def group(conn, %{"conversation_id" => conv}) do
    case MLS.group_view(me(conn), conv) do
      {:ok, view} -> json(conn, view)
      error -> error(conn, error)
    end
  end

  def commit(conn, %{"conversation_id" => conv} = params) do
    case MLS.commit(me(conn), caller_device(conn), conv, params) do
      {:ok, epoch} -> json(conn, %{epoch: epoch})
      error -> error(conn, error)
    end
  end

  def commits(conn, %{"conversation_id" => conv} = params) do
    since =
      case Integer.parse(params["since_epoch"] || "0") do
        {n, ""} -> n
        _ -> 0
      end

    case MLS.commits_since(me(conn), conv, since) do
      {:ok, commits} -> json(conn, %{commits: commits})
      error -> error(conn, error)
    end
  end

  defp error(conn, {:error, :mls_unavailable}),
    do: ApiError.send_error(conn, 503, :mls_unavailable)

  defp error(conn, {:error, :invalid_device}), do: ApiError.send_error(conn, 422, :invalid_device)
  defp error(conn, {:error, :not_friends}), do: ApiError.send_error(conn, 403, :not_friends)
  defp error(conn, {:error, :not_found}), do: ApiError.send_error(conn, 404, :not_found)

  defp error(conn, {:error, :rate_limited}),
    do: ApiError.send_error(conn, 429, :rate_limited, retry_after: 60)

  defp error(conn, {:error, {:epoch_conflict, epoch}}),
    do: ApiError.send_error(conn, 409, :epoch_conflict, extra: [epoch: epoch])

  defp error(conn, {:error, {:not_ready, missing}}),
    do: ApiError.send_error(conn, 409, :not_ready, extra: [missing: missing])

  defp error(conn, _), do: ApiError.send_error(conn, 400, :bad_request)
end
