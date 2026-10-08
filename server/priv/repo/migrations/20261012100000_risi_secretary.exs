defmodule RisiMe.Repo.Migrations.RisiSecretary do
  use Ecto.Migration

  # Contract v1.24 §24.11–§24.12 (decision 066), server S6/S7: Risi's derived data. Additive
  # only. No raw chat text is ever stored here: commitments and facts are derived lines, and
  # `source_message_ids` are TimeUUIDs. Everything of a conversation is deleted by
  # `RisiMe.Agent.forget/1` (Official off) and by the §15 delete path.
  def up do
    # pgvector (the dev/pilot Postgres is the pgvector image, decision 061 / CLAUDE.md stack).
    execute "CREATE EXTENSION IF NOT EXISTS vector"

    create table(:risi_commitments, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :conversation_id, :string, null: false
      add :chat_id, :string, null: false
      # proposed | confirmed | edited | done (declined, cancelled and expired rows are deleted)
      add :state, :string, null: false
      add :text, :string, size: 400, null: false
      add :owner_id, :uuid, null: false
      add :counterpart_ids, {:array, :uuid}, null: false, default: []
      add :due, :utc_datetime_usec
      # "datetime" | "date" (only a day was given: reminder at 09:00 local) | nil
      add :due_kind, :string
      add :due_text, :string, size: 200
      add :source_message_ids, {:array, :string}, null: false, default: []
      add :confidence, :float
      add :call_ref, :uuid
      add :card_message_id, :string
      add :by, :uuid
      # Bumped on every reschedule: a timer job carrying an older value is a no-op.
      add :schedule_v, :integer, null: false, default: 0
      add :proposed_at, :utc_datetime_usec, null: false
      add :confirmed_at, :utc_datetime_usec
      timestamps(type: :utc_datetime_usec)
    end

    create index(:risi_commitments, [:conversation_id, :state])
    create index(:risi_commitments, [:owner_id])
    create index(:risi_commitments, [:counterpart_ids], using: :gin)

    create table(:risi_facts, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :subject_user_id, :uuid, null: false
      add :chat_id, :string, null: false
      add :conversation_id, :string, null: false
      # commitment | date | person | preference | topic (§24.11)
      add :kind, :string, null: false
      add :text, :string, size: 400, null: false
      add :source_message_ids, {:array, :string}, null: false, default: []
      add :commitment_id, references(:risi_commitments, type: :uuid, on_delete: :delete_all)
      timestamps(type: :utc_datetime_usec, updated_at: false)
    end

    create index(:risi_facts, [:subject_user_id])
    create index(:risi_facts, [:conversation_id])

    # pgvector over the fact text only. Dimension-free until an embedding model is served
    # (RisiMe.Agent.Embeddings is a stub today); an HNSW index needs a fixed dimension.
    execute """
    CREATE TABLE risi_fact_embeddings (
      fact_id uuid PRIMARY KEY REFERENCES risi_facts(id) ON DELETE CASCADE,
      model text NOT NULL,
      embedding vector NOT NULL,
      inserted_at timestamp(6) without time zone NOT NULL
    )
    """

    # Per Official conversation: the extraction cursor and lease, and the digest guard.
    create table(:risi_chat_state, primary_key: false) do
      add :conversation_id, :string, primary_key: true
      add :extracted_upto, :string
      add :extracting_until, :utc_datetime_usec
      add :last_digest_on, :date
      timestamps(type: :utc_datetime_usec)
    end
  end

  def down do
    drop table(:risi_chat_state)
    execute "DROP TABLE risi_fact_embeddings"
    drop table(:risi_facts)
    drop table(:risi_commitments)
  end
end
