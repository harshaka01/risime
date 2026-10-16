defmodule RisiRegistryWeb.Plugs.RequireAllowed do
  @moduledoc """
  Lets a request through only for a signed-in, allow-listed account. The allow-list is re-read
  on every request, so removing someone takes effect at once. Everyone else goes back to the
  sign-in page and sees no data.
  """
  import Plug.Conn
  import Phoenix.Controller, only: [redirect: 2]
  alias RisiRegistry.AllowList

  def init(opts), do: opts

  def call(conn, _opts) do
    case current_user(conn) do
      nil ->
        conn |> configure_session(drop: true) |> redirect(to: "/marketplace") |> halt()

      user ->
        assign(conn, :current_user, user)
    end
  end

  @doc "The session's user if they are still on the allow-list, else nil."
  def current_user(conn) do
    case get_session(conn, "user") do
      %{"email" => email} = user when is_binary(email) ->
        if AllowList.allowed?(email), do: user

      _ ->
        nil
    end
  end
end
