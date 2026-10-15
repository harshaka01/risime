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

  # Decision 089: the admin console. Browser session (encrypted cookie), CSRF, strict CSP.
  pipeline :admin_browser do
    plug :accepts, ["html"]
    plug :fetch_session
    plug :fetch_live_flash
    plug :put_root_layout, html: {RisiMeWeb.Admin.Layouts, :root}
    plug :protect_from_forgery
    plug RisiMeWeb.Admin.Headers
  end

  pipeline :admin_required do
    plug RisiMeWeb.Admin.RequireAdmin
  end

  scope "/admin", RisiMeWeb.Admin do
    get "/assets/app.js", AssetsController, :app_js
  end

  scope "/admin", RisiMeWeb.Admin do
    pipe_through :admin_browser

    get "/sign-in", AuthController, :sign_in
    get "/auth/login", AuthController, :login
    get "/auth/callback", AuthController, :callback
    post "/sign-out", AuthController, :sign_out
  end

  scope "/admin", RisiMeWeb.Admin do
    pipe_through [:admin_browser, :admin_required]

    # Harsha's call-voice pick (2026-10-15): signed, 24-h sample links posted in his Risi chat.
    get "/voice-samples/:token", VoiceSampleController, :show

    live_session :admin, on_mount: RisiMeWeb.Admin.LiveAuth do
      live "/", SpeechLive, :index
      live "/speech", SpeechLive, :index
      live "/voices", VoicesLive, :index
      live "/:page", ComingSoonLive, :show
    end
  end

  # Ops, not wire protocol: no auth, outside /api/v1.
  scope "/", RisiMeWeb do
    pipe_through :api

    get "/health", HealthController, :show
    # v1.32 §32: loopback + bearer token only; 404 while OPS_ALERT_TOKEN is unset.
    post "/internal/ops-alert", OpsAlertController, :create
  end

  scope "/api/v1", RisiMeWeb do
    pipe_through :api

    get "/auth/config", AuthController, :config
    # v1.20 §21.3: authenticates the Keycloak token itself (the identity maps to no user yet).
    post "/auth/signup", AuthController, :signup
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
    post "/mls/groups/:conversation_id/rejoin", MLSController, :rejoin
    post "/groups", GroupController, :create
    get "/groups", GroupController, :index
    get "/groups/:id", GroupController, :show
    post "/groups/:id/members", GroupController, :add_members
    delete "/groups/:id/members/:user_id", GroupController, :remove_member
    patch "/groups/:id/members/:user_id", GroupController, :set_role
    post "/groups/:id/leave", GroupController, :leave
    post "/groups/:id/rejoin", GroupController, :rejoin
    get "/groups/:id/messages/:message_id/receipts", GroupController, :receipts
    # v1.24 §24.2, §24.4, §24.8.
    get "/chats", ChatController, :index
    get "/chats/:chat_id", ChatController, :show
    patch "/chats/:chat_id", ChatController, :update
    post "/chats/:chat_id/official", ChatController, :create_official
    # v1.24 §24.11.
    post "/risi/feedback", RisiController, :feedback
    # Proposal 2026-10-14-voice-translate (v1.50 §37.13.2): per-viewer Translate.
    post "/risi/translate", RisiController, :translate
    post "/risi/chat", RisiController, :create_chat
    get "/risi/facts", RisiController, :facts
    delete "/risi/facts/:fact_id", RisiController, :delete_fact
    delete "/risi/facts", RisiController, :delete_facts
    get "/risi/commitments", RisiController, :commitments
    # v1.25 §25.3.
    post "/risi/tool_calls/:id/result", RisiController, :tool_result
    # v1.26 §26.2, §26.4.
    get "/risi/skills", RisiSkillsController, :index
    patch "/risi/skills", RisiSkillsController, :update
    get "/risi/skills/:id/activity", RisiSkillsController, :activity
    delete "/risi/skills/:id/activity", RisiSkillsController, :clear
    post "/risi/skills/:id/activity/:entry_id/undo", RisiSkillsController, :undo
    # v1.29 §29.3.
    get "/risi/calendar/google", RisiGoogleController, :show
    put "/risi/calendar/google", RisiGoogleController, :put
    delete "/risi/calendar/google", RisiGoogleController, :delete
    get "/risi/calendar/events", RisiCalendarController, :index
    post "/risi/calendar/events", RisiCalendarController, :create
    get "/risi/calendar/changes", RisiCalendarController, :changes
    get "/risi/calendar/events/:id", RisiCalendarController, :show
    patch "/risi/calendar/events/:id", RisiCalendarController, :update
    delete "/risi/calendar/events/:id", RisiCalendarController, :delete
    post "/risi/calendar/events/:id/respond", RisiCalendarController, :respond
    post "/risi/calendar/suggestions/:suggestion_id/resolve", RisiCalendarController, :resolve
    get "/risi/calendar/settings", RisiCalendarController, :settings
    patch "/risi/calendar/settings", RisiCalendarController, :update_settings
    delete "/risi/calendar", RisiCalendarController, :delete_all
    # v1.29 §30.6 Risi Notes.
    # v1.35 §34.4 Risi's items.
    get "/risi/items", RisiItemsController, :index
    patch "/risi/items/:id", RisiItemsController, :update
    delete "/risi/items/:id", RisiItemsController, :delete

    get "/risi/notes", RisiNotesController, :index
    get "/risi/notes/:id", RisiNotesController, :show
    delete "/risi/notes/:id", RisiNotesController, :delete
    delete "/risi/notes", RisiNotesController, :delete_all
    # v1.36 §35.3: Risi memory controls (decision 077).
    delete "/risi/memory", RisiMemoryController, :delete
    put "/risi/learning", RisiMemoryController, :put_learning
    get "/risi/learning", RisiMemoryController, :learning
    # Decision 084: read-only deep-agents view, RISI_DEV_USERS only (404 otherwise).
    get "/risi/agents", RisiAgentsController, :show
    post "/blobs", BlobController, :create
    post "/backups", BackupController, :create
    get "/backups", BackupController, :index
    delete "/backups", BackupController, :delete
    put "/backup_key", BackupController, :put_key
    get "/backup_key", BackupController, :get_key
    get "/blobs/usage", BlobController, :usage
    delete "/blobs/:id", BlobController, :delete
    put "/me/devices/:device_id", DeviceController, :put
    delete "/me/devices/:device_id", DeviceController, :delete
    delete "/me/devices/:device_id/push_token", DeviceController, :unregister_push
    get "/contacts", ContactsController, :index
    get "/calls/turn", CallsController, :turn
    post "/calls/rooms", CallsController, :rooms
    # v1.39 §36.1, §36.3, §36.4: call transcription.
    get "/me/risi/dev", MeController, :risi_dev
    put "/me/risi/dev", MeController, :put_risi_dev
    # v1.41 §37.7: the sender's voice-note setting.
    get "/me/risi/voice", MeController, :risi_voice
    put "/me/risi/voice", MeController, :put_risi_voice
    # Proposal 2026-10-14-transcribe-progress (flag RISI_VOICE_PROGRESS): Stop / Try again.
    post "/me/risi/voice/cancel", MeController, :risi_voice_cancel
    post "/me/risi/voice/retry", MeController, :risi_voice_retry
    # Decision 092 (proposal 2026-10-14-private-transcribe): the Private translate request.
    # `log: false`: Phoenix never logs its parameters (the text).
    post "/translate", TranslateController, :create, log: false
    get "/calls/:call_id/transcribe", CallsController, :transcribe
    post "/calls/:call_id/transcribe/chunks", CallsController, :transcribe_chunk
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
