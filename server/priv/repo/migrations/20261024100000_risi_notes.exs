defmodule RisiMe.Repo.Migrations.RisiNotes do
  use Ecto.Migration

  # v1.29 §30.7 Risi Notes (decision 073). Additive only (hard rule 9).
  #
  # Queries first: (1) a user's notes, newest first (recipients by user → notes by id, ended_at);
  # (2) one note with its recipients (PKs); (3) a conversation's notes (Official off); (4) notes
  # older than 365 days (retention).
  #
  # **No plaintext text column**: `body_sealed` holds topic, language and key points, AES-256-GCM
  # under RISI_DATA_KEY (AAD `risi_notes:<note_id>:body`). Items stay in `risi_commitments`
  # (`summary_id = note_id`), events in `risi_events` (`source_note_id`).
  #
  # `risi_commitments.done_at` / `reopen_state`: §30.5 `item_reopen` (within 7 days of done, back
  # to the state before done). Ids, states and times only.
  def change do
    create table(:risi_notes, primary_key: false) do
      add :note_id, :uuid, primary_key: true
      add :conversation_id, :text, null: false
      add :chat_id, :text, null: false
      add :source, :text, null: false
      add :call_id, :uuid
      add :media, :text
      add :duration_s, :integer
      add :started_at, :utc_datetime_usec, null: false
      add :ended_at, :utc_datetime_usec, null: false
      add :participants, {:array, :uuid}, null: false, default: []
      add :body_sealed, :binary, null: false
      add :made_by, :map
      add :call_ref, :uuid
      add :created_at, :utc_datetime_usec, null: false
    end

    create constraint(:risi_notes, :risi_notes_source,
             check: "source IN ('chat','call','request')"
           )

    # nonce 12 + tag 16 + ≥ 1 byte of ciphertext.
    create constraint(:risi_notes, :risi_notes_body_sealed,
             check: "octet_length(body_sealed) >= 29"
           )

    create index(:risi_notes, [:conversation_id])
    create index(:risi_notes, [:created_at])

    create table(:risi_note_recipients, primary_key: false) do
      add :note_id,
          references(:risi_notes, column: :note_id, type: :uuid, on_delete: :delete_all),
          primary_key: true

      add :user_id, :uuid, primary_key: true
      add :deleted, :boolean, null: false, default: false
      add :ended_at, :utc_datetime_usec, null: false
    end

    create index(:risi_note_recipients, [:user_id, :deleted, :ended_at])

    alter table(:risi_commitments) do
      add :done_at, :utc_datetime_usec
      add :reopen_state, :string
    end
  end
end
