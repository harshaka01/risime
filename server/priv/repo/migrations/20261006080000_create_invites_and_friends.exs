defmodule RisiMe.Repo.Migrations.CreateInvitesAndFriends do
  use Ecto.Migration

  # Contract v1.6 §9 / decision 030.
  def up do
    alter table(:users) do
      add :invited_by_id, references(:users, type: :binary_id, on_delete: :nilify_all)
      add :disabled_at, :utc_datetime_usec
    end

    create table(:invites, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :inviter_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :phone, :string, null: false
      add :email, :string, null: false
      add :name, :string, null: false
      add :status, :string, null: false, default: "pending"
      add :expires_at, :utc_datetime_usec, null: false
      timestamps(type: :utc_datetime_usec)
    end

    create index(:invites, [:inviter_id, :inserted_at])
    create index(:invites, [:email, :status])
    create index(:invites, [:status, :expires_at])

    # Undirected: one row per pair, user_a < user_b.
    create table(:friendships, primary_key: false) do
      add :user_a, references(:users, type: :binary_id, on_delete: :delete_all), primary_key: true
      add :user_b, references(:users, type: :binary_id, on_delete: :delete_all), primary_key: true
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create constraint(:friendships, :ordered_pair, check: "user_a < user_b")
    create index(:friendships, [:user_b])

    # Stored against the phone that was entered (the target may not exist yet).
    create table(:friend_requests, primary_key: false) do
      add :id, :binary_id, primary_key: true
      add :from_user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :to_phone, :string, null: false
      add :status, :string, null: false, default: "pending"
      add :expires_at, :utc_datetime_usec, null: false
      timestamps(type: :utc_datetime_usec)
    end

    # A pending or declined request can't be repeated (silent no-op).
    create unique_index(:friend_requests, [:from_user_id, :to_phone],
             where: "status IN ('pending', 'declined')",
             name: :friend_requests_open_index
           )

    create index(:friend_requests, [:to_phone, :status])
    create index(:friend_requests, [:status, :expires_at])

    create table(:blocks, primary_key: false) do
      add :blocker_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :blocked_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:blocks, [:blocked_id])

    # Disabling a user also closes their sockets (the v1.4 reset trigger, extended).
    execute """
    CREATE OR REPLACE FUNCTION risime_notify_verification_reset() RETURNS trigger AS $$
    BEGIN
      IF (OLD.phone_verified_for IS NOT NULL AND OLD.phone_verified_for = OLD.phone
          AND (NEW.phone_verified_for IS NULL OR NEW.phone_verified_for <> NEW.phone))
         OR (OLD.keycloak_sub IS NOT NULL AND OLD.keycloak_sub IS DISTINCT FROM NEW.keycloak_sub)
         OR (OLD.disabled_at IS NULL AND NEW.disabled_at IS NOT NULL)
      THEN
        PERFORM pg_notify('risime_verification_reset', NEW.id::text);
      END IF;
      RETURN NEW;
    END;
    $$ LANGUAGE plpgsql
    """

    execute "DROP TRIGGER IF EXISTS users_verification_reset ON users"

    execute """
    CREATE TRIGGER users_verification_reset
    AFTER UPDATE OF phone, phone_verified_for, keycloak_sub, disabled_at ON users
    FOR EACH ROW EXECUTE FUNCTION risime_notify_verification_reset()
    """
  end

  def down do
    execute "DROP TRIGGER IF EXISTS users_verification_reset ON users"

    execute """
    CREATE TRIGGER users_verification_reset
    AFTER UPDATE OF phone, phone_verified_for, keycloak_sub ON users
    FOR EACH ROW EXECUTE FUNCTION risime_notify_verification_reset()
    """

    drop table(:blocks)
    drop table(:friend_requests)
    drop table(:friendships)
    drop table(:invites)

    alter table(:users) do
      remove :disabled_at
      remove :invited_by_id
    end
  end
end
