defmodule RisiMe.Repo.Migrations.RisiBrief do
  use Ecto.Migration

  # Contract v1.36 §35.4 (Risi Brain v2 step 4): the 07:00 daily brief, at most once a day per
  # user (ids and a date only). Additive only.
  def change do
    create table(:risi_brief_state, primary_key: false) do
      add :user_id, :uuid, primary_key: true
      add :last_on, :date, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
