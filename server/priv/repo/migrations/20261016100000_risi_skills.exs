defmodule RisiMe.Repo.Migrations.RisiSkills do
  use Ecto.Migration

  # Contract v1.26 §26.2, §26.4, §26.8 (server S14, decision 069). Additive only (hard rule 9).
  #
  # risi_skills: the user's state per skill (no row = off). `ever_on` tells a revoke
  # (`skill_needed` `was_on: true`) from a skill never turned on.
  # risi_skill_devices: the Android permission each device last reported, deleted with the
  # device (FK to devices.id).
  # risi_skill_activity: the activity log. `sealed` (AES-256-GCM under RISI_MEMORY_KEY) holds
  # the summary, the undo hint, the undo token and the undo data; kept 90 days (hourly prune),
  # deleted with "Clear activity" and with the account. Never in the learning log.
  def change do
    create table(:risi_skills, primary_key: false) do
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :skill_id, :string, primary_key: true
      # off | ask | allowed
      add :state, :string, null: false
      add :ever_on, :boolean, null: false, default: false
      add :changed_at, :utc_datetime_usec, null: false
    end

    create table(:risi_skill_devices, primary_key: false) do
      add :device_ref, references(:devices, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :skill_id, :string, primary_key: true
      add :user_id, :binary_id, null: false
      add :device_id, :binary_id, null: false
      # granted | denied | not_asked | not_needed | unsupported | unknown
      add :permission, :string, null: false
      add :reported_at, :utc_datetime_usec, null: false
    end

    create table(:risi_skill_activity, primary_key: false) do
      add :entry_id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :skill_id, :string, null: false
      add :action, :string, null: false
      # confirm | allowed | calendar_offer | settings | undo
      add :via, :string, null: false
      add :conversation_id, :string
      add :target_conversation_id, :string
      add :device_id, :binary_id
      # server | client | manual | none
      add :undo_kind, :string, null: false
      # available | pending | done | failed | expired | NULL
      add :undo_state, :string
      add :undo_until, :utc_datetime_usec
      add :sealed, :binary, null: false
      add :at, :utc_datetime_usec, null: false
    end

    create index(:risi_skill_activity, [:user_id, :skill_id, :at])
    create index(:risi_skill_activity, [:at])
  end
end
