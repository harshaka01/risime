defmodule RisiMe.Repo.Migrations.RisiCallTranscribe do
  use Ecto.Migration

  # Contract v1.39 §36 (decision 080): the call-transcription fast path. Additive only.
  #
  # `risi_call_transcribe_sessions` is the consent record (§36.8): ids, the participants with
  # their consents and bound devices, the state, the reason, timestamps and counts. No text, no
  # audio. Rows are kept 90 days (`RisiMe.Agent.CallTranscribe.prune/0`).
  #
  # `risi_dev_settings` holds a developer's §36.1 setting (`transcribe_compare`, default false).
  def change do
    create table(:risi_call_transcribe_sessions, primary_key: false) do
      add :session_id, :uuid, primary_key: true
      add :call_id, :uuid, null: false
      add :conversation_id, :string, null: false
      add :chat_id, :string, null: false
      add :state, :string, null: false
      add :reason, :string
      add :requested_by, :uuid, null: false
      add :requested_at, :utc_datetime_usec, null: false
      add :expires_at, :utc_datetime_usec
      add :started_at, :utc_datetime_usec
      add :ended_at, :utc_datetime_usec
      add :by, :uuid
      add :discard, :boolean, null: false, default: false
      add :compare, :boolean, null: false, default: false
      # [{"user_id", "consent", "at", "device_id"}]
      add :participants, {:array, :map}, null: false
      add :chunks, :integer, null: false, default: 0
      add :speech_ms, :integer, null: false, default: 0
      add :posts, :integer, null: false, default: 0
      add :inserted_at, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    create index(:risi_call_transcribe_sessions, [:call_id, :requested_at])
    create index(:risi_call_transcribe_sessions, [:state])
    create index(:risi_call_transcribe_sessions, [:inserted_at])

    create table(:risi_dev_settings, primary_key: false) do
      add :user_id, :uuid, primary_key: true
      add :transcribe_compare, :boolean, null: false, default: false
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
