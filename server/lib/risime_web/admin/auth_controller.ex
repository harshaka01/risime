defmodule RisiMeWeb.Admin.AuthController do
  @moduledoc """
  Admin console sign-in (decision 089): Keycloak authorization code + PKCE (`RisiMe.Admin.OIDC`)
  with the same realm and client as the app. The `state` and PKCE verifier wait in the encrypted
  session cookie (single use, 10 min). After the callback the identity must map to an existing
  RisiMe account (`users.keycloak_sub`) that is an active admin; anyone else gets a plain 403.
  The session is renewed on sign-in (no fixation) and holds only the user id and the time.
  Keycloak tokens are verified and dropped, never stored.
  """
  use RisiMeWeb, :controller

  require Logger

  alias RisiMe.Accounts.User
  alias RisiMe.Admin
  alias RisiMe.Admin.OIDC
  alias RisiMe.Repo
  alias RisiMeWeb.Admin.Session

  @pending_ttl_s 600

  def sign_in(conn, _params) do
    if Session.admin_id(get_session(conn)) do
      redirect(conn, to: "/admin")
    else
      enabled = RisiMe.Auth.Config.oidc_enabled?()

      conn
      |> put_resp_content_type("text/html")
      |> send_resp(200, sign_in_page(enabled))
    end
  end

  def login(conn, _params) do
    if RisiMe.Auth.Config.oidc_enabled?() do
      {url, pending} = OIDC.start(redirect_uri())

      conn
      |> put_session("admin_oidc", %{
        "state" => pending.state,
        "verifier" => pending.verifier,
        "at" => System.os_time(:second)
      })
      |> redirect(external: url)
    else
      conn |> put_resp_content_type("text/plain") |> send_resp(503, "Keycloak sign-in is off\n")
    end
  end

  def callback(conn, %{"code" => code, "state" => state})
      when is_binary(code) and is_binary(state) do
    pending = get_session(conn, "admin_oidc")
    conn = delete_session(conn, "admin_oidc")

    with %{"state" => expected, "verifier" => verifier, "at" => at} <- pending,
         true <- System.os_time(:second) - at < @pending_ttl_s,
         true <- Plug.Crypto.secure_compare(expected, state),
         {:ok, %{sub: sub, email: email}} <- OIDC.finish(code, verifier, redirect_uri()),
         %User{disabled_at: nil, kind: "user"} = user <- Repo.get_by(User, keycloak_sub: sub) do
      Admin.seed_signin(user, email)

      if Admin.admin?(user.id) do
        Admin.audit(user.id, "sign_in", "admin console")

        conn
        |> configure_session(renew: true)
        |> put_session("admin_user_id", user.id)
        |> put_session("admin_at", System.os_time(:second))
        |> redirect(to: "/admin")
      else
        Logger.info("admin console: sign-in refused (not an admin)")
        conn |> configure_session(drop: true) |> Session.forbidden()
      end
    else
      reason ->
        Logger.info("admin console: sign-in refused (#{refusal(reason)})")
        conn |> configure_session(drop: true) |> Session.forbidden()
    end
  end

  def callback(conn, _params) do
    conn |> delete_session("admin_oidc") |> Session.forbidden()
  end

  def sign_out(conn, _params) do
    case Session.admin_id(get_session(conn)) do
      nil -> :ok
      id -> Admin.audit(id, "sign_out", "admin console")
    end

    conn |> configure_session(drop: true) |> redirect(to: "/admin/sign-in")
  end

  @doc "The OIDC redirect URI: the public URL + /admin/auth/callback."
  def redirect_uri, do: RisiMeWeb.Endpoint.url() <> "/admin/auth/callback"

  defp refusal({:error, reason}) when is_atom(reason), do: reason
  defp refusal(nil), do: :no_pending_state
  defp refusal(false), do: :state
  defp refusal(%User{}), do: :account_disabled
  defp refusal(_), do: :no_account

  defp sign_in_page(enabled) do
    body =
      if enabled,
        do:
          ~s(<p><a class="btn" href="/admin/auth/login">Sign in with your RisiMe account</a></p>),
        else: ~s[<p class="warn">Keycloak sign-in is off on this server (OIDC_ENABLED).</p>]

    """
    <!DOCTYPE html>
    <html lang="en"><head><meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <meta name="robots" content="noindex, nofollow" />
    <title>RisiMe admin</title><style>#{RisiMeWeb.Admin.Layouts.css()}</style></head>
    <body><div class="center"><h1>RisiMe admin</h1>
    <p class="muted">For RisiMe administrators only.</p>#{body}</div></body></html>
    """
  end
end
