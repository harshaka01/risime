defmodule RisiMe.Repo.Migrations.RisiHealthStuck do
  use Ecto.Migration

  # Decision 088 (contract v1.46 §12.12.9): the Health agent's daily record of conversations it
  # found stuck (starting, re-add or rebuild waiting more than 5 minutes), what it did, and
  # whether it repaired them. Ids, codes and counts only; no content. Additive only.
  def change do
    create table(:risi_health_stuck, primary_key: false) do
      add :day, :date, primary_key: true
      add :conversation_id, :string, primary_key: true
      add :kind, :string, primary_key: true
      add :tab, :string, null: false
      add :action, :string, null: false
      add :repaired, :boolean, null: false, default: false
      add :sweeps, :integer, null: false, default: 1
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
