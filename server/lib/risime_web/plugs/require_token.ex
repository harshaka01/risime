defmodule RisiMeWeb.Plugs.RequireToken do
  @moduledoc "Authenticates `Authorization: Bearer <token>`; assigns :current_user and :current_token."
  import Plug.Conn

  def init(opts), do: opts

  def call(conn, _opts) do
    with ["Bearer " <> token] <- get_req_header(conn, "authorization"),
         {user, token_record} <- RisiMe.Accounts.fetch_by_token(String.trim(token)) do
      conn
      |> assign(:current_user, user)
      |> assign(:current_token, token_record)
    else
      _ -> RisiMeWeb.ApiError.send_error(conn, 401, :unauthorized)
    end
  end
end
