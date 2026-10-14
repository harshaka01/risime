defmodule RisiMe.Repo.Migrations.RisiVoiceQueue do
  use Ecto.Migration

  # Proposal 2026-10-14-voice-queue (§37.8 amendment): a Risi-chat voice note whose speech
  # service is down, busy or out of memory waits here for the retry job instead of getting a
  # "please type it" answer. One row per waiting note: the blob reference and its key, sealed
  # under RISI_DATA_KEY (AAD binds the row); never audio, never plaintext. The row is deleted
  # when the note is transcribed or given up (bounded, 30 min). Additive only.
  def change do
    create table(:risi_voice_queue, primary_key: false) do
      add :note_message_id, :string, primary_key: true
      add :conversation_id, :string, null: false
      add :sender, :uuid, null: false
      add :sealed, :binary, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end
  end
end
