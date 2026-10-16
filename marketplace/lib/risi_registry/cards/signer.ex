defmodule RisiRegistry.Cards.Signer do
  @moduledoc """
  Signs a card the way the verifier expects (an owner-side helper, used by tests and as the
  reference for owners such as RisiWork). Replaces any existing `signatures`.
  """
  alias RisiRegistry.Cards.Verifier

  @doc "Returns `card` with one signature by `jwk` (a private `JOSE.JWK`) under `kid`."
  def sign(card, %JOSE.JWK{} = jwk, kid, alg) do
    payload = Verifier.signing_payload(card)
    header = %{"alg" => alg, "kid" => kid}

    {_, compact} = jwk |> JOSE.JWS.sign(payload, header) |> JOSE.JWS.compact()
    [protected, _payload, signature] = String.split(compact, ".")

    Map.put(card, "signatures", [%{"protected" => protected, "signature" => signature}])
  end
end
