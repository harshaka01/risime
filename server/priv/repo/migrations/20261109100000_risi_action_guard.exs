defmodule RisiMe.Repo.Migrations.RisiActionGuard do
  use Ecto.Migration

  # Principle 4 (docs/ARCHITECTURE-PRINCIPLES.md, audit G9): the consequential-action guard's
  # decisions (`RisiMe.Agent.ActionGuard`). Ids, codes and an args hash only; never the args,
  # a message or a name. Additive only.
  def change do
    create table(:risi_action_guard, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :user_id, :binary_id
      add :approver_id, :binary_id
      add :conversation_id, :string
      add :tool, :string, null: false
      add :category, :string, null: false
      add :basis, :string, null: false
      add :decision, :string, null: false
      add :write_id, :binary_id
      add :args_sha256, :string
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:risi_action_guard, [:user_id, :inserted_at])
  end
end
