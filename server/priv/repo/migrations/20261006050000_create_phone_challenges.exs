defmodule RisiMe.Repo.Migrations.CreatePhoneChallenges do
  use Ecto.Migration

  # Contract v1.4 §7 / decision 022. One row per attempted SMS send: it holds the code hash and
  # is what the per-phone (24 h) and per-server (1 h) SMS budgets count.
  def change do
    alter table(:users) do
      add :phone_verified_for, :string
    end

    create table(:phone_challenges, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :phone, :string, null: false
      add :code_hash, :binary, null: false
      add :expires_at, :utc_datetime_usec, null: false
      add :attempts, :integer, null: false, default: 0
      add :consumed_at, :utc_datetime_usec

      timestamps(type: :utc_datetime_usec, updated_at: false)
    end

    create index(:phone_challenges, [:user_id, :inserted_at])
    create index(:phone_challenges, [:phone, :inserted_at])
    create index(:phone_challenges, [:inserted_at])
  end
end
