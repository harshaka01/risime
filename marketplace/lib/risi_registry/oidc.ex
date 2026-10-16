defmodule RisiRegistry.OIDC do
  @moduledoc """
  Sign-in with Keycloak (realm `aoa`, client `risi-marketplace`): authorization code flow with
  PKCE (S256), public client, so there is no client secret anywhere.

  Settings live in `config :risi_registry, :oidc` (`issuer`, `client_id`, `redirect_uri`,
  `scopes`), filled from `OIDC_ISSUER`, `OIDC_CLIENT_ID` and `OIDC_REDIRECT_URI`. With no issuer
  set, sign-in is reported as not configured and nothing is fetched.
  """
  alias RisiRegistry.HTTP
  alias RisiRegistry.OIDC.Discovery

  @allowed_algs ["RS256", "ES256", "PS256"]
  @clock_skew_s 60

  def settings, do: Application.get_env(:risi_registry, :oidc, [])
  def configured?, do: is_binary(settings()[:issuer]) and settings()[:issuer] != ""

  @doc """
  Starts a sign-in. Returns the authorize URL and the values to keep in the session
  (`state`, `nonce`, `verifier`).
  """
  def authorize do
    with true <- configured?() || {:error, :not_configured},
         {:ok, doc} <- Discovery.config(settings()[:issuer]) do
      state = random()
      nonce = random()
      verifier = random(48)

      query =
        URI.encode_query(%{
          "response_type" => "code",
          "client_id" => settings()[:client_id],
          "redirect_uri" => settings()[:redirect_uri],
          "scope" => settings()[:scopes] || "openid email profile",
          "state" => state,
          "nonce" => nonce,
          "code_challenge" => challenge(verifier),
          "code_challenge_method" => "S256"
        })

      {:ok,
       %{
         url: doc["authorization_endpoint"] <> "?" <> query,
         state: state,
         nonce: nonce,
         verifier: verifier
       }}
    end
  end

  @doc "PKCE S256 challenge for `verifier` (RFC 7636)."
  def challenge(verifier),
    do: :crypto.hash(:sha256, verifier) |> Base.url_encode64(padding: false)

  @doc """
  Exchanges the authorization `code` (with the PKCE `verifier`) and verifies the ID token
  against the issuer's keys, `nonce`, audience and expiry. Returns the claims.
  """
  def exchange(code, verifier, nonce) when is_binary(code) and is_binary(verifier) do
    s = settings()

    with true <- configured?() || {:error, :not_configured},
         {:ok, doc} <- Discovery.config(s[:issuer]),
         {:ok, %{status: 200, body: body}} <-
           HTTP.post_form(
             doc["token_endpoint"],
             [
               grant_type: "authorization_code",
               code: code,
               redirect_uri: s[:redirect_uri],
               client_id: s[:client_id],
               code_verifier: verifier
             ],
             max_bytes: 64 * 1024
           ),
         {:ok, %{"id_token" => id_token}} when is_binary(id_token) <- Jason.decode(body) do
      verify_id_token(id_token, nonce)
    else
      {:ok, %{status: _}} -> {:error, :token_rejected}
      {:ok, _} -> {:error, :no_id_token}
      {:error, %Jason.DecodeError{}} -> {:error, :no_id_token}
      {:error, _} = err -> err
    end
  end

  @doc "Verifies an ID token's signature and claims."
  def verify_id_token(token, nonce) do
    s = settings()

    with {:ok, %{"alg" => alg} = header} <- peek(token),
         true <- alg in @allowed_algs || {:error, :alg_not_allowed},
         {:ok, jwk} <- find_key(s[:issuer], header["kid"]),
         {true, %JOSE.JWT{fields: claims}, _} <-
           JOSE.JWT.verify_strict(JOSE.JWK.from_map(jwk), [alg], token),
         :ok <- check_claims(claims, s, nonce) do
      {:ok, claims}
    else
      {false, _, _} -> {:error, :bad_id_token_signature}
      {:error, _} = err -> err
      _ -> {:error, :bad_id_token}
    end
  rescue
    _ -> {:error, :bad_id_token}
  end

  defp peek(token) do
    {:ok, JOSE.JWS.peek_protected(token) |> Jason.decode!()}
  rescue
    _ -> {:error, :bad_id_token}
  end

  defp find_key(issuer, kid) do
    with {:ok, keys} <- Discovery.jwks(issuer) do
      case pick(keys, kid) do
        nil ->
          with {:ok, keys} <- Discovery.jwks(issuer, refresh: true),
               key when is_map(key) <- pick(keys, kid) do
            {:ok, key}
          else
            nil -> {:error, :unknown_signing_key}
            err -> err
          end

        key ->
          {:ok, key}
      end
    end
  end

  defp pick(keys, kid) do
    signing = Enum.filter(keys, &(Map.get(&1, "use", "sig") == "sig"))

    case kid do
      nil -> if length(signing) == 1, do: hd(signing)
      kid -> Enum.find(signing, &(&1["kid"] == kid))
    end
  end

  defp check_claims(claims, s, nonce) do
    now = System.system_time(:second)
    aud = List.wrap(claims["aud"])

    cond do
      claims["iss"] != s[:issuer] ->
        {:error, :wrong_issuer}

      s[:client_id] not in aud ->
        {:error, :wrong_audience}

      length(aud) > 1 and claims["azp"] != s[:client_id] ->
        {:error, :wrong_audience}

      not is_integer(claims["exp"]) or claims["exp"] + @clock_skew_s < now ->
        {:error, :expired}

      is_integer(claims["iat"]) and claims["iat"] - @clock_skew_s > now ->
        {:error, :issued_in_future}

      not is_binary(nonce) or claims["nonce"] != nonce ->
        {:error, :bad_nonce}

      not is_binary(claims["sub"]) ->
        {:error, :no_subject}

      true ->
        :ok
    end
  end

  defp random(bytes \\ 32),
    do: :crypto.strong_rand_bytes(bytes) |> Base.url_encode64(padding: false)
end
