defmodule RisiMe.Agent.Mls.Nif do
  @moduledoc """
  The Risi member client's MLS core: the Rustler NIF `crypto/risime-mls-nif` over `risime-mls`
  (contract v1.24 §24.11, decision 065).

  ## Loading
  The NIF is **optional at build time**: this module always compiles, and loads
  `priv/native/librisime_mls_nif.so` (or the path in `RISI_MLS_NIF`, without `.so`) when the
  module loads. Build it with `scripts/build-mls-nif` (`--prod` for the release). Without the
  library every call raises `:nif_not_loaded` and `loaded?/0` is `false`; nothing else in the
  server depends on it, so `mix compile` and `mix test` need no Rust.

  ## Journal contract
  A handle holds one device's whole MLS state in memory, loaded by `open/5` from its sealed
  `risi_mls_kv` rows. **Every** call returns `{:ok, result, journal}`, where
  `journal = [{key :: binary, sealed_value :: binary | :delete}]` lists the rows the call changed,
  already sealed. The caller must:

    1. persist the journal (upsert / delete by `key`) in **one** Postgres transaction;
    2. only then act on `result` (send the commit, message or key packages);
    3. if persisting fails, drop the handle and `open/5` again from the rows (the handle is
       ahead of the database).

  Calls on one handle are serialised by a mutex in the NIF; run them from one process per
  device anyway so journals are persisted in call order.

  Errors are `{:error, {kind :: atom, message :: String.t()}}` and change nothing. `kind` is one
  of the core's errors (`:malformed`, `:invalid_key_package`, `:untrusted_credential`,
  `:missing_attestation`, `:unknown_group`, `:group_exists`, `:unknown_member`,
  `:removed_from_group`, `:wrong_epoch`, `:decryption_failed`, `:not_application_message`,
  `:welcome`, `:commit_pending`, `:no_pending_commit`, `:storage`, `:other`,
  `:policy_violation`) or `:private_tab`, `:tampered` (a sealed row failed authentication),
  `:bad_kek`, `:bad_arg`, `:poisoned` (a call panicked: reopen).

  ## Sealing
  Done in Rust: AES-256-GCM under `RISI_MLS_KEK` (32 bytes, passed to `open/5`; never log it),
  a random 96-bit nonce per value, `sealed = nonce ‖ ciphertext ‖ tag`, and
  `AAD = "risi-kv-v1" ‖ u16be(byte_size(device_id)) ‖ device_id ‖ key`. The database only ever
  holds sealed values (§12.0 as amended by §24.11).

  ## Official only
  `join_from_welcome/2` into a group whose `group_meta` is not `tab: official` (a `dm:` group, a
  Private group) returns `{:error, {:private_tab, _}}` and changes nothing; `encrypt/4`,
  `process_detailed/3`, `process_commits/3` and `self_update/2` refuse such a group too (§24.0).

  ## Results
  - `join_from_welcome/2`: `%{group_id, epoch, members}`.
  - `members/2`: `[%{user_id, device_id, leaf_index, signature_key, kind: :user | :agent}]`.
  - `group_meta/2`: `nil` (a `dm:` group) or
    `%{name: String.t() | nil, admins, tab: :private | :official, chat_id, agents, json}`.
  - `process_detailed/3`: `%{type: :application, sender: %{user_id, device_id}, plaintext,
    epoch, sender_leaf, authenticated_data, sender_is_admin}`, `%{type: :commit, epoch,
    committer, added, removed, removed_self, discarded_own_pending, meta_changed}` or
    `%{type: :own_echo}`.
  - `process_commits/3`: `%{epoch, applied: [commit], skipped, removed_self}`.
  - `self_update/2`: `%{commit, welcome, epoch, added, removed, meta_changed}`; follow with
    `commit_accepted/2` (server `200`) or `commit_rejected/2` (`409`).
  """

  @on_load :load_nif

  @type handle :: reference()
  @type journal :: [{binary(), binary() | :delete}]
  @type error :: {:error, {atom(), String.t()}}
  @type result(t) :: {:ok, t, journal()} | error()

  @doc false
  def load_nif do
    loaded =
      case nif_path() do
        nil ->
          false

        path ->
          File.exists?(path <> ".so") and :erlang.load_nif(String.to_charlist(path), 0) == :ok
      end

    :persistent_term.put({__MODULE__, :loaded}, loaded)
    # Never fail the module load: the NIF is optional (see the moduledoc).
    :ok
  end

  defp nif_path do
    case {System.get_env("RISI_MLS_NIF"), :code.priv_dir(:risime)} do
      {nil, {:error, _}} -> nil
      {nil, priv} -> Path.join(priv, "native/librisime_mls_nif")
      {path, _} -> path
    end
  end

  @doc "Whether the NIF library is loaded."
  @spec loaded?() :: boolean()
  def loaded?, do: :persistent_term.get({__MODULE__, :loaded}, false)

  @doc """
  Open the device from its sealed rows (`[{key, sealed}]`, `[]` on first use: the journal then
  holds the new identity). `trust_anchors` are the attestation public JWKs (JSON strings).
  """
  @spec open(String.t(), String.t(), [String.t()], binary(), [{binary(), binary()}]) ::
          {:ok, handle(), journal()} | error()
  def open(_user_id, _device_id, _trust_anchors, _kek, _sealed_rows), do: nif()

  @spec signature_public_key(handle()) :: result(binary())
  def signature_public_key(_h), do: nif()

  @spec set_attestation(handle(), String.t()) :: result(:ok)
  def set_attestation(_h, _jws), do: nif()

  @spec generate_key_packages(handle(), pos_integer()) :: result([binary()])
  def generate_key_packages(_h, _count), do: nif()

  @spec last_resort_key_package(handle()) :: result(binary())
  def last_resort_key_package(_h), do: nif()

  @spec join_from_welcome(handle(), binary()) :: result(map())
  def join_from_welcome(_h, _welcome), do: nif()

  @spec process_detailed(handle(), binary(), binary()) :: result(map())
  def process_detailed(_h, _group_id, _message), do: nif()

  @spec process_commits(handle(), binary(), [binary()]) :: result(map())
  def process_commits(_h, _group_id, _commits), do: nif()

  @doc "An application message (PrivateMessage) with optional `authenticated_data`."
  @spec encrypt(handle(), binary(), binary(), binary()) :: result(binary())
  def encrypt(h, group_id, plaintext, aad \\ <<>>)
  def encrypt(_h, _group_id, _plaintext, _aad), do: nif()

  @spec self_update(handle(), binary()) :: result(map())
  def self_update(_h, _group_id), do: nif()

  @spec commit_accepted(handle(), binary()) :: result(non_neg_integer())
  def commit_accepted(_h, _group_id), do: nif()

  @spec commit_rejected(handle(), binary()) :: result(:ok)
  def commit_rejected(_h, _group_id), do: nif()

  @doc "Remove all state of a group (Official off, Risi removed). Idempotent."
  @spec purge_group(handle(), binary()) :: result(:ok)
  def purge_group(_h, _group_id), do: nif()

  @spec members(handle(), binary()) :: result([map()])
  def members(_h, _group_id), do: nif()

  @spec group_meta(handle(), binary()) :: result(map() | nil)
  def group_meta(_h, _group_id), do: nif()

  @spec epoch(handle(), binary()) :: result(non_neg_integer())
  def epoch(_h, _group_id), do: nif()

  # Test support: present only in a library built with the `test-peer` feature
  # (`scripts/build-mls-nif` without `--prod`).

  @doc false
  def test_peer_enabled, do: nif()
  @doc false
  def test_attestor_jwk(_seed), do: nif()
  @doc false
  def test_attest(_seed, _user_id, _device_id, _signature_key, _agent?), do: nif()
  @doc false
  def test_create_group(_h, _group_id, _key_packages, _meta_json), do: nif()

  defp nif, do: :erlang.nif_error(:nif_not_loaded)
end
