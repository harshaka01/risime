defmodule RisiMeWeb.OpsRouter do
  @moduledoc false
  use Phoenix.Router
  import Phoenix.LiveDashboard.Router

  pipeline :browser do
    plug :fetch_session
    plug :fetch_query_params
    plug :protect_from_forgery
    plug :put_secure_browser_headers
  end

  scope "/" do
    pipe_through :browser

    live_dashboard "/dashboard",
      metrics: RisiMeWeb.Telemetry,
      env_keys: ["MIX_ENV"],
      csp_nonce_assign_key: nil
  end
end
