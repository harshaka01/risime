defmodule RisiMe.Admin.AuditEntry do
  @moduledoc "One row of the append-only `admin_audit_log` (decision 089)."
  use Ecto.Schema

  schema "admin_audit_log" do
    field :actor_id, :binary_id
    field :action, :string
    field :target, :string
    field :before, :string
    field :after, :string
    field :at, :utc_datetime_usec
  end
end
