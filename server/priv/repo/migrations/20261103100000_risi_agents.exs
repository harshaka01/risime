defmodule RisiMe.Repo.Migrations.RisiAgents do
  use Ecto.Migration

  # Decision 084 (PART 6, deep agents steps 0–2). Additive only (hard rule 9). No chat text in
  # any of these tables: names, labels, counts and timings.
  #
  # * risi_agent_flags: runtime flag overrides (`RISI_AGENTS`, `RISI_AGENT_<NAME>`), read by
  #   RisiMe.Agents.Flags (5-s cache per node): flipping a flag needs no deploy.
  # * risi_agent_runs: one row per orchestrated agent run (shadow, canary, on, and the legacy
  #   turn's timing): mode, the legacy route and the agent's decision, status, fallback,
  #   latency, budget, learned-state version. `user_ref` is a hash prefix, never a user id.
  # * risi_agent_scores: the daily scoreboard and the golden-set results, per agent and day.
  def change do
    create table(:risi_agent_flags, primary_key: false) do
      add :name, :string, primary_key: true
      add :value, :string, null: false
      add :updated_by, :string
      add :updated_at, :utc_datetime_usec, null: false
    end

    create table(:risi_agent_runs, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :agent, :string, null: false
      add :mode, :string, null: false
      add :legacy_route, :string
      add :decision, :string
      add :agreed, :boolean
      add :status, :string, null: false
      add :fell_back, :boolean, null: false, default: false
      add :reason, :string
      add :latency_ms, :integer
      # The legacy turn: request at the server → `risi_progress` `working` (typing budget).
      add :first_ms, :integer
      add :budget_ms, :integer
      add :learned_version, :string
      add :by, :string
      add :lang, :string
      add :user_ref, :string
      add :at, :utc_datetime_usec, null: false
    end

    create index(:risi_agent_runs, [:agent, :at])
    create index(:risi_agent_runs, [:at])

    create table(:risi_agent_scores, primary_key: false) do
      add :day, :date, primary_key: true
      add :agent, :string, primary_key: true
      add :source, :string, primary_key: true
      add :n, :integer, null: false, default: 0
      add :accuracy, :float
      add :acceptance, :float
      add :agreement, :float
      add :halluc, :integer
      add :p50_ms, :integer
      add :p95_ms, :integer
      add :incidents, :integer, null: false, default: 0
      add :detail, :map
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
