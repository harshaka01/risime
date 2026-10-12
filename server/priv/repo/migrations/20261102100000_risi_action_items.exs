defmodule RisiMe.Repo.Migrations.RisiActionItems do
  use Ecto.Migration

  # Contract v1.42 §38 (decision 082): Risi voice → action items. Additive only (hard rule 9).
  #
  # * risi_commitments: ledger items gain their §38.3 `kind` (promise | task | meeting; NULL =
  #   an older item, a "promise" on the wire), the line they came from (`source_ref`, ids and
  #   times only) and the language spoken (`lang`).
  # * risi_discussions: the extraction's whole source (`source_ref`) and, for a voice note, its
  #   message id (`note_message_id`, for the §38.6 delete rules); `source` may be `voice_note`.
  # * risi_action_extras: the card's items that are not ledger items (`decision`, `question`,
  #   `number`, and unowned `task`s): text sealed with RISI_DATA_KEY (AAD
  #   `risi_action_extras:<id>:text`), no plaintext column, kept 30 days (`expires_at`).
  def change do
    alter table(:risi_commitments) do
      add :kind, :string
      add :source_ref, :map
      add :lang, :string
    end

    alter table(:risi_discussions) do
      add :source_ref, :map
      add :note_message_id, :string
    end

    create index(:risi_discussions, [:note_message_id])

    create table(:risi_action_extras, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :summary_id, :uuid, null: false
      add :conversation_id, :string, null: false
      add :kind, :string, null: false
      add :text_sealed, :binary, null: false
      add :lang, :string
      add :source_ref, :map
      add :expires_at, :utc_datetime_usec, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:risi_action_extras, [:summary_id])
    create index(:risi_action_extras, [:conversation_id])
    create index(:risi_action_extras, [:expires_at])
  end
end
