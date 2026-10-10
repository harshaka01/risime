defmodule RisiMeWeb.OpsAlertController do
  @moduledoc """
  `POST /internal/ops-alert` (contract v1.32 §32): loopback only, bearer `OPS_ALERT_TOKEN`
  (constant-time compare), refused when any proxy header is present, 10 per hour.
  """
  use RisiMeWeb, :controller

  alias RisiMe.{OpsAlert, RateLimiter}
  alias RisiMeWeb.ApiError

  @proxy_headers ~w(x-forwarded-for forwarded x-real-ip)

  def create(conn, params) do
    with {:ok, token} <- configured(),
         :ok <- authorize(conn, token),
         :ok <- limit(),
         {:ok, {state, check, detail}} <- parse(params) do
      %{sent: sent, held: held} = OpsAlert.send_alert(state, check, detail)
      conn |> put_status(202) |> json(%{sent: sent, held: held})
    else
      :off -> ApiError.send_error(conn, 404, :not_found)
      :unauthorized -> ApiError.send_error(conn, 401, :invalid_token)
      :rate_limited -> ApiError.send_error(conn, 429, :rate_limited, retry_after: 3600)
      :bad_request -> ApiError.send_error(conn, 422, :bad_request)
    end
  end

  defp configured do
    case OpsAlert.token() do
      nil -> :off
      t -> {:ok, t}
    end
  end

  defp authorize(conn, token) do
    with true <- loopback?(conn.remote_ip),
         false <- Enum.any?(@proxy_headers, &(get_req_header(conn, &1) != [])),
         ["Bearer " <> given] <- get_req_header(conn, "authorization"),
         true <- Plug.Crypto.secure_compare(given, token) do
      :ok
    else
      _ -> :unauthorized
    end
  end

  defp loopback?({127, _, _, _}), do: true
  defp loopback?({0, 0, 0, 0, 0, 0, 0, 1}), do: true
  defp loopback?({0, 0, 0, 0, 0, 65535, 32512, 1}), do: true
  defp loopback?(_), do: false

  defp limit do
    case RateLimiter.hit(:ops_alert, OpsAlert.bucket_key(), 10, :timer.hours(1)) do
      :ok -> :ok
      _ -> :rate_limited
    end
  end

  defp parse(params) do
    case OpsAlert.parse(params) do
      {:ok, v} -> {:ok, v}
      :error -> :bad_request
    end
  end
end
