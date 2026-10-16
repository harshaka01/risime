defmodule RisiRegistry.Cards.Keys do
  @moduledoc """
  Owner keys with rotation (agent-connector.md section 4, review change 2).

  The owner publishes `https://<domain>/.well-known/risi-keys.json`:

      {"keys": [{"kid": "2026-10", "kty": "OKP", "crv": "Ed25519", "x": "…",
                 "alg": "EdDSA", "expires": "2027-04-01T00:00:00Z"}, …]}

  `refresh/1` mirrors that list into `owner_keys`: new keys are added, listed keys updated, and
  keys no longer listed are retired (kept for the audit trail, never used again). A key that
  carries private material (`d`, `p`, `q`, …) is refused outright.
  """
  import Ecto.Query
  alias RisiRegistry.{HTTP, Repo}
  alias RisiRegistry.Registry.OwnerKey

  @private_members ~w(d p q dp dq qi k oth)
  @key_types ~w(EC OKP)

  def keys_url(domain), do: "https://#{domain}/.well-known/risi-keys.json"

  @doc "Fetches the owner's key set and mirrors it. Returns the usable keys."
  def refresh(domain) do
    with {:ok, body} <- fetch(keys_url(domain)),
         {:ok, %{"keys" => keys}} when is_list(keys) <- decode(body),
         {:ok, parsed} <- parse_all(keys) do
      now = DateTime.utc_now()
      Repo.transaction(fn -> mirror(domain, parsed, now) end)
      {:ok, usable(domain)}
    else
      {:ok, _other} -> {:error, :bad_key_set}
      {:error, _} = err -> err
    end
  end

  @doc "The key `kid` for `domain`, refreshing the set once if it is not known yet."
  def find(domain, kid) do
    case lookup(domain, kid) do
      %OwnerKey{} = key ->
        {:ok, key}

      nil ->
        with {:ok, _} <- refresh(domain),
             %OwnerKey{} = key <- lookup(domain, kid) do
          {:ok, key}
        else
          nil -> {:error, :unknown_key}
          {:error, _} = err -> err
        end
    end
  end

  def usable(domain) do
    now = DateTime.utc_now()

    OwnerKey
    |> where([k], k.domain == ^domain)
    |> Repo.all()
    |> Enum.filter(&OwnerKey.usable?(&1, now))
  end

  defp lookup(domain, kid), do: Repo.get_by(OwnerKey, domain: domain, kid: kid)

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
      {:ok, map} -> {:ok, map}
      {:error, _} -> {:error, :not_json}
    end
  end

  defp parse_all(keys) do
    Enum.reduce_while(keys, {:ok, []}, fn key, {:ok, acc} ->
      case parse(key) do
        {:ok, k} -> {:cont, {:ok, [k | acc]}}
        {:error, _} = err -> {:halt, err}
      end
    end)
  end

  defp parse(%{"kid" => kid, "kty" => kty} = key) when is_binary(kid) and kty in @key_types do
    cond do
      Enum.any?(@private_members, &Map.has_key?(key, &1)) ->
        {:error, :private_key_published}

      true ->
        with {:ok, expires} <- parse_expires(key["expires"]) do
          jwk = Map.drop(key, ["expires"])
          {:ok, %{kid: kid, alg: key["alg"], jwk: jwk, expires_at: expires}}
        end
    end
  end

  defp parse(_), do: {:error, :bad_key}

  defp parse_expires(nil), do: {:ok, nil}

  defp parse_expires(text) when is_binary(text) do
    case DateTime.from_iso8601(text) do
      {:ok, dt, _} -> {:ok, %{dt | microsecond: {elem(dt.microsecond, 0), 6}}}
      _ -> {:error, :bad_expires}
    end
  end

  defp parse_expires(_), do: {:error, :bad_expires}

  defp mirror(domain, parsed, now) do
    listed = Enum.map(parsed, & &1.kid)

    for k <- parsed do
      case lookup(domain, k.kid) do
        nil ->
          Repo.insert!(%OwnerKey{
            domain: domain,
            kid: k.kid,
            alg: k.alg,
            jwk: k.jwk,
            expires_at: k.expires_at
          })

        %OwnerKey{} = existing ->
          existing
          |> Ecto.Changeset.change(alg: k.alg, jwk: k.jwk, expires_at: k.expires_at)
          |> Repo.update!()
      end
    end

    OwnerKey
    |> where([k], k.domain == ^domain and k.kid not in ^listed and is_nil(k.retired_at))
    |> Repo.update_all(set: [retired_at: now, updated_at: now])
  end
end
