defmodule RisiMeWeb.Plugs.RequireToken do
  @moduledoc """
  Authenticates `Authorization: Bearer <token>` (contract v1.3 §6.1) and assigns
  `:current_user` and `:current_auth` (`RisiMe.Auth.auth()`).
  Errors: 401 `invalid_token`, 403 `not_allowlisted`, 409 `identity_conflict`.
  """
  import Plug.Conn

  alias RisiMeWeb.ApiError

  def init(opts), do: opts

  def call(conn, _opts) do
    token =
      case get_req_header(conn, "authorization") do
        ["Bearer " <> token] -> String.trim(token)
        _ -> nil
      end

    case RisiMe.Auth.authenticate(token) do
      {:ok, auth} ->
        conn
        |> assign(:current_user, auth.user)
        |> assign(:current_auth, auth)

      {:error, :invalid_token} ->
        ApiError.send_error(conn, 401, :invalid_token)

      {:error, :not_allowlisted} ->
        ApiError.send_error(conn, 403, :not_allowlisted)

      {:error, :identity_conflict} ->
        ApiError.send_error(conn, 409, :identity_conflict)
    end
  end
end
