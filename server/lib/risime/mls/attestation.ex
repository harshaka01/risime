defmodule RisiMe.MLS.Attestation do
  @moduledoc """
  The server's device attestation key (contract v1.7 §10.1, decision 034).

  * `ATTESTATION_KEY_FILE` (default `~/risime-keys/attestation_ed25519.jwk`, mode 600) holds the
    active Ed25519 private JWK, created once by `mix risime.attestation.gen`. It is loaded at
    boot into this process; its state never prints the private key.
  * `ATTESTATION_PREVIOUS_KEYS` (optional) is a file of public JWKs (`{"keys": [...]}`) still
    trusted during a rotation; they are published, never used to sign.
  * Without the active key, E2EE is off: every MLS endpoint answers `503 mls_unavailable`.
  * `kid` is the RFC 7638 thumbprint of the public key.
  """
  use GenServer

  require Logger

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "True when an attestation key is loaded (E2EE can be used)."
  def available?, do: GenServer.call(__MODULE__, :available?)

  @doc """
  Signs a device binding; `{:ok, jws}` or `{:error, :mls_unavailable}`. `kind: "agent"` adds the
  v1.24 §24.11 claim `"kind": "agent"` (absent means `"user"`); only the server's own Risi device
  (`RisiMe.Agent.Mls`) is ever attested as an agent.
  """
  def sign(user_id, device_id, signature_key_b64, opts \\ []) do
    GenServer.call(__MODULE__, {:sign, user_id, device_id, signature_key_b64, opts[:kind]})
  end

  @doc "Public JWKs: the active key plus still-trusted previous keys."
  def public_keys, do: GenServer.call(__MODULE__, :public_keys)

  @doc "Reloads the key files (after rotation, and in tests)."
  def reload, do: GenServer.call(__MODULE__, :reload)

  @doc "Generates a new Ed25519 private JWK file (mode 600). Refuses to overwrite."
  def generate(path) do
    if File.exists?(path) do
      {:error, :exists}
    else
      File.mkdir_p!(Path.dirname(path))
      {_, map} = JOSE.JWK.generate_key({:okp, :Ed25519}) |> JOSE.JWK.to_map()
      File.write!(path, Jason.encode!(map))
      File.chmod!(path, 0o600)
      {:ok, kid(JOSE.JWK.from_map(map))}
    end
  end

  defp kid(jwk), do: JOSE.JWK.thumbprint(jwk)

  ## Server (state: nil | %{jwk, kid, public}, previous: [public maps])

  defmodule Key do
    @moduledoc false
    defstruct [:jwk, :kid, :public]

    defimpl Inspect do
      def inspect(k, _), do: "#RisiMe.MLS.Attestation.Key<kid: #{k.kid}>"
    end
  end

  @impl true
  def init(:ok), do: {:ok, load()}

  @impl true
  def handle_call(:available?, _from, state), do: {:reply, state.key != nil, state}

  def handle_call(:reload, _from, _state) do
    state = load()
    {:reply, state.key != nil, state}
  end

  def handle_call(:public_keys, _from, state) do
    active = if state.key, do: [state.key.public], else: []
    {:reply, active ++ state.previous, state}
  end

  def handle_call({:sign, _u, _d, _k, _kind}, _from, %{key: nil} = state),
    do: {:reply, {:error, :mls_unavailable}, state}

  def handle_call({:sign, user_id, device_id, sig_key, kind}, _from, %{key: key} = state) do
    claims = %{
      "aud" => "risime-mls",
      "user_id" => user_id,
      "device_id" => device_id,
      "signature_key" => sig_key,
      "iat" => System.os_time(:second),
      "v" => 1
    }

    claims = if kind == "agent", do: Map.put(claims, "kind", "agent"), else: claims

    header = %{"alg" => "EdDSA", "kid" => key.kid, "typ" => "risime-attest+jwt"}
    {_, jws} = key.jwk |> JOSE.JWT.sign(header, claims) |> JOSE.JWS.compact()
    {:reply, {:ok, jws}, state}
  end

  defp load do
    %{key: load_active(), previous: load_previous()}
  end

  defp load_active do
    path = Application.get_env(:risime, :attestation_key_file)

    with path when is_binary(path) <- path,
         {:ok, raw} <- File.read(path),
         {:ok, %{"kty" => "OKP", "crv" => "Ed25519", "d" => _} = map} <- Jason.decode(raw) do
      jwk = JOSE.JWK.from_map(map)
      {_, public} = jwk |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()
      kid = kid(jwk)
      public = Map.merge(public, %{"kid" => kid, "use" => "sig", "alg" => "EdDSA"})
      %Key{jwk: jwk, kid: kid, public: public}
    else
      nil ->
        nil

      {:error, :enoent} ->
        Logger.info("E2EE off: no attestation key (ATTESTATION_KEY_FILE)")
        nil

      _ ->
        Logger.error("attestation key file is unreadable or not an Ed25519 private JWK")
        nil
    end
  end

  defp load_previous do
    with path when is_binary(path) <- Application.get_env(:risime, :attestation_previous_keys),
         {:ok, raw} <- File.read(path),
         {:ok, %{"keys" => keys}} when is_list(keys) <- Jason.decode(raw) do
      for %{"kty" => "OKP", "crv" => "Ed25519"} = k <- keys do
        k = Map.drop(k, ["d"])

        Map.merge(k, %{
          "kid" => k["kid"] || kid(JOSE.JWK.from_map(k)),
          "use" => "sig",
          "alg" => "EdDSA"
        })
      end
    else
      _ -> []
    end
  end
end
