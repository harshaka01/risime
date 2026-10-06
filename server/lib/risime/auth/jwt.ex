defmodule RisiMe.Auth.JWT do
  @moduledoc """
  Verifies a Keycloak access token by the rules of contract v1.3 §6.0.

  Returns `{:ok, %{sub, email, exp}}` (`email` lowercased) or `{:error, reason}`, where
  `reason` is for logs and tests only; callers answer `invalid_token` for all of them.
  """
  alias RisiMe.Auth.{Config, JWKS}

  @algs ~w(RS256 RS384 RS512 PS256 PS384 PS512 ES256 ES384 ES512 EdDSA)

  @doc "True if `token` has the shape of a JWS compact serialisation (three base64url parts)."
  def jwt_shape?(token) when is_binary(token),
    do: Regex.match?(~r/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*$/, token)

  def jwt_shape?(_), do: false

  @spec verify(String.t(), keyword) :: {:ok, map} | {:error, atom}
  def verify(token, opts \\ []) do
    now = Keyword.get_lazy(opts, :now, fn -> System.os_time(:second) end)

    with true <- jwt_shape?(token) || {:error, :malformed},
         {:ok, header} <- header(token),
         {:ok, alg} <- alg(header),
         {:ok, {jwk, map}} <- key(header),
         :ok <- kty_matches(alg, map),
         {:ok, claims} <- signature(jwk, alg, token),
         :ok <- check_claims(claims, now) do
      {:ok,
       %{
         sub: claims["sub"],
         email: String.downcase(claims["email"]),
         exp: claims["exp"],
         name: if(is_binary(claims["name"]), do: claims["name"])
       }}
    end
  end

  defp header(token) do
    {:ok, token |> JOSE.JWS.peek_protected() |> Jason.decode!()}
  rescue
    _ -> {:error, :malformed}
  end

  defp alg(%{"alg" => alg}) when alg in @algs, do: {:ok, alg}
  defp alg(_), do: {:error, :alg_not_allowed}

  defp key(%{"kid" => kid}) when is_binary(kid) do
    case JWKS.get(kid) do
      {:ok, key} -> {:ok, key}
      :error -> {:error, :unknown_kid}
    end
  end

  defp key(_), do: {:error, :no_kid}

  defp kty_matches(alg, map) do
    ok =
      case {alg, map} do
        {"RS" <> _, %{"kty" => "RSA"}} -> true
        {"PS" <> _, %{"kty" => "RSA"}} -> true
        {"ES256", %{"kty" => "EC", "crv" => "P-256"}} -> true
        {"ES384", %{"kty" => "EC", "crv" => "P-384"}} -> true
        {"ES512", %{"kty" => "EC", "crv" => "P-521"}} -> true
        {"EdDSA", %{"kty" => "OKP", "crv" => crv}} when crv in ["Ed25519", "Ed448"] -> true
        _ -> false
      end

    if ok, do: :ok, else: {:error, :kty_mismatch}
  end

  defp signature(jwk, alg, token) do
    case JOSE.JWT.verify_strict(jwk, [alg], token) do
      {true, %JOSE.JWT{fields: claims}, _jws} -> {:ok, claims}
      _ -> {:error, :bad_signature}
    end
  rescue
    _ -> {:error, :bad_signature}
  end

  defp check_claims(c, now) do
    leeway = Config.oidc(:leeway_s)
    client = Config.oidc(:client_id)

    cond do
      c["iss"] != Config.oidc(:issuer) -> {:error, :bad_issuer}
      c["typ"] != "Bearer" -> {:error, :bad_typ}
      c["azp"] != client and client not in List.wrap(c["aud"]) -> {:error, :bad_audience}
      not is_integer(c["exp"]) -> {:error, :no_exp}
      now > c["exp"] + leeway -> {:error, :expired}
      is_number(c["nbf"]) and now < c["nbf"] - leeway -> {:error, :not_yet_valid}
      is_number(c["iat"]) and c["iat"] > now + leeway -> {:error, :issued_in_future}
      not (is_binary(c["sub"]) and c["sub"] != "") -> {:error, :no_sub}
      not (is_binary(c["email"]) and c["email"] != "") -> {:error, :no_email}
      c["email_verified"] != true -> {:error, :email_not_verified}
      true -> :ok
    end
  end
end
