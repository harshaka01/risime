defmodule RisiMe.Repo.Migrations.RisiCallSend do
  use Ecto.Migration

  # Contract v1.55 §41.11 (decision 094, `RisiMe.Agent.CallSend`): Risi's voice sends into Official
  # chats during a Call Risi. `risi_call_sends` keeps what an Undo needs (ids and the recipient's
  # display name, never the text) for the 2-minute window and a day after (pruned);
  # `risi_call_send_settings` is the per-user `voice_send` setting (absent = the default).
  # Additive.
  def change do
    create table(:risi_call_sends, primary_key: false) do
      add :write_id, :binary_id, primary_key: true
      add :user_id, :binary_id, null: false
      add :recipient_user_id, :binary_id
      add :name, :text, null: false
      add :conversation_id, :text, null: false
      add :message_id, :text, null: false
      add :risi_chat_id, :text
      add :request_id, :text
      add :call_id, :text
      add :state, :text, null: false, default: "sent"
      add :undo_until, :utc_datetime_usec, null: false
      add :inserted_at, :utc_datetime_usec, null: false
    end

    create index(:risi_call_sends, [:user_id, :inserted_at])

    create table(:risi_call_send_settings, primary_key: false) do
      add :user_id, :binary_id, primary_key: true
      add :voice_send, :boolean, null: false
      add :updated_at, :utc_datetime_usec, null: false
    end
  end
end
