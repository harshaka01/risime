defmodule RisiMe.Repo.Migrations.CreateGroups do
  use Ecto.Migration

  # Contract v1.9 §12.10 / decision 041. Additive: 1:1 conversations keep their v1.7 tables.
  def change do
    # §12.1: the `groups` capability per MLS device.
    alter table(:devices) do
      add :capabilities, {:array, :string}, null: false, default: []
    end

    create table(:groups, primary_key: false) do
      # "grp:<uuid>", the conversation id (also the key of mls_groups / mls_commits).
      add :id, :string, primary_key: true
      add :created_by, references(:users, type: :binary_id, on_delete: :nilify_all)
      add :client_group_id, :binary_id, null: false
      add :state, :string, null: false
      add :generation, :integer, null: false, default: 1
      add :created_at, :utc_datetime_usec, null: false
    end

    create unique_index(:groups, [:created_by, :client_group_id])

    create table(:group_members, primary_key: false) do
      add :group_id, references(:groups, type: :string, on_delete: :delete_all), primary_key: true

      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :role, :string, null: false
      add :kind, :string, null: false, default: "user"
      add :state, :string, null: false
      add :joined_at, :utc_datetime_usec
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:group_members, [:user_id])

    # §12.4 pending operations. `payload` holds user_ids / role / added / removed; `tried` the
    # devices already named for this op (the committer rotation), `naming` a sequence number
    # that lets a stale committer timer recognise it was superseded.
    create table(:group_ops, primary_key: false) do
      add :op_id, :binary_id, primary_key: true
      add :group_id, references(:groups, type: :string, on_delete: :delete_all), null: false
      add :type, :string, null: false
      add :actor, :binary_id, null: false
      add :payload, :map, null: false
      add :committer_user, :binary_id
      add :committer_device, :binary_id
      add :committer_until, :utc_datetime_usec
      add :naming, :integer, null: false, default: 0
      add :tried, {:array, :binary_id}, null: false, default: []
      add :expires_at, :utc_datetime_usec
      add :created_at, :utc_datetime_usec, null: false
    end

    create index(:group_ops, [:group_id])

    # §12.6 blobs: metadata here, bytes on local disk outside the repo (BLOB_DIR).
    create table(:blobs, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :owner, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :purpose, :string, null: false
      add :conversation_id, :string, null: false
      add :size, :integer, null: false
      add :sha256, :binary, null: false
      add :expires_at, :utc_datetime_usec, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:blobs, [:expires_at])

    # Users of the devices named in a Welcome's `to_devices` that references the blob.
    create table(:blob_readers, primary_key: false) do
      add :blob_id, references(:blobs, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :user_id, :binary_id, primary_key: true
    end

    # §12.8: log entries carry `commit` or `commit_ref`.
    alter table(:mls_commits) do
      modify :commit, :binary, null: true, from: {:binary, null: false}
      add :commit_ref, :map
    end
  end
end
