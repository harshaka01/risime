defmodule RisiRegistry.Repo.Migrations.CreateRegistry do
  use Ecto.Migration

  def change do
    # The registration record (agent-connector.md section 2).
    create table(:agents) do
      add :agent_id, :string, null: false
      add :name, :string, null: false
      add :description, :text, null: false, default: ""
      add :owner, :map, null: false, default: %{}
      add :domain, :string, null: false
      add :card_url, :string, null: false
      add :card_hash, :string
      add :key_id, :string
      add :tier, :string, null: false
      add :skills, {:array, :map}, null: false, default: []
      add :scopes, {:array, :map}, null: false, default: []
      add :data_classes, {:array, :string}, null: false, default: []
      add :limits, :map, null: false, default: %{}
      add :status, :string, null: false, default: "proposed"
      add :verification, :string, null: false, default: "unverified"
      add :consequential, :boolean, null: false, default: false
      add :tests, :map
      timestamps()
    end

    create unique_index(:agents, [:agent_id])

    create constraint(:agents, :status_valid,
             check: "status IN ('proposed', 'active', 'suspended', 'delisted')"
           )

    create constraint(:agents, :tier_valid, check: "tier IN ('official', 'verified', 'listed')")

    create constraint(:agents, :verification_valid,
             check: "verification IN ('verified', 'unverified')"
           )

    # No agent ever sees Private chats (marketplace.md, agreed).
    create constraint(:agents, :no_private_chats,
             check: "NOT ('private_chats' = ANY(data_classes))"
           )

    # Owner signing keys, mirrored from https://<domain>/.well-known/risi-keys.json.
    create table(:owner_keys) do
      add :domain, :string, null: false
      add :kid, :string, null: false
      add :alg, :string
      add :jwk, :map, null: false
      add :expires_at, :utc_datetime_usec
      add :retired_at, :utc_datetime_usec
      timestamps()
    end

    create unique_index(:owner_keys, [:domain, :kid])

    # Audit (agent-connector.md sections 11 and 12). Content-free by construction: there is no
    # column for a question, an answer, a card body or a figure.
    create table(:audit_events) do
      add :at, :utc_datetime_usec, null: false
      add :action, :string, null: false
      add :actor, :string
      add :agent_id, :string
      add :skill, :string
      add :company_id, :string
      add :result_code, :string
      add :latency_ms, :integer
      add :request_id, :string
      add :reason, :string
    end

    create index(:audit_events, [:agent_id, :at])
    create index(:audit_events, [:action, :at])

    # Who may get past sign-in. Data, never code (architect 22:10).
    create table(:allowed_accounts) do
      add :email, :string, null: false
      add :added_by, :string, null: false
      timestamps(updated_at: false)
    end

    create unique_index(:allowed_accounts, ["lower(email)"], name: :allowed_accounts_email_index)
  end
end
