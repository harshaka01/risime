defmodule RisiMe.Repo.Migrations.RisiLedger do
  use Ecto.Migration

  # Contract v1.27 §27.2–§27.6, §27.9 (server S21–S24). Additive only (hard rule 9).
  #
  # * risi_chat_state: the quiet rule's cutoff (`summary_cutoff_ts`, the server_ts of the last
  #   message covered by a discussion summary), the first counted message after it
  #   (`discussion_since`, for the 4-h rule), and the spacing guard (`last_summary_at`,
  #   `summaries_today` on `summaries_on`, the chat's local date). A Risi chat's row keeps its
  #   owner's personal digest guard (`last_digest_on`).
  # * risi_commitments: ledger items are commitments with commitment_id = item_id
  #   (`summary_id`, `all_day`, `source` chat | call, `item_state`; a NULL `item_state` is a
  #   v1.24 card, also when it came from a summary's owner fallback). `overdue_sent_at` /
  #   `nudged_at`: §27.6 "at most once per item in its life".
  # * risi_discussions: one per summary. Every chat-derived text is sealed with RISI_DATA_KEY
  #   (`key_points_sealed`, `summary_sealed`; no plaintext column exists). `made_by` holds model
  #   names only. Deleted with the chat's Risi data (§24.4) and after 90 days.
  # * risi_followups_pending: a copy waiting for its recipient's Risi chat (24 h), sealed.
  def change do
    alter table(:risi_chat_state) do
      add :summary_cutoff_ts, :utc_datetime_usec
      add :discussion_since, :utc_datetime_usec
      add :last_summary_at, :utc_datetime_usec
      add :summaries_today, :integer, null: false, default: 0
      add :summaries_on, :date
    end

    alter table(:risi_commitments) do
      add :summary_id, :uuid
      add :all_day, :boolean, null: false, default: false
      add :source, :string
      add :item_state, :string
      add :overdue_sent_at, :utc_datetime_usec
      add :nudged_at, :utc_datetime_usec
    end

    create index(:risi_commitments, [:summary_id])

    create table(:risi_discussions, primary_key: false) do
      add :summary_id, :uuid, primary_key: true
      add :conversation_id, :string, null: false
      add :chat_id, :string, null: false
      add :source, :string, null: false
      add :call_id, :uuid
      add :media, :string
      add :duration_s, :integer
      add :started_at, :utc_datetime_usec, null: false
      add :ended_at, :utc_datetime_usec, null: false
      add :participants, {:array, :uuid}, null: false, default: []
      add :recipients, {:array, :uuid}, null: false, default: []
      add :key_points_sealed, :binary, null: false
      add :summary_sealed, :binary, null: false
      add :items_count, :integer, null: false, default: 0
      add :call_ref, :uuid
      add :made_by, :map
      add :created_at, :utc_datetime_usec, null: false
    end

    create index(:risi_discussions, [:conversation_id])
    create index(:risi_discussions, [:created_at])

    create table(:risi_followups_pending, primary_key: false) do
      add :summary_id, :uuid, primary_key: true
      add :user_id, :uuid, primary_key: true
      add :conversation_id, :string, null: false
      add :envelope_sealed, :binary, null: false
      add :expires_at, :utc_datetime_usec, null: false
    end

    create index(:risi_followups_pending, [:user_id])
    create index(:risi_followups_pending, [:conversation_id])
  end
end
