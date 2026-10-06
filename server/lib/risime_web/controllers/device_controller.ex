defmodule RisiMeWeb.DeviceController do
  @moduledoc "`PUT` / `DELETE /api/v1/me/devices/{device_id}` (contract v1.5 §8.1)."
  use RisiMeWeb, :controller

  alias RisiMe.Devices
  alias RisiMeWeb.ApiError

  def put(conn, %{"device_id" => device_id} = params) do
    auth = conn.assigns.current_auth
    token_id = if auth.kind == :dev, do: auth.token_record.id

    case Devices.register(conn.assigns.current_user.id, device_id, params, token_id) do
      {:ok, nil} -> send_resp(conn, 204, "")
      {:ok, attestation} -> json(conn, %{attestation: attestation})
      {:error, :invalid_device} -> ApiError.send_error(conn, 422, :invalid_device)
      {:error, :mls_unavailable} -> ApiError.send_error(conn, 503, :mls_unavailable)
      {:error, :rate_limited} -> ApiError.send_error(conn, 429, :rate_limited, retry_after: 3600)
    end
  end

  def delete(conn, %{"device_id" => device_id}) do
    case Devices.delete(conn.assigns.current_user.id, device_id) do
      :ok -> send_resp(conn, 204, "")
      {:error, :invalid_device} -> ApiError.send_error(conn, 422, :invalid_device)
    end
  end
end
