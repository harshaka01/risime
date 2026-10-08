defmodule RisiMe.Repo.Migrations.OpsStrikes do
  use Ecto.Migration

  # v1.21 §12.12.4: per-op, per-device naming strikes `{device_id => [count, last_at]}` on
  # group `devices` ops and DM ops. Additive.
  def change do
    alter table(:group_ops) do
      add :strikes, :map, null: false, default: %{}
    end

    alter table(:mls_dm_ops) do
      add :strikes, :map, null: false, default: %{}
    end
  end
end
