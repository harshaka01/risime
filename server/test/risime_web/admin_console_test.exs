defmodule RisiMeWeb.AdminConsoleTest do
  @moduledoc """
  Decision 089: the admin console at /admin. Keycloak sign-in (authorization code + PKCE, the
  token endpoint a Req.Test stub, access tokens signed by the test JWKS), the session, the
  plain 403 for non-admins, CSRF, the pages, and the Speech models page's save and test.
  """
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Accounts
  alias RisiMe.Admin
  alias RisiMe.Admin.{AuditEntry, OIDC, Settings}
  alias RisiMe.Agent.SpeechRoutes
  alias RisiMe.Repo
  alias RisiMeWeb.Admin.SpeechLive

  setup do
    Settings.refresh()
    on_exit(&Settings.refresh/0)
    :ok
  end

  defp user(email) do
    sub = Ecto.UUID.generate()
    allowlist_entry(%{email: email})
    {:ok, user} = Accounts.map_identity(%{sub: sub, email: email})
    {user, sub}
  end

  defp admin(email) do
    {u, sub} = user(email)
    {:ok, _} = Admin.seed(email)
    {u, sub}
  end

  defp admin_conn(conn, user_id) do
    Plug.Test.init_test_session(conn, %{
      "admin_user_id" => user_id,
      "admin_at" => System.os_time(:second)
    })
  end

  # Follows the login redirect and returns {conn after login, state}.
  defp start_login(conn) do
    conn = get(conn, "/admin/auth/login")

    assert "https://risicloud.ai/realms/aoa/protocol/openid-connect/auth?" <> q =
             redirected_to(conn)

    params = URI.decode_query(q)
    assert params["client_id"] == "risime"
    assert params["response_type"] == "code"
    assert params["code_challenge_method"] == "S256"
    assert params["redirect_uri"] == "http://localhost:4002/admin/auth/callback"
    assert params["scope"] =~ "openid"
    {conn, params}
  end

  defp stub_token(sub, email, check \\ fn _ -> :ok end) do
    Req.Test.stub(OIDC, fn conn ->
      {:ok, body, conn} = Plug.Conn.read_body(conn)
      form = URI.decode_query(body)
      check.(form)
      assert form["grant_type"] == "authorization_code"
      assert form["client_id"] == "risime"
      assert is_binary(form["code_verifier"])
      Req.Test.json(conn, %{"access_token" => access_token(email, %{"sub" => sub})})
    end)
  end

  describe "access" do
    test "no session: /admin redirects to the sign-in page", %{conn: conn} do
      assert get(conn, "/admin") |> redirected_to() == "/admin/sign-in"
      assert get(conn, "/admin/speech") |> redirected_to() == "/admin/sign-in"

      html = get(conn, "/admin/sign-in") |> html_response(200)
      assert html =~ "/admin/auth/login"
    end

    test "a session of a non-admin (or a removed admin) gets a plain 403", %{conn: conn} do
      {u, _} = user("plain@example.com")
      conn = conn |> admin_conn(u.id) |> get("/admin/speech")
      assert conn.status == 403
      assert conn.resp_body == "403 Forbidden\n"
    end

    test "an expired session is not accepted", %{conn: conn} do
      {a, _} = admin("old@example.com")

      conn =
        conn
        |> Plug.Test.init_test_session(%{
          "admin_user_id" => a.id,
          "admin_at" => System.os_time(:second) - 13 * 3600
        })
        |> get("/admin/speech")

      assert conn.status == 403
    end

    test "an admin sees the shell and the Speech models page", %{conn: conn} do
      {a, _} = admin("boss@example.com")
      conn = conn |> admin_conn(a.id) |> get("/admin")
      html = html_response(conn, 200)

      for nav <- ["Speech models", "Intelligence", "Agents", "Servers", "Users and admins"],
          do: assert(html =~ nav)

      assert html =~ "Mixed / Singlish"
      assert html =~ ~s(name="csrf-token")
      assert html =~ "/admin/assets/app.js"

      [csp] = get_resp_header(conn, "content-security-policy")
      assert csp =~ "frame-ancestors 'none'"
      assert csp =~ "script-src 'self'"
      assert get_resp_header(conn, "cache-control") == ["no-store"]
    end

    test "pages not built yet say coming soon", %{conn: conn} do
      {a, _} = admin("boss2@example.com")
      conn = admin_conn(conn, a.id)

      for page <- ~w(intelligence agents servers users) do
        assert get(conn, "/admin/" <> page) |> html_response(200) =~ "Coming soon."
      end
    end

    test "the script bundle is served", %{conn: conn} do
      conn = get(conn, "/admin/assets/app.js")
      assert conn.status == 200
      assert conn.resp_body =~ "LiveSocket(\"/admin/live\""
    end

    test "sign-out needs the CSRF token", %{conn: conn} do
      {a, _} = admin("csrf@example.com")

      assert_raise Plug.CSRFProtection.InvalidCSRFTokenError, fn ->
        conn
        |> put_private(:plug_skip_csrf_protection, false)
        |> admin_conn(a.id)
        |> post("/admin/sign-out", %{})
      end
    end
  end

  describe "Keycloak sign-in (code + PKCE)" do
    test "an admin signs in: session set, audit-logged, PKCE verifier matches", %{conn: conn} do
      {a, sub} = admin("kc@example.com")
      {conn, params} = start_login(conn)

      stub_token(sub, "kc@example.com", fn form ->
        challenge =
          :crypto.hash(:sha256, form["code_verifier"]) |> Base.url_encode64(padding: false)

        assert challenge == params["code_challenge"]
        assert form["code"] == "the-code"
      end)

      conn =
        get(recycle(conn), "/admin/auth/callback", %{
          "code" => "the-code",
          "state" => params["state"]
        })

      assert redirected_to(conn) == "/admin"
      assert get_session(conn, "admin_user_id") == a.id
      assert get_session(conn, "admin_oidc") == nil

      assert Repo.exists?(
               from e in AuditEntry, where: e.action == "sign_in" and e.actor_id == ^a.id
             )

      [cookie] = get_resp_header(conn, "set-cookie")
      assert cookie =~ "secure"
      assert cookie =~ "HttpOnly"
      assert cookie =~ "SameSite=Lax"

      assert get(recycle(conn), "/admin/speech") |> html_response(200) =~ "Speech models"
    end

    test "a wrong state is a 403 (and the pending state is single-use)", %{conn: conn} do
      {_a, sub} = admin("kc2@example.com")
      {conn, params} = start_login(conn)
      stub_token(sub, "kc2@example.com")

      conn = get(recycle(conn), "/admin/auth/callback", %{"code" => "c", "state" => "forged"})
      assert conn.status == 403
      refute get_session(conn, "admin_user_id")

      conn =
        get(recycle(conn), "/admin/auth/callback", %{"code" => "c", "state" => params["state"]})

      assert conn.status == 403
    end

    test "a Keycloak user who is not an admin gets a plain 403", %{conn: conn} do
      {_a, _} = admin("someadmin@example.com")
      {_u, sub} = user("notadmin@example.com")
      {conn, params} = start_login(conn)
      stub_token(sub, "notadmin@example.com")

      conn =
        get(recycle(conn), "/admin/auth/callback", %{"code" => "c", "state" => params["state"]})

      assert conn.status == 403
      assert conn.resp_body == "403 Forbidden\n"
    end

    test "an identity without a RisiMe account gets a 403", %{conn: conn} do
      {conn, params} = start_login(conn)
      stub_token(Ecto.UUID.generate(), "stranger@example.com")

      conn =
        get(recycle(conn), "/admin/auth/callback", %{"code" => "c", "state" => params["state"]})

      assert conn.status == 403
    end

    test "the one-time seed at first sign-in", %{conn: conn} do
      old = Application.get_env(:risime, :admin_seed_email)
      on_exit(fn -> Application.put_env(:risime, :admin_seed_email, old) end)
      Application.put_env(:risime, :admin_seed_email, "seedme@example.com")

      {u, sub} = user("seedme@example.com")
      {conn, params} = start_login(conn)
      stub_token(sub, "seedme@example.com")

      conn =
        get(recycle(conn), "/admin/auth/callback", %{"code" => "c", "state" => params["state"]})

      assert redirected_to(conn) == "/admin"
      assert Admin.admin?(u.id)
    end

    test "a failed token exchange is a 403", %{conn: conn} do
      {conn, params} = start_login(conn)
      Req.Test.stub(OIDC, fn conn -> Plug.Conn.send_resp(conn, 400, "{}") end)

      conn =
        get(recycle(conn), "/admin/auth/callback", %{"code" => "c", "state" => params["state"]})

      assert conn.status == 403
    end
  end

  describe "Speech models page" do
    defp socket(admin_id, installed) do
      %Phoenix.LiveView.Socket{
        assigns: %{
          __changed__: %{},
          admin_id: admin_id,
          installed: installed,
          clips: ["a.ogg"],
          test: nil,
          flash: %{}
        }
      }
    end

    test "save applies a manual model at once and Auto clears it; audit-logged" do
      {a, _} = admin("speech@example.com")
      installed = {:ok, %{models: [%{id: "qwen3-asr-0.6b-sinhala", language: "si"}], routes: %{}}}

      {:noreply, s} =
        SpeechLive.handle_event(
          "save",
          %{"lang" => "si", "model" => "qwen3-asr-0.6b-sinhala"},
          socket(a.id, installed)
        )

      assert {:ok, _} = s.assigns.notice
      assert SpeechRoutes.choice("si") == "qwen3-asr-0.6b-sinhala"
      assert s.assigns.choices["si"] == "qwen3-asr-0.6b-sinhala"

      {:noreply, s} =
        SpeechLive.handle_event("save", %{"lang" => "si", "model" => "not-there"}, s)

      assert {:error, _} = s.assigns.notice
      assert SpeechRoutes.choice("si") == "qwen3-asr-0.6b-sinhala"

      {:noreply, _} = SpeechLive.handle_event("save", %{"lang" => "si", "model" => "auto"}, s)
      assert SpeechRoutes.choice("si") == "auto"

      assert Repo.all(from e in AuditEntry, where: e.actor_id == ^a.id, select: e.action) ==
               ["setting_set", "setting_clear"]
    end

    test "test refuses a clip that is not in the test-clip listing" do
      {a, _} = admin("speech2@example.com")

      {:noreply, s} =
        SpeechLive.handle_event("test", %{"clip" => "../../etc/passwd"}, socket(a.id, :loading))

      assert {:error, _} = s.assigns.notice
      assert s.assigns.test == nil
    end
  end

  describe "Voices page and Auto (Part 12 items 2–3)" do
    test "renders the voice choice, the engine catalogue (not installed) and Auto", %{conn: conn} do
      {a, _} = admin("voices@example.com")
      html = conn |> admin_conn(a.id) |> get("/admin/voices") |> html_response(200)

      for s <- ["Voices (text-to-speech)", "Mixed / Singlish", "Kokoro", "Orpheus", "Chatterbox"],
          do: assert(html =~ s)

      assert html =~ "Indic Parler"
      assert html =~ "today&#39;s route"

      [csp] =
        conn
        |> admin_conn(a.id)
        |> get("/admin/voices")
        |> get_resp_header("content-security-policy")

      assert csp =~ "media-src 'self' data:"
    end

    test "the Speech page lists the model families and what Auto does today", %{conn: conn} do
      {a, _} = admin("families@example.com")
      html = conn |> admin_conn(a.id) |> get("/admin/speech") |> html_response(200)

      for s <- [
            "Qwen-Sinhala",
            "omniASR 1B",
            "omniASR 3B",
            "omniASR 7B",
            "Tamil models",
            "Phone (on-device)"
          ],
          do: assert(html =~ s)

      assert html =~ "Auto uses today"
      assert html =~ "promotion rule"
    end

    test "saving a voice on the Voices page is validated and audit-logged" do
      {a, _} = admin("voices2@example.com")

      installed = [
        %{
          id: "kokoro",
          name: "Kokoro",
          module: nil,
          status: :ok,
          voices: [%{id: "kokoro:si1", name: "si", languages: ["si"]}]
        }
      ]

      socket = %Phoenix.LiveView.Socket{
        assigns: %{__changed__: %{}, admin_id: a.id, installed: installed, test: nil, flash: %{}}
      }

      {:noreply, s} =
        RisiMeWeb.Admin.VoicesLive.handle_event(
          "save",
          %{"lang" => "si", "voice" => "kokoro:si1"},
          socket
        )

      assert {:ok, _} = s.assigns.notice
      assert RisiMe.Agent.TTS.voice_for("si") == {:manual, "kokoro:si1"}

      {:noreply, s} =
        RisiMeWeb.Admin.VoicesLive.handle_event("test", %{"text" => "  "}, s)

      assert {:error, _} = s.assigns.notice

      assert Repo.all(from e in AuditEntry, where: e.actor_id == ^a.id, select: e.action) ==
               ["setting_set"]
    end
  end

  describe "test clips" do
    test "lists only top-level audio files; reads only listed names" do
      dir = Path.join(System.tmp_dir!(), "risime-clips-#{System.unique_integer([:positive])}")
      File.mkdir_p!(Path.join(dir, "sub"))
      File.write!(Path.join(dir, "b.ogg"), "ogg")
      File.write!(Path.join(dir, "a.wav"), "wav")
      File.write!(Path.join(dir, "notes.txt"), "no")
      File.write!(Path.join([dir, "sub", "c.ogg"]), "no")
      old = Application.get_env(:risime, :admin_test_clips_dir)
      Application.put_env(:risime, :admin_test_clips_dir, dir)

      on_exit(fn ->
        Application.put_env(:risime, :admin_test_clips_dir, old)
        File.rm_rf!(dir)
      end)

      assert RisiMe.Admin.TestClips.list() == ["a.wav", "b.ogg"]
      assert RisiMe.Admin.TestClips.read("b.ogg") == {:ok, "ogg"}
      assert RisiMe.Admin.TestClips.read("sub/c.ogg") == {:error, :unknown_clip}
      assert RisiMe.Admin.TestClips.read("notes.txt") == {:error, :unknown_clip}
    end
  end
end
