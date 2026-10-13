defmodule RisiMeWeb.Admin.Session do
  @moduledoc """
  The admin console's session (decision 089). After a Keycloak sign-in the encrypted session
  cookie holds `admin_user_id` and `admin_at` (unix seconds). A session is valid for 12 h and
  only while the user is still an active admin: `admin?/1` is checked on every request, every
  LiveView mount and every LiveView event, so removing an admin ends their access at once.
  """
  @max_age_s 12 * 3600

  @doc "The admin's user id from a session map (string keys), or nil."
  def admin_id(%{"admin_user_id" => id, "admin_at" => at})
      when is_binary(id) and is_integer(at) do
    now = System.os_time(:second)

    if now - at < @max_age_s and now >= at - 60 and RisiMe.Admin.admin?(id), do: id
  end

  def admin_id(_), do: nil

  @doc "A plain 403 (non-admins)."
  def forbidden(conn) do
    conn
    |> Plug.Conn.put_resp_content_type("text/plain")
    |> Plug.Conn.send_resp(403, "403 Forbidden\n")
    |> Plug.Conn.halt()
  end
end
