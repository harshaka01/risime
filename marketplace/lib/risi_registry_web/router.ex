defmodule RisiRegistryWeb.Router do
  use Phoenix.Router
  import Plug.Conn
  import Phoenix.Controller

  pipeline :api do
    plug :accepts, ["json"]
  end

  pipeline :browser do
    plug :accepts, ["html"]
    plug :fetch_session
    plug :protect_from_forgery

    plug :put_secure_browser_headers, %{
      "content-security-policy" =>
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
      "referrer-policy" => "no-referrer"
    }
  end

  pipeline :signed_in do
    plug RisiRegistryWeb.Plugs.RequireAllowed
  end

  # The read-only registry API (phase 0). Loopback only; Risi is the caller.
  scope "/api/v1", RisiRegistryWeb do
    pipe_through :api
    get "/agents", AgentApiController, :index
    get "/agents/:domain/:slug", AgentApiController, :show
    get "/agents/:domain/:slug/status", AgentApiController, :status
  end

  scope "/", RisiRegistryWeb do
    pipe_through :api
    get "/health", HealthController, :show
  end

  # The signed-in page shell (phase 1, first piece). The only prefix a reverse proxy forwards.
  scope "/marketplace", RisiRegistryWeb do
    pipe_through :browser
    get "/", PageController, :home
    get "/auth/login", AuthController, :login
    get "/auth/callback", AuthController, :callback
    post "/auth/logout", AuthController, :logout
  end

  scope "/marketplace", RisiRegistryWeb do
    pipe_through [:browser, :signed_in]
    get "/agents", PageController, :agents
  end
end
