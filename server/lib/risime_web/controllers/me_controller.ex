defmodule RisiMeWeb.MeController do
  use RisiMeWeb, :controller

  alias RisiMe.Accounts
  alias RisiMeWeb.{ApiError, ApiJSON}

  def show(conn, _params), do: json(conn, %{user: user_json(conn, conn.assigns.current_user)})

  # v1.24 §24.11: `{"tz": "<IANA zone>"}` alone sets the timezone (`422 bad_request` for an
  # unknown zone); `display_name` as before. Both may come together (tz is checked first).
  def update(conn, params) do
    user = conn.assigns.current_user

    # Every field is validated before anything is written (one update).
    with :ok <- check_tz(params),
         {:ok, user} <- Accounts.update_me(user, Map.take(params, ["tz", "display_name"])) do
      json(conn, %{user: user_json(conn, user)})
    else
      {:error, :bad_tz} -> ApiError.send_error(conn, 422, :bad_request)
      {:error, _changeset} -> ApiError.send_error(conn, 422, :invalid_display_name)
    end
  end

  defp check_tz(%{"tz" => tz}), do: if(Accounts.valid_tz?(tz), do: :ok, else: {:error, :bad_tz})
  defp check_tz(_params), do: :ok

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

  # v1.24 §24.11: `User` gains `tz` once set (absent: the server default).
  defp user_json(conn, user) do
    json = ApiJSON.user(user, Accounts.phone_verified?(user, conn.assigns.current_auth.kind))
    if user.tz, do: Map.put(json, :tz, user.tz), else: json
  end
end
