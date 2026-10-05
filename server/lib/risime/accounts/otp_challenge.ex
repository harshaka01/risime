defmodule RisiMe.Accounts.OtpChallenge do
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "otp_challenges" do
    field :phone, :string
    field :code_hash, :binary
    field :expires_at, :utc_datetime_usec
    field :attempts, :integer, default: 0
    field :consumed_at, :utc_datetime_usec

    timestamps(type: :utc_datetime_usec, updated_at: false)
  end
end
