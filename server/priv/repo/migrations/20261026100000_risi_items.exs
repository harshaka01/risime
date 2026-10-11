defmodule RisiMe.Repo.Migrations.RisiItems do
  use Ecto.Migration

  # v1.35 §34.4 Risi's items: a per-user ledger of what Risi set up (phone_event_added,
  # risi_calendar_event, reminder, scheduled_message; promises and follow-ups are read live).
  # Additive only (hard rule 9).
  #
  # Queries first: (1) a user's items in a time range (user_id, start_at); (2) one item by id;
  # (3) an item by its source ref (user_id, kind, ref_key) for idempotent writes and backfill.
  #
  # **No plaintext title column**: `title_sealed` / `calendar_sealed` are AES-256-GCM under
  # RISI_DATA_KEY (AAD `risi_items:<id>:title` / `…:calendar`). `ref` holds ids only.
  def change do
    create table(:risi_items, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :user_id, :uuid, null: false
      add :kind, :text, null: false
      add :ref, :map, null: false
      add :ref_key, :text, null: false
      add :title_sealed, :binary
      add :calendar_sealed, :binary
      add :conversation_id, :text
      add :start_at, :utc_datetime_usec
      add :end_at, :utc_datetime_usec
      add :all_day, :boolean, null: false, default: false
      add :state, :text, null: false, default: "active"
      add :created_at, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    create constraint(:risi_items, :risi_items_kind,
             check:
               "kind IN ('phone_event_added','risi_calendar_event','reminder','scheduled_message')"
           )

    create index(:risi_items, [:user_id, :start_at])
    create unique_index(:risi_items, [:user_id, :kind, :ref_key])
  end
end
