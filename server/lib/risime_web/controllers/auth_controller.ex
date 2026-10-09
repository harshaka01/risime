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

    body =
      body
      |> Map.put(:phone_verification, phone)
      |> Map.put(:signup, if(Accounts.open_signup?(), do: "open", else: "invite"))
      # v1.22 §22.1.
      |> Map.put(:backup, RisiMe.Backups.switch())

    # v1.24 §24.15: `tabs: "on"` while TABS=on; left out while off (absent = off), so the reply
    # stays exactly v1.23 until the switch.
    body = if RisiMe.Risi.tabs_on?(), do: Map.put(body, :tabs, "on"), else: body
    # v1.25 §25.8: `risi_tools: "on"` while RISI_TOOLS=on; left out while off (absent = off).
    body = if RisiMe.Risi.tools_on?(), do: Map.put(body, :risi_tools, "on"), else: body
    # v1.26 §26.9: `risi_skills: "on"` while RISI_SKILLS=on and the skills can run (their
    # RISI_MEMORY_KEY is set); left out otherwise (absent = off).
    body = if RisiMe.Agent.Skills.on?(), do: Map.put(body, :risi_skills, "on"), else: body
    # v1.27 §27.10: `risi_ledger` / `risi_transcribe` "on" while switched on (absent = off).
    body = if RisiMe.Risi.ledger_on?(), do: Map.put(body, :risi_ledger, "on"), else: body
    body = if RisiMe.Risi.transcribe_on?(), do: Map.put(body, :risi_transcribe, "on"), else: body
    # v1.29 §29.1: `risi_events: "on"` while RISI_EVENTS=on (absent = off: v1.28 exactly).
    body = if RisiMe.Risi.events_on?(), do: Map.put(body, :risi_events, "on"), else: body
    # v1.29 §30.1: `risi_notes: "on"` while RISI_NOTES=on (only with the ledger; absent = off).
    body = if RisiMe.Risi.notes_on?(), do: Map.put(body, :risi_notes, "on"), else: body

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

  @doc "`POST /auth/signup` (contract v1.20 §21.3)."
  def signup(conn, params) do
    alias RisiMe.Auth.{Config, JWT}

    token =
      case get_req_header(conn, "authorization") do
        ["Bearer " <> token] -> String.trim(token)
        _ -> nil
      end

    cond do
      not Accounts.open_signup?() ->
        failure(conn, :signup_refused)
        ApiError.send_error(conn, 403, :signup_closed)

      not (Config.oidc_enabled?() and JWT.jwt_shape?(token)) ->
        failure(conn, :invalid_token)
        ApiError.send_error(conn, 401, :invalid_token)

      true ->
        case JWT.verify(token) do
          {:ok, identity} ->
            signup_result(
              conn,
              Accounts.signup(identity, params, RisiMeWeb.ClientIP.from_conn(conn))
            )

          {:error, _} ->
            failure(conn, :invalid_token)
            ApiError.send_error(conn, 401, :invalid_token)
        end
    end
  end

  defp signup_result(conn, {:ok, user}),
    do: json(conn, %{user: ApiJSON.user(user, Accounts.phone_verified?(user, :jwt))})

  defp signup_result(conn, {:error, {:rate_limited, seconds}}) do
    failure(conn, :signup_rate_limited)
    ApiError.send_error(conn, 429, :rate_limited, retry_after: seconds)
  end

  defp signup_result(conn, {:error, :bad_request}) do
    failure(conn, :signup_refused)
    ApiError.send_error(conn, 400, :bad_request)
  end

  defp signup_result(conn, {:error, :phone_taken}) do
    failure(conn, :signup_refused)
    ApiError.send_error(conn, 409, :phone_taken)
  end

  defp signup_result(conn, {:error, :not_allowlisted}) do
    failure(conn, :not_allowlisted)
    ApiError.send_error(conn, 403, :not_allowlisted)
  end

  defp signup_result(conn, {:error, :identity_conflict}) do
    failure(conn, :identity_conflict)
    ApiError.send_error(conn, 409, :identity_conflict)
  end

  # With a JWT this is a no-op: the client ends its Keycloak session itself (§6.1).
  def logout(conn, _params) do
    case conn.assigns.current_auth do
      %{kind: :dev, token_record: token} ->
        :ok = Accounts.revoke_token(token)
        # Decision 050: a plain logout keeps MLS devices in their groups; push stops.
        RisiMe.Devices.clear_push_for_token(token.id)
        RisiMeWeb.Endpoint.broadcast(RisiMeWeb.UserSocket.id_for(token), "disconnect", %{})

      %{kind: :jwt} ->
        :ok
    end

    send_resp(conn, 204, "")
  end
end
