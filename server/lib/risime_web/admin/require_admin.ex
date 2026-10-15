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
        conn
        |> remember_sample_link()
        |> Phoenix.Controller.redirect(to: "/admin/sign-in")
        |> halt()
    end
  end

  # A voice-sample link (2026-10-15) opened before signing in comes back after the sign-in.
  defp remember_sample_link(conn) do
    if return_to?(conn.request_path),
      do: put_session(conn, "admin_return_to", conn.request_path),
      else: conn
  end

  @doc "True for a path the sign-in may return to (a voice-sample link only)."
  def return_to?(path) when is_binary(path),
    do: Regex.match?(~r|\A/admin/voice-samples/[A-Za-z0-9_\-.]{1,600}\z|, path)

  def return_to?(_), do: false
end
