defmodule RisiMe.Repo.Migrations.AddUsersLastSeenAt do
  use Ecto.Migration

  # Presence / last seen (PROTOCOL.md v1.2 §2.5). Null = never connected.
  def change do
    alter table(:users) do
      add :last_seen_at, :utc_datetime_usec
    end
  end
end
