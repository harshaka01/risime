defmodule RisiMe.Repo.Migrations.RisiVoice do
  use Ecto.Migration

  # Contract v1.41 §37 (decision 081): voice notes. Additive only.
  #
  # `risi_voice_settings`: the sender's §37.7 setting (`transcribe`, `action_items`; both default
  # true, so a missing row means true).
  #
  # `risi_voice_transcripts`: which Risi post is the `voice_transcript` of which note (§37.6
  # delete rules: the note deleted for everyone, or `transcript_delete` by its sender). Ids and
  # the speaker only; never text, never audio, never a key. Removed with the transcript and on
  # Official off.
  def change do
    create table(:risi_voice_settings, primary_key: false) do
      add :user_id, :uuid, primary_key: true
      add :transcribe, :boolean, null: false, default: true
      add :action_items, :boolean, null: false, default: true
      add :updated_at, :utc_datetime_usec, null: false
    end

    create table(:risi_voice_transcripts, primary_key: false) do
      add :conversation_id, :string, primary_key: true
      add :note_message_id, :string, primary_key: true
      add :transcript_message_id, :string, null: false
      add :speaker, :uuid, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end
  end
end
