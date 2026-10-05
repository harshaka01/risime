defmodule RisiMeWeb.MeController do
  use RisiMeWeb, :controller

  alias RisiMe.Accounts
  alias RisiMeWeb.{ApiError, ApiJSON}

  def show(conn, _params), do: json(conn, %{user: ApiJSON.user(conn.assigns.current_user)})

  def update(conn, params) do
    case Accounts.update_profile(conn.assigns.current_user, Map.take(params, ["display_name"])) do
      {:ok, user} -> json(conn, %{user: ApiJSON.user(user)})
      {:error, _changeset} -> ApiError.send_error(conn, 422, :invalid_display_name)
    end
  end
end
