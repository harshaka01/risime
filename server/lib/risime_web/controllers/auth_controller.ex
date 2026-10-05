defmodule RisiMeWeb.AuthController do
  use RisiMeWeb, :controller

  alias RisiMe.Accounts
  alias RisiMeWeb.{ApiError, ApiJSON}

  def request(conn, params) do
    case Accounts.request_otp(params["phone"], params["email"]) do
      :ok -> json(conn, %{status: "sent", expires_in: Accounts.otp_ttl_seconds()})
      {:error, :rate_limited} -> ApiError.send_error(conn, 429, :rate_limited)
      {:error, code} -> ApiError.send_error(conn, 422, code)
    end
  end

  def verify(conn, params) do
    case Accounts.verify_otp(params["phone"], params["code"], params["device_name"]) do
      {:ok, token, user} -> json(conn, %{token: token, user: ApiJSON.user(user)})
      {:error, :invalid_code} -> ApiError.send_error(conn, 401, :invalid_code)
      {:error, :expired} -> ApiError.send_error(conn, 410, :expired)
      {:error, :too_many_attempts} -> ApiError.send_error(conn, 429, :too_many_attempts)
    end
  end

  def logout(conn, _params) do
    token = conn.assigns.current_token
    :ok = Accounts.revoke_token(token)
    RisiMeWeb.Endpoint.broadcast(RisiMeWeb.UserSocket.id_for(token), "disconnect", %{})
    send_resp(conn, 204, "")
  end
end
