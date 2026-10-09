defmodule RisiMe.Repo.Migrations.RisiActionDrafts do
  use Ecto.Migration

  # P0 2026-10-09 (Risi action loop). Additive only (hard rule 9).
  #
  # `risi_action_drafts`: one pending action draft (an event or a reminder the asker is building
  # with Risi) per (user, conversation). The draft (title, date, time, …) is chat-derived, so it
  # is sealed with RISI_DATA_KEY (AAD `risi_action_drafts:<user>:<conversation>`). `asks` and
  # `stalls` drive the loop guard; `write_id` is the card last proposed from it. 24-h TTL
  # (`expires_at`, pruned hourly; deleted when its write is done or cancelled).
  #
  # `risi_calendar_choices`: the calendar the asker's phone last reported (Calendar skill PATCH
  # or a calendar_add result), sealed with RISI_DATA_KEY (it may name an account), the action
  # card's `calendar` hint.
  def change do
    create table(:risi_action_drafts, primary_key: false) do
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :conversation_id, :string, primary_key: true
      add :draft, :binary, null: false
      add :asks, :integer, null: false, default: 0
      add :stalls, :integer, null: false, default: 0
      add :write_id, :binary_id
      add :updated_at, :utc_datetime_usec, null: false
      add :expires_at, :utc_datetime_usec, null: false
    end

    create index(:risi_action_drafts, [:conversation_id])
    create index(:risi_action_drafts, [:expires_at])

    create table(:risi_calendar_choices, primary_key: false) do
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :choice, :binary, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
