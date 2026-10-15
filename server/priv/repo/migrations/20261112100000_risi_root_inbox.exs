defmodule RisiMe.Repo.Migrations.RisiRootInbox do
  use Ecto.Migration

  # Harsha 2026-10-15 15:31 SLT (`RisiMe.Agent.RootInbox`): root's questions to Harsha in his
  # Risi chat (`scripts/risi-notify`) and his tap or text reply (`scripts/risi-inbox`).
  # Additive; one open question per topic (a new post on a topic supersedes the old one).
  def change do
    create table(:risi_root_questions, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :user_id, :binary_id, null: false
      add :conversation_id, :text, null: false
      add :message_id, :text
      add :topic, :text, null: false
      add :kind, :text, null: false, default: "ask"
      add :text, :text, null: false
      add :options, {:array, :text}, null: false, default: []
      add :posted_at, :utc_datetime_usec, null: false
      add :superseded_at, :utc_datetime_usec
      add :answered_at, :utc_datetime_usec
      add :answer, :text
      add :answer_kind, :text
      add :delivered_at, :utc_datetime_usec
    end

    create index(:risi_root_questions, [:user_id, :posted_at])
    create index(:risi_root_questions, [:user_id, :topic])
  end
end
