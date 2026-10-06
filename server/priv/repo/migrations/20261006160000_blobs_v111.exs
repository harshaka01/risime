defmodule RisiMe.Repo.Migrations.BlobsV111 do
  use Ecto.Migration

  # Contract v1.11 §14.8 (decision 042): idempotent uploads by `client_blob_id`, soft delete,
  # a nullable `expires_at` (null = the current group icon), the quota and conversation indexes.
  def up do
    alter table(:blobs) do
      add :client_blob_id, :binary_id
      add :deleted_at, :utc_datetime_usec
      modify :expires_at, :utc_datetime_usec, null: true
    end

    create unique_index(:blobs, [:owner, :client_blob_id],
             where: "client_blob_id IS NOT NULL",
             name: :blobs_owner_client_blob_id_index
           )

    create index(:blobs, [:conversation_id])

    # The quota sum (live bytes per owner and purpose) and the upload counts (rate, usage).
    drop_if_exists index(:blobs, [:owner, :purpose])

    execute """
    CREATE INDEX blobs_live_owner_purpose_index ON blobs (owner, purpose) INCLUDE (size)
    WHERE deleted_at IS NULL
    """

    create index(:blobs, [:owner, :purpose, :inserted_at])
  end

  def down do
    drop index(:blobs, [:owner, :purpose, :inserted_at])
    execute "DROP INDEX IF EXISTS blobs_live_owner_purpose_index"
    create_if_not_exists index(:blobs, [:owner, :purpose])
    drop index(:blobs, [:conversation_id])
    drop index(:blobs, [:owner, :client_blob_id], name: :blobs_owner_client_blob_id_index)

    execute "DELETE FROM blobs WHERE expires_at IS NULL"

    alter table(:blobs) do
      remove :client_blob_id
      remove :deleted_at
      modify :expires_at, :utc_datetime_usec, null: false
    end
  end
end
