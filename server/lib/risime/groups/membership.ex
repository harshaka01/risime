defmodule RisiMe.Groups.Membership do
  @moduledoc """
  Membership history for the `media` read rule (contract v1.11 §14.2, §14.8; decision 042).

  `group_member_intervals(group_id, user_id, active_from, active_until | null)`: an interval is
  **opened** when a member becomes `active` and **closed** when they turn `pending_remove` (or
  are deleted), always inside the transaction that changes `group_members.state`. At most one
  interval per pair is open (partial unique index `gmi_one_open`), so `open/3` is idempotent.

  Invariant (tested): an open interval exists if and only if the member's state is `active`.
  """
  import Ecto.Query

  alias RisiMe.Repo

  @doc "Opens an interval for each user that has none open."
  def open(_group_id, [], _at), do: :ok

  def open(group_id, user_ids, %DateTime{} = at) do
    rows =
      for u <- Enum.uniq(user_ids),
          do: %{group_id: group_id, user_id: Ecto.UUID.dump!(u), active_from: at}

    Repo.insert_all("group_member_intervals", rows,
      on_conflict: :nothing,
      conflict_target: {:unsafe_fragment, "(group_id, user_id) WHERE active_until IS NULL"}
    )

    :ok
  end

  @doc "Closes the users' open intervals (idempotent)."
  def close(group_id, user_ids, at \\ DateTime.utc_now())
  def close(_group_id, [], _at), do: :ok

  def close(group_id, user_ids, %DateTime{} = at) do
    Repo.update_all(
      from(i in "group_member_intervals",
        where:
          i.group_id == ^group_id and i.user_id in type(^user_ids, {:array, :binary_id}) and
            is_nil(i.active_until)
      ),
      set: [active_until: at]
    )

    :ok
  end

  @doc """
  True if `user_id` was an active member of the group at some point in `[since, now]`: an
  interval that is still open or closed at or after `since` (§14.2 `media` readers).
  """
  def active_since?(group_id, user_id, %DateTime{} = since) do
    Repo.exists?(
      from i in "group_member_intervals",
        where:
          i.group_id == ^group_id and i.user_id == type(^user_id, :binary_id) and
            (is_nil(i.active_until) or i.active_until >= ^since)
    )
  end
end
