defmodule RisiMe.Repo.Migrations.RisiKeyChecks do
  use Ecto.Migration

  # P0 2026-10-08 (RISI_MLS_KEK replaced after Risi had sealed its rows): a key-check value per
  # sealed store, so a wrong key is reported as `kek_mismatch` before any open is tried.
  # `name` is "mls_kek:<risi device_id>" or "data_key"; `mac` = HMAC-SHA256(key, label ‖ name)
  # (`RisiMe.Agent.KeyCheck`). Never the key itself. Additive only.
  def change do
    create table(:risi_key_checks, primary_key: false) do
      add :name, :string, null: false, primary_key: true
      add :mac, :binary, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
