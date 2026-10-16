defmodule RisiRegistryWeb.PageController do
  use Phoenix.Controller, formats: [:html]
  import Plug.Conn
  alias RisiRegistry.Registry
  alias RisiRegistryWeb.{Pages, Plugs.RequireAllowed}

  def home(conn, _params) do
    if RequireAllowed.current_user(conn),
      do: redirect(conn, to: "/marketplace/agents"),
      else: html(conn, Pages.signed_out())
  end

  def agents(conn, _params) do
    html(
      conn,
      Pages.agents(Registry.list_all(), conn.assigns.current_user, get_csrf_token())
    )
  end
end
