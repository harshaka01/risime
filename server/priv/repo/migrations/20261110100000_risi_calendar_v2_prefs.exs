defmodule RisiMe.Repo.Migrations.RisiCalendarV2Prefs do
  use Ecto.Migration

  # Proposal 2026-10-14-calendar-v2 (§44.10). Additive only. One row per user: the titles consent
  # (`times` until the user says Yes), the first phone read after a grant (the "✓ I can see your
  # calendars now." line, once), the all-day reminder time and the last 5 reminder leads the user
  # chose (L1 preference memory). No titles, no event data.
  def change do
    create table(:risi_calendar_prefs, primary_key: false) do
      add :user_id, :binary_id, primary_key: true
      add :titles, :string, null: false, default: "times"
      add :titles_asked_at, :utc_datetime_usec
      add :first_read_at, :utc_datetime_usec
      add :allday_remind_at, :time, null: false, default: "18:00:00"
      add :lead_samples, {:array, :integer}, null: false, default: []
      add :updated_at, :utc_datetime_usec, null: false
    end

    create constraint(:risi_calendar_prefs, :risi_calendar_prefs_titles,
             check: "titles IN ('times', 'titles')"
           )
  end
end
