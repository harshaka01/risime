defmodule RisiMe.Repo.Migrations.TwoTabs do
  use Ecto.Migration

  # Contract v1.24 §24.6 (decision 065). Additive only (hard rule 9): every existing group becomes
  # its chat's Private tab with `chat_id = id`; nothing is moved, renamed or deleted.
  def up do
    alter table(:groups) do
      add :chat_id, :string
      add :tab, :string, null: false, default: "private"
      add :chat_kind, :string, null: false, default: "group"
    end

    flush()
    execute "UPDATE groups SET chat_id = id WHERE chat_id IS NULL"

    alter table(:groups) do
      modify :chat_id, :string, null: false, from: {:string, null: true}
    end

    # A group inserted without a chat_id (any pre-v1.24 code path) is its own chat's Private tab.
    execute """
    CREATE FUNCTION groups_default_chat_id() RETURNS trigger AS $$
    BEGIN
      IF NEW.chat_id IS NULL THEN NEW.chat_id := NEW.id; END IF;
      RETURN NEW;
    END $$ LANGUAGE plpgsql
    """

    execute """
    CREATE TRIGGER groups_default_chat_id BEFORE INSERT ON groups
    FOR EACH ROW EXECUTE FUNCTION groups_default_chat_id()
    """

    # One Private and at most one Official conversation per chat, ever (§24.2, §24.4).
    create unique_index(:groups, [:chat_id, :tab])

    # A missing row: `official` is on and no Official conversation exists yet (`none`).
    create table(:chats, primary_key: false) do
      add :chat_id, :string, primary_key: true
      add :kind, :string, null: false
      add :official, :string, null: false, default: "on"
      add :official_conversation_id, :string
      add :changed_by, :binary_id
      add :changed_at, :utc_datetime_usec
    end

    alter table(:users) do
      add :kind, :string, null: false, default: "user"
      # §24.11: IANA zone; null = the server default (RISI_DEFAULT_TZ).
      add :tz, :string
    end
  end

  def down do
    alter table(:users) do
      remove :tz
      remove :kind
    end

    drop table(:chats)
    execute "DROP TRIGGER groups_default_chat_id ON groups"
    execute "DROP FUNCTION groups_default_chat_id()"
    drop unique_index(:groups, [:chat_id, :tab])

    alter table(:groups) do
      remove :chat_kind
      remove :tab
      remove :chat_id
    end
  end
end
