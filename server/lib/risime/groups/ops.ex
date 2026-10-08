defmodule RisiMe.Groups.Ops do
  @moduledoc """
  Pending operations and committer naming (contract v1.9 §12.4).

  Every op has at most one named committer device. It has 60 s (`committer_until`); an Oban
  timer (`RisiMe.Workers.GroupTimer`) then names the next candidate. Candidates, in order: the
  first candidate (the requesting admin's device, or for a `devices` op one of the affected
  user's other in-group devices), then online admin devices, most recently seen first. When no
  candidate is online the op waits (`committer` null) and the first authorised device whose
  inbox joins is named (`device_joined/2`). Every naming sends a `group_op` event to the named
  device's user (with a push wake-up).

  `add` and `role` ops expire after 24 h; `remove`, `devices` and `rebuild` never expire.
  All functions here run inside `RisiMe.Groups.locked/2` unless noted.

  v1.14 §12.4a: a `devices` op that is member-committable (`member_committable?/3`) may also be
  committed by any active member's in-group device while the `member_devices` gate is open
  (`gate_open?/3`); those devices are the third candidate tier. While a `devices` op waits with
  no online candidate, a `wake` job pushes the first candidates' own tokens every 6 h.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Devices, Groups, Messaging, MLS, Presence, RateLimiter, Repo, TimeUUID}
  alias RisiMe.Devices.Device
  alias RisiMe.Groups.{Group, Member, Op, Strikes}
  alias RisiMe.Workers.GroupTimer

  @committer_s 60
  @expiry_h 24
  @member_cap "member_devices"
  @wake_max 10
  @wake_every_h 6
  @wake_per_device_day 4

  def committer_seconds, do: @committer_s

  ## Queries

  def list(group_id),
    do:
      Repo.all(
        from o in Op, where: o.group_id == ^group_id, order_by: [asc: o.created_at, asc: o.op_id]
      )

  def get(group_id, op_id), do: Repo.get_by(Op, group_id: group_id, op_id: op_id)

  @doc "The pending `role` op for a user, if any."
  def role_op(group_id, user_id) do
    group_id
    |> list()
    |> Enum.find(&(&1.type == "role" and &1.payload["user_ids"] == [user_id]))
  end

  @doc "A pending rejoin (`devices` op removing and re-adding this device), if any."
  def rejoin_op(group_id, {u, d}) do
    ref = ref_json({u, d})

    group_id
    |> list()
    |> Enum.find(&(&1.type == "devices" and ref in &1.payload["added"]))
  end

  @doc """
  Makes sure a device with a new MLS state on a (possibly) stale leaf is removed and re-added
  (§12.8 rejoin): a sign-in after a logout keeps the device id but wipes its MLS state. Pending
  removal-only `devices` ops of the device are dropped first, as they would otherwise remove the
  re-added leaf. Idempotent: an op that already (re-)adds the device is kept.
  """
  def ensure_rejoin(%Group{} = g, {u, _d} = ref) do
    json = ref_json(ref)

    case rejoin_op(g.id, ref) do
      %Op{} ->
        :ok

      nil ->
        for %Op{type: "devices"} = op <- list(g.id), json in (op.payload["removed"] || []) do
          removed = op.payload["removed"] -- [json]

          if (op.payload["added"] || []) == [] and removed == [],
            do: Repo.delete!(op),
            else:
              op
              |> Ecto.Changeset.change(payload: %{op.payload | "removed" => removed})
              |> Repo.update!()
        end

        removed = if MapSet.member?(Groups.in_group(g.id), ref), do: [ref], else: []
        create(g, "devices", u, %{added: [ref], removed: removed}, :auto)
    end
  end

  @doc """
  The device refs a `devices` op changes. None of them can commit it: a device can't commit its
  own removal, and a device being (re-)added has no state for the group yet.
  """
  def changing(%Op{type: "devices"} = op),
    do: MapSet.new(refs(op, "added") ++ refs(op, "removed"))

  def changing(_op), do: MapSet.new()

  @doc """
  The `removed` list a commit completing this `devices` op must declare: only the op's devices
  still in the group (a rejoining device's old leaf may already be gone).
  """
  def removed_now(%Op{} = op, in_group),
    do: Enum.filter(refs(op, "removed"), &MapSet.member?(in_group, &1))

  ## JSON

  @doc "The `PendingOp` object."
  def json(%Op{} = op) do
    p = op.payload

    %{
      op_id: op.op_id,
      type: op.type,
      actor: op.actor,
      user_ids: p["user_ids"] || [],
      role: p["role"],
      added: p["added"] || [],
      removed: p["removed"] || [],
      committer:
        op.committer_device && %{user_id: op.committer_user, device_id: op.committer_device},
      committer_until: op.committer_until && Messaging.iso(op.committer_until),
      expires_at: op.expires_at && Messaging.iso(op.expires_at),
      created_at: Messaging.iso(op.created_at)
    }
  end

  @doc false
  def ref_json({u, d}), do: %{"user_id" => u, "device_id" => d}

  @doc false
  def refs(op, key),
    do: for(%{"user_id" => u, "device_id" => d} <- op.payload[key] || [], do: {u, d})

  ## Create and name

  @doc """
  Creates an op and names its first committer: `{user_id, device_id}` (the requesting admin's
  device), `:auto` (the candidate rules) or nil (no first candidate, e.g. a leave).
  """
  def create(%Group{} = g, type, actor, attrs, first) do
    now = DateTime.utc_now()

    op =
      Repo.insert!(%Op{
        op_id: Ecto.UUID.generate(),
        group_id: g.id,
        type: type,
        actor: actor,
        payload: %{
          "user_ids" => Map.get(attrs, :user_ids, []),
          "role" => Map.get(attrs, :role),
          "added" => Enum.map(Map.get(attrs, :added, []), &ref_json/1),
          "removed" => Enum.map(Map.get(attrs, :removed, []), &ref_json/1)
        },
        expires_at: if(type in ["add", "role"], do: DateTime.add(now, @expiry_h, :hour)),
        created_at: now,
        naming: 0,
        tried: []
      })

    if op.expires_at,
      do:
        {:ok, _} =
          Oban.insert(
            GroupTimer.new(%{"kind" => "expire", "op_id" => op.op_id},
              scheduled_at: op.expires_at
            )
          )

    case first do
      {_u, _d} = ref -> assign(g, op, ref, :append)
      :auto -> name_next(g, op)
      nil -> name_next(g, op)
    end
  end

  @doc """
  Names the next candidate (or waits for one to come online). A `devices` op is pruned first
  (§12.12.5; nil when that finished it) and only devices within their naming budget are named
  (§12.12.4); an exhausted op waits with no committer and no wake pushes.
  """
  def name_next(%Group{} = g, %Op{type: "devices"} = op) do
    case prune(g, op) do
      nil -> nil
      op -> name_budgeted(g, op)
    end
  end

  def name_next(%Group{} = g, %Op{} = op) do
    cands = online_candidates(g, op)

    case Enum.reject(cands, fn {_u, d} -> d in op.tried end) do
      [ref | _] ->
        assign(g, op, ref, :append)

      [] ->
        case cands do
          [ref | _] -> assign(g, op, ref, :reset)
          [] -> assign(g, op, nil, :append)
        end
    end
  end

  defp name_budgeted(g, op) do
    all = candidates(g, op)
    budget = Strikes.in_budget(op.strikes, all)
    online = Enum.filter(budget, fn {_u, d} -> Presence.device_online?(d) end)

    case Enum.reject(online, fn {_u, d} -> d in op.tried end) do
      [ref | _] ->
        assign(g, op, ref, :append)

      [] ->
        case online do
          [ref | _] ->
            assign(g, op, ref, :reset)

          [] ->
            if all != [] and budget == [] do
              Logger.info("devices_op_exhausted: candidates=#{length(all)}")
              waiting(op, Strikes.next_expiry(op.strikes, all))
            else
              waiting(op, if(cleanup?(op), do: nil, else: DateTime.utc_now()))
            end
        end
    end
  end

  # No committer. `wake_at`: when the §12.4a wake job runs (nil: no wake job, e.g. a cleanup op).
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

  defp assign(_g, op, nil, _mode) do
    op
    |> Ecto.Changeset.change(
      committer_user: nil,
      committer_device: nil,
      committer_until: nil,
      naming: op.naming + 1
    )
    |> Repo.update!()
  end

  defp assign(g, op, {u, d}, mode) do
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
          %{"kind" => "committer", "op_id" => op.op_id, "naming" => op.naming},
          scheduled_at: until
        )
      )

    if op.type == "devices", do: Logger.info("devices_op_named: naming=#{op.naming}")
    publish_op(g, op)
    op
  end

  @doc "Sends the `group_op` event for the op's current naming to the committer's user."
  def publish_op(%Group{} = g, %Op{committer_user: u} = op) when is_binary(u) do
    event = %{
      event_id: TimeUUID.generate(),
      kind: "group_op",
      data: %{"group_id" => g.id, "generation" => g.generation, "op" => json(op)}
    }

    # §12.7: it wakes that user by push like a message.
    Messaging.publish_batch([{u, event, [push: true]}])
  end

  def publish_op(_g, _op), do: :ok

  ## Candidates and authorisation

  @doc "The user whose devices a `devices` op changes."
  def affected_user(%Op{type: "devices"} = op) do
    case refs(op, "added") ++ refs(op, "removed") do
      [{u, _} | _] -> u
      [] -> op.actor
    end
  end

  def affected_user(_), do: nil

  # Devices that may commit this op, best first, online only.
  defp online_candidates(g, op),
    do: g |> candidates(op) |> Enum.filter(fn {_u, d} -> Presence.device_online?(d) end)

  @doc """
  Devices that may commit this op, best first, online or not: for `devices`, the affected user's
  other in-group devices; then admin devices; then (§12.4a, member path open) the other active
  members' in-group devices. Each tier most recently seen first.
  """
  def candidates(g, op) do
    own =
      case op.type do
        "devices" ->
          u = affected_user(op)
          changing = MapSet.new(refs(op, "added") ++ refs(op, "removed"))

          g.id
          |> Groups.in_group()
          |> Enum.filter(fn {uu, _} = ref -> uu == u and not MapSet.member?(changing, ref) end)

        _ ->
          []
      end

    leaving = if op.type == "remove", do: op.payload["user_ids"], else: []

    changing = changing(op)

    # MLS can't commit its own removal: a remove op never names the removed users' devices; a
    # `devices` op never names a device it changes (e.g. the rejoining device itself).
    (by_recency(own) ++ by_recency(admin_devices(g)) ++ by_recency(member_devices(g, op)))
    |> Enum.uniq()
    |> Enum.reject(fn {u, _d} = ref -> u in leaving or MapSet.member?(changing, ref) end)
    |> drop_dormant(op)
  end

  # v1.21: a superseded device (§12.1) that isn't online can't be named and is no candidate for
  # a `devices` op (it would count towards `candidates` forever). Once seen again it is current.
  defp drop_dormant(refs, %Op{type: "devices"}) do
    superseded = refs |> Enum.map(&elem(&1, 0)) |> Enum.uniq() |> MLS.superseded_devices()

    Enum.reject(refs, fn {_u, d} = ref ->
      MapSet.member?(superseded, ref) and not Presence.device_online?(d)
    end)
  end

  defp drop_dormant(refs, _op), do: refs

  @doc "§12.12.6: a cleanup op (removes stale leaves; tiers 1–2 only, no wake pushes)."
  def cleanup?(%Op{type: "devices", payload: p}), do: p["cleanup"] == true
  def cleanup?(_), do: false

  # §12.4a tier 3: the other active members' in-group devices (never agents, never superseded),
  # only while the member path is open. Sorted by device id first, so recency ties are stable.
  defp member_devices(g, %Op{type: "devices"} = op) do
    in_group = Groups.in_group(g.id)

    if member_path?(g, op, in_group) do
      affected = affected_user(op)
      users = MapSet.new(active_user_ids(g.id))
      superseded = in_group |> Enum.map(&elem(&1, 0)) |> Enum.uniq() |> MLS.superseded_devices()

      in_group
      |> Enum.filter(fn {u, _} = ref ->
        u != affected and MapSet.member?(users, u) and not MapSet.member?(superseded, ref)
      end)
      |> Enum.sort_by(&elem(&1, 1))
    else
      []
    end
  end

  # v1.24 §24.4: an agent `remove` op (Official off) may be committed by any active human
  # member's in-group device.
  defp member_devices(g, %Op{type: "remove"} = op) do
    if agent_remove?(g, op) do
      users = MapSet.new(active_user_ids(g.id))

      g.id
      |> Groups.in_group()
      |> Enum.filter(fn {u, _} -> MapSet.member?(users, u) end)
      |> Enum.sort_by(&elem(&1, 1))
    else
      []
    end
  end

  defp member_devices(_g, _op), do: []

  @doc "v1.24 §24.4: a `remove` op whose every target is an agent member."
  def agent_remove?(%Group{} = g, %Op{type: "remove"} = op) do
    users = op.payload["user_ids"] || []

    users != [] and
      Enum.all?(users, fn u -> match?(%Member{kind: "agent"}, Groups.member(g.id, u)) end)
  end

  def agent_remove?(_g, _op), do: false

  defp active_user_ids(group_id),
    do:
      Repo.all(
        from m in Member,
          where: m.group_id == ^group_id and m.state == "active" and m.kind == "user",
          select: m.user_id
      )

  @doc """
  §12.4a: a `devices` op a non-admin may commit for another user: it changes one user's devices,
  that user is an active member (not an agent) with a leaf in the group, it adds at least one
  device, and every device it removes it also re-adds (a rejoin or a re-key).
  """
  def member_committable?(g, op, in_group \\ nil)

  def member_committable?(%Group{} = g, %Op{type: "devices"} = op, in_group) do
    added = refs(op, "added")
    removed = refs(op, "removed")

    case Enum.uniq(for {u, _} <- added ++ removed, do: u) do
      [u] ->
        in_group = in_group || Groups.in_group(g.id)

        added != [] and Enum.all?(removed, &(&1 in added)) and
          Enum.any?(in_group, fn {uu, _} -> uu == u end) and
          match?(%Member{state: "active", kind: "user"}, Groups.member(g.id, u))

      _ ->
        false
    end
  end

  def member_committable?(_g, _op, _in_group), do: false

  @doc """
  §12.4a rollout gate: true while every in-group leaf that is not superseded, still registered
  and not changed by the op advertises `member_devices`. A leaf whose device row is gone gets no
  `grp:` traffic (§12.1), so it can't reject a commit.
  """
  def gate_open?(g, op, in_group \\ nil) do
    in_group = in_group || Groups.in_group(g.id)
    changing = changing(op)
    leaves = Enum.reject(in_group, &MapSet.member?(changing, &1))
    users = leaves |> Enum.map(&elem(&1, 0)) |> Enum.uniq()
    superseded = MLS.superseded_devices(users)

    caps =
      Repo.all(
        from d in Device,
          where: d.user_id in ^users,
          select: {{d.user_id, d.device_id}, d.capabilities}
      )
      |> Map.new()

    Enum.all?(leaves, fn ref ->
      MapSet.member?(superseded, ref) or not Map.has_key?(caps, ref) or
        @member_cap in (caps[ref] || [])
    end)
  end

  @doc "§12.4a: member-committable and the gate is open."
  def member_path?(g, op, in_group \\ nil) do
    in_group = in_group || Groups.in_group(g.id)
    member_committable?(g, op, in_group) and gate_open?(g, op, in_group)
  end

  # Leaves of active admins; after a reset (no MLS state), their current groups devices.
  defp admin_devices(g) do
    admins = Groups.admin_ids(g.id)

    if Groups.epoch(g.id) == nil,
      do: admins |> Groups.device_refs() |> Enum.to_list(),
      else: g.id |> Groups.in_group() |> Enum.filter(fn {u, _} -> u in admins end)
  end

  @doc false
  def by_recency([]), do: []

  def by_recency(refs) do
    keys = for {_u, d} <- refs, do: "device:" <> d

    seen =
      Repo.all(
        from i in "app_instances",
          where: i.instance_key in ^keys,
          select: {i.instance_key, type(i.last_seen_at, :utc_datetime_usec)}
      )
      |> Map.new()

    Enum.sort_by(
      refs,
      fn {_u, d} -> seen["device:" <> d] || ~U[1970-01-01 00:00:00Z] end,
      {:desc, DateTime}
    )
  end

  @doc """
  True if the device may commit (complete) the op (§12.4): `devices` by the affected user's own
  in-group device or any admin device; everything else by any admin device.
  """
  def authorised?(%Group{} = g, %Op{} = op, {u, _d} = ref) do
    admin? = u in Groups.admin_ids(g.id)
    epoch = Groups.epoch(g.id)
    in_group? = epoch != nil and MapSet.member?(Groups.in_group(g.id), ref)

    case op.type do
      "devices" ->
        in_group? and not MapSet.member?(changing(op), ref) and
          (admin? or u == affected_user(op) or
             (u in active_user_ids(g.id) and member_path?(g, op)))

      "rebuild" ->
        admin? and epoch == nil and MapSet.member?(Groups.device_refs([u]), ref)

      "remove" ->
        (admin? or (agent_remove?(g, op) and u in active_user_ids(g.id))) and in_group? and
          u not in op.payload["user_ids"]

      _ ->
        admin? and in_group?
    end
  end

  @doc """
  A groups-capable device joined its inbox: it is named for every waiting op (no committer)
  of its groups that it is authorised for. Runs its own transactions.
  """
  def device_joined(user_id, device_id) do
    waiting =
      Repo.all(
        from o in Op,
          join: m in Member,
          on: m.group_id == o.group_id,
          where: m.user_id == ^user_id and m.state == "active" and is_nil(o.committer_device),
          select: {o.group_id, o.op_id}
      )

    for {group_id, op_id} <- waiting do
      Groups.locked(group_id, fn ->
        with %Group{} = g <- Groups.get_group(group_id),
             %Op{committer_device: nil} = op <- get(group_id, op_id),
             %Op{} = op <- prune_if_devices(g, op),
             true <- authorised?(g, op, {user_id, device_id}),
             false <- op.type == "devices" and Strikes.out?(op.strikes, device_id) do
          assign(g, op, {user_id, device_id}, :append)
        end

        {:ok, nil}
      end)
    end

    :ok
  end

  defp prune_if_devices(g, %Op{type: "devices"} = op), do: prune(g, op)
  defp prune_if_devices(_g, op), do: op

  ## Wake-ups (§12.4a)

  defp schedule_wake(op_id, at) do
    {:ok, _} =
      Oban.insert(
        GroupTimer.new(%{"kind" => "wake", "op_id" => op_id},
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
  A `devices` op still waits with no committer: sends the §8.2 inbox push to the own token of
  each of the first #{@wake_max} candidates within their naming budget (skipping online devices
  and devices without a token, at most #{@wake_per_device_day} per device per day), then checks
  again in #{@wake_every_h} h. v1.21: the op is pruned first (under the group lock); cleanup ops
  get no wake pushes; an exhausted op gets none either and is checked again when the first
  budget comes back. Logs counts only.
  """
  def wake(op_id) do
    with %Op{type: "devices", committer_device: nil} = op <- Repo.get(Op, op_id),
         false <- cleanup?(op),
         {:ok, {%Group{} = g, %Op{committer_device: nil} = op}} <-
           Groups.locked(op.group_id, fn ->
             with %Group{} = g <- Groups.get_group(op.group_id),
                  %Op{} = op <- Repo.get(Op, op_id),
                  %Op{} = op <- prune(g, op) do
               {:ok, {g, op}}
             else
               _ -> {:ok, nil}
             end
           end) do
      all = candidates(g, op)

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
          |> Enum.take(@wake_max)
          |> Enum.reject(fn {_u, d} -> Presence.device_online?(d) end)

        tokens = wake_tokens(targets)
        RisiMe.Push.Dispatcher.push_inbox(tokens)
        Logger.info("group_op_wake: candidates=#{length(targets)} pushed=#{length(tokens)}")
        schedule_wake(op.op_id, DateTime.add(DateTime.utc_now(), @wake_every_h, :hour))
      end
    end

    :ok
  end

  @doc false
  def wake_tokens(targets) do
    for {_u, d} <- targets,
        token = Devices.device_push_token(d),
        token != nil,
        RateLimiter.hit_if_allowed(:group_wake, d, @wake_per_device_day, :timer.hours(24)) == :ok,
        do: token
  end

  def wake_every_h, do: @wake_every_h

  ## Re-naming waiting ops (§12.11)

  @doc """
  §12.11: names a committer for every waiting `devices` op (no committer), in `group_ids` (or
  every group). `dry_run: true` (the default) changes nothing and only counts. Each op is named
  under its group's lock; an op named meanwhile is skipped, so a re-run is harmless. Returns
  counts only: `devices_ops` (all pending `devices` ops in scope), `waiting`, `member_committable`,
  `gate_closed` (member-committable but gated), `with_candidates` (any candidate, online or
  not), `named` and `still_waiting` (real run only).
  """
  def name_waiting(opts \\ []) do
    dry_run = Keyword.get(opts, :dry_run, true)
    group_ids = Keyword.get(opts, :group_ids)

    scope =
      if group_ids,
        do: from(o in Op, where: o.type == "devices" and o.group_id in ^group_ids),
        else: from(o in Op, where: o.type == "devices")

    all = Repo.all(from o in scope, select: {o.group_id, o.op_id, o.committer_device})
    waiting = for {gid, oid, nil} <- all, do: {gid, oid}

    zero = %{
      devices_ops: length(all),
      waiting: length(waiting),
      member_committable: 0,
      gate_closed: 0,
      with_candidates: 0,
      named: 0,
      still_waiting: 0
    }

    Enum.reduce(waiting, zero, fn {gid, oid}, acc ->
      with %Group{} = g <- Groups.get_group(gid),
           %Op{} = op <- get(gid, oid) do
        in_group = Groups.in_group(gid)
        mc? = member_committable?(g, op, in_group)
        gate? = mc? and gate_open?(g, op, in_group)

        acc
        |> bump(:member_committable, mc?)
        |> bump(:gate_closed, mc? and not gate?)
        |> bump(:with_candidates, candidates(g, op) != [])
        |> then(&if(dry_run, do: &1, else: name_one(&1, gid, oid)))
      else
        _ -> acc
      end
    end)
  end

  @doc "§12.11: `name_waiting/1` (real run) for the groups where `user_id` is an active member."
  def name_waiting_for_user(user_id) do
    group_ids =
      Repo.all(
        from m in Member,
          where: m.user_id == ^user_id and m.state == "active",
          select: m.group_id
      )

    if group_ids == [],
      do: :ok,
      else: name_waiting(dry_run: false, group_ids: group_ids)
  end

  defp name_one(acc, gid, oid) do
    {:ok, named?} =
      Groups.locked(gid, fn ->
        with %Group{} = g <- Groups.get_group(gid),
             %Op{committer_device: nil} = op <- get(gid, oid) do
          {:ok, match?(%Op{committer_device: d} when d != nil, name_next(g, op))}
        else
          _ -> {:ok, false}
        end
      end)

    if named?, do: bump(acc, :named, true), else: bump(acc, :still_waiting, true)
  end

  defp bump(acc, key, true), do: Map.update!(acc, key, &(&1 + 1))
  defp bump(acc, _key, false), do: acc

  ## Timers (Oban)

  @doc "The committer's 60 s ran out: name the next candidate unless superseded or done."
  def committer_timeout(op_id, naming) do
    with %Op{} = op <- Repo.get(Op, op_id) do
      Groups.locked(op.group_id, fn ->
        with %Op{naming: ^naming} = op <- Repo.get(Op, op_id),
             %Group{} = g <- Groups.get_group(op.group_id) do
          g |> strike(op) |> then(&name_next(g, &1))
        end

        {:ok, nil}
      end)
    end

    :ok
  end

  # §12.12.4: the named device's 60 s ran out without an accepted commit completing the op.
  defp strike(_g, %Op{type: "devices", committer_device: d} = op) when is_binary(d) do
    op
    |> Ecto.Changeset.change(strikes: Strikes.add(op.strikes, d))
    |> Repo.update!()
  end

  defp strike(_g, op), do: op

  @doc "24 h passed: an `add` drops its `pending_add` members (`add_expired`); a `role` op is dropped."
  def expire(op_id) do
    with %Op{} = op <- Repo.get(Op, op_id) do
      Groups.locked(op.group_id, fn ->
        with %Op{} = op <- Repo.get(Op, op_id),
             %Group{} = g <- Groups.get_group(op.group_id) do
          Repo.delete!(op)
          if op.type == "add", do: expire_add(g, op)
        end

        {:ok, nil}
      end)
    end

    :ok
  end

  defp expire_add(g, op) do
    users = op.payload["user_ids"]

    {_, dropped} =
      Repo.delete_all(
        from(m in Member,
          where: m.group_id == ^g.id and m.user_id in ^users and m.state == "pending_add",
          select: m.user_id
        )
      )

    if dropped != [] do
      g
      |> Groups.group_events("add_expired", op.actor, dropped, epoch: Groups.epoch(g.id))
      |> Messaging.publish_batch()
    end
  end

  ## Maintenance

  @doc """
  A user leaves the pending state of the group (removed, leaving, or a cancelled add): their
  `add` membership and `devices` ops go.
  """
  def drop_user(%Group{} = g, user_id) do
    for op <- list(g.id) do
      cond do
        op.type == "add" and user_id in op.payload["user_ids"] ->
          case op.payload["user_ids"] -- [user_id] do
            [] ->
              Repo.delete!(op)

            rest ->
              op
              |> Ecto.Changeset.change(payload: %{op.payload | "user_ids" => rest})
              |> Repo.update!()
          end

        op.type == "devices" and affected_user(op) == user_id ->
          Repo.delete!(op)

        true ->
          :ok
      end
    end

    :ok
  end

  @doc "A device is gone: pending `devices` ops stop adding it."
  def forget_device(%Group{} = g, ref) do
    json = ref_json(ref)

    for %Op{type: "devices"} = op <- list(g.id), json in op.payload["added"] do
      added = op.payload["added"] -- [json]

      if added == [] and op.payload["removed"] -- [json] == [],
        do: Repo.delete!(op),
        else:
          op
          |> Ecto.Changeset.change(
            payload: %{
              op.payload
              | "added" => added,
                "removed" => op.payload["removed"] -- [json]
            }
          )
          |> Repo.update!()
    end

    :ok
  end

  ## v1.21 §12.12.5: ops whose targets are gone

  @doc """
  Prunes a `devices` op (§12.12.5), under the group lock: `added` loses devices that can no longer
  receive (§12.1: row gone, unseen for 30 days, or superseded; such a device also leaves
  `removed`), `removed` loses devices no longer in the group (and, for a cleanup op, devices no
  longer stale). An op whose affected user isn't an active member is dropped; one with nothing
  left to do is done (deleted, no event). Returns the op, or nil when it was deleted.
  """
  def prune(%Group{} = g, %Op{type: "devices"} = op) do
    case prune_plan(g, op) do
      {:keep, ^op, 0} ->
        op

      {:keep, new, _n} ->
        op |> Ecto.Changeset.change(payload: new.payload) |> Repo.update!()

      {_gone, _, _n} ->
        Repo.delete!(op)
        nil
    end
  end

  def prune(_g, op), do: op

  @doc """
  The effect of `prune/2` without writing: `{:keep, op_with_new_payload, pruned_devices}`,
  `{:done, op, pruned_devices}` or `{:dropped, op, pruned_devices}`.
  """
  def prune_plan(%Group{} = g, %Op{type: "devices"} = op) do
    u = affected_user(op)
    in_group = Groups.in_group(g.id)
    added = refs(op, "added")
    removed = refs(op, "removed")

    cond do
      not match?(%Member{state: "active"}, Groups.member(g.id, u)) ->
        {:dropped, op, 0}

      Groups.epoch(g.id) == nil ->
        {:keep, op, 0}

      true ->
        users = Enum.uniq([u | for({uu, _} <- added ++ removed, do: uu)])
        receiving = RisiMe.MLS.DmOps.addable(users)
        {added2, gone} = Enum.split_with(added, &MapSet.member?(receiving, &1))
        stale = if cleanup?(op), do: stale_leaves(g.id, users), else: nil

        removed2 =
          Enum.filter(removed, fn ref ->
            MapSet.member?(in_group, ref) and ref not in gone and
              (stale == nil or MapSet.member?(stale, ref))
          end)

        n = length(added) - length(added2) + length(removed) - length(removed2)
        new = %{op | payload: put_refs(op.payload, added2, removed2)}

        if Enum.all?(added2, &MapSet.member?(in_group, &1)) and removed2 == [],
          do: {:done, op, n},
          else: {:keep, if(n == 0, do: op, else: new), n}
    end
  end

  defp put_refs(payload, added, removed),
    do:
      Map.merge(payload, %{
        "added" => Enum.map(added, &ref_json/1),
        "removed" => Enum.map(removed, &ref_json/1)
      })

  @doc "Prunes every `devices` op of the group (after an accepted commit)."
  def prune_all(%Group{} = g) do
    for %Op{type: "devices"} = op <- list(g.id), do: prune(g, op)
    :ok
  end

  ## v1.21 §12.12.2–3: rejoin replies and the reset guard

  @doc "The pending `devices` op that adds the device, if any."
  def adding_op(group_id, ref) do
    json = ref_json(ref)

    group_id
    |> list()
    |> Enum.find(&(&1.type == "devices" and json in (&1.payload["added"] || [])))
  end

  @doc "`{candidates, exhausted}` of an op (§12.12.2), online or not."
  def rejoin_status(%Group{} = g, %Op{} = op) do
    all = candidates(g, op)
    {length(all), Strikes.exhausted?(op.strikes, all)}
  end

  @doc """
  §12.12.3 server guard: `{:error, {:rejoin_pending, op_id, candidates}}` while a pending
  `devices` op adds the calling device, is not exhausted, and has a candidate or was created less
  than 24 h ago. Under the group lock.
  """
  def rejoin_guard(%Group{} = g, ref) do
    case adding_op(g.id, ref) do
      nil ->
        :ok

      op ->
        {n, exhausted?} = rejoin_status(g, op)
        young? = DateTime.diff(DateTime.utc_now(), op.created_at) < 24 * 3600

        if not exhausted? and (n > 0 or young?),
          do: {:error, {:rejoin_pending, op.op_id, n}},
          else: :ok
    end
  end

  ## v1.21 §12.12.6: stale leaves

  @doc """
  The stale leaves of the given users in a conversation (groups and DMs): in the group, the
  device superseded (§12.1) and unseen (census or `PUT`) for `STALE_LEAF_HOURS`, and its user
  keeps at least one non-superseded leaf there.
  """
  def stale_leaves(conv, user_ids) do
    leaves =
      Repo.all(
        from gd in "mls_group_devices",
          where:
            gd.conversation_id == ^conv and gd.user_id in type(^user_ids, {:array, :binary_id}),
          select: {type(gd.user_id, :binary_id), type(gd.device_id, :binary_id)}
      )

    stale_among(leaves)
  end

  @doc false
  def stale_among([]), do: MapSet.new()

  def stale_among(leaves) do
    users = leaves |> Enum.map(&elem(&1, 0)) |> Enum.uniq()
    superseded = MLS.superseded_devices(users)
    cutoff = DateTime.add(DateTime.utc_now(), -stale_hours(), :hour)
    seen = last_seen(Enum.map(leaves, &elem(&1, 1)))

    live_users =
      for {u, _} = ref <- leaves, not MapSet.member?(superseded, ref), into: MapSet.new(), do: u

    for {u, d} = ref <- leaves,
        MapSet.member?(superseded, ref),
        MapSet.member?(live_users, u),
        unseen_since?(seen[d], cutoff),
        into: MapSet.new(),
        do: ref
  end

  defp unseen_since?(nil, _cutoff), do: true
  defp unseen_since?(t, cutoff), do: DateTime.compare(t, cutoff) == :lt

  @doc "`STALE_LEAF_HOURS` (default 24)."
  def stale_hours, do: Application.get_env(:risime, :stale_leaf_hours, 24)

  # The later of the census and the `PUT` last-seen times, per device id.
  defp last_seen(device_ids) do
    keys = for d <- device_ids, do: "device:" <> d

    census =
      Repo.all(
        from i in "app_instances",
          where: i.instance_key in ^keys,
          select: {i.instance_key, type(i.last_seen_at, :utc_datetime_usec)}
      )
      |> Map.new()

    put =
      Repo.all(
        from d in Device, where: d.device_id in ^device_ids, select: {d.device_id, d.last_seen_at}
      )
      |> Map.new()

    Map.new(device_ids, fn d ->
      {d, latest(census["device:" <> d], put[d])}
    end)
  end

  defp latest(nil, b), do: b
  defp latest(a, nil), do: a
  defp latest(a, b), do: if(DateTime.compare(a, b) == :gt, do: a, else: b)

  @doc """
  §12.12.6: makes sure a cleanup op removes `stale` (a user's stale leaves not yet removed by
  another pending op) in the group; at most one cleanup op per (group, user). Creates or widens
  it; `name: true` names a committer when it has none. Under the group lock. Returns
  `{:created | :widened | :unchanged, op}` or `{:none, nil}`.
  """
  def ensure_cleanup(%Group{} = g, user_id, stale, opts \\ []) do
    name? = Keyword.get(opts, :name, true)
    ops = list(g.id)

    taken =
      for %Op{type: "devices"} = op <- ops,
          not cleanup?(op),
          ref <- refs(op, "removed"),
          into: MapSet.new(),
          do: ref

    stale = stale |> Enum.reject(&MapSet.member?(taken, &1)) |> Enum.sort()
    existing = Enum.find(ops, &(cleanup?(&1) and affected_user(&1) == user_id))

    cond do
      stale == [] ->
        {if(existing, do: :unchanged, else: :none), existing}

      existing ->
        removed = Enum.uniq(refs(existing, "removed") ++ stale)

        if removed == refs(existing, "removed") do
          op =
            if name? and existing.committer_device == nil,
              do: name_next(g, existing),
              else: existing

          {:unchanged, op}
        else
          op =
            existing
            |> Ecto.Changeset.change(payload: put_refs(existing.payload, [], removed))
            |> Repo.update!()

          {:widened, if(name? and op.committer_device == nil, do: name_next(g, op), else: op)}
        end

      true ->
        now = DateTime.utc_now()

        op =
          Repo.insert!(%Op{
            op_id: Ecto.UUID.generate(),
            group_id: g.id,
            type: "devices",
            actor: user_id,
            payload: %{
              "user_ids" => [],
              "role" => nil,
              "added" => [],
              "removed" => Enum.map(stale, &ref_json/1),
              "cleanup" => true
            },
            created_at: now,
            naming: 0,
            tried: [],
            strikes: %{}
          })

        {:created, if(name?, do: name_next(g, op), else: op)}
    end
  end

  @doc "After a commit: `devices` ops whose changes have all landed are done."
  def settle_devices(%Group{} = g) do
    in_group = Groups.in_group(g.id)

    for %Op{type: "devices"} = op <- list(g.id) do
      added = Enum.reject(refs(op, "added"), &MapSet.member?(in_group, &1))
      removed = Enum.filter(refs(op, "removed"), &MapSet.member?(in_group, &1))
      rejoin? = Enum.any?(refs(op, "added"), &(&1 in refs(op, "removed")))

      # A rejoin (remove + re-add of one device) is settled only by the commit that completes it.
      if added == [] and removed == [] and not rejoin?, do: Repo.delete!(op)
    end

    :ok
  end
end
