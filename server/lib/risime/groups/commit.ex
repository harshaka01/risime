defmodule RisiMe.Groups.Commit do
  @moduledoc """
  Commits for `grp:` conversations (contract v1.9 §12.4, §12.7, §12.8).

  Inside the group lock: generation/epoch compare-and-set, the shared admin policy
  (`RisiMe.Groups.Policy`), pending-op matching, caps, then the commit log, the leaf set and
  every inbox event (`mls_commit`, `mls_welcome`, then the `group_event`) before the reply.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Blobs, Groups, Messaging, MLS, RateLimiter, Repo, TimeUUID}
  alias RisiMe.Groups.{Group, Member, Membership, Op, Ops, Policy}

  @inline_max 64 * 1024
  @commit_total_max 1024 * 1024
  @welcome_total_max 2 * 1024 * 1024
  @page_default 50
  @page_max 200

  ## Commit

  @doc "`POST /mls/groups/grp:…/commit`. Returns `{:ok, new_epoch}` or `{:error, reason}`."
  def commit(me, device_id, conv, params) do
    with true <- MLS.available?() || {:error, :mls_unavailable},
         true <- Groups.group_id?(conv) || {:error, :not_found},
         {:ok, dev} <- Groups.caller_device(me, device_id),
         {:ok, req} <- parse(params),
         :ok <- check_refs(me, conv, req),
         :ok <- limit(me) do
      Groups.locked(conv, fn -> locked_commit(me, dev, conv, req) end)
    end
  end

  defp limit(me) do
    case RateLimiter.hit(:mls_commit, me, 60, :timer.minutes(1)) do
      :ok -> :ok
      _ -> {:error, :rate_limited}
    end
  end

  defp parse(%{"generation" => g, "epoch" => e} = p)
       when is_integer(g) and g >= 1 and is_integer(e) and e >= 0 do
    with {:ok, commit, commit_ref} <- payload(p["commit"], p["commit_ref"], true),
         {:ok, welcome, welcome_ref} <- payload(p["welcome"], p["welcome_ref"], false),
         {:ok, added} <- device_refs(p["added"] || []),
         {:ok, removed} <- device_refs(p["removed"] || []),
         {:ok, op_id} <- op_id(p["op_id"]),
         meta when is_boolean(meta) <- Map.get(p, "meta_changed", false) || false,
         true <- added == [] == (welcome == nil and welcome_ref == nil) do
      {:ok,
       %{
         generation: g,
         epoch: e,
         commit: commit,
         commit_ref: commit_ref,
         welcome: welcome,
         welcome_ref: welcome_ref,
         added: added,
         removed: removed,
         op_id: op_id,
         meta_changed: meta
       }}
    else
      _ -> {:error, :bad_request}
    end
  end

  defp parse(_), do: {:error, :bad_request}

  # Exactly one of inline/ref for the commit (`required`), at most one for the Welcome.
  defp payload(nil, nil, true), do: :error
  defp payload(nil, nil, false), do: {:ok, nil, nil}

  defp payload(b64, nil, _) when is_binary(b64) do
    case Base.decode64(b64) do
      {:ok, bin} when byte_size(bin) in 1..@inline_max -> {:ok, bin, nil}
      _ -> :error
    end
  end

  defp payload(nil, %{"blob_id" => id, "size" => size, "sha256" => sha}, _)
       when is_binary(id) and is_integer(size) and size > 0 and is_binary(sha) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> {:ok, nil, %{"blob_id" => id, "size" => size, "sha256" => sha}}
      :error -> :error
    end
  end

  defp payload(_, _, _), do: :error

  defp op_id(nil), do: {:ok, nil}
  defp op_id(id), do: Groups.cast_uuid(id)

  defp device_refs(list) when is_list(list) do
    refs =
      for %{"user_id" => u, "device_id" => d} <- list,
          {:ok, u} <- [Ecto.UUID.cast(u)],
          {:ok, d} <- [Ecto.UUID.cast(d)],
          do: {u, d}

    if length(refs) == length(list), do: {:ok, Enum.uniq(refs)}, else: :error
  end

  defp device_refs(_), do: :error

  # §12.6: a commit may reference only blobs the caller uploaded for this conversation.
  defp check_refs(me, conv, req) do
    ok? =
      (req.commit_ref == nil or Blobs.ref_ok?(me, conv, req.commit_ref, @commit_total_max)) and
        (req.welcome_ref == nil or Blobs.ref_ok?(me, conv, req.welcome_ref, @welcome_total_max))

    if ok?, do: :ok, else: {:error, :bad_request}
  end

  defp locked_commit(me, dev, conv, req) do
    g = Groups.get_group(conv)
    m = g && Groups.member(conv, me)
    mls = g && MLS.group(conv)
    epoch = mls && mls.epoch

    cond do
      m == nil or m.state != "active" or (g.state == "creating" and g.created_by != me) ->
        {:error, :not_found}

      req.generation != g.generation ->
        {:error, {:epoch_conflict, epoch || 0}}

      epoch == nil and req.epoch != 0 ->
        {:error, {:epoch_conflict, 0}}

      epoch != nil and req.epoch != epoch ->
        {:error, {:epoch_conflict, epoch}}

      epoch == nil and g.state == "creating" ->
        create_commit(me, dev, g, req)

      epoch == nil ->
        rebuild_commit(me, dev, g, req)

      true ->
        normal_commit(me, dev, g, req, epoch)
    end
  end

  # Epoch 0 of a new group: all groups-capable devices of every member (§12.3).
  defp create_commit(me, dev, g, req) do
    member_ids = g.id |> Groups.members() |> Enum.map(& &1.user_id)
    others = member_ids -- [me]
    {ready, missing} = Groups.readiness(others)
    devices = Groups.device_refs(member_ids)

    cond do
      MapSet.size(ready) != length(others) ->
        {:error, {:not_ready, missing}}

      MapSet.size(devices) > Groups.max_devices() ->
        {:error, :too_many_devices}

      not MapSet.member?(devices, {me, dev}) or req.removed != [] or
          MapSet.new(req.added) != MapSet.delete(devices, {me, dev}) ->
        {:error, :bad_request}

      true ->
        now = DateTime.utc_now()
        start_group(g, devices, now)

        Repo.update_all(from(x in Group, where: x.id == ^g.id), set: [state: "active"])

        Repo.update_all(from(x in Member, where: x.group_id == ^g.id),
          set: [state: "active", joined_at: now]
        )

        Membership.open(g.id, member_ids, now)

        g = %{g | state: "active"}

        events =
          route(g, dev, req, member_ids) ++
            Groups.group_events(g, "created", me, others,
              epoch: 1,
              members: true,
              push_targets: true
            )

        Messaging.publish_batch(events)
        {:ok, 1}
    end
  end

  # Epoch 0 of a new generation after a reset: completes the `rebuild` op (§12.8).
  defp rebuild_commit(me, dev, g, req) do
    op = req.op_id && Ops.get(g.id, req.op_id)
    members = Groups.active_member_ids(g.id)
    devices = Groups.device_refs(members)

    cond do
      op == nil or op.type != "rebuild" ->
        {:error, :bad_request}

      me not in Groups.admin_ids(g.id) ->
        {:error, :not_admin}

      MapSet.size(devices) > Groups.max_devices() ->
        {:error, :too_many_devices}

      not MapSet.member?(devices, {me, dev}) or req.removed != [] or
          MapSet.new(req.added) != MapSet.delete(devices, {me, dev}) ->
        {:error, :bad_request}

      true ->
        start_group(g, devices, DateTime.utc_now())
        Repo.delete!(op)
        Messaging.publish_batch(route(g, dev, req, members))
        {:ok, 1}
    end
  end

  defp start_group(g, devices, now) do
    Repo.insert_all("mls_groups", [
      %{
        conversation_id: g.id,
        generation: g.generation,
        epoch: 1,
        e2ee_since: now,
        updated_at: now
      }
    ])

    MLS.set_group_devices(g.id, Enum.to_list(devices), [])
  end

  defp normal_commit(me, dev, g, req, epoch) do
    in_group = Groups.in_group(g.id)
    members = Groups.members(g.id)
    active = for m <- members, m.state == "active", do: m.user_id
    admins = for m <- members, m.state == "active", m.role == "admin", do: m.user_id
    agents = for m <- members, m.kind == "agent", do: m.user_id
    op = req.op_id && Ops.get(g.id, req.op_id)

    meta =
      cond do
        op && op.type == "role" -> %{admins: new_admins(admins, op)}
        req.meta_changed -> %{name_changed: true, admins: nil}
        true -> nil
      end

    policy = %{
      admins: admins,
      agents: agents,
      committer: me,
      adds: req.added,
      removes: req.removed,
      leaf_users: in_group |> Enum.map(&elem(&1, 0)) |> Enum.uniq(),
      meta: meta
    }

    size = MapSet.size(in_group) - length(req.removed) + length(req.added)
    ctx = %{g: g, me: me, admins: admins, active: active, in_group: in_group, members: members}

    with true <- MapSet.member?(in_group, {me, dev}) || {:error, :bad_request},
         true <- (req.op_id == nil or op != nil) || {:error, :bad_request},
         :ok <- Policy.check(policy),
         :ok <- member_needs_op(me in admins, Policy.others?(policy), op),
         {:ok, role} <- match_op(op, ctx, req),
         true <- size <= Groups.max_devices() || {:error, :too_many_devices} do
      if op && op.type == "devices",
        do: Logger.info("group commit: op=devices committer_role=#{role}")

      apply_commit(me, dev, g, req, epoch, op, active)
    end
  end

  # v1.14 §12.4a: a non-admin's commit on another user's leaves must name its pending `devices`
  # op (no list-only matching for this case).
  defp member_needs_op(false, true, %Op{type: "devices"}), do: :ok
  defp member_needs_op(false, true, _op), do: {:error, :not_admin}
  defp member_needs_op(_admin?, _others?, _op), do: :ok

  defp new_admins(admins, op) do
    [u] = op.payload["user_ids"]
    if op.payload["role"] == "admin", do: Enum.uniq(admins ++ [u]), else: admins -- [u]
  end

  # Server-only checks (§12.4): the declared lists against the op, or the §10.2 rules. Returns
  # `{:ok, committer_role}` (`admin`, `own`, `member`; for the log only).
  defp match_op(nil, %{me: me, admins: admins, active: active, in_group: in_group}, req) do
    current = Groups.device_refs(active)

    added_ok =
      Enum.all?(req.added, &(MapSet.member?(current, &1) and not MapSet.member?(in_group, &1)))

    removed_ok =
      Enum.all?(req.removed, fn {u, _} = ref ->
        MapSet.member?(in_group, ref) and (u == me or not MapSet.member?(current, ref))
      end)

    if added_ok and removed_ok,
      do: {:ok, if(me in admins, do: "admin", else: "own")},
      else: {:error, :bad_request}
  end

  defp match_op(%Op{type: type} = op, ctx, req) do
    %{g: g, me: me, admins: admins, in_group: in_group, members: members} = ctx
    users = op.payload["user_ids"]
    admin? = me in admins

    case type do
      "add" ->
        pending = for m <- members, m.user_id in users, m.state == "pending_add", do: m.user_id
        expect(admin?, req.removed == [] and MapSet.new(req.added) == Groups.device_refs(pending))

      "remove" ->
        out = MapSet.filter(in_group, fn {u, _} -> u in users end)
        # MLS can't commit its own removal (a leaving admin's devices never complete it).
        expect(admin?, me not in users and req.added == [] and MapSet.new(req.removed) == out)

      "role" ->
        expect(admin?, req.added == [] and req.removed == [])

      "devices" ->
        own? = me == Ops.affected_user(op)

        # v1.14 §12.4a: any active (non-agent) member, for a member-committable op with the
        # `member_devices` gate open.
        member? =
          not admin? and not own? and
            Enum.any?(members, &(&1.user_id == me and &1.state == "active" and &1.kind == "user")) and
            Ops.member_path?(g, op, in_group)

        role =
          cond do
            own? -> "own"
            admin? -> "admin"
            true -> "member"
          end

        expect(
          admin? or own? or member?,
          MapSet.new(req.added) == MapSet.new(Ops.refs(op, "added")) and
            MapSet.new(req.removed) == MapSet.new(Ops.removed_now(op, in_group)),
          role
        )

      _ ->
        {:error, :bad_request}
    end
  end

  defp expect(ok?, match?, role \\ "admin")
  defp expect(false, _, _role), do: {:error, :not_admin}
  defp expect(true, true, role), do: {:ok, role}
  defp expect(true, false, _role), do: {:error, :bad_request}

  defp apply_commit(me, dev, g, req, epoch, op, active_before) do
    new_epoch = epoch + 1

    {1, _} =
      Repo.update_all(
        from(x in "mls_groups", where: x.conversation_id == ^g.id and x.epoch == ^epoch),
        set: [epoch: new_epoch, updated_at: DateTime.utc_now()]
      )

    in_before = Groups.in_group(g.id)
    MLS.set_group_devices(g.id, req.added, req.removed)
    removed_users = req.removed |> Enum.map(&elem(&1, 0)) |> Enum.uniq()
    route_events = route(g, dev, req, Enum.uniq(active_before ++ removed_users))

    op = op || devices_op_for(g, req, in_before)
    group_events = complete(g, me, op, req, new_epoch, removed_users)
    if op, do: Repo.delete!(op)
    Ops.settle_devices(g)

    Messaging.publish_batch(route_events ++ group_events)
    {:ok, new_epoch}
  end

  # An op-less commit completes the `devices` op whose lists it matches (§12.4).
  # `in_group` is the leaf set before the commit (a rejoining device's old leaf may be gone).
  defp devices_op_for(g, %{added: added, removed: removed}, in_group)
       when added != [] or removed != [] do
    g.id
    |> Ops.list()
    |> Enum.find(fn op ->
      op.type == "devices" and MapSet.new(Ops.refs(op, "added")) == MapSet.new(added) and
        MapSet.new(Ops.removed_now(op, in_group)) == MapSet.new(removed)
    end)
  end

  defp devices_op_for(_g, _req, _in_group), do: nil

  # Membership effects of the completed op and its `group_event` (after the mls_* events).
  defp complete(g, me, op, req, new_epoch, removed_users) do
    case op do
      %Op{type: "add"} ->
        users = op.payload["user_ids"]
        now = DateTime.utc_now()

        {_, joined} =
          Repo.update_all(
            from(m in Member,
              where: m.group_id == ^g.id and m.user_id in ^users and m.state == "pending_add",
              select: m.user_id
            ),
            set: [state: "active", joined_at: now]
          )

        Membership.open(g.id, joined, now)

        Groups.group_events(g, "added", op.actor, joined, epoch: new_epoch, push_targets: true)

      %Op{type: "remove"} ->
        users = op.payload["user_ids"]

        for u <- users, do: Groups.delete_member(g.id, u)

        action = if users == [op.actor], do: "left", else: "removed"

        Groups.group_events(g, action, op.actor, users,
          epoch: new_epoch,
          extra: removed_users
        )

      %Op{type: "role"} ->
        [u] = op.payload["user_ids"]
        role = op.payload["role"]

        Repo.update_all(from(m in Member, where: m.group_id == ^g.id and m.user_id == ^u),
          set: [role: role]
        )

        Groups.group_events(g, "role_changed", op.actor, [u], epoch: new_epoch, role: role)

      nil when req.meta_changed ->
        Groups.group_events(g, "metadata_changed", me, [], epoch: new_epoch)

      _ ->
        []
    end
  end

  # The commit log entry plus `mls_commit` to every given user and `mls_welcome` to the users of
  # the added devices (§10.3, §12.7). Returns the events to publish.
  defp route(g, dev, req, commit_users) do
    now = DateTime.utc_now()

    Repo.insert_all("mls_commits", [
      %{
        conversation_id: g.id,
        generation: g.generation,
        epoch: req.epoch,
        commit: req.commit,
        commit_ref: req.commit_ref,
        from_device: Ecto.UUID.dump!(dev),
        inserted_at: now
      }
    ])

    MLS.prune_commit_log(g.id)

    commit_data = %{
      "conversation_id" => g.id,
      "generation" => g.generation,
      "epoch" => req.epoch,
      "commit" => req.commit && Base.encode64(req.commit),
      "commit_ref" => req.commit_ref,
      "from_device" => dev
    }

    commits =
      for u <- commit_users,
          do:
            {u, %{event_id: TimeUUID.generate(), kind: "mls_commit", data: commit_data},
             [push: false]}

    welcomes =
      if req.added != [] do
        if req.welcome_ref,
          do: Blobs.grant(req.welcome_ref["blob_id"], Enum.map(req.added, &elem(&1, 0))),
          else: :ok

        welcome = req.welcome && Base.encode64(req.welcome)

        for {u, refs} <- Enum.group_by(req.added, &elem(&1, 0), &elem(&1, 1)) do
          {u,
           %{
             event_id: TimeUUID.generate(),
             kind: "mls_welcome",
             data: %{
               "conversation_id" => g.id,
               "generation" => g.generation,
               "epoch" => req.epoch + 1,
               "welcome" => welcome,
               "welcome_ref" => req.welcome_ref,
               "to_devices" => refs
             }
           }, [push: false]}
        end
      else
        []
      end

    commits ++ welcomes
  end

  ## Catch-up (§12.8)

  @doc """
  `GET …/commits?since_epoch=e&limit=n` for a group member: `{:ok, commits, has_more}`, or
  `{:error, :log_expired}` when `since_epoch` is older than the retained log.
  """
  def commits_since(me, conv, since, limit) do
    limit = page(limit)

    with {:ok, g, _m} <- Groups.visible(me, conv) do
      epoch = Groups.epoch(conv)
      since = if is_integer(since) and since >= 0, do: since, else: 0

      oldest =
        Repo.one(
          from c in "mls_commits",
            where: c.conversation_id == ^conv and c.generation == ^g.generation,
            select: min(c.epoch)
        )

      if epoch != nil and since < epoch and (oldest == nil or since < oldest) do
        {:error, :log_expired}
      else
        rows =
          Repo.all(
            from c in "mls_commits",
              where:
                c.conversation_id == ^conv and c.generation == ^g.generation and
                  c.epoch >= ^since,
              order_by: [asc: c.epoch],
              limit: ^(limit + 1),
              select: %{
                epoch: c.epoch,
                commit: c.commit,
                commit_ref: c.commit_ref,
                from_device: type(c.from_device, :binary_id)
              }
          )

        {:ok,
         rows
         |> Enum.take(limit)
         |> Enum.map(&%{&1 | commit: &1.commit && Base.encode64(&1.commit)}),
         length(rows) > limit}
      end
    end
  end

  @doc false
  def page(limit) when is_integer(limit) and limit > 0, do: min(limit, @page_max)
  def page(_), do: @page_default

  ## Reset (§12.8)

  @doc "`POST /mls/groups/grp:…/reset` (admins). Returns `{:ok, new_generation}`."
  def reset(me, device_id, conv, params) do
    with true <- MLS.available?() || {:error, :mls_unavailable},
         {:ok, dev} <- Groups.caller_device(me, device_id),
         %{"generation" => gen} when is_integer(gen) <- params do
      Groups.locked(conv, fn ->
        with {:ok, g, m} <- Groups.visible(me, conv),
             true <- g.state == "active" || {:error, :not_found},
             true <- m.role == "admin" || {:error, :not_admin},
             true <- gen == g.generation || {:error, {:generation_conflict, g.generation}},
             :ok <- reset_limit(conv) do
          do_reset(me, dev, g)
        end
      end)
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  defp reset_limit(conv) do
    case RateLimiter.hit(:group_reset, conv, 1, :timer.hours(1)) do
      :ok -> :ok
      _ -> {:error, :rate_limited}
    end
  end

  defp do_reset(me, dev, g) do
    new_gen = g.generation + 1

    Repo.update_all(from(x in Group, where: x.id == ^g.id), set: [generation: new_gen])
    Repo.delete_all(from x in "mls_groups", where: x.conversation_id == ^g.id)
    Repo.delete_all(from x in "mls_group_devices", where: x.conversation_id == ^g.id)

    # add/role ops are dropped (their pending_add members too), remove ops finish (those users
    # are out), devices ops are dropped.
    {_, gone} =
      Repo.delete_all(
        from m in Member,
          where: m.group_id == ^g.id and m.state in ["pending_add", "pending_remove"],
          select: m.user_id
      )

    Membership.close(g.id, gone)

    # v1.11 §14.5: a reset expires every blob of the group (the new generation starts empty).
    Blobs.expire_conversation(g.id)
    # v1.15 §17.4: the old generation's history requests can't be authenticated any more.
    RisiMe.History.conversation_reset(g.id)

    Repo.delete_all(from o in Op, where: o.group_id == ^g.id)

    g = %{g | generation: new_gen}

    Groups.group_events(g, "reset", me, [], members: true, rebuilder: {me, dev}, epoch: nil)
    |> Messaging.publish_batch()

    Ops.create(g, "rebuild", me, %{}, {me, dev})
    {:ok, new_gen}
  end
end
