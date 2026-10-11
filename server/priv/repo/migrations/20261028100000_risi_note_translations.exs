defmodule RisiMe.Repo.Migrations.RisiNoteTranslations do
  use Ecto.Migration

  # Contract v1.36 §35.4 (Risi Brain v2 step 3): a Risi Note per participant "in that
  # participant's language". One translation per (note, language), shared by every recipient of
  # that language; topic and key points sealed under RISI_DATA_KEY (AAD
  # `risi_note_translations:<note_id>:<language>`), deleted with the note. Additive only.
  def change do
    create table(:risi_note_translations, primary_key: false) do
      add :note_id,
          references(:risi_notes, column: :note_id, type: :uuid, on_delete: :delete_all),
          primary_key: true

      add :language, :text, primary_key: true
      add :body_sealed, :binary, null: false
      add :created_at, :utc_datetime_usec, null: false
    end
  end
end
