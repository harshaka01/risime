defmodule RisiMe.Repo.Migrations.RisiItemOffers do
  use Ecto.Migration

  # Items 8–10 (2026-10-09): proactive offers and the vague-item flag. Additive only (hard
  # rule 9).
  #
  # `risi_item_offers`: one row per (item, user, kind) Risi offered proactively in that user's
  # Risi chat: `calendar` ("Add to calendar?"), `reminder` ("Remind me?") or `clarify` (one
  # question for a vague item). The primary key is the "never twice" rule: an offer is made at
  # most once per item, whatever happens to its card (Add, Cancel, expiry). Ids and states only,
  # no text (a card's args are sealed in `risi_pending_writes`). Deleted with the item.
  #
  # `risi_commitments.needs_clarification`: an item without a usable due ("sometime", "soon", no
  # date): no calendar offer, one clarification question instead; cleared when a due is set.
  def change do
    create table(:risi_item_offers, primary_key: false) do
      add :item_id, references(:risi_commitments, type: :uuid, on_delete: :delete_all),
        primary_key: true

      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all),
        primary_key: true

      add :kind, :string, primary_key: true
      add :state, :string, null: false, default: "offered"
      add :write_id, :binary_id
      add :inserted_at, :utc_datetime_usec, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end

    create index(:risi_item_offers, [:write_id])

    alter table(:risi_commitments) do
      add :needs_clarification, :boolean, null: false, default: false
    end
  end
end
