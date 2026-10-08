defmodule RisiMe.Workers.StaleLeaves do
  @moduledoc """
  v1.21 §12.12.6: the hourly stale-leaf sweep, and the §12.12.7 one-off recovery.

  A leaf is stale when its device is superseded (§12.1), unseen (census or `PUT`) for
  `STALE_LEAF_HOURS` (default 24), and its user keeps at least one non-superseded leaf in the same
  group. For `grp:` groups the sweep creates or widens one cleanup op per (group, user)
  (`RisiMe.Groups.Ops.ensure_cleanup/4`: tiers 1–2 only, no wake pushes); for DMs the stale
  leaves join the `removed` list of the user's op (`RisiMe.MLS.DmOps.ensure_cleanup/4`). Then
  committers are named. A Postgres query over `mls_group_devices`, `devices` and
  `app_instances`; no Cassandra. Logs counts only.
  """
  use Oban.Worker, queue: :groups, max_attempts: 3

  import Ecto.Query

  require Logger

  alias RisiMe.{Groups, MLS, Repo}
  alias RisiMe.Groups.{Group, Member, Op, Ops, Strikes}
  alias RisiMe.MLS.{DmOp, DmOps}

  @impl Oban.Worker
  def perform(%Oban.Job{}) do
    counts = sweep(name: true)
    Logger.info("stale_leaves: #{inspect(counts)}")
    :ok
  end

  @doc """
  Finds the stale leaves of every group and DM and creates or widens their cleanup ops; names
  committers unless `name: false`. Returns counts: `stale_leaves`, `cleanup_ops` (created or
  widened).
  """
  def sweep(opts \\ []) do
    name? = Keyword.get(opts, :name, true)

    stale_by_conv()
    |> Enum.reduce(%{stale_leaves: 0, cleanup_ops: 0}, fn {conv, by_user}, acc ->
      changed = cleanup_conv(conv, by_user, name?)

      %{
        acc
        | stale_leaves:
            acc.stale_leaves + (by_user |> Map.values() |> Enum.map(&length/1) |> Enum.sum()),
          cleanup_ops: acc.cleanup_ops + changed
      }
    end)
  end

  # `%{conv => %{user_id => [stale refs]}}` over every conversation with MLS state.
  defp stale_by_conv do
    rows =
      Repo.all(
        from gd in "mls_group_devices",
          join: g in "mls_groups",
          on: g.conversation_id == gd.conversation_id,
          where: not is_nil(g.epoch),
          select:
            {gd.conversation_id, type(gd.user_id, :binary_id), type(gd.device_id, :binary_id)}
      )

    users = rows |> Enum.map(&elem(&1, 1)) |> Enum.uniq()
    superseded = MLS.superseded_devices(users)

    rows
    |> Enum.group_by(&elem(&1, 0), fn {_c, u, d} -> {u, d} end)
    |> Enum.flat_map(fn {conv, leaves} ->
      if Enum.any?(leaves, &MapSet.member?(superseded, &1)) do
        case conv
             |> Ops.stale_leaves(Enum.map(leaves, &elem(&1, 0)) |> Enum.uniq())
             |> Enum.to_list() do
          [] -> []
          stale -> [{conv, Enum.group_by(stale, &elem(&1, 0))}]
        end
      else
        []
      end
    end)
  end

  defp cleanup_conv("grp:" <> _ = conv, by_user, name?) do
    {:ok, n} =
      Groups.locked(conv, fn ->
        case Groups.get_group(conv) do
          %Group{state: "active"} = g ->
            {:ok,
             Enum.count(by_user, fn {u, _} ->
               match?(%Member{state: "active", kind: "user"}, Groups.member(conv, u)) and
                 elem(
                   Ops.ensure_cleanup(g, u, Enum.to_list(Ops.stale_leaves(conv, [u])),
                     name: name?
                   ),
                   0
                 ) in [:created, :widened]
             end)}

          _ ->
            {:ok, 0}
        end
      end)

    n
  end

  defp cleanup_conv("dm:" <> _ = conv, by_user, name?) do
    case MLS.members(conv) do
      {:ok, members} ->
        {:ok, n} =
          DmOps.locked(conv, fn ->
            Enum.count(by_user, fn {u, _} ->
              u in members and
                elem(
                  DmOps.ensure_cleanup(conv, u, Enum.to_list(Ops.stale_leaves(conv, [u])),
                    name: name?
                  ),
                  0
                ) in [:created, :widened]
            end)
          end)

        n

      _ ->
        0
    end
  end

  defp cleanup_conv(_conv, _by_user, _name?), do: 0

  ## §12.12.7 recovery

  @doc """
  §12.12.7: for every pending `devices` op (groups and DMs) applies the §12.12.5 pruning and
  clears every strike, creates the §12.12.6 cleanup ops, and with `dry_run: false` names
  committers. **Dry run by default:** everything runs in one transaction that is rolled back, so
  nothing changes. Idempotent. Returns counts only: `ops`, `pruned_devices`, `done_ops`,
  `cleanup_ops`, `stale_leaves`, `named`, `waiting`, `exhausted`.
  """
  def recover(opts \\ []) do
    dry_run = Keyword.get(opts, :dry_run, true)

    run = fn ->
      zero = %{ops: 0, pruned_devices: 0, done_ops: 0, named: 0, waiting: 0, exhausted: 0}

      acc =
        Enum.reduce(group_ops(), zero, fn {gid, oid}, acc ->
          {:ok, acc} =
            Groups.locked(gid, fn ->
              with %Group{} = g <- Groups.get_group(gid),
                   %Op{} = op <- Ops.get(gid, oid) do
                {:ok, apply_plan(acc, Ops.prune_plan(g, op), &Ops.prune(g, &1))}
              else
                _ -> {:ok, acc}
              end
            end)

          acc
        end)

      acc =
        Enum.reduce(dm_ops(), acc, fn oid, acc ->
          case Repo.get(DmOp, oid) do
            %DmOp{conversation_id: conv} ->
              {:ok, acc} =
                DmOps.locked(conv, fn ->
                  case Repo.get(DmOp, oid) do
                    %DmOp{} = op -> apply_plan(acc, DmOps.prune_plan(op), &DmOps.prune/1)
                    nil -> acc
                  end
                end)

              acc

            nil ->
              acc
          end
        end)

      Repo.update_all(from(o in Op, where: o.type == "devices"), set: [strikes: %{}])
      Repo.update_all(DmOp, set: [strikes: %{}])

      acc = Map.merge(acc, sweep(name: false))
      acc = if dry_run, do: acc, else: name_all(acc)
      count_waiting(acc)
    end

    if dry_run do
      {:error, {:dry_run, counts}} = Repo.transaction(fn -> Repo.rollback({:dry_run, run.()}) end)
      counts
    else
      run.()
    end
  end

  defp apply_plan(acc, {kind, op, n}, prune) do
    acc = %{acc | ops: acc.ops + 1, pruned_devices: acc.pruned_devices + n}
    _ = prune.(op)
    if kind in [:done, :dropped], do: %{acc | done_ops: acc.done_ops + 1}, else: acc
  end

  defp group_ops,
    do:
      Repo.all(
        from o in Op,
          where: o.type == "devices",
          order_by: [asc: o.created_at],
          select: {o.group_id, o.op_id}
      )

  defp dm_ops, do: Repo.all(from o in DmOp, order_by: [asc: o.created_at], select: o.op_id)

  # A real run: names every waiting op (pruned again under the lock).
  defp name_all(acc) do
    named =
      Enum.count(group_ops(), fn {gid, oid} ->
        {:ok, named?} =
          Groups.locked(gid, fn ->
            with %Group{} = g <- Groups.get_group(gid),
                 %Op{committer_device: nil} = op <- Ops.get(gid, oid),
                 %Op{committer_device: d} when d != nil <- Ops.name_next(g, op) do
              {:ok, true}
            else
              _ -> {:ok, false}
            end
          end)

        named?
      end) +
        Enum.count(dm_ops(), fn oid ->
          with %DmOp{conversation_id: conv} <- Repo.get(DmOp, oid),
               {:ok, true} <-
                 DmOps.locked(conv, fn ->
                   with %DmOp{committer_device: nil} = op <- Repo.get(DmOp, oid),
                        %DmOp{committer_device: d} when d != nil <- DmOps.name_next(op),
                        do: true,
                        else: (_ -> false)
                 end) do
            true
          else
            _ -> false
          end
        end)

    %{acc | named: named}
  end

  # Ops still without a committer, and those of them that are exhausted.
  defp count_waiting(acc) do
    groups =
      for {gid, oid} <- group_ops(),
          %Group{} = g <- [Groups.get_group(gid)],
          %Op{committer_device: nil} = op <- [Ops.get(gid, oid)],
          do: Strikes.exhausted?(op.strikes, Ops.candidates(g, op))

    dms =
      for oid <- dm_ops(),
          %DmOp{committer_device: nil} = op <- [Repo.get(DmOp, oid)],
          do: Strikes.exhausted?(op.strikes, DmOps.candidates(op))

    all = groups ++ dms
    %{acc | waiting: length(all), exhausted: Enum.count(all, & &1)}
  end
end
