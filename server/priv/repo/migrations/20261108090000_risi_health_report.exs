defmodule RisiMe.Repo.Migrations.RisiHealthReport do
  use Ecto.Migration

  # Incident 2026-10-14 (decision 088 amendment, proposal 2026-10-14-health-nudge): the Health
  # agent remembers what it last told the operators about a stuck conversation (`reported`:
  # `waiting_other` | `needs_fix` | `fixed`) and when it last reminded the other person of a
  # Private 1:1 (`nudged_at`, at most once a day per chat). Kept in Postgres so a deploy doesn't
  # repeat either. Codes and times only. Additive only.
  def change do
    alter table(:risi_health_stuck) do
      add :reported, :string
      add :nudged_at, :utc_datetime_usec
    end
  end
end
