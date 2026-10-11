defmodule RisiMe.Repo.Migrations.RisiMemory do
  use Ecto.Migration

  # Contract v1.36 §35 (decision 077, Risi Brain v2): Risi's long-term memory of Official chats.
  # Additive only (hard rule 9; fast lane rule 12).
  #
  # Queries first:
  #   (1) the stored messages of a set of conversations in a time range, oldest first
  #       (conversation_id, at);
  #   (2) one message by (conversation_id, message_id) (idempotent writes, §15 deletes);
  #   (3) a user's own messages / Risi's posts to them (§35.3 deletes): (sender_id) and
  #       (addressed_to);
  #   (4) the items of a set of conversations in a range, and an item by person (people_ids GIN);
  #   (5) nearest neighbours of a query vector among (1)/(4)'s candidates (pgvector `<=>`; the
  #       candidates are filtered by conversation first, so no ANN index is needed at pilot scale).
  #
  # **No plaintext text column anywhere**: `body_sealed` / `text_sealed` are AES-256-GCM under
  # RISI_DATA_KEY with the row AAD `<table>:<id>:<column>` (RisiMe.Agent.Seal). The vectors hold
  # no text (computed on spark2 only).
  def change do
    create table(:risi_memory_messages, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :conversation_id, :text, null: false
      add :message_id, :text, null: false
      add :sender_id, :uuid, null: false
      # Risi's own post: the user it answered (notify of one), else null (a post to the chat).
      add :addressed_to, :uuid
      add :from_risi, :boolean, null: false, default: false
      add :at, :utc_datetime_usec, null: false
      add :body_sealed, :binary, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create unique_index(:risi_memory_messages, [:conversation_id, :message_id])
    create index(:risi_memory_messages, [:conversation_id, :at])
    create index(:risi_memory_messages, [:sender_id])
    create index(:risi_memory_messages, [:addressed_to])

    create table(:risi_memory_items, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :conversation_id, :text, null: false
      # person | project | decision | promise | meeting | deadline | open_question | summary
      add :kind, :text, null: false
      add :people_ids, {:array, :uuid}, null: false, default: []
      # {text, people (names), project} sealed together.
      add :text_sealed, :binary, null: false
      add :at, :utc_datetime_usec
      add :source_message_ids, {:array, :text}, null: false, default: []
      add :inserted_at, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    create index(:risi_memory_items, [:conversation_id, :inserted_at])
    create index(:risi_memory_items, [:people_ids], using: :gin)

    execute(
      """
      CREATE TABLE risi_memory_vectors (
        owner_id uuid PRIMARY KEY,
        owner_kind text NOT NULL,
        conversation_id text NOT NULL,
        model text NOT NULL,
        embedding vector(384) NOT NULL,
        inserted_at timestamp(6) without time zone NOT NULL
      )
      """,
      "DROP TABLE risi_memory_vectors"
    )

    execute(
      "CREATE INDEX risi_memory_vectors_conv ON risi_memory_vectors (conversation_id)",
      "SELECT 1"
    )

    # §35.3 "Stop Risi learning from my messages": scope '*' = everywhere, else a conversation.
    create table(:risi_learning_optouts, primary_key: false) do
      add :user_id, :uuid, primary_key: true
      add :scope, :text, primary_key: true
      add :enabled, :boolean, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    # Per conversation: the ingest cursor and lease, and when the 24-h buffer was copied in.
    create table(:risi_memory_state, primary_key: false) do
      add :conversation_id, :text, primary_key: true
      add :seeded_at, :utc_datetime_usec
      add :ingested_upto, :utc_datetime_usec
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
