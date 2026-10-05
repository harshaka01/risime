defmodule RisiMe.Accounts.AllowlistEntry do
  @moduledoc "A person allowed to log in. Users are created from this on first verify."
  use Ecto.Schema
  import Ecto.Changeset

  alias RisiMe.Accounts.Validate

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "allowlist" do
    field :phone, :string
    field :email, :string
    field :display_name, :string
    field :company, :string

    timestamps(type: :utc_datetime_usec)
  end

  def changeset(entry, attrs) do
    entry
    |> cast(attrs, [:phone, :email, :display_name, :company])
    |> update_change(:email, &Validate.normalize_email/1)
    |> validate_required([:phone, :email, :display_name, :company])
    |> Validate.phone(:phone)
    |> Validate.email(:email)
    |> validate_length(:display_name, min: 1, max: 64)
    |> validate_length(:company, min: 1, max: 64)
    |> unique_constraint(:phone)
  end
end
