defmodule RisiMe.Repo.Migrations.CreateHistorySharing do
  use Ecto.Migration

  # Contract v1.15 §17.11: history sharing state, limits and the request ciphertext (no content).
  # Queries first: H1 the open request of (device, conversation) (unique partial index), H2 by id
  # (PK, advisory lock per request), H3 a user's requests in 24 h, H5 how often a member was
  # named (candidates by user and time), H6 dormant candidates of a device on an inbox join,
  # H7 open requests of a conversation, H9 `history` blob readers by request.
  @open "state IN ('searching', 'waiting_for_member', 'refresh', 'accepted', 'receiving')"

  def change do
    create table(:history_requests, primary_key: false) do
      add :request_id, :uuid, primary_key: true

      add :requester_user, references(:users, type: :binary_id, on_delete: :delete_all),
        null: false

      add :requester_device, :uuid, null: false
      add :conversation_id, :string, null: false
      add :generation, :integer, null: false
      add :range_from, :utc_datetime_usec, null: false
      add :range_to, :utc_datetime_usec, null: false
      add :gap_count, :integer, null: false
      add :sources, :string, null: false
      add :state, :string, null: false
      add :phase, :string, null: false
      add :provider_user, :uuid
      add :provider_device, :uuid
      add :accepted_at, :utc_datetime_usec
      add :last_part_at, :utc_datetime_usec
      add :parts, :integer
      add :parts_delivered, {:array, :integer}, null: false, default: []
      add :parts_acked, {:array, :integer}, null: false, default: []
      add :request_ciphertext, :binary
      add :request_epoch, :bigint, null: false
      add :created_at, :utc_datetime_usec, null: false
      add :expires_at, :utc_datetime_usec, null: false
      add :closed_at, :utc_datetime_usec
    end

    create unique_index(:history_requests, [:requester_device, :conversation_id],
             where: @open,
             name: :history_requests_one_open
           )

    create index(:history_requests, [:requester_user, :conversation_id, :created_at])
    create index(:history_requests, [:requester_user, :created_at])
    create index(:history_requests, [:conversation_id], where: @open)
    create index(:history_requests, [:created_at])

    create table(:history_candidates, primary_key: false) do
      add :request_id,
          references(:history_requests,
            column: :request_id,
            type: :uuid,
            on_delete: :delete_all
          ),
          primary_key: true

      add :device_id, :uuid, primary_key: true
      add :user_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      add :phase, :string, null: false
      add :wait, :string
      add :named_at, :utc_datetime_usec
      add :named_until, :utc_datetime_usec
      add :answer, :string
      add :answered_at, :utc_datetime_usec
    end

    create index(:history_candidates, [:user_id, :named_at])
    create index(:history_candidates, [:device_id], where: "answer IS NULL")

    alter table(:blobs) do
      add :request_id, :uuid
    end

    create index(:blobs, [:request_id], where: "request_id IS NOT NULL")
  end
end
