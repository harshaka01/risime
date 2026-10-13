defmodule RisiMe.Repo.Migrations.AdminConsole do
  use Ecto.Migration

  # Decision 089 (admin console). Additive only (hard rule 9). No message content anywhere.
  #
  # * admins: who may use /admin. Admins live here, never in code. A removal sets `removed_at`
  #   (the row stays); at most one active row per user. `added_by` nil = the one-time seed.
  # * admin_audit_log: append-only (a trigger refuses UPDATE and DELETE). Every admin action:
  #   who, what, target, before/after summary, when.
  # * admin_settings: runtime settings changed from the console (e.g. `speech_route_si`), read
  #   with a 5-s cache (RisiMe.Admin.Settings): table > env > default. No row = default.
  def up do
    create table(:admins, primary_key: false) do
      add :id, :uuid, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :restrict), null: false
      add :added_by, references(:users, type: :binary_id, on_delete: :nilify_all)
      add :added_at, :utc_datetime_usec, null: false
      add :removed_at, :utc_datetime_usec
      add :removed_by, references(:users, type: :binary_id, on_delete: :nilify_all)
    end

    create unique_index(:admins, [:user_id],
             where: "removed_at IS NULL",
             name: :admins_active_user_index
           )

    create table(:admin_audit_log) do
      # nil = the server itself (the boot seed).
      add :actor_id, :binary_id
      add :action, :string, null: false
      add :target, :text
      add :before, :text
      add :after, :text
      add :at, :utc_datetime_usec, null: false
    end

    create index(:admin_audit_log, [:at])

    execute """
    CREATE FUNCTION admin_audit_log_append_only() RETURNS trigger AS $$
    BEGIN
      RAISE EXCEPTION 'admin_audit_log is append-only';
    END;
    $$ LANGUAGE plpgsql
    """

    execute """
    CREATE TRIGGER admin_audit_log_append_only
    BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION admin_audit_log_append_only()
    """

    create table(:admin_settings, primary_key: false) do
      add :key, :string, primary_key: true
      add :value, :string, null: false
      add :updated_by, :binary_id
      add :updated_at, :utc_datetime_usec, null: false
    end
  end

  def down do
    drop table(:admin_settings)
    execute "DROP TRIGGER admin_audit_log_append_only ON admin_audit_log"
    execute "DROP FUNCTION admin_audit_log_append_only()"
    drop table(:admin_audit_log)
    drop table(:admins)
  end
end
