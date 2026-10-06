defmodule RisiMe.Repo.Migrations.AddAppInstancesFirstSeenAt do
  use Ecto.Migration

  # Contract v1.10 §13.2: `history_before`. Existing rows stay null (no backfill: a late
  # estimate would hide decryptable messages). `history_reset` marks a row whose device was
  # removed, so the next connect sets a fresh `first_seen_at`.
  def change do
    alter table(:app_instances) do
      add :first_seen_at, :utc_datetime_usec
      add :history_reset, :boolean, null: false, default: false
    end
  end
end
