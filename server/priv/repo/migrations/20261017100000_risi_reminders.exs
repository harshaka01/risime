defmodule RisiMe.Repo.Migrations.RisiReminders do
  use Ecto.Migration

  # Contract v1.25 §25.4/§25.5 and v1.26 §26.4 (server S15). Additive only (hard rule 9).
  # One row per confirmed (or allowed) `set_reminder`: where it fires (the conversation of its
  # card, or the asker's Risi chat for a personal one), when, who is reminded (the asker plus
  # every Me too, minus every Not me), and its text sealed with RISI_DATA_KEY (wiped when it
  # fires or is cancelled; the row is deleted a day later).
  def change do
    create table(:risi_reminders, primary_key: false) do
      add :reminder_id, :binary_id, primary_key: true
      add :owner_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :conversation_id, :string, null: false
      add :request_conversation_id, :string
      add :request_id, :binary_id
      add :turn_id, :binary_id
      add :write_id, :binary_id
      add :due_at, :utc_datetime_usec, null: false
      add :text, :binary
      add :participants, {:array, :binary_id}, null: false, default: []
      add :me_too, :boolean, null: false, default: false
      # pending | fired | cancelled
      add :state, :string, null: false, default: "pending"
      add :inserted_at, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    create index(:risi_reminders, [:owner_id, :state])
    create index(:risi_reminders, [:conversation_id])
    create index(:risi_reminders, [:state, :updated_at])
  end
end
