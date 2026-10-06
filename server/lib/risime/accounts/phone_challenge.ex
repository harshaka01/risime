defmodule RisiMe.Accounts.PhoneChallenge do
  @moduledoc "One SMS verification code sent (or attempted) to a user's allowlisted phone."
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id
  schema "phone_challenges" do
    belongs_to :user, RisiMe.Accounts.User
    field :phone, :string
    field :code_hash, :binary
    field :expires_at, :utc_datetime_usec
    field :attempts, :integer, default: 0
    field :consumed_at, :utc_datetime_usec

    timestamps(type: :utc_datetime_usec, updated_at: false)
  end
end
