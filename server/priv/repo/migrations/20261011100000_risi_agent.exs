defmodule RisiMe.Repo.Migrations.RisiAgent do
  use Ecto.Migration

  # Contract v1.24 §24.11 (decisions 065, 067), server S5: the Risi member client's state.
  # Additive only.
  def change do
    # The NIF's journal store: one row per key of Risi's device, `value` sealed in Rust
    # (AES-256-GCM under RISI_MLS_KEK, AAD bound to device_id and key). Never readable here.
    create table(:risi_mls_kv, primary_key: false) do
      add :device_id, :uuid, null: false, primary_key: true
      add :key, :binary, null: false, primary_key: true
      add :value, :binary, null: false
    end

    # Agent.Inbox: the last inbox event_id (TimeUUID) Risi's device has handled, advanced only
    # after that event's journal is persisted.
    create table(:risi_agent_cursor, primary_key: false) do
      add :device_id, :uuid, null: false, primary_key: true
      add :cursor, :string, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
