defmodule RisiMeWeb.Plugs.DevLocalAuth do
  @moduledoc "The dev login routes exist only with `DEV_LOCAL_AUTH=true` (contract v1.3 §6.1); else 404."
  def init(opts), do: opts

  def call(conn, _opts) do
    if RisiMe.Auth.Config.dev_local_auth?(),
      do: conn,
      else: RisiMeWeb.ApiError.send_error(conn, 404, :not_found)
  end
end
