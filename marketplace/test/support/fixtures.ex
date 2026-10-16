defmodule RisiRegistry.Fixtures do
  @moduledoc "Owner keys, signed cards and a fake Keycloak, all served through the HTTP stub."
  alias RisiRegistry.{HTTPStub, Registry}
  alias RisiRegistry.Cards.{Keys, Signer}

  @issuer "https://idp.test/realms/aoa"

  def issuer, do: @issuer

  ## Owner keys and cards

  def owner_key, do: JOSE.JWK.generate_key({:okp, :Ed25519})

  @doc "Public JWK map for `jwk` with `kid` (and optional `expires`)."
  def public_entry(jwk, kid, expires \\ nil) do
    {_, pub} = jwk |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()
    entry = Map.merge(pub, %{"kid" => kid, "alg" => "EdDSA", "use" => "sig"})
    if expires, do: Map.put(entry, "expires", expires), else: entry
  end

  def publish_keys(domain, entries),
    do: HTTPStub.put(Keys.keys_url(domain), %{"keys" => entries})

  def card(overrides \\ %{}) do
    Map.merge(
      %{
        "name" => "Test Agent",
        "description" => "Answers test questions.",
        "url" => "https://agent.test/a2a",
        "version" => "1.0.0",
        "capabilities" => %{"streaming" => false},
        "skills" => [%{"id" => "test.ask", "name" => "Ask", "description" => "Ask a question"}],
        "x-risi" => %{
          "scopes" => [%{"id" => "read_test", "label" => "Reads your test data"}],
          "data_classes" => ["own_figures"],
          "consequential" => false,
          "key_id" => "k1"
        }
      },
      overrides
    )
  end

  def card_url(domain), do: "https://#{domain}/.well-known/agent-card.json"

  def publish_card(domain, card), do: HTTPStub.put(card_url(domain), %{} = card)

  def sign(card, jwk, kid), do: Signer.sign(card, jwk, kid, "EdDSA")

  @doc """
  An agent on `domain` with a published, signed card, registered and (unless `approve: false`)
  approved and activated. Returns `{agent, jwk}`.
  """
  def active_agent(domain \\ "agent.test", slug \\ "good", opts \\ []) do
    jwk = Keyword.get_lazy(opts, :jwk, &owner_key/0)
    publish_keys(domain, [public_entry(jwk, "k1")])
    publish_card(domain, sign(card(), jwk, "k1"))

    {:ok, agent} =
      Registry.register(%{
        agent_id: "#{domain}/#{slug}",
        name: "Test Agent #{slug}",
        domain: domain,
        card_url: card_url(domain),
        tier: Keyword.get(opts, :tier, "listed")
      })

    agent =
      if Keyword.get(opts, :approve, true) do
        {:ok, _} = Registry.approve_card(agent.agent_id, "admin:test", "fixture")
        {:ok, agent} = Registry.set_status(agent.agent_id, "active", "admin:test", "fixture")
        agent
      else
        agent
      end

    {agent, jwk}
  end

  ## Fake Keycloak

  @doc "Publishes discovery and JWKS for the fake issuer. Returns the signing key."
  def fake_idp do
    jwk = JOSE.JWK.generate_key({:ec, "P-256"})
    {_, pub} = jwk |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()

    HTTPStub.put(@issuer <> "/.well-known/openid-configuration", %{
      "issuer" => @issuer,
      "authorization_endpoint" => @issuer <> "/protocol/openid-connect/auth",
      "token_endpoint" => @issuer <> "/protocol/openid-connect/token",
      "jwks_uri" => @issuer <> "/protocol/openid-connect/certs"
    })

    HTTPStub.put(@issuer <> "/protocol/openid-connect/certs", %{
      "keys" => [Map.merge(pub, %{"kid" => "idp1", "use" => "sig", "alg" => "ES256"})]
    })

    jwk
  end

  def id_token(jwk, claims, kid \\ "idp1") do
    now = System.system_time(:second)

    claims =
      Map.merge(
        %{
          "iss" => @issuer,
          "aud" => "risi-marketplace",
          "azp" => "risi-marketplace",
          "sub" => "user-123",
          "iat" => now,
          "exp" => now + 300,
          "email_verified" => true,
          "name" => "Test Person"
        },
        claims
      )

    {_, token} =
      jwk
      |> JOSE.JWT.sign(%{"alg" => "ES256", "kid" => kid}, claims)
      |> JOSE.JWS.compact()

    token
  end
end
