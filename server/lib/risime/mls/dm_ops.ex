defmodule RisiMe.MLS.DmOp do
  @moduledoc "A pending DM `devices` op (proposal 2026-10-07-dm-device-readd §2, §6)."
  use Ecto.Schema

  @primary_key {:op_id, :binary_id, autogenerate: false}
  schema "mls_dm_ops" do
    field :conversation_id, :string
    field :user_id, :binary_id
    field :payload, :map
    field :committer_user, :binary_id
    field :committer_device, :binary_id
    field :committer_until, :utc_datetime_usec
    field :naming, :integer, default: 0
    field :tried, {:array, :binary_id}, default: []
    field :strikes, :map, default: %{}
    field :created_at, :utc_datetime_usec
  end
end

defmodule RisiMe.MLS.DmOps do
  @moduledoc """
  DMs re-add a member's new device (proposal 2026-10-07-dm-device-readd, v1.16).

  At most one pending `devices` op per (DM, user), in the §12.2 `PendingOp` shape: `added` = the
  user's MLS devices that can still receive and aren't in the group, `removed` = the user's
  in-group superseded devices (§12.1), plus a rejoining device that is still in the group (then in
  both lists: removed and re-added).

  The committer is named like §12.4a: in-group devices of either participant that are not
  superseded and advertise `member_devices`, the affected user's own first, each tier most
  recently seen first, online only, 60 s each (`GroupTimer` kind `dm_committer`), then the next.
  v1.21 §12.12.4: 3 timed-out namings per device per op (strikes) put a device out of budget;
  with every candidate out of budget the op is exhausted and waits (`committer` null). A waiting
  op names the first in-budget candidate whose inbox joins, and wakes candidates by push. Each
  naming sends `mls_dm_op` (stored, push wake) to the named device's user.

  Everything that reads and writes an op runs under the DM's critical section
  (`pg_advisory_xact_lock(hashtext("mls:" <> conv))`, the §10.2 commit lock).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Messaging, MLS, Presence, Repo, TimeUUID}
  alias RisiMe.Groups.Strikes
  alias RisiMe.Devices.Device
  alias RisiMe.MLS.DmOp
  alias RisiMe.Workers.GroupTimer

  @committer_s 60
  @cap "member_devices"
  @receive_days 30

  ## Locking and queries

  @doc "Runs `fun` inside the DM's critical section (a transaction; nests in an outer one)."
  def locked(conv, fun) do
    Repo.transaction(fn ->
      Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["mls:" <> conv])
      fun.()
    end)
  end

  def list(conv),
    do:
      Repo.all(
        from o in DmOp,
          where: o.conversation_id == ^conv,
          order_by: [asc: o.created_at, asc: o.op_id]
      )

  def get(conv, op_id) do
    case Ecto.UUID.cast(op_id || "") do
      {:ok, id} -> Repo.get_by(DmOp, conversation_id: conv, op_id: id)
      :error -> nil
    end
  end

  defp for_user(conv, user_id), do: Repo.get_by(DmOp, conversation_id: conv, user_id: user_id)

  @doc "The e2ee DMs (epoch not null) of a user."
  def e2ee_dms(user_id) do
    Repo.all(
      from g in "mls_groups",
        where: like(g.conversation_id, ^"dm:%#{user_id}%") and not is_nil(g.epoch),
        select: g.conversation_id
    )
    |> Enum.filter(&match?({:ok, _}, MLS.members(&1)))
  end

  @doc "The `{user_id, device_id}` leaves of a DM group (current generation)."
  def in_group(conv) do
    Repo.all(
      from gd in "mls_group_devices",
        where: gd.conversation_id == ^conv,
        select: {type(gd.user_id, :binary_id), type(gd.device_id, :binary_id)}
    )
    |> MapSet.new()
  end

  @doc """
  MLS devices of `user_ids` that can still receive (§12.1): registered with an MLS key, not
  superseded, and seen (census or `PUT`) in the last 30 days.
  """
  def addable(user_ids) do
    since = DateTime.add(DateTime.utc_now(), -@receive_days, :day)
    superseded = MLS.superseded_devices(user_ids)
    census = MapSet.new(MLS.receiving_instances(user_ids))

    for {u, d, seen} <-
          Repo.all(
            from d in Device,
              where: d.user_id in ^user_ids and not is_nil(d.mls_signature_key),
              select: {d.user_id, d.device_id, d.last_seen_at}
          ),
        ref = {u, d},
        not MapSet.member?(superseded, ref),
        MapSet.member?(census, ref) or (seen != nil and DateTime.compare(seen, since) == :gt),
        into: MapSet.new(),
        do: ref
  end

  ## JSON

  @doc "The §12.2 `PendingOp` object."
  def json(%DmOp{} = op) do
    %{
      op_id: op.op_id,
      type: "devices",
      actor: op.user_id,
      user_ids: [],
      role: nil,
      added: op.payload["added"] || [],
      removed: op.payload["removed"] || [],
      committer:
        op.committer_device && %{user_id: op.committer_user, device_id: op.committer_device},
      committer_until: op.committer_until && Messaging.iso(op.committer_until),
      expires_at: nil,
      created_at: Messaging.iso(op.created_at)
    }
  end

  defp ref_json({u, d}), do: %{"user_id" => u, "device_id" => d}

  @doc false
  def refs(%DmOp{payload: p}, key),
    do: for(%{"user_id" => u, "device_id" => d} <- p[key] || [], do: {u, d})

  defp changing(op), do: MapSet.new(refs(op, "added") ++ refs(op, "removed"))

  ## Create / widen (§2)

  @doc """
  Makes sure `user_id` has a pending op in `conv` that adds their devices that can receive and
  aren't in the group, and removes their in-group superseded devices. `rejoin: device_id` also
  adds that device (it asked) and, if it is still in the group, removes and re-adds it. Must run
  in the DM's critical section. Returns the op, or nil when nothing needs adding.
  """
  def ensure(conv, user_id, opts \\ []) do
    rejoin = Keyword.get(opts, :rejoin)
    name? = Keyword.get(opts, :name, true)

    with %{epoch: epoch} when epoch != nil <- MLS.group(conv),
         {:ok, members} <- MLS.members(conv),
         true <- user_id in members do
      in_group = in_group(conv)
      superseded = MLS.superseded_devices([user_id])

      addable =
        if rejoin,
          do: MapSet.put(addable([user_id]), {user_id, rejoin}),
          else: addable([user_id])

      readd = if rejoin && MapSet.member?(in_group, {user_id, rejoin}), do: [{user_id, rejoin}]

      added =
        Enum.reject(addable, &MapSet.member?(in_group, &1)) ++ (readd || [])

      removed =
        (Enum.filter(in_group, fn {u, _} = ref ->
           u == user_id and MapSet.member?(superseded, ref)
         end) --
           added) ++ (readd || [])

      existing = for_user(conv, user_id)

      cond do
        added == [] ->
          existing

        existing ->
          payload = %{
            "added" => merge_refs(existing.payload["added"], added),
            "removed" => merge_refs(existing.payload["removed"], removed)
          }

          # Widened: every candidate may try again.
          op =
            if payload == existing.payload,
              do: existing,
              else:
                existing
                |> Ecto.Changeset.change(
                  payload: payload,
                  tried: [],
                  # v1.21 §12.12.4: a new device in `added` clears every strike of the op.
                  strikes:
                    if(payload["added"] == existing.payload["added"],
                      do: existing.strikes,
                      else: %{}
                    )
                )
                |> Repo.update!()

          if name? and op.committer_device == nil, do: name_next(op), else: op

        true ->
          op =
            Repo.insert!(%DmOp{
              op_id: Ecto.UUID.generate(),
              conversation_id: conv,
              user_id: user_id,
              payload: %{
                "added" => Enum.map(Enum.uniq(added), &ref_json/1),
                "removed" => Enum.map(Enum.uniq(removed), &ref_json/1)
              },
              created_at: DateTime.utc_now(),
              naming: 0,
              tried: []
            })

          if name?, do: name_next(op), else: op
      end
    else
      _ -> nil
    end
  end

  defp merge_refs(old, new),
    do: Enum.uniq((old || []) ++ Enum.map(new, &ref_json/1))

  @doc """
  A device of `user_id` is usable (key packages uploaded, or its inbox joined): an op in each of
  the user's e2ee DMs whose group lacks the device. Runs its own transactions. Best effort.
  """
  def device_ready(user_id, device_id) do
    if MapSet.member?(addable([user_id]), {user_id, device_id}) do
      for conv <- e2ee_dms(user_id), not MapSet.member?(in_group(conv), {user_id, device_id}) do
        locked(conv, fn -> ensure(conv, user_id) end)
      end
    end

    :ok
  end

  ## Settle (§2: kept current, done)

  @doc """
  After an accepted commit (in the critical section): prunes every op of the DM (`prune/1`).
  """
  def settle(conv) do
    for op <- list(conv), do: prune(op)
    :ok
  end

  @doc """
  v1.21 §12.12.5 (in the critical section): `added` keeps the devices that can still receive
  (§12.1; a dropped device also leaves `removed`), `removed` keeps the leaves still in the group
  that are re-added by the op or still superseded. An op left with nothing to do is done (deleted,
  no event). Returns the op, or nil when it was deleted.
  """
  def prune(%DmOp{} = op) do
    case prune_plan(op) do
      {:keep, ^op, 0} -> op
      {:keep, new, _} -> op |> Ecto.Changeset.change(payload: new.payload) |> Repo.update!()
      {:done, _, _} -> Repo.delete!(op) && nil
    end
  end

  @doc "The effect of `prune/1` without writing: `{:keep | :done, op, pruned_devices}`."
  def prune_plan(%DmOp{} = op) do
    in_group = in_group(op.conversation_id)
    added = refs(op, "added")
    removed = refs(op, "removed")
    receiving = addable([op.user_id])
    superseded = MLS.superseded_devices([op.user_id])
    {added2, gone} = Enum.split_with(added, &MapSet.member?(receiving, &1))

    removed2 =
      Enum.filter(removed, fn ref ->
        MapSet.member?(in_group, ref) and ref not in gone and
          (ref in added2 or MapSet.member?(superseded, ref))
      end)

    n = length(added) - length(added2) + length(removed) - length(removed2)

    new = %{
      op
      | payload: %{
          "added" => Enum.map(added2, &ref_json/1),
          "removed" => Enum.map(removed2, &ref_json/1)
        }
    }

    # `removed` keeps only leaves still in the group: a rejoining device leaves `removed` once
    # its old leaf is gone, and is done once it is back.
    if removed2 == [] and Enum.all?(added2, &MapSet.member?(in_group, &1)),
      do: {:done, op, n},
      else: {:keep, if(n == 0, do: op, else: new), n}
  end

  @doc "A DM reset (§3): its ops go."
  def drop(conv) do
    Repo.delete_all(from o in DmOp, where: o.conversation_id == ^conv)
    :ok
  end

  ## Commit authorisation (§2.2)

  @doc """
  True if a commit by `me` with `op_id` may remove `ref`, a still-current leaf of the other
  participant: the op lists it in `removed`, and it is re-added by the op (a rejoin), or it is
  superseded and its user keeps a non-superseded leaf once `leaves_after` is the group.
  """
  def removal_allowed?(conv, op_id, {u, _} = ref, leaves_after) do
    case get(conv, op_id) do
      %DmOp{} = op ->
        superseded = MLS.superseded_devices([u])

        ref in refs(op, "removed") and
          (ref in refs(op, "added") or
             (MapSet.member?(superseded, ref) and
                Enum.any?(leaves_after, fn {uu, _} = l ->
                  uu == u and not MapSet.member?(superseded, l)
                end)))

      nil ->
        false
    end
  end

  ## Naming (§2.1)

  @doc """
  The devices that may commit the op, best first, online or not: in-group devices of either
  participant, not superseded, not changed by the op, advertising `member_devices`; the affected
  user's own first, then the peer's; each tier most recently seen first, ties by device id.
  """
  def candidates(%DmOp{} = op) do
    {:ok, members} = MLS.members(op.conversation_id)
    changing = changing(op)
    superseded = MLS.superseded_devices(members)

    caps =
      Repo.all(
        from d in Device,
          where: d.user_id in ^members,
          select: {{d.user_id, d.device_id}, d.capabilities}
      )
      |> Map.new()

    eligible =
      op.conversation_id
      |> in_group()
      |> Enum.filter(fn ref ->
        not MapSet.member?(changing, ref) and not MapSet.member?(superseded, ref) and
          @cap in (caps[ref] || [])
      end)
      |> Enum.sort_by(&elem(&1, 1))

    {own, peer} = Enum.split_with(eligible, fn {u, _} -> u == op.user_id end)
    RisiMe.Groups.Ops.by_recency(own) ++ RisiMe.Groups.Ops.by_recency(peer)
  end

  @doc """
  Prunes the op (§12.12.5), then names the next online candidate within its naming budget
  (§12.12.4), or waits: with a wake job (§12.12.4 DM wake-ups) unless the op is exhausted or
  only removes stale leaves. In the critical section. Returns the op, or nil when it was done.
  """
  def name_next(%DmOp{} = op) do
    case prune(op) do
      nil -> nil
      op -> name_budgeted(op)
    end
  end

  defp name_budgeted(op) do
    all = candidates(op)
    budget = Strikes.in_budget(op.strikes, all)
    online = Enum.filter(budget, fn {_u, d} -> Presence.device_online?(d) end)

    case Enum.reject(online, fn {_u, d} -> d in op.tried end) do
      [ref | _] ->
        assign(op, ref, :append)

      [] ->
        case online do
          [ref | _] ->
            assign(op, ref, :reset)

          [] ->
            if all != [] and budget == [] do
              Logger.info("devices_op_exhausted: candidates=#{length(all)}")
              waiting(op, Strikes.next_expiry(op.strikes, all))
            else
              waiting(op, if(refs(op, "added") == [], do: nil, else: DateTime.utc_now()))
            end
        end
    end
  end

  defp waiting(op, wake_at) do
    op =
      op
      |> Ecto.Changeset.change(
        committer_user: nil,
        committer_device: nil,
        committer_until: nil,
        naming: op.naming + 1
      )
      |> Repo.update!()

    if wake_at, do: schedule_wake(op.op_id, wake_at)
    op
  end

  defp schedule_wake(op_id, at) do
    {:ok, _} =
      Oban.insert(
        GroupTimer.new(%{"kind" => "dm_wake", "op_id" => op_id},
          scheduled_at: at,
          unique: [
            period: :infinity,
            keys: [:kind, :op_id],
            states: [:available, :scheduled, :retryable]
          ]
        )
      )

    :ok
  end

  @doc """
  v1.21 §12.12.4 DM wake-ups: while the op waits with no committer, the §12.4a inbox push to the
  own tokens of the first 10 in-budget candidates (offline, with a token, at most 4 per device per
  day across ops), again every 6 h. None for an exhausted op (checked again when the first budget
  comes back) or one that only removes stale leaves. Logs counts only.
  """
  def wake(op_id) do
    with %DmOp{committer_device: nil, conversation_id: conv} <- Repo.get(DmOp, op_id),
         {:ok, %DmOp{committer_device: nil} = op} <-
           locked(conv, fn ->
             with %DmOp{} = op <- Repo.get(DmOp, op_id), do: prune(op)
           end),
         true <- refs(op, "added") != [] do
      all = candidates(op)

      if Strikes.exhausted?(op.strikes, all) do
        Logger.info("devices_op_exhausted: candidates=#{length(all)}")

        case Strikes.next_expiry(op.strikes, all) do
          nil -> :ok
          at -> schedule_wake(op.op_id, at)
        end
      else
        targets =
          op.strikes
          |> Strikes.in_budget(all)
          |> Enum.take(10)
          |> Enum.reject(fn {_u, d} -> Presence.device_online?(d) end)

        tokens = RisiMe.Groups.Ops.wake_tokens(targets)
        RisiMe.Push.Dispatcher.push_inbox(tokens)
        Logger.info("dm_op_wake: candidates=#{length(targets)} pushed=#{length(tokens)}")

        schedule_wake(
          op.op_id,
          DateTime.add(DateTime.utc_now(), RisiMe.Groups.Ops.wake_every_h(), :hour)
        )
      end
    end

    :ok
  end

  @doc "`{candidates, exhausted}` of an op (§12.12.2), online or not."
  def status(%DmOp{} = op) do
    all = candidates(op)
    {length(all), Strikes.exhausted?(op.strikes, all)}
  end

  @doc """
  §12.12.3 server guard for the DM reset: `{:error, {:rejoin_pending, op_id, candidates}}` while
  the user's op adds the calling device, is not exhausted and has a candidate. In the critical
  section.
  """
  def rejoin_guard(conv, {u, _d} = ref) do
    case for_user(conv, u) do
      %DmOp{} = op ->
        {n, exhausted?} = if ref in refs(op, "added"), do: status(op), else: {0, false}

        if n > 0 and not exhausted?,
          do: {:error, {:rejoin_pending, op.op_id, n}},
          else: :ok

      nil ->
        :ok
    end
  end

  @doc """
  §12.12.6 for DMs: the stale leaves of `user_id` join the `removed` list of the user's op (one
  is created with `added: []` when there is none). In the critical section. Returns
  `{:created | :widened | :unchanged, op}` or `{:none, nil}`.
  """
  def ensure_cleanup(conv, user_id, stale, opts \\ []) do
    name? = Keyword.get(opts, :name, true)
    existing = for_user(conv, user_id)
    stale = Enum.sort(stale)

    cond do
      stale == [] ->
        {if(existing, do: :unchanged, else: :none), existing}

      existing ->
        removed = Enum.uniq(refs(existing, "removed") ++ stale)

        if removed == refs(existing, "removed") do
          {:unchanged,
           if(name? and existing.committer_device == nil, do: name_next(existing), else: existing)}
        else
          op =
            existing
            |> Ecto.Changeset.change(
              payload: %{existing.payload | "removed" => Enum.map(removed, &ref_json/1)}
            )
            |> Repo.update!()

          {:widened, if(name? and op.committer_device == nil, do: name_next(op), else: op)}
        end

      true ->
        op =
          Repo.insert!(%DmOp{
            op_id: Ecto.UUID.generate(),
            conversation_id: conv,
            user_id: user_id,
            payload: %{"added" => [], "removed" => Enum.map(stale, &ref_json/1)},
            created_at: DateTime.utc_now(),
            naming: 0,
            tried: [],
            strikes: %{}
          })

        {:created, if(name?, do: name_next(op), else: op)}
    end
  end

  defp assign(op, nil, _mode) do
    op
    |> Ecto.Changeset.change(
      committer_user: nil,
      committer_device: nil,
      committer_until: nil,
      naming: op.naming + 1
    )
    |> Repo.update!()
  end

  defp assign(op, {u, d}, mode) do
    until = DateTime.add(DateTime.utc_now(), @committer_s, :second)
    tried = if mode == :reset, do: [d], else: Enum.uniq(op.tried ++ [d])

    op =
      op
      |> Ecto.Changeset.change(
        committer_user: u,
        committer_device: d,
        committer_until: until,
        naming: op.naming + 1,
        tried: tried
      )
      |> Repo.update!()

    {:ok, _} =
      Oban.insert(
        GroupTimer.new(
          %{"kind" => "dm_committer", "op_id" => op.op_id, "naming" => op.naming},
          scheduled_at: until
        )
      )

    Logger.info("devices_op_named: naming=#{op.naming}")
    publish(op)
    op
  end

  defp publish(%DmOp{committer_user: u} = op) do
    generation = (MLS.group(op.conversation_id) || %{generation: 1}).generation

    event = %{
      event_id: TimeUUID.generate(),
      kind: "mls_dm_op",
      data: %{
        "conversation_id" => op.conversation_id,
        "generation" => generation,
        "op" => json(op)
      }
    }

    Messaging.publish_batch([{u, event, [push: true]}])
  end

  @doc "The named committer's 60 s ran out: settle, then name the next unless superseded or done."
  def committer_timeout(op_id, naming) do
    with %DmOp{conversation_id: conv} <- Repo.get(DmOp, op_id) do
      locked(conv, fn ->
        with %DmOp{naming: ^naming} = op <- Repo.get(DmOp, op_id) do
          op = strike(op)
          settle(conv)

          with %DmOp{} = op <- Repo.get(DmOp, op.op_id), do: name_next(op)
        end
      end)
    end

    :ok
  end

  # §12.12.4: the named device's 60 s ran out without an accepted commit completing the op.
  defp strike(%DmOp{committer_device: d} = op) when is_binary(d),
    do: op |> Ecto.Changeset.change(strikes: Strikes.add(op.strikes, d)) |> Repo.update!()

  defp strike(op), do: op

  @doc """
  A device's inbox joined: ops for its own DMs that lack it (`device_ready/2`), then it is named
  for every waiting op (no committer) of its DMs it is a candidate for. Own transactions.
  """
  def device_joined(user_id, device_id) do
    device_ready(user_id, device_id)

    waiting =
      Repo.all(
        from o in DmOp,
          where: like(o.conversation_id, ^"dm:%#{user_id}%") and is_nil(o.committer_device),
          select: {o.conversation_id, o.op_id}
      )

    for {conv, op_id} <- waiting do
      locked(conv, fn ->
        with %DmOp{committer_device: nil} = op <- Repo.get(DmOp, op_id),
             %DmOp{} = op <- prune(op),
             true <- {user_id, device_id} in candidates(op),
             false <- Strikes.out?(op.strikes, device_id) do
          assign(op, {user_id, device_id}, :append)
        end
      end)
    end

    :ok
  end

  ## Recovery (§7)

  @doc """
  Scans every e2ee DM for participants whose receiving MLS devices aren't in the group.
  `dry_run: true` (the default) only counts; `dry_run: false` creates (§2) and names the ops.
  Returns counts only.
  """
  def recover(opts \\ []) do
    dry_run = Keyword.get(opts, :dry_run, true)

    convs =
      Repo.all(
        from g in "mls_groups",
          where: like(g.conversation_id, "dm:%") and not is_nil(g.epoch),
          select: g.conversation_id
      )

    zero = %{
      dms: 0,
      affected_dms: 0,
      missing_devices: 0,
      superseded_leaves: 0,
      with_candidates: 0,
      without_candidates: 0,
      ops: 0,
      named: 0
    }

    Enum.reduce(convs, zero, fn conv, acc ->
      case MLS.members(conv) do
        {:ok, members} -> recover_dm(conv, members, dry_run, %{acc | dms: acc.dms + 1})
        :error -> acc
      end
    end)
  end

  defp recover_dm(conv, members, dry_run, acc) do
    in_group = in_group(conv)
    superseded = MLS.superseded_devices(members)

    affected =
      for u <- members,
          missing = Enum.reject(addable([u]), &MapSet.member?(in_group, &1)),
          missing != [],
          do: {u, missing}

    if affected == [] do
      acc
    else
      Enum.reduce(affected, %{acc | affected_dms: acc.affected_dms + 1}, fn {u, missing}, acc ->
        stale =
          Enum.count(in_group, fn {uu, _} = ref -> uu == u and MapSet.member?(superseded, ref) end)

        probe = %DmOp{
          conversation_id: conv,
          user_id: u,
          payload: %{"added" => Enum.map(missing, &ref_json/1), "removed" => []}
        }

        has_candidates? = candidates(probe) != []

        acc = %{
          acc
          | missing_devices: acc.missing_devices + length(missing),
            superseded_leaves: acc.superseded_leaves + stale,
            with_candidates: acc.with_candidates + if(has_candidates?, do: 1, else: 0),
            without_candidates: acc.without_candidates + if(has_candidates?, do: 0, else: 1)
        }

        if dry_run do
          acc
        else
          {:ok, op} = locked(conv, fn -> ensure(conv, u) end)

          %{
            acc
            | ops: acc.ops + if(op, do: 1, else: 0),
              named: acc.named + if(op && op.committer_device, do: 1, else: 0)
          }
        end
      end)
    end
  end
end
