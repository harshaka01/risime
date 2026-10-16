defmodule RisiRegistryWeb.AuthTest do
  use RisiRegistryWeb.ConnCase, async: false
  alias RisiRegistry.{AllowList, Audit, HTTPStub, OIDC}

  @token_url "https://idp.test/realms/aoa/protocol/openid-connect/token"

  setup do
    idp = fake_idp()
    :ok = AllowList.add("listed@example.test", "test")
    RisiRegistry.Seeds.run()
    %{idp: idp}
  end

  # Starts sign-in, then makes the fake IdP answer the token request with an ID token carrying
  # `claims` (the nonce from the authorize URL unless overridden). Returns the callback conn.
  defp sign_in(conn, idp, claims, opts \\ []) do
    conn = get(conn, "/marketplace/auth/login")
    location = redirected_to(conn, 302)
    query = location |> URI.parse() |> Map.get(:query) |> URI.decode_query()
    test_pid = self()

    HTTPStub.put(@token_url, fn form ->
      send(test_pid, {:token_form, form, query})
      nonce = Keyword.get(opts, :nonce, query["nonce"])
      token = id_token(idp, Map.put(claims, "nonce", nonce))
      {:ok, %{status: 200, body: Jason.encode!(%{"id_token" => token, "access_token" => "x"})}}
    end)

    state = Keyword.get(opts, :state, query["state"])

    conn
    |> recycle()
    |> get("/marketplace/auth/callback", %{"code" => "code-1", "state" => state})
  end

  test "the sign-in page shows no data", %{conn: conn} do
    body = conn |> get("/marketplace") |> html_response(200)
    assert body =~ "Sign in"
    refute body =~ "RisiWork Ask"
  end

  test "the agents page needs sign-in", %{conn: conn} do
    assert redirected_to(get(conn, "/marketplace/agents"), 302) == "/marketplace"
  end

  test "login redirects to Keycloak with code + PKCE S256 as a public client", %{conn: conn} do
    location = conn |> get("/marketplace/auth/login") |> redirected_to(302)
    uri = URI.parse(location)
    q = URI.decode_query(uri.query)

    assert uri.host == "idp.test"
    assert uri.path == "/realms/aoa/protocol/openid-connect/auth"
    assert q["response_type"] == "code"
    assert q["client_id"] == "risi-marketplace"
    assert q["redirect_uri"] == "https://risicloud.ai/marketplace/auth/callback"
    assert q["code_challenge_method"] == "S256"
    assert byte_size(q["code_challenge"]) == 43
    assert q["state"] != nil and q["nonce"] != nil
    refute Map.has_key?(q, "client_secret")
  end

  test "an allow-listed account signs in and sees the agents list", %{conn: conn, idp: idp} do
    conn = sign_in(conn, idp, %{"email" => "Listed@Example.test", "sub" => "sub-ok"})
    assert redirected_to(conn, 302) == "/marketplace/agents"

    # PKCE: the token request carries the verifier that matches the challenge; no secret.
    assert_received {:token_form, form, query}
    assert OIDC.challenge(form[:code_verifier]) == query["code_challenge"]
    assert form[:grant_type] == "authorization_code"
    refute Map.has_key?(form, :client_secret)

    page = conn |> recycle() |> get("/marketplace/agents") |> html_response(200)
    assert page =~ "RisiWork Ask"
    assert page =~ "risicloud.ai/risiwork-ask"
    assert page =~ "Sign out"

    assert [%{actor: "oidc:sub-ok", result_code: "allowed"}] = Audit.recent(action: "sign_in")
  end

  test "a non-listed account gets a polite page and no data", %{conn: conn, idp: idp} do
    conn = sign_in(conn, idp, %{"email" => "stranger@example.test", "sub" => "sub-no"})
    body = html_response(conn, 403)
    assert body =~ "Not open yet"
    refute body =~ "RisiWork"

    assert redirected_to(conn |> recycle() |> get("/marketplace/agents"), 302) == "/marketplace"
    assert [%{actor: "oidc:sub-no", result_code: "not_listed"}] = Audit.recent(action: "sign_in")
    refute inspect(Audit.recent()) =~ "stranger"
  end

  test "an unverified email is not enough", %{conn: conn, idp: idp} do
    conn =
      sign_in(conn, idp, %{"email" => "listed@example.test", "email_verified" => false})

    assert html_response(conn, 403) =~ "Not open yet"
  end

  test "a wrong state or nonce fails and is audited", %{conn: conn, idp: idp} do
    c1 = sign_in(conn, idp, %{"email" => "listed@example.test"}, state: "forged")
    assert html_response(c1, 401) =~ "did not complete"

    c2 = sign_in(build_conn(), idp, %{"email" => "listed@example.test"}, nonce: "other")
    assert html_response(c2, 401) =~ "did not complete"

    codes = Audit.recent(action: "sign_in") |> Enum.map(& &1.result_code) |> Enum.sort()
    assert codes == ["failed:bad_nonce", "failed:bad_state"]
  end

  test "a token from another client or signed by another key is refused", %{idp: idp} do
    other = JOSE.JWK.generate_key({:ec, "P-256"})

    assert {:error, :wrong_audience} =
             OIDC.verify_id_token(id_token(idp, %{"aud" => "risime", "nonce" => "n"}), "n")

    assert {:error, :bad_id_token_signature} =
             OIDC.verify_id_token(id_token(other, %{"nonce" => "n"}), "n")

    expired = id_token(idp, %{"nonce" => "n", "exp" => System.system_time(:second) - 3600})
    assert {:error, :expired} = OIDC.verify_id_token(expired, "n")
  end

  test "removing someone from the allow-list ends their access at once", %{conn: conn, idp: idp} do
    conn = sign_in(conn, idp, %{"email" => "listed@example.test"})
    RisiRegistry.Repo.delete_all(AllowList.Account)
    assert redirected_to(conn |> recycle() |> get("/marketplace/agents"), 302) == "/marketplace"
  end

  test "sign-in without an issuer configured says so", %{conn: conn} do
    old = Application.get_env(:risi_registry, :oidc)
    Application.put_env(:risi_registry, :oidc, Keyword.put(old, :issuer, nil))
    on_exit(fn -> Application.put_env(:risi_registry, :oidc, old) end)
    assert conn |> get("/marketplace/auth/login") |> html_response(503) =~ "not set up"
  end
end
