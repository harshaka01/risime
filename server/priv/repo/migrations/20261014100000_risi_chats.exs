defmodule RisiMe.Repo.Migrations.RisiChats do
  use Ecto.Migration

  # Contract v1.25 §25.2 (decision 068): one Risi chat per user. Additive only (hard rule 9).
  # The group itself is an ordinary `groups` row (tab official, chat_kind risi, chat_id = id);
  # this table records its owner and whether it was left. The partial unique index resolves
  # concurrent `POST /api/v1/risi/chat` calls (the loser gets the winner's chat); a left chat no
  # longer counts, so a later POST creates a new one. Deleting the group (a `creating` chat not
  # completed in 10 minutes) deletes the row.
  def change do
    create table(:risi_chats, primary_key: false) do
      add :conversation_id, references(:groups, type: :string, on_delete: :delete_all),
        primary_key: true

      add :owner_id, references(:users, type: :binary_id, on_delete: :delete_all), null: false
      # creating | active | left
      add :state, :string, null: false, default: "creating"
      add :left_at, :utc_datetime_usec
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create unique_index(:risi_chats, [:owner_id],
             where: "state <> 'left'",
             name: :risi_chats_one_per_owner
           )
  end
end
