defmodule RisiRegistry.Cards.Verifier do
  @moduledoc """
  Card fetch, canonical form, signature and hash check (agent-connector.md section 4 with the
  architect's review changes 2 and 3).

  The card is the agent's A2A card. Its signatures sit in the top-level `signatures` array, in
  the A2A `AgentCardSignature` shape: `{"protected": <base64url JWS header>, "signature":
  <base64url>}`. The header names `alg` and `kid`. The signed payload is the RFC 8785 canonical
  form of the card **without** `signatures`, and `card_hash` is the SHA-256 (lowercase hex) of
  that same payload. So:

    * re-signing the same content with another listed key keeps `card_hash`, and the agent
      stays verified (key rotation);
    * any change to the content changes `card_hash`, and the agent drops to unverified.
  """
  alias RisiRegistry.Cards.{JCS, Keys}
  alias RisiRegistry.HTTP
  alias RisiRegistry.Registry.OwnerKey

  @type verified :: %{card: map(), card_hash: String.t(), kid: String.t()}

  @doc "SHA-256 hex of the canonical card without its signatures."
  def card_hash(card) when is_map(card) do
    card |> signing_payload() |> sha256_hex()
  end

  @doc "The bytes that are signed: JCS of the card minus `signatures`."
  def signing_payload(card), do: card |> Map.delete("signatures") |> JCS.encode!()

  @doc """
  Fetches the card at `agent.card_url`, checks a signature by one of the owner's usable keys,
  and checks the content hash against `agent.card_hash`.

  Pass `expected_hash: :any` to verify only the signature (used when a person approves a new
  card and its hash becomes the record's `card_hash`).
  """
  @spec fetch_and_verify(map(), keyword()) :: {:ok, verified()} | {:error, atom() | tuple()}
  def fetch_and_verify(%{card_url: url, domain: domain} = agent, opts \\ []) do
    expected = Keyword.get(opts, :expected_hash, agent.card_hash)

    with :ok <- check_url(url, domain),
         {:ok, body} <- fetch(url),
         {:ok, card} <- decode(body) do
      verify(card, domain, expected)
    end
  end

  @doc "Verifies an already-decoded card for `domain` against `expected_hash` (or `:any`)."
  def verify(card, domain, expected_hash) when is_map(card) do
    with {:ok, payload} <- payload(card),
         hash = sha256_hex(payload),
         :ok <- check_hash(hash, expected_hash),
         {:ok, kid} <- check_signatures(card, payload, domain) do
      {:ok, %{card: card, card_hash: hash, kid: kid}}
    end
  end

  def verify(_card, _domain, _expected), do: {:error, :not_an_object}

  defp payload(card) do
    case JCS.encode(Map.delete(card, "signatures")) do
      {:ok, bin} -> {:ok, bin}
      {:error, _} -> {:error, :not_canonicalisable}
    end
  end

  defp check_hash(_hash, :any), do: :ok
  defp check_hash(_hash, nil), do: {:error, :no_approved_hash}
  defp check_hash(hash, hash), do: :ok
  defp check_hash(_hash, _expected), do: {:error, :hash_mismatch}

  defp check_signatures(card, payload, domain) do
    case card["signatures"] do
      [_ | _] = sigs ->
        sigs
        |> Enum.take(4)
        |> Enum.reduce_while({:error, :bad_signature}, fn sig, acc ->
          case check_one(sig, payload, domain) do
            {:ok, kid} -> {:halt, {:ok, kid}}
            {:error, reason} -> {:cont, prefer(acc, reason)}
          end
        end)

      _ ->
        {:error, :no_signature}
    end
  end

  # Keep the most specific failure reason for the audit line.
  defp prefer({:error, :bad_signature}, reason), do: {:error, reason}
  defp prefer(acc, _reason), do: acc

  defp check_one(%{"protected" => protected, "signature" => signature}, payload, domain)
       when is_binary(protected) and is_binary(signature) do
    allowed = Application.get_env(:risi_registry, :registry)[:allowed_algs]

    with {:ok, header_json} <- b64(protected),
         {:ok, %{"alg" => alg, "kid" => kid} = header} when is_binary(kid) <-
           Jason.decode(header_json),
         true <- alg in allowed || {:error, :alg_not_allowed},
         true <- Map.get(header, "b64", true) == true || {:error, :unsupported_header},
         {:ok, key} <- Keys.find(domain, kid),
         true <- OwnerKey.usable?(key, DateTime.utc_now()) || {:error, :key_not_usable},
         true <- key.alg in [nil, alg] || {:error, :alg_mismatch} do
      compact = protected <> "." <> Base.url_encode64(payload, padding: false) <> "." <> signature

      case JOSE.JWS.verify_strict(JOSE.JWK.from_map(key.jwk), [alg], compact) do
        {true, _, _} -> {:ok, kid}
        _ -> {:error, :bad_signature}
      end
    else
      {:ok, _} -> {:error, :bad_header}
      {:error, %Jason.DecodeError{}} -> {:error, :bad_header}
      {:error, reason} -> {:error, reason}
      :error -> {:error, :bad_header}
    end
  rescue
    _ -> {:error, :bad_signature}
  end

  defp check_one(_sig, _payload, _domain), do: {:error, :bad_signature}

  defp b64(text) do
    case Base.url_decode64(text, padding: false) do
      {:ok, bin} -> {:ok, bin}
      :error -> {:error, :bad_header}
    end
  end

  defp check_url(url, domain) do
    require_https = Application.get_env(:risi_registry, :registry)[:require_https]
    uri = URI.parse(url)

    cond do
      require_https and uri.scheme != "https" -> {:error, :card_url_not_https}
      uri.host != domain -> {:error, :card_url_off_domain}
      true -> :ok
    end
  end

  defp fetch(url) do
    max = Application.get_env(:risi_registry, :registry)[:max_document_bytes]

    case HTTP.get(url, max_bytes: max) do
      {:ok, %{status: 200, body: body}} when byte_size(body) <= max -> {:ok, body}
      {:ok, %{status: 200}} -> {:error, :too_large}
      {:ok, %{status: status}} -> {:error, {:http_status, status}}
      {:error, :too_large} -> {:error, :too_large}
      {:error, _} -> {:error, :fetch_failed}
    end
  end

  defp decode(body) do
    case Jason.decode(body) do
      {:ok, %{} = card} -> {:ok, card}
      {:ok, _} -> {:error, :not_an_object}
      {:error, _} -> {:error, :not_json}
    end
  end

  defp sha256_hex(bin), do: :crypto.hash(:sha256, bin) |> Base.encode16(case: :lower)
end
