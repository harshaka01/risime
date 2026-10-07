defmodule RisiMe.Groups.Policy do
  @moduledoc """
  The admin policy of contract v1.9 §12.4 and v1.14 §12.4a, shared with the MLS core through
  `contract/v1/group_policy_cases.json` (both suites run every case).

  Pure: given the admins, the agents, the committing user, the leaves (`{user, device}`) the
  commit adds and removes, the users with a leaf in the base epoch, and the `group_meta` change,
  it says whether the commit is allowed. The server-only checks (pending ops, the
  `member_devices` gate, caps, current devices) live in `RisiMe.Groups.Commit`.

    * anyone may self-update (no adds, removes or meta change);
    * any member may add or remove leaves of their own user;
    * a non-admin may add leaves of users who already have a leaf (never agents), and remove
      another user's leaf only when the same commit re-adds that same device;
    * only admins may change `group_meta`, or otherwise touch another user's leaves;
    * a new admin list may never be empty and never name an agent.
  """

  @type meta :: nil | %{optional(:admins) => [term] | nil, optional(:name_changed) => boolean}
  @type leaf :: {term, term}

  @spec check(%{
          admins: Enumerable.t(),
          agents: Enumerable.t(),
          committer: term,
          adds: [leaf],
          removes: [leaf],
          leaf_users: Enumerable.t(),
          meta: meta
        }) :: :ok | {:error, :not_admin | :bad_request}
  def check(%{committer: committer} = c) do
    agents = Enum.to_list(c.agents)
    admin? = committer in Enum.to_list(c.admins) and committer not in agents
    meta = c.meta
    new_admins = meta && Map.get(meta, :admins)

    cond do
      not admin? and meta != nil ->
        {:error, :not_admin}

      not admin? and not member_ok?(c, committer, agents) ->
        {:error, :not_admin}

      new_admins == [] ->
        {:error, :bad_request}

      is_list(new_admins) and Enum.any?(new_admins, &(&1 in agents)) ->
        {:error, :bad_request}

      true ->
        :ok
    end
  end

  @doc "True if the commit touches leaves of a user other than the committer."
  def others?(%{committer: me, adds: adds, removes: removes}),
    do: Enum.any?(adds ++ removes, fn {u, _} -> u != me end)

  defp member_ok?(c, me, agents) do
    leaf_users = MapSet.new(c.leaf_users)

    adds_ok =
      Enum.all?(c.adds, fn {u, _} ->
        u == me or (u not in agents and MapSet.member?(leaf_users, u))
      end)

    removes_ok =
      Enum.all?(c.removes, fn {u, _} = leaf ->
        u == me or (u not in agents and leaf in c.adds)
      end)

    adds_ok and removes_ok
  end
end
