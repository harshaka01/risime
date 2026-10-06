defmodule RisiMe.Repo.Migrations.CreateMlsTables do
  use Ecto.Migration

  # Contract v1.7 §10 / decision 034.
  def change do
    alter table(:devices) do
      modify :push_token, :text, null: true, from: {:text, null: false}
      add :mls_signature_key, :binary
      add :mls_attestation, :text
      add :mls_attested_at, :utc_datetime_usec
    end

    # Census of app instances seen on the socket (device_id + app_version query params).
    # Pre-v1.7 apps send no device_id: one "legacy" instance per dev token or per JWT user.
    create table(:app_instances, primary_key: false) do
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :instance_key, :string, primary_key: true
      add :device_id, :binary_id
      add :app_version, :string
      add :last_seen_at, :utc_datetime_usec, null: false
    end

    create table(:mls_key_packages) do
      add :device_ref, references(:devices, type: :binary_id, on_delete: :delete_all), null: false
      add :key_package, :binary, null: false
      add :last_resort, :boolean, null: false, default: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:mls_key_packages, [:device_ref, :last_resort, :id])

    create unique_index(:mls_key_packages, [:device_ref],
             where: "last_resort",
             name: :mls_key_packages_one_last_resort
           )

    create table(:mls_groups, primary_key: false) do
      add :conversation_id, :string, primary_key: true
      add :generation, :integer, null: false, default: 1
      add :epoch, :bigint, null: false
      add :e2ee_since, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    create table(:mls_group_devices, primary_key: false) do
      add :conversation_id, :string, primary_key: true
      add :device_id, :binary_id, primary_key: true
      add :user_id, :binary_id, null: false
    end

    create index(:mls_group_devices, [:user_id])

    create table(:mls_commits, primary_key: false) do
      add :conversation_id, :string, primary_key: true
      add :generation, :integer, primary_key: true
      add :epoch, :bigint, primary_key: true
      add :commit, :binary, null: false
      add :from_device, :binary_id, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:mls_commits, [:inserted_at])
  end
end
