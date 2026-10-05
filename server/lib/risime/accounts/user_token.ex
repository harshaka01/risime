defmodule RisiMe.Accounts.UserToken do
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id
  schema "user_tokens" do
    belongs_to :user, RisiMe.Accounts.User
    field :token_hash, :binary
    field :device_name, :string
    field :last_seen_at, :utc_datetime_usec
    field :revoked_at, :utc_datetime_usec

    timestamps(type: :utc_datetime_usec, updated_at: false)
  end
end
