defmodule RisiMe.Repo.Migrations.RisiSpeechLangsCounts do
  use Ecto.Migration

  # P1 2026-10-14 (Tamil notes in Sinhala script): the Voice agent's per-speaker language profile
  # (RisiMe.Agent.SpeechLang) counts voice notes and "Wrong language?" corrections per language,
  # next to the speech milliseconds. Counts only; no text, no audio. Additive only.
  def change do
    alter table(:risi_speech_langs) do
      add :notes, :integer, null: false, default: 0
      add :corrections, :integer, null: false, default: 0
    end
  end
end
