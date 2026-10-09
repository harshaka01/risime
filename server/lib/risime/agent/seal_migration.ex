defmodule RisiMe.Agent.SealMigration do
  @moduledoc """
  Privacy fix 2026-10-09: seals the Risi-derived text written in plaintext before it
  (`risi_commitments.text`/`due_text`, `risi_facts.text`) with `RISI_DATA_KEY`, exactly as
  `RisiMe.Agent.Commitment.seal/1` and `RisiMe.Agent.Fact.seal/1` do, and blanks the plaintext.
  Called by the migration `SealRisiText` inside its transaction (raw SQL, not the schemas, so it
  keeps working when they change). Idempotent: only rows that still hold plaintext are touched.

  Without the key and with plaintext rows left it raises (the deploy stops): neither keeping the
  plaintext nor dropping users' commitments is acceptable. With no such rows it needs no key.
  """
  alias RisiMe.Agent.Seal

  @doc "Seals the plaintext rows through `repo`. Returns `%{commitments: n, facts: n}`."
  def seal_postgres(repo) do
    %{rows: cs} =
      repo.query!(
        "SELECT id, text, due_text FROM risi_commitments WHERE text IS NOT NULL OR due_text IS NOT NULL"
      )

    %{rows: fs} = repo.query!("SELECT id, text FROM risi_facts WHERE text IS NOT NULL")

    if (cs != [] or fs != []) and Seal.data() == :error,
      do:
        raise(
          "RISI_DATA_KEY (base64, 32 bytes) is required to seal #{length(cs)} commitment(s) " <>
            "and #{length(fs)} fact(s) held in plaintext"
        )

    for [id, text, due_text] <- cs do
      uuid = Ecto.UUID.load!(id)

      repo.query!(
        "UPDATE risi_commitments SET text_sealed = COALESCE($2, text_sealed), " <>
          "due_text_sealed = COALESCE($3, due_text_sealed), text = NULL, due_text = NULL " <>
          "WHERE id = $1",
        [
          id,
          seal!("risi_commitments", uuid, "text", text),
          seal!("risi_commitments", uuid, "due_text", due_text)
        ]
      )
    end

    for [id, text] <- fs do
      repo.query!(
        "UPDATE risi_facts SET text_sealed = $2, text = NULL WHERE id = $1",
        [id, seal!("risi_facts", Ecto.UUID.load!(id), "text", text)]
      )
    end

    %{commitments: length(cs), facts: length(fs)}
  end

  @doc """
  The reverse (the migration's `down`): opens the sealed texts back into the plaintext columns.
  Raises without the key when sealed rows exist.
  """
  def unseal_postgres(repo) do
    %{rows: cs} =
      repo.query!(
        "SELECT id, text_sealed, due_text_sealed FROM risi_commitments WHERE text_sealed IS NOT NULL"
      )

    %{rows: fs} =
      repo.query!("SELECT id, text_sealed FROM risi_facts WHERE text_sealed IS NOT NULL")

    for [id, t, d] <- cs do
      uuid = Ecto.UUID.load!(id)

      repo.query!(
        "UPDATE risi_commitments SET text = $2, due_text = $3 WHERE id = $1",
        [
          id,
          open!("risi_commitments", uuid, "text", t),
          open!("risi_commitments", uuid, "due_text", d)
        ]
      )
    end

    for [id, t] <- fs do
      repo.query!("UPDATE risi_facts SET text = $2 WHERE id = $1", [
        id,
        open!("risi_facts", Ecto.UUID.load!(id), "text", t)
      ])
    end

    :ok
  end

  defp seal!(table, id, column, text) do
    {:ok, sealed} = Seal.seal_text(table, id, column, text)
    sealed
  end

  defp open!(table, id, column, sealed) do
    case Seal.open_text(table, id, column, sealed) do
      {:ok, text} -> text
      :error -> raise "RISI_DATA_KEY can't open #{table}.#{column} (missing or wrong key)"
    end
  end
end
