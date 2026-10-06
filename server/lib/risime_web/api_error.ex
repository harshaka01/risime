defmodule RisiMeWeb.ApiError do
  @moduledoc "Sends a contract-shaped REST error."
  import Plug.Conn
  import Phoenix.Controller, only: [json: 2]

  @messages %{
    invalid_phone: "Phone must be in E.164 format, e.g. +94771234567",
    invalid_email: "Email address is invalid",
    rate_limited: "Too many requests, try again later",
    invalid_code: "The code is incorrect",
    expired: "The code has expired, request a new one",
    too_many_attempts: "Too many attempts, request a new code",
    invalid_token: "Missing, invalid or expired token",
    not_allowlisted: "This email is not on the RisiMe allowlist",
    identity_conflict: "This phone number is linked to another RisiCloud account",
    not_found: "Not found",
    phone_unverified: "Confirm your phone number to continue",
    already_verified: "Your phone number is already confirmed",
    invalid_device: "Device registration is invalid",
    sms_unavailable: "Couldn't send the SMS right now, try again shortly",
    invalid_display_name: "Display name must be 1-64 characters"
  }

  @doc """
  Sends `{"error": {"code", "message", ...extra}}`. Every 429 carries `Retry-After`
  (contract v1.4 §7.1): pass `retry_after: seconds` in `opts` (default 60).
  """
  def send_error(conn, status, code, opts \\ []) do
    %{error: error} = RisiMeWeb.ErrorJSON.error(code, Map.fetch!(@messages, code))
    error = Map.merge(error, Map.new(Keyword.get(opts, :extra, [])))

    conn =
      if status == 429,
        do:
          put_resp_header(
            conn,
            "retry-after",
            Integer.to_string(Keyword.get(opts, :retry_after, 60))
          ),
        else: conn

    conn
    |> put_status(status)
    |> json(%{error: error})
    |> halt()
  end
end
