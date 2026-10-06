defmodule RisiMe.Repo.Migrations.AddVerificationResetTrigger do
  use Ecto.Migration

  # Contract v1.4 §7.2: when a user's phone verification is reset (phone change, or
  # phone_verified_for cleared) or their Keycloak identity is re-bound, the server closes that
  # user's sockets. A trigger covers every path, including `mix risime.allow --rebind` (another
  # VM) and manual SQL; the server LISTENs on the channel (RisiMe.Accounts.ResetListener).
  def up do
    execute """
    CREATE OR REPLACE FUNCTION risime_notify_verification_reset() RETURNS trigger AS $$
    BEGIN
      IF (OLD.phone_verified_for IS NOT NULL AND OLD.phone_verified_for = OLD.phone
          AND (NEW.phone_verified_for IS NULL OR NEW.phone_verified_for <> NEW.phone))
         OR (OLD.keycloak_sub IS NOT NULL AND OLD.keycloak_sub IS DISTINCT FROM NEW.keycloak_sub)
      THEN
        PERFORM pg_notify('risime_verification_reset', NEW.id::text);
      END IF;
      RETURN NEW;
    END;
    $$ LANGUAGE plpgsql
    """

    execute """
    CREATE TRIGGER users_verification_reset
    AFTER UPDATE OF phone, phone_verified_for, keycloak_sub ON users
    FOR EACH ROW EXECUTE FUNCTION risime_notify_verification_reset()
    """
  end

  def down do
    execute "DROP TRIGGER IF EXISTS users_verification_reset ON users"
    execute "DROP FUNCTION IF EXISTS risime_notify_verification_reset()"
  end
end
