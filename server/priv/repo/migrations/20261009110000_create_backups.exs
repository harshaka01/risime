defmodule RisiMe.Repo.Migrations.CreateBackups do
  use Ecto.Migration

  # v1.22 §22.8 (decision 059): encrypted backups. Additive. Queries: a user's backups by
  # `uploaded_at` (list, retention, the 24-h commit rate); a user's key record; a blob's
  # `backup_id` (the commit's part check, `DELETE /backups`).
  def change do
    create table(:backups, primary_key: false) do
      add :backup_id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :device_id, :binary_id, null: false
      add :device_name, :string
      add :created_at, :utc_datetime_usec, null: false
      add :uploaded_at, :utc_datetime_usec, null: false
      add :size, :bigint, null: false
      add :sha256, :string, null: false
      add :schema, :integer, null: false
      add :app_version, :string, null: false
      add :bk_id, :string, null: false
      add :parts, {:array, :map}, null: false
      add :current, :boolean, null: false, default: true
      add :expires_at, :utc_datetime_usec
    end

    create index(:backups, [:user_id, :uploaded_at])

    create table(:backup_keys, primary_key: false) do
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :v, :integer, null: false
      add :bk_id, :string, null: false
      add :record, :map, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    alter table(:blobs) do
      add :backup_id, :binary_id
    end

    create index(:blobs, [:backup_id], where: "backup_id IS NOT NULL")
  end
end
