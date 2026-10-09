defmodule RisiMe.Repo.Migrations.RisiWrites do
  use Ecto.Migration

  # Contract v1.25 §25.3/§25.4 and v1.26 §26 (server S13). Additive only (hard rule 9).
  #
  # risi_pending_writes: one row per write proposal (a `confirm` card, or an allowed write).
  # `args` are sealed with RISI_DATA_KEY and wiped (NULL) once the write is done, cancelled or
  # void; rows are deleted a day after `expires_at` (24 h after the card).
  #
  # risi_tool_calls: one row per client tool call (§25.3). No args here: they live only in the
  # per-device `risi_tool_call` event (2-min TTL, deleted when the result arrives).
  def change do
    create table(:risi_pending_writes, primary_key: false) do
      add :write_id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      # Where the request was asked, and where the card was posted (the confirm must come from
      # there).
      add :conversation_id, :string, null: false
      add :card_conversation_id, :string, null: false
      add :request_id, :binary_id
      add :turn_id, :binary_id
      add :device_id, :binary_id
      add :tool, :string, null: false
      add :skill_id, :string
      add :args, :binary
      # pending | running | confirmed (ran and may run again) | done | cancelled | void
      add :state, :string, null: false, default: "pending"
      # confirm | allowed
      add :via, :string
      add :personal, :boolean, null: false, default: false
      add :card_message_id, :string
      add :expires_at, :utc_datetime_usec, null: false
      add :confirmed_at, :utc_datetime_usec
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:risi_pending_writes, [:user_id, :skill_id, :state])
    create index(:risi_pending_writes, [:expires_at])
    create index(:risi_pending_writes, [:card_conversation_id])

    create table(:risi_tool_calls, primary_key: false) do
      add :tool_call_id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :device_id, :binary_id, null: false
      add :tool, :string, null: false
      add :write_id, :binary_id
      add :undo_entry_id, :binary_id
      add :turn_id, :binary_id
      add :event_id, :string, null: false
      # waiting | answered
      add :state, :string, null: false, default: "waiting"
      add :expires_at, :utc_datetime_usec, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:risi_tool_calls, [:inserted_at])
  end
end
