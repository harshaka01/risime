defmodule RisiMeWeb.Router do
  use RisiMeWeb, :router

  pipeline :api do
    plug :accepts, ["json"]
  end

  pipeline :authenticated do
    plug RisiMeWeb.Plugs.RequireToken
  end

  # Ops, not wire protocol: no auth, outside /api/v1.
  scope "/", RisiMeWeb do
    pipe_through :api

    get "/health", HealthController, :show
  end

  scope "/api/v1", RisiMeWeb do
    pipe_through :api

    post "/auth/request", AuthController, :request
    post "/auth/verify", AuthController, :verify
  end

  scope "/api/v1", RisiMeWeb do
    pipe_through [:api, :authenticated]

    post "/auth/logout", AuthController, :logout
    get "/me", MeController, :show
    patch "/me", MeController, :update
    get "/contacts", ContactsController, :index
  end

  # Swoosh mailbox preview in development: the dev OTP emails land here.
  if Application.compile_env(:risime, :dev_routes) do
    scope "/dev" do
      pipe_through [:fetch_session, :protect_from_forgery]

      forward "/mailbox", Plug.Swoosh.MailboxPreview
    end
  end
end
