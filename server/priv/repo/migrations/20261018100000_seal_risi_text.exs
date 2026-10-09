defmodule RisiMe.Repo.Migrations.SealRisiText do
  use Ecto.Migration

  # Privacy fix 2026-10-09: Risi-derived text is sealed at rest with RISI_DATA_KEY (AES-256-GCM,
  # AAD "<table>:<id>:<column>", RisiMe.Agent.Seal), like the buffer. One transaction: the
  # sealed columns are added, every plaintext row is sealed and blanked
  # (RisiMe.Agent.SealMigration), then CHECK constraints keep the plaintext columns NULL for
  # good. The plaintext columns stay (NULL) so an older release still finds them (rollback is
  # code only); the constraint means no code can put plaintext back. Without the key and with
  # plaintext rows left the migration raises and nothing changes.
  def up do
    alter table(:risi_commitments) do
      add :text_sealed, :binary
      add :due_text_sealed, :binary
    end

    alter table(:risi_facts) do
      add :text_sealed, :binary
    end

    execute "ALTER TABLE risi_commitments ALTER COLUMN text DROP NOT NULL"
    execute "ALTER TABLE risi_facts ALTER COLUMN text DROP NOT NULL"
    flush()

    counts = RisiMe.Agent.SealMigration.seal_postgres(repo())
    IO.puts("risi text sealed: #{inspect(counts)}")

    execute "ALTER TABLE risi_commitments ALTER COLUMN text_sealed SET NOT NULL"
    execute "ALTER TABLE risi_facts ALTER COLUMN text_sealed SET NOT NULL"

    create constraint(:risi_commitments, :risi_commitments_no_plaintext,
             check: "text IS NULL AND due_text IS NULL"
           )

    create constraint(:risi_facts, :risi_facts_no_plaintext, check: "text IS NULL")
  end

  def down do
    drop constraint(:risi_commitments, :risi_commitments_no_plaintext)
    drop constraint(:risi_facts, :risi_facts_no_plaintext)
    flush()

    RisiMe.Agent.SealMigration.unseal_postgres(repo())

    execute "ALTER TABLE risi_commitments ALTER COLUMN text SET NOT NULL"
    execute "ALTER TABLE risi_facts ALTER COLUMN text SET NOT NULL"

    alter table(:risi_commitments) do
      remove :text_sealed
      remove :due_text_sealed
    end

    alter table(:risi_facts) do
      remove :text_sealed
    end
  end
end
