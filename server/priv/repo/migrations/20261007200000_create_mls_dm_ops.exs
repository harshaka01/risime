defmodule RisiMe.Repo.Migrations.CreateMlsDmOps do
  use Ecto.Migration

  # Proposal 2026-10-07-dm-device-readd (v1.16): DM `devices` ops (one pending op per DM and
  # user, §2) and DM resets (§3: `epoch` null while a reset DM awaits its rebuild).
  def change do
    create table(:mls_dm_ops, primary_key: false) do
      add :op_id, :binary_id, primary_key: true
      add :conversation_id, :string, null: false
      add :user_id, :binary_id, null: false
      add :payload, :map, null: false
      add :committer_user, :binary_id
      add :committer_device, :binary_id
      add :committer_until, :utc_datetime_usec
      add :naming, :integer, null: false, default: 0
      add :tried, {:array, :binary_id}, null: false, default: []
      add :created_at, :utc_datetime_usec, null: false
    end

    create unique_index(:mls_dm_ops, [:conversation_id, :user_id])

    alter table(:mls_groups) do
      modify :epoch, :bigint, null: true, from: {:bigint, null: false}
    end
  end
end
