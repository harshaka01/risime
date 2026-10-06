defmodule RisiMe.Repo.Migrations.CreateDevices do
  use Ecto.Migration

  # Contract v1.5 §8 / decision 028: app installs and their FCM tokens.
  def change do
    create table(:devices, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :device_id, :binary_id, null: false
      add :platform, :string, null: false
      add :push_token, :text, null: false
      add :app_version, :string
      # The dev token it was registered with (dev logout removes these devices).
      add :user_token_id, references(:user_tokens, type: :binary_id, on_delete: :nilify_all)
      add :last_seen_at, :utc_datetime_usec, null: false

      timestamps(type: :utc_datetime_usec)
    end

    create unique_index(:devices, [:user_id, :device_id])
    create index(:devices, [:push_token])
    create index(:devices, [:last_seen_at])
    create index(:devices, [:user_token_id])
  end
end
