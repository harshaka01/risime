defmodule RisiMe.Repo.Migrations.AddObanJobsTable do
  use Ecto.Migration

  # Pinned so a later Oban upgrade doesn't silently change what this migration does.
  # Upgrades that need new Oban schema versions get their own migration.
  def up, do: Oban.Migration.up(version: 14)

  def down, do: Oban.Migration.down(version: 1)
end
