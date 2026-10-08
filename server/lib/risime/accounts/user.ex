defmodule RisiMe.Accounts.User do
  use Ecto.Schema
  import Ecto.Changeset

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "users" do
    field :phone, :string
    field :email, :string
    field :display_name, :string
    field :company, :string
    field :last_seen_at, :utc_datetime_usec
    field :keycloak_sub, :string
    field :phone_verified_for, :string
    field :invited_by_id, :binary_id
    field :disabled_at, :utc_datetime_usec
    # v1.20 §21: "open" for open sign-up (a self-asserted phone); nil for allowlist/invite users.
    field :signup_source, :string
    # v1.24 §24.6: "user" or "agent" (Risi); §24.11: IANA zone, nil = server default.
    field :kind, :string, default: "user"
    field :tz, :string

    timestamps(type: :utc_datetime_usec)
  end

  def profile_changeset(user, attrs) do
    user
    |> cast(attrs, [:display_name])
    |> update_change(:display_name, &(&1 && String.trim(&1)))
    |> validate_required([:display_name])
    |> validate_length(:display_name, min: 1, max: 64)
  end
end
