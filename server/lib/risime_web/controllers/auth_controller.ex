defmodule RisiMeWeb.AuthController do
  use RisiMeWeb, :controller

  alias RisiMe.Accounts
  alias RisiMeWeb.{ApiError, ApiJSON}

  @doc "`GET /auth/config` (contract v1.3 §6.1): which sign-in modes this server offers."
  def config(conn, _params) do
    alias RisiMe.Auth.Config
    modes = Config.modes()

    phone = if Accounts.phone_verification_required?(), do: "required", else: "off"

    body =
      if "oidc" in modes,
        do: %{modes: modes, issuer: Config.oidc(:issuer), client_id: Config.oidc(:client_id)},
        else: %{modes: modes}

    body = Map.put(body, :phone_verification, phone)

    json(conn, body)
  end

  # Per client IP, on top of the per-phone limit (decision 024).
  @request_ip_limit 10
  @verify_ip_limit 20
  @ip_window :timer.minutes(15)

  def request(conn, params) do
    with :ok <- ip_limit(conn, :auth_request_ip, @request_ip_limit) do
      case Accounts.request_otp(params["phone"], params["email"]) do
        :ok ->
          json(conn, %{status: "sent", expires_in: Accounts.otp_ttl_seconds()})

        {:error, :rate_limited} ->
          failure(conn, :rate_limited)
          ApiError.send_error(conn, 429, :rate_limited, retry_after: 900)

        {:error, code} ->
          ApiError.send_error(conn, 422, code)
      end
    end
  end

  def verify(conn, params) do
    with :ok <- ip_limit(conn, :auth_verify_ip, @verify_ip_limit) do
      do_verify(conn, params)
    end
  end

  defp do_verify(conn, params) do
    case Accounts.verify_otp(params["phone"], params["code"], params["device_name"]) do
      # A dev-login session counts as verified (contract v1.4 §7.1).
      {:ok, token, user} ->
        json(conn, %{token: token, user: ApiJSON.user(user, true)})

      {:error, :invalid_code} ->
        failure(conn, :invalid_code)
        ApiError.send_error(conn, 401, :invalid_code)

      {:error, :expired} ->
        ApiError.send_error(conn, 410, :expired)

      {:error, :too_many_attempts} ->
        failure(conn, :too_many_attempts)
        ApiError.send_error(conn, 429, :too_many_attempts, retry_after: 60)
    end
  end

  defp ip_limit(conn, bucket, limit) do
    case RisiMe.RateLimiter.hit(bucket, RisiMeWeb.ClientIP.from_conn(conn), limit, @ip_window) do
      :ok ->
        :ok

      {:error, :rate_limited} ->
        failure(conn, :rate_limited)

        ApiError.send_error(conn, 429, :rate_limited,
          retry_after: RisiMe.RateLimiter.retry_after_s(@ip_window)
        )
    end
  end

  defp failure(conn, kind),
    do: RisiMe.AuthLog.failure(RisiMeWeb.ClientIP.from_conn(conn), kind, conn.request_path)

  # With a JWT this is a no-op: the client ends its Keycloak session itself (§6.1).
  def logout(conn, _params) do
    case conn.assigns.current_auth do
      %{kind: :dev, token_record: token} ->
        :ok = Accounts.revoke_token(token)
        RisiMe.Devices.delete_for_token(token.id)
        RisiMeWeb.Endpoint.broadcast(RisiMeWeb.UserSocket.id_for(token), "disconnect", %{})

      %{kind: :jwt} ->
        :ok
    end

    send_resp(conn, 204, "")
  end
end
