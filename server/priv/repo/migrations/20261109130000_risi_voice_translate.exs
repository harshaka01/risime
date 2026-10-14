defmodule RisiMe.Repo.Migrations.RisiVoiceTranslate do
  use Ecto.Migration

  # Proposal 2026-10-14-voice-translate (v1.50 §37.13). Additive only.
  #
  # `risi_voice_transcripts` records the note's blob id and ciphertext SHA-256 (never the key), so
  # a "Wrong language?" request (`transcript_language`) is honoured only for the note's own audio.
  # `risi_voice_settings.always_translate`: off | en | si | ta (the viewer's own setting).
  def change do
    alter table(:risi_voice_transcripts) do
      add :blob_id, :string
      add :blob_sha256, :binary
    end

    alter table(:risi_voice_settings) do
      add :always_translate, :string, null: false, default: "off"
    end
  end
end
