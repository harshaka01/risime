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
    unauthorized: "Missing or invalid token",
    invalid_display_name: "Display name must be 1-64 characters"
  }

  def send_error(conn, status, code) do
    conn
    |> put_status(status)
    |> json(RisiMeWeb.ErrorJSON.error(code, Map.fetch!(@messages, code)))
    |> halt()
  end
end
