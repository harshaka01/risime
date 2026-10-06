defmodule RisiMeWeb.Plugs.RequireToken do
  @moduledoc """
  Authenticates `Authorization: Bearer <token>` (contract v1.3 §6.1) and assigns
  `:current_user` and `:current_auth` (`RisiMe.Auth.auth()`).
  Errors: 401 `invalid_token`, 403 `not_allowlisted`, 409 `identity_conflict`, then (unless
  the route is exempt, `phone_gate: false`) 403 `phone_unverified` (contract v1.4).
  """
  import Plug.Conn

  alias RisiMeWeb.ApiError

  def init(opts), do: Keyword.get(opts, :phone_gate, true)

  def call(conn, phone_gate?) do
    token =
      case get_req_header(conn, "authorization") do
        ["Bearer " <> token] -> String.trim(token)
        _ -> nil
      end

    case RisiMe.Auth.authenticate(token) do
      {:ok, auth} ->
        # Contract v1.4 §7.1: after 401 / 403 not_allowlisted / 409, the phone gate.
        if phone_gate? and not RisiMe.Accounts.phone_verified?(auth.user, auth.kind) do
          ApiError.send_error(conn, 403, :phone_unverified)
        else
          conn
          |> assign(:current_user, auth.user)
          |> assign(:current_auth, auth)
        end

      {:error, :invalid_token} ->
        ApiError.send_error(conn, 401, :invalid_token)

      {:error, :not_allowlisted} ->
        ApiError.send_error(conn, 403, :not_allowlisted)

      {:error, :identity_conflict} ->
        ApiError.send_error(conn, 409, :identity_conflict)
    end
  end
end
