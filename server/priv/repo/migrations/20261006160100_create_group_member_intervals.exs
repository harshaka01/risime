defmodule RisiMe.Repo.Migrations.CreateGroupMemberIntervals do
  use Ecto.Migration

  # Contract v1.11 §14.8: membership history for the `media` read rule. An interval opens when a
  # member becomes `active` and closes when they turn `pending_remove` (or are deleted); at most
  # one open interval per (group, user). Existing active members get one open interval.
  def up do
    create table(:group_member_intervals) do
      add :group_id, references(:groups, type: :string, on_delete: :delete_all), null: false
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :active_from, :utc_datetime_usec, null: false
      add :active_until, :utc_datetime_usec
    end

    create index(:group_member_intervals, [:group_id, :user_id, :active_until])

    create unique_index(:group_member_intervals, [:group_id, :user_id],
             where: "active_until IS NULL",
             name: :gmi_one_open
           )

    execute """
    INSERT INTO group_member_intervals (group_id, user_id, active_from)
    SELECT group_id, user_id, coalesce(joined_at, inserted_at)
    FROM group_members WHERE state = 'active'
    """
  end

  def down do
    drop table(:group_member_intervals)
  end
end
