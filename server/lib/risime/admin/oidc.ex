defmodule RisiMe.Admin.OIDC do
  @moduledoc """
  The admin console's browser sign-in (decision 089): OpenID Connect authorization code + PKCE
  (S256) against the **same Keycloak realm and client as the app** (`OIDC_ISSUER`,
  `OIDC_CLIENT_ID`, decision 018). Keycloak only: no Google token ever reaches the server.

  * `start/1` → the authorization URL plus `state` and the PKCE `verifier` (kept in the
    encrypted session cookie until the callback; single use).
  * `finish/3` exchanges the code at the token endpoint (with the verifier) and verifies the
    access token with the app's own rules (`RisiMe.Auth.JWT.verify/1`: signature from the
    realm's JWKS, issuer, client, expiry, verified email). Tokens are never stored or logged.

  Endpoints are Keycloak's (`<issuer>/protocol/openid-connect/auth` and `/token`). Tests stub
  the token endpoint with `config :risime, :admin_oidc, req_options: [plug: ...]`.
  """
  alias RisiMe.Auth.{Config, JWT}

  @doc "The authorization URL for `redirect_uri`, with the `state` and `verifier` to keep."
  def start(redirect_uri) do
    state = random()
    verifier = random() <> random()
    challenge = :crypto.hash(:sha256, verifier) |> Base.url_encode64(padding: false)

    query =
      URI.encode_query(%{
        "response_type" => "code",
        "client_id" => Config.oidc(:client_id),
        "redirect_uri" => redirect_uri,
        "scope" => "openid email profile",
        "state" => state,
        "code_challenge" => challenge,
        "code_challenge_method" => "S256"
      })

    {endpoint("auth") <> "?" <> query, %{state: state, verifier: verifier}}
  end

  @doc """
  Exchanges `code` (with the PKCE `verifier`) and verifies the access token.
  `{:ok, %{sub, email, ...}}` or `{:error, reason}` (reason for logs and tests only).
  """
  def finish(code, verifier, redirect_uri) when is_binary(code) and is_binary(verifier) do
    form =
      [
        grant_type: "authorization_code",
        code: code,
        redirect_uri: redirect_uri,
        client_id: Config.oidc(:client_id),
        code_verifier: verifier
      ] ++
        case Application.get_env(:risime, :admin_oidc_client_secret) do
          s when is_binary(s) and s != "" -> [client_secret: s]
          _ -> []
        end

    req =
      Req.new(
        [
          url: endpoint("token"),
          receive_timeout: 10_000,
          connect_options: [timeout: 5_000],
          retry: false,
          redirect: false
        ] ++ Keyword.get(Application.get_env(:risime, :admin_oidc, []), :req_options, [])
      )

    case Req.post(req, form: form) do
      {:ok, %{status: 200, body: %{"access_token" => token}}} when is_binary(token) ->
        JWT.verify(token)

      {:ok, %{status: _}} ->
        {:error, :token_exchange_failed}

      {:error, _} ->
        {:error, :token_endpoint_unreachable}
    end
  end

  def finish(_, _, _), do: {:error, :bad_request}

  defp endpoint(kind), do: Config.oidc(:issuer) <> "/protocol/openid-connect/" <> kind

  defp random, do: :crypto.strong_rand_bytes(32) |> Base.url_encode64(padding: false)
end
