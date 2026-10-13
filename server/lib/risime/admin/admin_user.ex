defmodule RisiMe.Admin.AdminUser do
  @moduledoc "One row of `admins` (decision 089). A removal sets `removed_at`; rows are kept."
  use Ecto.Schema

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "admins" do
    field :user_id, :binary_id
    field :added_by, :binary_id
    field :added_at, :utc_datetime_usec
    field :removed_at, :utc_datetime_usec
    field :removed_by, :binary_id
  end
end
