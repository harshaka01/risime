defmodule RisiMe.Repo.Migrations.RisiCalendar do
  use Ecto.Migration

  # v1.29 §29.5 Risi Calendar (decision 073). Additive only (hard rule 9).
  #
  # Queries first: (1) a user's events in a time range (participants by user → events by id,
  # start index); (2) a user's changes since a cursor (`risi_calendar_log` by (user, seq));
  # (3) one event with its participants (PK + participants PK); (4) due reminders (Oban jobs per
  # (event, user), args ids and `schedule_v` only).
  #
  # **No plaintext title or notes column**: `title_sealed` / `notes_sealed` are AES-256-GCM under
  # RISI_DATA_KEY (AAD `risi_events:<event_id>:title|notes`, `RisiMe.Agent.Seal.seal_text/4`).
  # Every other table holds ids, times, states and counters only.
  def change do
    create table(:risi_events, primary_key: false) do
      add :event_id, :uuid, primary_key: true
      # Not a foreign key: the events of a deleted owner are cancelled by the server (§29.4).
      add :owner, :uuid, null: false
      add :title_sealed, :binary, null: false
      add :notes_sealed, :binary
      add :start_at, :utc_datetime_usec, null: false
      add :end_at, :utc_datetime_usec, null: false
      add :all_day, :boolean, null: false
      add :tz, :text, null: false
      add :created_by, :text, null: false
      add :state, :text, null: false, default: "active"
      add :version, :integer, null: false, default: 1
      add :source_conversation_id, :text
      add :source_message_ids, {:array, :uuid}, null: false, default: []
      # Ids only (§29.4: "only ids remain in source"): no foreign keys, so an item or a note
      # deleted with its chat's Risi data never deletes a user's event.
      add :source_item_id, :uuid
      add :source_note_id, :uuid
      add :created_at, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
      add :cancelled_at, :utc_datetime_usec
    end

    create constraint(:risi_events, :risi_events_time,
             check: "start_at < end_at AND end_at - start_at <= interval '14 days'"
           )

    create constraint(:risi_events, :risi_events_created_by,
             check: "created_by IN ('risi','user')"
           )

    create constraint(:risi_events, :risi_events_state, check: "state IN ('active','cancelled')")

    # nonce 12 + tag 16 + ≥ 1 byte of ciphertext.
    create constraint(:risi_events, :risi_events_title_sealed,
             check: "octet_length(title_sealed) BETWEEN 29 AND 4096"
           )

    create constraint(:risi_events, :risi_events_notes_sealed,
             check: "notes_sealed IS NULL OR octet_length(notes_sealed) BETWEEN 29 AND 32768"
           )

    create index(:risi_events, [:start_at])
    create index(:risi_events, [:source_item_id])
    create index(:risi_events, [:source_conversation_id, :created_at])
    create index(:risi_events, [:state, :cancelled_at])

    create table(:risi_event_participants, primary_key: false) do
      add :event_id,
          references(:risi_events, column: :event_id, type: :uuid, on_delete: :delete_all),
          primary_key: true

      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :status, :text, null: false
      add :responded_at, :utc_datetime_usec
      add :reminder_min, :integer
      add :removed, :boolean, null: false, default: false
      # The reminder job's version (§29.10: rescheduled on every time/reminder change).
      add :schedule_v, :integer, null: false, default: 0
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create constraint(:risi_event_participants, :risi_event_participants_status,
             check: "status IN ('proposed','accepted','declined')"
           )

    create constraint(:risi_event_participants, :risi_event_participants_reminder,
             check: "reminder_min IS NULL OR reminder_min BETWEEN 0 AND 10080"
           )

    create index(:risi_event_participants, [:user_id, :removed])

    create table(:risi_event_suggestions, primary_key: false) do
      add :suggestion_id, :uuid, primary_key: true

      add :event_id,
          references(:risi_events, column: :event_id, type: :uuid, on_delete: :delete_all),
          null: false

      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :start_at, :utc_datetime_usec, null: false
      add :end_at, :utc_datetime_usec, null: false
      add :all_day, :boolean, null: false
      add :state, :text, null: false, default: "open"
      add :created_at, :utc_datetime_usec, null: false
    end

    create constraint(:risi_event_suggestions, :risi_event_suggestions_state,
             check: "state IN ('open','used','kept','closed')"
           )

    create constraint(:risi_event_suggestions, :risi_event_suggestions_time,
             check: "start_at < end_at"
           )

    create index(:risi_event_suggestions, [:event_id, :user_id, :state])

    # The per-user change log: the sync cursor (ids only; 30 days).
    execute "CREATE SEQUENCE risi_calendar_log_seq", "DROP SEQUENCE risi_calendar_log_seq"

    create table(:risi_calendar_log, primary_key: false) do
      add :user_id, :binary_id, primary_key: true
      add :seq, :bigint, primary_key: true
      add :event_id, :uuid, null: false
      add :at, :utc_datetime_usec, null: false
    end

    create index(:risi_calendar_log, [:at])

    create table(:risi_calendar_settings, primary_key: false) do
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :default_reminder_min, :integer, default: 30
      add :default_duration_min, :integer, null: false, default: 60
      add :digest_events, :boolean, null: false, default: true
      add :updated_at, :utc_datetime_usec, null: false
    end

    create constraint(:risi_calendar_settings, :risi_calendar_settings_values,
             check:
               "(default_reminder_min IS NULL OR default_reminder_min BETWEEN 0 AND 10080) AND " <>
                 "default_duration_min BETWEEN 5 AND 1440"
           )

    # Invites held for a participant who isn't a calendar user yet (or has no Risi chat), until
    # they become one or the event starts (§29.1, §29.6).
    create table(:risi_calendar_invites_pending, primary_key: false) do
      add :event_id,
          references(:risi_events, column: :event_id, type: :uuid, on_delete: :delete_all),
          primary_key: true

      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :reason, :text, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:risi_calendar_invites_pending, [:user_id])

    # `POST /risi/calendar/events` idempotency: a repeated `client_event_id` within 24 h.
    create table(:risi_calendar_client_ids, primary_key: false) do
      add :user_id, :binary_id, primary_key: true
      add :client_event_id, :uuid, primary_key: true
      add :event_id, :uuid, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:risi_calendar_client_ids, [:inserted_at])
  end
end
