defmodule RisiMe.Repo.Migrations.DeviceAppId do
  use Ecto.Migration

  # Contract v1.37 §8.1 (decision 079): the client's Android application id picks the Firebase
  # project for this device's pushes. Additive and nullable: old rows mean the original project.
  def change do
    alter table(:devices) do
      add :app_id, :string, size: 100
    end
  end
end
