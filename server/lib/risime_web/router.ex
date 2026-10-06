defmodule RisiMeWeb.Router do
  use RisiMeWeb, :router

  pipeline :api do
    plug :accepts, ["json"]
  end

  pipeline :dev_auth do
    plug RisiMeWeb.Plugs.DevLocalAuth
  end

  pipeline :authenticated do
    plug RisiMeWeb.Plugs.RequireToken
  end

  # Exempt from the phone gate (contract v1.4 §7.1).
  pipeline :authenticated_ungated do
    plug RisiMeWeb.Plugs.RequireToken, phone_gate: false
  end

  # Ops, not wire protocol: no auth, outside /api/v1.
  scope "/", RisiMeWeb do
    pipe_through :api

    get "/health", HealthController, :show
  end

  scope "/api/v1", RisiMeWeb do
    pipe_through :api

    get "/auth/config", AuthController, :config
    get "/mls/attestation_keys", MLSController, :attestation_keys
  end

  scope "/api/v1", RisiMeWeb do
    pipe_through [:api, :dev_auth]

    post "/auth/request", AuthController, :request
    post "/auth/verify", AuthController, :verify
  end

  scope "/api/v1", RisiMeWeb do
    pipe_through [:api, :authenticated_ungated]

    post "/auth/logout", AuthController, :logout
    get "/me", MeController, :show
    post "/me/phone/verify/request", MeController, :phone_request
    post "/me/phone/verify/confirm", MeController, :phone_confirm
  end

  scope "/api/v1", RisiMeWeb do
    pipe_through [:api, :authenticated]

    patch "/me", MeController, :update
    post "/invites", SocialController, :create_invite
    get "/invites", SocialController, :list_invites
    delete "/invites/:id", SocialController, :revoke_invite
    get "/friends", SocialController, :index
    post "/friends/requests", SocialController, :request
    post "/friends/requests/:id/accept", SocialController, :accept
    post "/friends/requests/:id/decline", SocialController, :decline
    delete "/friends/requests/:id", SocialController, :cancel
    delete "/friends/:user_id", SocialController, :unfriend
    post "/blocks", SocialController, :block
    delete "/blocks/:user_id", SocialController, :unblock
    post "/me/devices/:device_id/key_packages", MLSController, :upload_key_packages
    get "/me/devices/:device_id/key_packages/count", MLSController, :key_package_count
    post "/mls/key_packages/claim", MLSController, :claim
    get "/mls/groups/:conversation_id", MLSController, :group
    post "/mls/groups/:conversation_id/commit", MLSController, :commit
    get "/mls/groups/:conversation_id/commits", MLSController, :commits
    post "/mls/groups/:conversation_id/reset", MLSController, :reset
    post "/groups", GroupController, :create
    get "/groups", GroupController, :index
    get "/groups/:id", GroupController, :show
    post "/groups/:id/members", GroupController, :add_members
    delete "/groups/:id/members/:user_id", GroupController, :remove_member
    patch "/groups/:id/members/:user_id", GroupController, :set_role
    post "/groups/:id/leave", GroupController, :leave
    post "/groups/:id/rejoin", GroupController, :rejoin
    get "/groups/:id/messages/:message_id/receipts", GroupController, :receipts
    post "/blobs", BlobController, :create
    get "/blobs/usage", BlobController, :usage
    delete "/blobs/:id", BlobController, :delete
    put "/me/devices/:device_id", DeviceController, :put
    delete "/me/devices/:device_id", DeviceController, :delete
    get "/contacts", ContactsController, :index
  end

  # v1.11 §14.2: blob bytes are served to any `Accept` (no JSON content negotiation).
  scope "/api/v1", RisiMeWeb do
    pipe_through [:authenticated]

    get "/blobs/:id", BlobController, :show
  end

  # Swoosh mailbox preview in development: the dev OTP emails land here.
  if Application.compile_env(:risime, :dev_routes) do
    scope "/dev" do
      pipe_through [:fetch_session, :protect_from_forgery]

      forward "/mailbox", Plug.Swoosh.MailboxPreview
    end
  end
end
