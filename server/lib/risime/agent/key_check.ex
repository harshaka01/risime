defmodule RisiMe.Agent.KeyCheck do
  @moduledoc """
  Key-check values for Risi's two sealed stores (P0 2026-10-08), in `risi_key_checks`:

    * `"mls_kek:<device_id>"`: `RISI_MLS_KEK` against the device's `risi_mls_kv` rows;
    * `"data_key"`: `RISI_DATA_KEY` against the `risi_buffer` bodies.

  `mac = HMAC-SHA256(key, "risi-kek-check-v1" ‖ name)`: it shows which key sealed the store
  without revealing it. Written after the first successful open (or while the store is empty),
  compared before every open, so a replaced key is reported as `kek_mismatch` instead of failing
  inside the NIF as `:tampered`.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Repo

  @label "risi-kek-check-v1"

  @doc "The check name of a device's MLS store."
  def mls_name(device_id), do: "mls_kek:" <> device_id

  @doc "The check name of the buffer key."
  def data_name, do: "data_key"

  @doc "`:ok` (same key), `:mismatch` (another key sealed the store) or `:absent` (no check yet)."
  def compare(name, key) do
    case Repo.one(from c in "risi_key_checks", where: c.name == ^name, select: c.mac) do
      nil -> :absent
      mac -> if :crypto.hash_equals(mac, mac(key, name)), do: :ok, else: :mismatch
    end
  rescue
    # Not migrated yet (a preflight before RisiMe.Release.migrate/0): no check, not an error.
    e in Postgrex.Error ->
      if match?(%{postgres: %{code: :undefined_table}}, e),
        do: :absent,
        else: reraise(e, __STACKTRACE__)
  end

  @doc "Records `key` as the key of the store `name` (upsert). Best effort: always `:ok`."
  def record(name, key) do
    Repo.insert_all(
      "risi_key_checks",
      [%{name: name, mac: mac(key, name), updated_at: DateTime.utc_now()}],
      on_conflict: {:replace, [:mac, :updated_at]},
      conflict_target: [:name]
    )

    :ok
  rescue
    e ->
      Logger.warning("Risi key check not written (#{name}): #{inspect(e.__struct__)}")
      :ok
  end

  defp mac(key, name), do: :crypto.mac(:hmac, :sha256, key, @label <> name)
end
