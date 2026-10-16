defmodule RisiRegistryWeb.Endpoint do
  use Phoenix.Endpoint, otp_app: :risi_registry

  # Signed and encrypted cookie, scoped to the page shell's path. `secure` is set in prod,
  # where the page is only served over https through the reverse proxy.
  @session_options [
    store: :cookie,
    key: "_risi_marketplace",
    signing_salt: "mkt-sign-v1",
    encryption_salt: "mkt-enc-v1",
    path: "/marketplace",
    same_site: "Lax",
    http_only: true,
    max_age: 8 * 60 * 60
  ]

  def session_options do
    Keyword.put(
      @session_options,
      :secure,
      Application.get_env(:risi_registry, :secure_cookie, false)
    )
  end

  plug Plug.RequestId
  plug Plug.Telemetry, event_prefix: [:phoenix, :endpoint]

  plug Plug.Parsers,
    parsers: [:urlencoded, :json],
    pass: ["*/*"],
    length: 64 * 1024,
    json_decoder: Phoenix.json_library()

  plug Plug.MethodOverride
  plug Plug.Head
  plug :session
  plug RisiRegistryWeb.Router

  defp session(conn, _opts), do: Plug.Session.call(conn, Plug.Session.init(session_options()))
end
