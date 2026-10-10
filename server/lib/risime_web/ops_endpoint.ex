defmodule RisiMeWeb.OpsEndpoint do
  @moduledoc """
  Decision 075: `/metrics` (PromEx) and Phoenix LiveDashboard on a separate listener bound to
  `127.0.0.1` only (`METRICS_PORT`, default 4021, `off` disables). Never routed on the main
  endpoint, so Caddy's public host cannot reach either. Admission is by oauth2-proxy in front.
  """
  use Phoenix.Endpoint, otp_app: :risime

  @session_options [
    store: :cookie,
    key: "_risime_ops_key",
    signing_salt: "ops-session-9Fq2",
    same_site: "Lax"
  ]

  socket "/live", Phoenix.LiveView.Socket, websocket: [connect_info: [session: @session_options]]

  plug PromEx.Plug, prom_ex_module: RisiMe.PromEx

  plug Plug.Parsers,
    parsers: [:urlencoded, :multipart, :json],
    pass: ["*/*"],
    json_decoder: Phoenix.json_library()

  plug Plug.MethodOverride
  plug Plug.Head
  plug Plug.Session, @session_options
  plug RisiMeWeb.OpsRouter
end
