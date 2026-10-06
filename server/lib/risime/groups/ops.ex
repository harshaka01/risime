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
  """
  import Ecto.Query

  alias RisiMe.{Groups, Messaging, Presence, Repo, TimeUUID}
  alias RisiMe.Groups.{Group, Member, Op}
  alias RisiMe.Workers.GroupTimer

  @committer_s 60
  @expiry_h 24

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

  @doc "Names the next candidate (or waits for one to come online)."
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

  # Devices that may commit this op, best first: for `devices`, the affected user's other
  # in-group devices; then admin devices. Online only, most recently seen first.
  defp online_candidates(g, op) do
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

    # MLS can't commit its own removal: a remove op never names the removed users' devices.
    (by_recency(own) ++ by_recency(admin_devices(g)))
    |> Enum.uniq()
    |> Enum.reject(fn {u, _d} -> u in leaving end)
    |> Enum.filter(fn {_u, d} -> Presence.device_online?(d) end)
  end

  # Leaves of active admins; after a reset (no MLS state), their current groups devices.
  defp admin_devices(g) do
    admins = Groups.admin_ids(g.id)

    if Groups.epoch(g.id) == nil,
      do: admins |> Groups.device_refs() |> Enum.to_list(),
      else: g.id |> Groups.in_group() |> Enum.filter(fn {u, _} -> u in admins end)
  end

  defp by_recency([]), do: []

  defp by_recency(refs) do
    keys = for {_u, d} <- refs, do: "device:" <> d

    seen =
      Repo.all(
        from i in "app_instances",
          where: i.instance_key in ^keys,
          select: {i.instance_key, i.last_seen_at}
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
      "devices" -> in_group? and (admin? or u == affected_user(op))
      "rebuild" -> admin? and epoch == nil and MapSet.member?(Groups.device_refs([u]), ref)
      "remove" -> admin? and in_group? and u not in op.payload["user_ids"]
      _ -> admin? and in_group?
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
             true <- authorised?(g, op, {user_id, device_id}) do
          assign(g, op, {user_id, device_id}, :append)
        end

        {:ok, nil}
      end)
    end

    :ok
  end

  ## Timers (Oban)

  @doc "The committer's 60 s ran out: name the next candidate unless superseded or done."
  def committer_timeout(op_id, naming) do
    with %Op{} = op <- Repo.get(Op, op_id) do
      Groups.locked(op.group_id, fn ->
        with %Op{naming: ^naming} = op <- Repo.get(Op, op_id),
             %Group{} = g <- Groups.get_group(op.group_id) do
          name_next(g, op)
        end

        {:ok, nil}
      end)
    end

    :ok
  end

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
