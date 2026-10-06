defmodule RisiMe.OIDCHelpers do
  @moduledoc """
  Locally generated signing keys and Keycloak-shaped access tokens for tests. Nothing here
  talks to the real realm.
  """
  alias RisiMe.Auth.JWKS

  @issuer "https://risicloud.ai/realms/aoa"

  def issuer, do: @issuer

  @doc "Test keys by name: :rsa (RS256), :rsa_ps (PS256), :ec (ES256), :ed (EdDSA), :other (RS256, never published)."
  def keys do
    case :persistent_term.get({__MODULE__, :keys}, nil) do
      nil ->
        keys = %{
          rsa: {"kid-rsa", "RS256", JOSE.JWK.generate_key({:rsa, 2048})},
          rsa_ps: {"kid-rsa-ps", "PS256", JOSE.JWK.generate_key({:rsa, 2048})},
          ec: {"kid-ec", "ES256", JOSE.JWK.generate_key({:ec, "P-256"})},
          ed: {"kid-ed", "EdDSA", JOSE.JWK.generate_key({:okp, :Ed25519})},
          other: {"kid-other", "RS256", JOSE.JWK.generate_key({:rsa, 2048})}
        }

        :persistent_term.put({__MODULE__, :keys}, keys)
        keys

      keys ->
        keys
    end
  end

  @doc "The public JWKS document for the given key names."
  def jwks(names \\ [:rsa, :rsa_ps, :ec, :ed]) do
    %{"keys" => Enum.map(names, &public_jwk/1)}
  end

  def public_jwk(name, extra \\ %{}) do
    {kid, alg, jwk} = Map.fetch!(keys(), name)
    {_, map} = jwk |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()
    map |> Map.merge(%{"kid" => kid, "use" => "sig", "alg" => alg}) |> Map.merge(extra)
  end

  @doc "Serves `jwks` as a static key set and reloads the cache."
  def install_jwks(jwks \\ jwks()) do
    put_oidc(jwks: {:static, jwks})
    JWKS.refresh_now()
  end

  def put_oidc(changes) do
    cfg = Application.get_env(:risime, :oidc, [])
    Application.put_env(:risime, :oidc, Keyword.merge(cfg, changes))
  end

  @doc "Keycloak-like access-token claims; `overrides` are merged (a nil value removes a claim)."
  def claims(overrides \\ %{}) do
    now = System.os_time(:second)

    %{
      "iss" => @issuer,
      "sub" => Ecto.UUID.generate(),
      "typ" => "Bearer",
      "azp" => "risime",
      "aud" => "account",
      "exp" => now + 300,
      "iat" => now,
      "email" => "someone@example.com",
      "email_verified" => true
    }
    |> Map.merge(Map.new(overrides))
    |> Map.reject(fn {_, v} -> is_nil(v) end)
  end

  @doc "Signs `claims` with the named test key. `header` overrides header fields."
  def sign(claims, name \\ :rsa, header \\ %{}) do
    {kid, alg, jwk} = Map.fetch!(keys(), name)
    header = Map.merge(%{"alg" => alg, "kid" => kid, "typ" => "JWT"}, header)
    {_, token} = jwk |> JOSE.JWT.sign(header, claims) |> JOSE.JWS.compact()
    token
  end

  @doc "A valid access token for `email` (and optional claim overrides)."
  def access_token(email, overrides \\ %{}) do
    overrides |> Map.new() |> Map.put_new("email", email) |> claims() |> sign()
  end
end
