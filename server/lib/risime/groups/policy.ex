defmodule RisiMe.Groups.Policy do
  @moduledoc """
  The admin policy of contract v1.9 §12.4 and v1.14 §12.4a, plus the v1.24 §24.1 tab and agent
  rules, shared with the MLS core through `contract/v1/group_policy_cases.json` (both suites run
  every case and every `tab_case`).

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

  v1.24 §24.1 (only when the input names a `:tab`; pre-v1.24 callers and cases leave it out):

    1. `tab` and `chat_id` are immutable: a meta change naming another tab, or changing the
       chat id, is rejected from anyone;
    2. Private (`tab: "private"`) and 3. `dm:` conversations reject every agent leaf and a
       non-empty `agents` (`{:error, :private_tab}`);
    4. Official: an agent leaf is accepted only for a user in `agents` and from an admin;
    5. Official: any member may remove **every** leaf of an agent user (a partial removal stays
       admin-only); such a commit may otherwise hold only the committer's own-user changes and no
       meta change.

  v1.25 §25.2 (crypto C4, `risi_cases`; only when the input names `chat_kind: "risi"`, the base
  epoch's `group_meta.chat_kind`):

    6. the base meta is valid only with `tab: "official"`, `chat_id` equal to the group's own
       conversation id (`:chat_id_is_own`), exactly one admin and exactly one agent;
    7. a commit may not change `chat_kind`, the admins or the agents;
    8. an added leaf must belong to the sole admin or the listed agent (never a second human).

  `chat_kind` never changes after epoch 0 for any group (`meta.chat_kind_changed`).

  Optional inputs: `:tab`, `:conversation` (`"grp"` | `"dm"`), `:agent_users` (users whose
  leaves attest `kind: "agent"`; default `agents`), `:leaves` (every base-epoch leaf, for rule
  5), and in `meta`: `:tab` (the new value, nil = unchanged), `:chat_id_changed`, `:agents` (the
  new list, nil = unchanged).
  """

  @type meta ::
          nil
          | %{
              optional(:admins) => [term] | nil,
              optional(:name_changed) => boolean,
              optional(:tab) => String.t() | nil,
              optional(:chat_id_changed) => boolean,
              optional(:agents) => [term] | nil
            }
  @type leaf :: {term, term}

  @spec check(map) :: :ok | {:error, :not_admin | :bad_request | :private_tab}
  def check(%{committer: committer} = c) do
    agents = Enum.to_list(c.agents)
    agent_users = Enum.uniq(agents ++ Enum.to_list(Map.get(c, :agent_users, [])))
    admin? = committer in Enum.to_list(c.admins) and committer not in agent_users
    meta = c.meta
    new_admins = meta && Map.get(meta, :admins)

    with :ok <- risi_rules(c, agents),
         :ok <- tab_rules(c, agents, agent_users, admin?) do
      cond do
        not admin? and meta != nil ->
          {:error, :not_admin}

        not admin? and not member_ok?(c, committer, agent_users) ->
          {:error, :not_admin}

        new_admins == [] ->
          {:error, :bad_request}

        is_list(new_admins) and Enum.any?(new_admins, &(&1 in agent_users)) ->
          {:error, :bad_request}

        true ->
          :ok
      end
    end
  end

  @doc "True if the commit touches leaves of a user other than the committer."
  def others?(%{committer: me, adds: adds, removes: removes}),
    do: Enum.any?(adds ++ removes, fn {u, _} -> u != me end)

  # v1.25 §25.2 rules 6–8 (and chat_kind is immutable for every group).
  defp risi_rules(c, agents) do
    meta = c.meta || %{}
    admins = Enum.to_list(c.admins)
    new_admins = Map.get(meta, :admins)
    new_agents = Map.get(meta, :agents)

    cond do
      Map.get(meta, :chat_kind_changed) == true ->
        {:error, :bad_request}

      Map.get(c, :chat_kind) != "risi" ->
        :ok

      Map.get(c, :tab) != "official" or Map.get(c, :chat_id_is_own, true) != true or
        length(admins) != 1 or length(agents) != 1 ->
        {:error, :bad_request}

      (is_list(new_admins) and Enum.sort(new_admins) != Enum.sort(admins)) or
          (is_list(new_agents) and Enum.sort(new_agents) != Enum.sort(agents)) ->
        {:error, :bad_request}

      Enum.any?(c.adds, fn {u, _} -> u not in admins and u not in agents end) ->
        {:error, :bad_request}

      true ->
        :ok
    end
  end

  # §24.1 rules 1–4 (rule 5 is in member_ok?/3).
  defp tab_rules(%{tab: tab} = c, agents, agent_users, admin?) do
    meta = c.meta || %{}
    private? = tab != "official" or Map.get(c, :conversation, "grp") == "dm"
    new_agents = Map.get(meta, :agents)
    agents_after = new_agents || agents
    agent_adds = for {u, _} <- c.adds, u in agent_users, uniq: true, do: u

    cond do
      Map.get(meta, :tab) not in [nil, tab] or Map.get(meta, :chat_id_changed) == true ->
        {:error, :bad_request}

      private? and (agent_adds != [] or agents_after not in [nil, []]) ->
        {:error, :private_tab}

      agent_adds != [] and not admin? ->
        {:error, :not_admin}

      Enum.any?(agent_adds, &(&1 not in agents_after)) ->
        {:error, :bad_request}

      true ->
        :ok
    end
  end

  defp tab_rules(_c, _agents, _agent_users, _admin?), do: :ok

  defp member_ok?(c, me, agent_users) do
    leaf_users = MapSet.new(c.leaf_users)

    adds_ok =
      Enum.all?(c.adds, fn {u, _} ->
        u == me or (u not in agent_users and MapSet.member?(leaf_users, u))
      end)

    removes_ok =
      Enum.all?(c.removes, fn {u, _} = leaf ->
        u == me or (u not in agent_users and leaf in c.adds) or
          (u in agent_users and agent_removal?(c, u))
      end)

    # Rule 5: a member's agent removal carries nothing but the committer's own-user adds.
    agent_removal? = Enum.any?(c.removes, fn {u, _} -> u in agent_users end)
    own_adds? = Enum.all?(c.adds, fn {u, _} -> u == me end)

    adds_ok and removes_ok and (own_adds? or not agent_removal?)
  end

  # §24.1 rule 5: in Official, a member may remove every leaf of an agent user (not a part).
  defp agent_removal?(%{tab: "official", leaves: leaves} = c, u) do
    Map.get(c, :conversation, "grp") != "dm" and
      Enum.all?(leaves, fn {uu, _} = leaf -> uu != u or leaf in c.removes end)
  end

  defp agent_removal?(_c, _u), do: false
end
