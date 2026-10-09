defmodule RisiMe.Repo.Migrations.RisiDailySummaries do
  use Ecto.Migration

  # 30-day summaries (Harsha 2026-10-09, proposal 2026-10-09-risi-30day-summaries). Additive only.
  # One row per Official conversation and local day (`scope: "day"`), rolled up per week
  # (`scope: "week"`); the summary (a derived JSON: summary, decisions, action items, open
  # questions) is sealed with RISI_DATA_KEY (`summary_sealed`, AAD
  # `risi_daily_summaries:<id>:summary`; no plaintext column). `covered_from`/`covered_to`: the
  # server_ts range of the messages it covers. Kept 35 days; deleted with the chat's Risi data
  # (§24.4) and on request (GET/DELETE /api/v1/risi/facts "Summaries").
  def change do
    create table(:risi_daily_summaries, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :conversation_id, :string, null: false
      add :chat_id, :string, null: false
      add :scope, :string, null: false
      add :period_from, :date, null: false
      add :period_to, :date, null: false
      add :tz, :string, null: false
      add :covered_from, :utc_datetime_usec
      add :covered_to, :utc_datetime_usec
      add :message_count, :integer, null: false, default: 0
      add :summary_sealed, :binary, null: false
      add :call_ref, :uuid
      add :made_by, :map
      add :created_at, :utc_datetime_usec, null: false
    end

    create unique_index(:risi_daily_summaries, [:conversation_id, :scope, :period_from])
    create index(:risi_daily_summaries, [:created_at])
  end
end
