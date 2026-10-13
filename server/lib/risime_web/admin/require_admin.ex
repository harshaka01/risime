defmodule RisiMeWeb.Admin.RequireAdmin do
  @moduledoc """
  Admin console pages (decision 089): no session → the sign-in page; a session whose user is
  no longer an admin → a plain 403.
  """
  import Plug.Conn

  alias RisiMeWeb.Admin.Session

  def init(opts), do: opts

  def call(conn, _opts) do
    session = get_session(conn)

    cond do
      Session.admin_id(session) ->
        conn

      is_binary(session["admin_user_id"]) ->
        conn |> configure_session(drop: true) |> Session.forbidden()

      true ->
        conn |> Phoenix.Controller.redirect(to: "/admin/sign-in") |> halt()
    end
  end
end
