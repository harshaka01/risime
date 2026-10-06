defmodule RisiMe.Groups.Policy do
  @moduledoc """
  The admin policy of contract v1.9 §12.4, shared with the MLS core through
  `contract/v1/group_policy_cases.json` (both suites run every case).

  Pure: given the admins, the agents, the committing user, the users whose leaves the commit
  adds and removes, and the `group_meta` change, it says whether the commit is allowed. The
  server-only checks (pending ops, caps, current devices) live in `RisiMe.Groups.Commit`.

    * anyone may self-update (no adds, removes or meta change);
    * any member may add or remove leaves of their own user;
    * only admins may touch another user's leaves or change `group_meta`;
    * a new admin list may never be empty and never name an agent.
  """

  @type meta :: nil | %{optional(:admins) => [term] | nil, optional(:name_changed) => boolean}

  @spec check(%{
          admins: Enumerable.t(),
          agents: Enumerable.t(),
          committer: term,
          adds: [term],
          removes: [term],
          meta: meta
        }) :: :ok | {:error, :not_admin | :bad_request}
  def check(%{committer: committer} = c) do
    admin? = committer in Enum.to_list(c.admins)
    others? = Enum.any?(c.adds ++ c.removes, &(&1 != committer))
    meta = c.meta
    new_admins = meta && Map.get(meta, :admins)

    cond do
      others? and not admin? ->
        {:error, :not_admin}

      meta != nil and not admin? ->
        {:error, :not_admin}

      new_admins == [] ->
        {:error, :bad_request}

      is_list(new_admins) and Enum.any?(new_admins, &(&1 in Enum.to_list(c.agents))) ->
        {:error, :bad_request}

      true ->
        :ok
    end
  end
end
