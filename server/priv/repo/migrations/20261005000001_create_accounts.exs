defmodule RisiMe.Repo.Migrations.CreateAccounts do
  use Ecto.Migration

  def change do
    create table(:allowlist, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :phone, :string, null: false
      add :email, :string, null: false
      add :display_name, :string, null: false
      add :company, :string, null: false

      timestamps(type: :utc_datetime_usec)
    end

    create unique_index(:allowlist, [:phone])

    create table(:users, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :phone, :string, null: false
      add :email, :string, null: false
      add :display_name, :string, null: false
      add :company, :string, null: false

      timestamps(type: :utc_datetime_usec)
    end

    create unique_index(:users, [:phone])

    create table(:otp_challenges, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :phone, :string, null: false
      add :code_hash, :binary, null: false
      add :expires_at, :utc_datetime_usec, null: false
      add :attempts, :integer, null: false, default: 0
      add :consumed_at, :utc_datetime_usec

      timestamps(type: :utc_datetime_usec, updated_at: false)
    end

    create index(:otp_challenges, [:phone, :inserted_at])

    create table(:user_tokens, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :token_hash, :binary, null: false
      add :device_name, :string
      add :last_seen_at, :utc_datetime_usec
      add :revoked_at, :utc_datetime_usec

      timestamps(type: :utc_datetime_usec, updated_at: false)
    end

    create unique_index(:user_tokens, [:token_hash])
    create index(:user_tokens, [:user_id])
  end
end
