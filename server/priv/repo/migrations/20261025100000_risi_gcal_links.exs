defmodule RisiMe.Repo.Migrations.RisiGcalLinks do
  use Ecto.Migration

  # v1.31 §31.3 Google Calendar link (decision 074). Additive only (hard rule 9).
  #
  # Query: one link per user (PK), looked up by user; deleted with its device (`device_id`).
  # **No column for a Google token, an email, a calendar name or id, or an event id**: the
  # server only knows that a link exists, which device holds it, its state and two counts.
  def change do
    create table(:risi_gcal_links, primary_key: false) do
      add :user_id, :uuid, primary_key: true
      add :device_id, :uuid, null: false
      add :state, :text, null: false
      add :read_calendars, :smallint, null: false
      add :write_calendar, :boolean, null: false
      add :mirror, :boolean, null: false
      add :connected_at, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
      add :reconnect_card_at, :utc_datetime_usec
    end

    create constraint(:risi_gcal_links, :risi_gcal_links_state,
             check: "state IN ('connected','reauth_needed')"
           )

    create constraint(:risi_gcal_links, :risi_gcal_links_read_calendars,
             check: "read_calendars BETWEEN 0 AND 10"
           )

    # Removing a device deletes its link (one lookup by device).
    create index(:risi_gcal_links, [:device_id])
  end
end
