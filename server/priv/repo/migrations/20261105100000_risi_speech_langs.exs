defmodule RisiMe.Repo.Migrations.RisiSpeechLangs do
  use Ecto.Migration

  # Decision 071 amendment d (speech hint): each user's transcribed speech per language, from
  # their call turns (§36) and voice notes (§37), so a Sinhala/Tamil speaker who types in Latin
  # letters still gets a `language_hint`. Language codes and milliseconds only; no text, no
  # audio. Additive only.
  def change do
    create table(:risi_speech_langs, primary_key: false) do
      add :user_id, :uuid, primary_key: true
      add :language, :string, primary_key: true
      add :speech_ms, :bigint, null: false, default: 0
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
