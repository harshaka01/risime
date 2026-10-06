defmodule RisiMe.Social.Invite do
  @moduledoc "An invite to join RisiMe (contract v1.6 §9.1)."
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id
  schema "invites" do
    belongs_to :inviter, RisiMe.Accounts.User
    field :phone, :string
    field :email, :string
    field :name, :string
    field :status, :string, default: "pending"
    field :expires_at, :utc_datetime_usec
    timestamps(type: :utc_datetime_usec)
  end
end

defmodule RisiMe.Social.FriendRequest do
  @moduledoc "A friend request, stored against the phone that was entered (contract v1.6 §9.2)."
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  @foreign_key_type :binary_id
  schema "friend_requests" do
    belongs_to :from_user, RisiMe.Accounts.User
    field :to_phone, :string
    field :status, :string, default: "pending"
    field :expires_at, :utc_datetime_usec
    timestamps(type: :utc_datetime_usec)
  end
end
