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
    invalid_display_name: "Display name must be 1-64 characters"
  }

  def send_error(conn, status, code) do
    conn
    |> put_status(status)
    |> json(RisiMeWeb.ErrorJSON.error(code, Map.fetch!(@messages, code)))
    |> halt()
  end
end
