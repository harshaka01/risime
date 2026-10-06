defmodule RisiMe.Auth.JWTTest do
  @moduledoc "Contract v1.3 §6.0 token rules, with locally generated keys."
  use ExUnit.Case, async: false

  import RisiMe.OIDCHelpers

  alias RisiMe.Auth.{JWKS, JWT}

  setup do
    install_jwks()
    on_exit(fn -> install_jwks() end)
  end

  defp verify(token, opts \\ []), do: JWT.verify(token, opts)

  test "accepts RS256, PS256, ES256 and EdDSA tokens and returns sub, lowercased email, exp" do
    for key <- [:rsa, :rsa_ps, :ec, :ed] do
      c = claims(%{"email" => "Some.One@Example.com"})
      assert {:ok, %{sub: sub, email: "some.one@example.com", exp: exp}} = verify(sign(c, key))
      assert sub == c["sub"] and exp == c["exp"]
    end
  end

  test "shape: a JWT has three dot-separated base64url parts; opaque tokens don't" do
    assert JWT.jwt_shape?(sign(claims()))
    refute JWT.jwt_shape?(:crypto.strong_rand_bytes(32) |> Base.url_encode64(padding: false))
    assert {:error, :malformed} = verify("a.b")
  end

  describe "attacks" do
    test "alg none is rejected" do
      header = Base.url_encode64(~s({"alg":"none","kid":"kid-rsa"}), padding: false)
      body = claims() |> Jason.encode!() |> Base.url_encode64(padding: false)
      assert {:error, :alg_not_allowed} = verify(header <> "." <> body <> ".")
    end

    test "HS256 signed with the RSA public key (alg confusion) is rejected" do
      {_, pem} = public_jwk(:rsa) |> JOSE.JWK.from_map() |> JOSE.JWK.to_pem()
      hmac = JOSE.JWK.from_oct(pem)

      {_, token} =
        JOSE.JWT.sign(hmac, %{"alg" => "HS256", "kid" => "kid-rsa"}, claims())
        |> JOSE.JWS.compact()

      assert {:error, :alg_not_allowed} = verify(token)
    end

    test "an alg that doesn't match the key's kty is rejected" do
      # ES256 header pointing at the RSA key.
      token = sign(claims(), :ec, %{"kid" => "kid-rsa"})
      assert {:error, :kty_mismatch} = verify(token)
    end

    test "a tampered payload fails the signature" do
      [h, _p, s] = String.split(sign(claims()), ".")

      p =
        claims(%{"email" => "evil@example.com"})
        |> Jason.encode!()
        |> Base.url_encode64(padding: false)

      assert {:error, :bad_signature} = verify(Enum.join([h, p, s], "."))
    end

    test "a token signed by an unpublished key is rejected" do
      assert {:error, :unknown_kid} = verify(sign(claims(), :other))
      assert {:error, :no_kid} = verify(sign(claims(), :rsa, %{"kid" => nil}))
    end
  end

  describe "claims" do
    test "foreign issuer" do
      assert {:error, :bad_issuer} =
               verify(sign(claims(%{"iss" => "https://evil.example/realms/aoa"})))

      assert {:error, :bad_issuer} = verify(sign(claims(%{"iss" => issuer() <> "/"})))
    end

    test "typ must be Bearer: ID and refresh tokens are rejected" do
      assert {:error, :bad_typ} = verify(sign(claims(%{"typ" => "ID"})))
      assert {:error, :bad_typ} = verify(sign(claims(%{"typ" => "Refresh"})))
      assert {:error, :bad_typ} = verify(sign(claims(%{"typ" => nil})))
    end

    test "azp must be risime, or aud must contain it" do
      assert {:error, :bad_audience} = verify(sign(claims(%{"azp" => "other-app"})))
      assert {:ok, _} = verify(sign(claims(%{"azp" => "other-app", "aud" => ["x", "risime"]})))
      assert {:ok, _} = verify(sign(claims(%{"azp" => nil, "aud" => "risime"})))
    end

    test "email must be present and verified" do
      assert {:error, :email_not_verified} = verify(sign(claims(%{"email_verified" => false})))
      assert {:error, :email_not_verified} = verify(sign(claims(%{"email_verified" => nil})))
      assert {:error, :email_not_verified} = verify(sign(claims(%{"email_verified" => "true"})))
      assert {:error, :no_email} = verify(sign(claims(%{"email" => nil})))
    end

    test "exp is required; exp, nbf and iat have 60 s leeway" do
      now = System.os_time(:second)
      assert {:error, :no_exp} = verify(sign(claims(%{"exp" => nil})))

      token = sign(claims(%{"exp" => now - 30}))
      assert {:ok, _} = verify(token, now: now)
      assert {:ok, _} = verify(token, now: now + 29)
      assert {:error, :expired} = verify(token, now: now + 31)

      assert {:ok, _} = verify(sign(claims(%{"nbf" => now + 50})), now: now)
      assert {:error, :not_yet_valid} = verify(sign(claims(%{"nbf" => now + 70})), now: now)
      assert {:ok, _} = verify(sign(claims(%{"iat" => now + 50})), now: now)
      assert {:error, :issued_in_future} = verify(sign(claims(%{"iat" => now + 70})), now: now)
    end

    test "sub is required" do
      assert {:error, :no_sub} = verify(sign(claims(%{"sub" => nil})))
    end
  end

  describe "JWKS cache" do
    test "fails closed with no keys" do
      install_jwks(%{"keys" => []})
      assert JWKS.size() == 0
      assert {:error, :unknown_kid} = verify(sign(claims()))
    end

    test "keys whose use isn't sig are ignored" do
      install_jwks(%{"keys" => [public_jwk(:rsa, %{"use" => "enc"}), public_jwk(:ec)]})
      assert {:error, :unknown_kid} = verify(sign(claims(), :rsa))
      assert {:ok, _} = verify(sign(claims(), :ec))
    end

    test "an unknown kid refetches at once, then at most once per min_refetch_ms" do
      install_jwks(jwks([:rsa]))
      put_oidc(jwks: {:static, jwks([:rsa, :ec])}, min_refetch_ms: 60_000)
      on_exit(fn -> put_oidc(min_refetch_ms: 60_000) end)

      # The refresh in install_jwks was just now, so the unknown kid is rate-limited…
      assert {:error, :unknown_kid} = verify(sign(claims(), :ec))

      # …and after the window it is fetched once and then found.
      put_oidc(min_refetch_ms: 0)
      assert {:ok, _} = verify(sign(claims(), :ec))
    end
  end

  describe "JWKS over HTTP (Req.Test)" do
    setup do
      {:ok, counter} = Agent.start_link(fn -> %{} end)
      %{counter: counter}
    end

    defp hits(counter, path), do: Agent.get(counter, &Map.get(&1, path, 0))

    defp serve(counter, certs_status \\ 200) do
      Req.Test.stub(JWKS, fn conn ->
        Agent.update(counter, &Map.update(&1, conn.request_path, 1, fn n -> n + 1 end))

        case conn.request_path do
          "/realms/aoa/.well-known/openid-configuration" ->
            Req.Test.json(conn, %{"jwks_uri" => issuer() <> "/protocol/openid-connect/certs"})

          "/realms/aoa/protocol/openid-connect/certs" when certs_status == 200 ->
            # JSON with a non-JSON content type must still be accepted.
            conn
            |> Plug.Conn.put_resp_content_type("application/octet-stream")
            |> Plug.Conn.send_resp(200, Jason.encode!(jwks([:rsa])))

          _ ->
            Plug.Conn.send_resp(conn, certs_status, "")
        end
      end)

      Req.Test.allow(JWKS, self(), Process.whereis(JWKS))
    end

    test "discovery, then certs; a failed fetch keeps the cached keys", %{counter: c} do
      serve(c)
      put_oidc(jwks: :discovery)
      assert JWKS.refresh_now() == 1
      assert hits(c, "/realms/aoa/.well-known/openid-configuration") == 1
      assert {:ok, _} = verify(sign(claims()))

      serve(c, 500)

      assert ExUnit.CaptureLog.capture_log(fn -> assert JWKS.refresh_now() == 1 end) =~
               "keeping 1 cached key"

      assert {:ok, _} = verify(sign(claims()))
    end

    test "concurrent unknown kids cause a single fetch", %{counter: c} do
      serve(c)
      put_oidc(jwks: {:url, issuer() <> "/protocol/openid-connect/certs"}, min_refetch_ms: 60_000)
      on_exit(fn -> put_oidc(min_refetch_ms: 60_000) end)
      install_jwks(%{"keys" => []})
      put_oidc(jwks: {:url, issuer() <> "/protocol/openid-connect/certs"}, min_refetch_ms: 0)
      # First unknown kid fetches; with min_refetch_ms back at 60 s the rest must not.
      assert {:ok, _} = verify(sign(claims()))
      put_oidc(min_refetch_ms: 60_000)
      before = hits(c, "/realms/aoa/protocol/openid-connect/certs")

      1..20
      |> Task.async_stream(fn _ -> verify(sign(claims(), :ec)) end)
      |> Enum.each(fn {:ok, res} -> assert {:error, :unknown_kid} = res end)

      assert hits(c, "/realms/aoa/protocol/openid-connect/certs") == before
    end
  end
end
