defmodule RisiMeWeb.MeController do
  use RisiMeWeb, :controller

  alias RisiMe.Accounts
  alias RisiMeWeb.{ApiError, ApiJSON}

  def show(conn, _params), do: json(conn, %{user: user_json(conn, conn.assigns.current_user)})

  def update(conn, params) do
    case Accounts.update_profile(conn.assigns.current_user, Map.take(params, ["display_name"])) do
      {:ok, user} -> json(conn, %{user: user_json(conn, user)})
      {:error, _changeset} -> ApiError.send_error(conn, 422, :invalid_display_name)
    end
  end

  @doc "`POST /me/phone/verify/request` (contract v1.4 §7.1)."
  def phone_request(conn, _params) do
    result =
      if conn.assigns.current_auth.kind == :dev,
        do: {:error, :already_verified},
        else: Accounts.request_phone_verification(conn.assigns.current_user)

    case result do
      {:ok, %{expires_in: expires_in, to: to}} ->
        json(conn, %{status: "sent", expires_in: expires_in, to: to})

      {:error, :already_verified} ->
        ApiError.send_error(conn, 409, :already_verified)

      {:error, {:rate_limited, seconds}} ->
        failure(conn, :rate_limited)
        ApiError.send_error(conn, 429, :rate_limited, retry_after: seconds)

      {:error, :sms_unavailable} ->
        ApiError.send_error(conn, 503, :sms_unavailable)
    end
  end

  @doc "`POST /me/phone/verify/confirm` (contract v1.4 §7.1)."
  def phone_confirm(conn, params) do
    result =
      if conn.assigns.current_auth.kind == :dev,
        do: {:error, :already_verified},
        else: Accounts.confirm_phone_verification(conn.assigns.current_user, params["code"])

    case result do
      {:ok, user} ->
        json(conn, %{user: user_json(conn, user)})

      {:error, :already_verified} ->
        ApiError.send_error(conn, 409, :already_verified)

      {:error, {:invalid_code, nil}} ->
        failure(conn, :phone_code_invalid)
        ApiError.send_error(conn, 401, :invalid_code)

      {:error, {:invalid_code, left}} ->
        failure(conn, :phone_code_invalid)
        ApiError.send_error(conn, 401, :invalid_code, extra: [attempts_left: left])

      {:error, :expired} ->
        ApiError.send_error(conn, 410, :expired)

      {:error, :too_many_attempts} ->
        failure(conn, :too_many_attempts)
        ApiError.send_error(conn, 429, :too_many_attempts, retry_after: 60)
    end
  end

  defp failure(conn, kind),
    do: RisiMe.AuthLog.failure(RisiMeWeb.ClientIP.from_conn(conn), kind, conn.request_path)

  defp user_json(conn, user),
    do: ApiJSON.user(user, Accounts.phone_verified?(user, conn.assigns.current_auth.kind))
end
