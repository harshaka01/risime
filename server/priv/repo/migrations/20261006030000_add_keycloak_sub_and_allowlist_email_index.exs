defmodule RisiMe.Repo.Migrations.AddKeycloakSubAndAllowlistEmailIndex do
  use Ecto.Migration

  # Contract v1.3 §6.1: users bind to a Keycloak `sub`; allowlist entries are found by email.
  def up do
    alter table(:users) do
      add :keycloak_sub, :string
    end

    create unique_index(:users, [:keycloak_sub])

    execute "UPDATE allowlist SET email = lower(email)"
    create unique_index(:allowlist, ["lower(email)"], name: :allowlist_lower_email_index)
  end

  def down do
    drop index(:allowlist, ["lower(email)"], name: :allowlist_lower_email_index)
    drop index(:users, [:keycloak_sub])

    alter table(:users) do
      remove :keycloak_sub
    end
  end
end
