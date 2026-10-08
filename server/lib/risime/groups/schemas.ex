defmodule RisiMe.Groups.Group do
  @moduledoc "A `grp:` conversation (contract v1.9 §12.10). `id` is the conversation id."
  use Ecto.Schema

  @primary_key {:id, :string, autogenerate: false}
  schema "groups" do
    field :created_by, :binary_id
    field :client_group_id, :binary_id
    field :state, :string
    field :generation, :integer, default: 1
    field :created_at, :utc_datetime_usec
  end
end

defmodule RisiMe.Groups.Member do
  @moduledoc "A group member: role `admin`/`member`, state `active`/`pending_add`/`pending_remove`."
  use Ecto.Schema

  @primary_key false
  schema "group_members" do
    field :group_id, :string, primary_key: true
    field :user_id, :binary_id, primary_key: true
    field :role, :string
    field :kind, :string, default: "user"
    field :state, :string
    field :joined_at, :utc_datetime_usec
    field :inserted_at, :utc_datetime_usec
  end
end

defmodule RisiMe.Groups.Op do
  @moduledoc """
  A pending operation (§12.4). `payload` = `%{"user_ids", "role", "added", "removed"}` (device
  refs as `%{"user_id", "device_id"}`). `tried` lists the devices already named; `naming` counts
  namings so a superseded committer timer is ignored.
  """
  use Ecto.Schema

  @primary_key {:op_id, :binary_id, autogenerate: false}
  schema "group_ops" do
    field :group_id, :string
    field :type, :string
    field :actor, :binary_id
    field :payload, :map
    field :committer_user, :binary_id
    field :committer_device, :binary_id
    field :committer_until, :utc_datetime_usec
    field :naming, :integer, default: 0
    field :tried, {:array, :binary_id}, default: []
    field :strikes, :map, default: %{}
    field :expires_at, :utc_datetime_usec
    field :created_at, :utc_datetime_usec
  end
end
