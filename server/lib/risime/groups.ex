defmodule RisiMe.Groups do
  @moduledoc """
  Groups with MLS (contract v1.9 §12, decision 041).

  The server knows membership, roles and pending operations only; names and icons live in the
  encrypted `group_meta`. A group is one MLS group (`grp:<uuid>#<generation>`) whose routing
  state reuses the v1.7 tables (`mls_groups`, `mls_group_devices`, `mls_commits`).

  Every mutation of a group runs in `locked/2`: a transaction holding the same per-conversation
  advisory lock as the commit path, so REST changes, commits, timers and resets are serialised
  per group and their inbox events are written in order.

  * `RisiMe.Groups.Ops`: pending ops, committer naming, expiry.
  * `RisiMe.Groups.Commit`: commit authorisation and routing, catch-up paging, reset.
  * `RisiMe.Groups.Policy`: the shared admin policy (`group_policy_cases.json`).
  """
  import Ecto.Query

  alias RisiMe.{Devices, Messaging, MLS, Repo, Social, TimeUUID}
  alias RisiMe.Accounts.User
  alias RisiMe.Devices.Device
  alias RisiMe.Groups.{Group, Member, Ops}

  @max_users 256
  @max_devices 768
  @census_days 30
  @creating_ttl_s 600

  def max_users, do: @max_users
  def max_devices, do: @max_devices

  ## Ids and locking

  @doc "True for a well-formed `grp:<lowercase uuid>`."
  def group_id?("grp:" <> u) when byte_size(u) == 36,
    do: Ecto.UUID.cast(u) == {:ok, u}

  def group_id?(_), do: false

  @doc """
  Runs `fun` in a transaction holding the group's advisory lock (shared with the commit path).
  `fun` returns `{:ok, value}` or `{:error, reason}` (which rolls back).
  """
  def locked(group_id, fun) do
    Repo.transaction(fn ->
      Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["mls:" <> group_id])

      case fun.() do
        {:ok, value} -> value
        {:error, reason} -> Repo.rollback(reason)
      end
    end)
  end

  ## Readiness (§12.1)

  @doc "Current MLS devices with the `groups` capability of the given users."
  def groups_devices([]), do: []

  def groups_devices(user_ids) do
    Repo.all(
      from d in Device,
        where:
          d.user_id in ^user_ids and not is_nil(d.mls_signature_key) and
            fragment("'groups' = ANY(?)", d.capabilities),
        order_by: [asc: d.inserted_at],
        select: %{
          user_id: d.user_id,
          device_id: d.device_id,
          attestation: d.mls_attestation,
          id: d.id
        }
    )
  end

  @doc "`{user_id, device_id}` refs of the users' current groups-capable devices."
  def device_refs(user_ids),
    do: user_ids |> groups_devices() |> MapSet.new(&{&1.user_id, &1.device_id})

  @doc """
  Group readiness (§12.1). Returns `{ready_user_ids, missing}`: a user is ready with at least one
  current MLS device with `groups`, and every other install of theirs that can still receive
  being groups-capable. `missing` reasons: `no_mls` (no current MLS device) or `legacy_app`.

  Only installs that can still receive count: a registered device of the user seen in the
  census (30 days), or a census instance without a `device_id` (a pre-v1.7 build) seen **after**
  the user's latest device registration. Such an instance seen before that registration is
  superseded and never blocks. A blocking device-less instance is listed as `legacy_app` with
  `device_id: nil`.
  """
  def readiness(user_ids) do
    user_ids = Enum.uniq(user_ids)
    since = DateTime.add(DateTime.utc_now(), -@census_days, :day)

    instances =
      Repo.all(
        from i in "app_instances",
          where: i.user_id in type(^user_ids, {:array, :binary_id}) and i.last_seen_at > ^since,
          order_by: [asc: i.last_seen_at],
          select:
            {type(i.user_id, :binary_id), type(i.device_id, :binary_id),
             type(i.last_seen_at, :utc_datetime_usec)}
      )

    registered =
      Repo.all(
        from d in RisiMe.Devices.Device,
          where: d.user_id in ^user_ids,
          select: {d.user_id, d.device_id, d.last_seen_at}
      )

    registered_ids = MapSet.new(registered, fn {u, d, _} -> {u, d} end)

    last_registration =
      Enum.reduce(registered, %{}, fn {u, _, t}, acc ->
        Map.update(acc, u, t, &if(DateTime.compare(t, &1) == :gt, do: t, else: &1))
      end)

    gdevs = groups_devices(user_ids)
    g_ids = MapSet.new(gdevs, & &1.device_id)
    mls = MLS.current_mls_devices(user_ids)

    # Registered installs seen recently that aren't groups-capable (e.g. a second phone on an
    # older build): these keep the user not-ready.
    stale =
      for {u, d, _} <- instances,
          d != nil and MapSet.member?(registered_ids, {u, d}) and not MapSet.member?(g_ids, d),
          do: %{user_id: u, device_id: d, reason: "legacy_app"}

    without =
      for u <- user_ids, not Enum.any?(gdevs, &(&1.user_id == u)) do
        case Enum.find(mls, &(&1.user_id == u)) do
          nil -> %{user_id: u, device_id: nil, reason: "no_mls"}
          d -> %{user_id: u, device_id: d.device_id, reason: "legacy_app"}
        end
      end

    # An old build still in use (seen after the latest registration) would miss group messages.
    legacy =
      for {u, nil, seen} <- instances,
          not superseded?(seen, last_registration[u]),
          uniq: true,
          do: %{user_id: u, device_id: nil, reason: "legacy_app"}

    missing = Enum.uniq_by(legacy ++ stale ++ without, &{&1.user_id, &1.device_id})
    not_ready = MapSet.new(missing, & &1.user_id)
    {user_ids |> Enum.reject(&MapSet.member?(not_ready, &1)) |> MapSet.new(), missing}
  end

  defp superseded?(_seen, nil), do: false
  defp superseded?(seen, registered_at), do: DateTime.compare(seen, registered_at) == :lt

  @doc "The subset of `user_ids` that is group-ready (`Friend.group_ready`)."
  def ready_set(user_ids) do
    if MLS.available?(), do: user_ids |> readiness() |> elem(0), else: MapSet.new()
  end

  ## Membership

  def get_group(id), do: if(group_id?(id), do: Repo.get(Group, id))

  def member(group_id, user_id), do: Repo.get_by(Member, group_id: group_id, user_id: user_id)

  def members(group_id),
    do:
      Repo.all(
        from m in Member,
          where: m.group_id == ^group_id,
          order_by: [asc: m.inserted_at, asc: m.user_id]
      )

  def active_member_ids(group_id),
    do:
      Repo.all(
        from m in Member,
          where: m.group_id == ^group_id and m.state == "active",
          select: m.user_id
      )

  @doc "Active admins' user ids."
  def admin_ids(group_id),
    do:
      Repo.all(
        from m in Member,
          where: m.group_id == ^group_id and m.state == "active" and m.role == "admin",
          select: m.user_id
      )

  @doc "Current epoch of the group's MLS state, or nil (creating, or reset and not rebuilt)."
  def epoch(group_id) do
    case MLS.group(group_id) do
      nil -> nil
      g -> g.epoch
    end
  end

  @doc "`{user_id, device_id}` leaves currently in the group."
  def in_group(group_id),
    do:
      MapSet.new(
        Repo.all(
          from gd in "mls_group_devices",
            where: gd.conversation_id == ^group_id,
            select: {type(gd.user_id, :binary_id), type(gd.device_id, :binary_id)}
        )
      )

  @doc """
  `{:ok, group, member}` when `user_id` is an active member who may see the group (a `creating`
  group is visible only to its creator); otherwise `{:error, :not_found}`.
  """
  def visible(user_id, id) do
    with %Group{} = g <- get_group(id),
         %Member{state: "active"} = m <- member(id, user_id),
         true <- g.state == "active" or g.created_by == user_id do
      {:ok, g, m}
    else
      _ -> {:error, :not_found}
    end
  end

  @doc "True if `user_id` is an active member of an active group (sends, typing, blobs)."
  def active_member?(group_id, user_id) do
    group_id?(group_id) and
      Repo.exists?(
        from m in Member,
          join: g in Group,
          on: g.id == m.group_id,
          where:
            m.group_id == ^group_id and m.user_id == type(^user_id, :binary_id) and
              m.state == "active" and g.state == "active"
      )
  end

  @doc "`{:ok, device_id}` for a groups-capable MLS device of the user, else `:invalid_device`."
  def caller_device(user_id, device_id) do
    with {:ok, d} <- cast_uuid(device_id),
         %Device{} = dev <- Repo.get_by(Device, user_id: user_id, device_id: d),
         true <- Devices.groups?(dev) do
      {:ok, d}
    else
      _ -> {:error, :invalid_device}
    end
  end

  ## Views (§12.2)

  @doc "`GET /groups`: my visible groups."
  def list(me) do
    Repo.all(
      from g in Group,
        join: m in Member,
        on: m.group_id == g.id,
        where:
          m.user_id == ^me and m.state == "active" and
            (g.state == "active" or g.created_by == ^me),
        order_by: [asc: g.created_at]
    )
    |> Enum.map(&group_json(&1, me))
  end

  @doc "`GET /groups/{id}`."
  def show(me, id) do
    with {:ok, g, _m} <- visible(me, id), do: {:ok, group_json(g, me)}
  end

  @doc "The `Group` object as seen by `viewer`."
  def group_json(%Group{} = g, viewer) do
    members = members(g.id)
    ids = Enum.map(members, & &1.user_id)
    users = users(ids)
    pairs = friend_pairs(ids)
    me = Enum.find(members, &(&1.user_id == viewer))

    %{
      id: g.id,
      state: g.state,
      created_by: g.created_by,
      created_at: Messaging.iso(g.created_at),
      generation: g.generation,
      epoch: epoch(g.id),
      my_role: (me && me.role) || "member",
      members: members_json(members, users, pairs, viewer),
      pending: g.id |> Ops.list() |> Enum.map(&Ops.json/1)
    }
  end

  @doc false
  def users(ids),
    do: Repo.all(from u in User, where: u.id in ^ids) |> Map.new(&{&1.id, &1})

  @doc false
  def members_json(members, users, pairs, viewer) do
    for m <- members, u = users[m.user_id], u != nil do
      %{
        user_id: m.user_id,
        display_name: u.display_name,
        phone: if(phone_visible?(pairs, viewer, m.user_id), do: u.phone),
        role: m.role,
        kind: m.kind,
        state: m.state,
        joined_at: m.joined_at && Messaging.iso(m.joined_at)
      }
    end
  end

  defp phone_visible?(_pairs, viewer, viewer), do: true

  defp phone_visible?(pairs, viewer, other),
    do: MapSet.member?(pairs, Enum.min_max([viewer, other]))

  @doc false
  # Friendships among the given users, as `{smaller_id, larger_id}` (one query).
  def friend_pairs(ids) do
    Repo.all(
      from f in "friendships",
        where:
          f.user_a in type(^ids, {:array, :binary_id}) and
            f.user_b in type(^ids, {:array, :binary_id}),
        select: {type(f.user_a, :binary_id), type(f.user_b, :binary_id)}
    )
    |> MapSet.new()
  end

  ## REST (§12.3)

  @doc "`POST /groups`. Returns `{:ok, :created | :existing, group_json}`."
  def create(me, device_id, params) do
    with true <- MLS.available?() || {:error, :mls_unavailable},
         {:ok, dev} <- caller_device(me, device_id),
         %{"client_group_id" => cgid, "member_ids" => ids} when is_list(ids) <- params,
         {:ok, cgid} <- cast_uuid(cgid),
         {:ok, ids} <- cast_ids(ids) do
      case Repo.get_by(Group, created_by: me, client_group_id: cgid) do
        %Group{} = g -> {:ok, :existing, group_json(g, me)}
        nil -> do_create(me, dev, cgid, Enum.uniq(ids) -- [me])
      end
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  defp do_create(me, _dev, cgid, others) do
    cond do
      others == [] ->
        {:error, :bad_request}

      length(others) > @max_users - 1 ->
        {:error, :too_many_members}

      not Enum.all?(others, &Social.friends?(me, &1)) ->
        {:error, :not_friends}

      true ->
        {ready, missing} = readiness(others)

        cond do
          MapSet.size(ready) != length(others) ->
            {:error, {:not_ready, missing}}

          length(groups_devices([me | others])) > @max_devices ->
            {:error, :too_many_devices}

          true ->
            insert_group(me, cgid, others)
        end
    end
  end

  defp insert_group(me, cgid, others) do
    now = DateTime.utc_now()
    id = "grp:" <> Ecto.UUID.generate()

    {:ok, result} =
      Repo.transaction(fn ->
        {n, _} =
          Repo.insert_all(
            Group,
            [
              %{
                id: id,
                created_by: me,
                client_group_id: cgid,
                state: "creating",
                generation: 1,
                created_at: now
              }
            ],
            on_conflict: :nothing,
            conflict_target: [:created_by, :client_group_id]
          )

        if n == 1 do
          rows =
            [member_row(id, me, "admin", "active", now, now)] ++
              for(u <- others, do: member_row(id, u, "member", "pending_add", nil, now))

          Repo.insert_all(Member, rows)

          # §12.3: a group still `creating` after 10 minutes is deleted.
          {:ok, _} =
            Oban.insert(
              RisiMe.Workers.GroupTimer.new(%{"kind" => "creating", "group_id" => id},
                schedule_in: @creating_ttl_s
              )
            )

          {:created, Repo.get!(Group, id)}
        else
          # A concurrent repeat of the same client_group_id won.
          {:existing, Repo.get_by!(Group, created_by: me, client_group_id: cgid)}
        end
      end)

    {kind, g} = result
    {:ok, kind, group_json(g, me)}
  end

  defp member_row(group_id, user_id, role, state, joined_at, now),
    do: %{
      group_id: group_id,
      user_id: user_id,
      role: role,
      kind: "user",
      state: state,
      joined_at: joined_at,
      inserted_at: now
    }

  @doc "`POST /groups/{id}/members` (admin)."
  def add_members(me, device_id, id, params) do
    with {:ok, dev} <- caller_device(me, device_id),
         %{"user_ids" => ids} when is_list(ids) and ids != [] <- params,
         {:ok, ids} <- cast_ids(ids) do
      locked(id, fn ->
        with {:ok, g, m} <- visible_active(me, id),
             :ok <- require_admin(m),
             {:ok, new} <- addable(g, me, Enum.uniq(ids) -- [me]) do
          if new != [] do
            now = DateTime.utc_now()

            Repo.insert_all(
              Member,
              for(u <- new, do: member_row(id, u, "member", "pending_add", nil, now))
            )

            Ops.create(g, "add", me, %{user_ids: new}, {me, dev})
          end

          {:ok, group_json(g, me)}
        end
      end)
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  defp addable(g, me, ids) do
    existing = g.id |> members() |> Map.new(&{&1.user_id, &1})
    new = Enum.reject(ids, &Map.has_key?(existing, &1))

    cond do
      new == [] ->
        {:ok, []}

      not Enum.all?(new, &Social.friends?(me, &1)) ->
        {:error, :not_friends}

      map_size(existing) + length(new) > @max_users ->
        {:error, :too_many_members}

      true ->
        {ready, missing} = readiness(new)

        pending =
          for {u, %{state: "pending_add"}} <- existing, do: u

        devices =
          MapSet.size(in_group(g.id)) + length(groups_devices(pending)) +
            length(groups_devices(new))

        cond do
          MapSet.size(ready) != length(new) -> {:error, {:not_ready, missing}}
          devices > @max_devices -> {:error, :too_many_devices}
          true -> {:ok, new}
        end
    end
  end

  @doc "`DELETE /groups/{id}/members/{user_id}` (admin). Removing yourself is a leave."
  def remove_member(me, device_id, id, target) do
    with {:ok, dev} <- caller_device(me, device_id),
         {:ok, target} <- cast_uuid(target) do
      if target == me do
        leave(me, device_id, id)
      else
        locked(id, fn -> do_remove(me, dev, id, target) end) |> ok_nil()
      end
    end
  end

  defp do_remove(me, dev, id, target) do
    with {:ok, g, m} <- visible_active(me, id),
         :ok <- require_admin(m) do
      case member(id, target) do
        nil ->
          {:ok, nil}

        %Member{state: "pending_remove"} ->
          {:ok, nil}

        %Member{state: "pending_add"} ->
          # Cancels a not-yet-committed add for this user.
          Ops.drop_user(g, target)
          delete_member(id, target)
          {:ok, nil}

        %Member{role: "admin"} when g.created_by != me ->
          {:error, :not_admin}

        %Member{} ->
          mark_removing(g, target)
          Ops.create(g, "remove", me, %{user_ids: [target]}, {me, dev})
          {:ok, nil}
      end
    end
  end

  @doc "`POST /groups/{id}/leave`."
  def leave(me, device_id, id) do
    with {:ok, _dev} <- caller_device(me, device_id) do
      locked(id, fn ->
        g = get_group(id)
        m = g && member(id, me)

        cond do
          m == nil ->
            {:error, :not_found}

          m.state == "pending_remove" ->
            {:ok, nil}

          m.state != "active" or g.state != "active" ->
            {:error, :not_found}

          m.role == "admin" and admin_ids(id) == [me] ->
            {:error, :last_admin}

          true ->
            mark_removing(g, me)
            Ops.create(g, "remove", me, %{user_ids: [me]}, nil)
            {:ok, nil}
        end
      end)
      |> ok_nil()
    end
  end

  # Removal is immediate on the server (§12.3): no more group events or sends.
  defp mark_removing(g, user_id) do
    Repo.update_all(
      from(m in Member, where: m.group_id == ^g.id and m.user_id == ^user_id),
      set: [state: "pending_remove"]
    )

    Ops.drop_user(g, user_id)
  end

  @doc false
  def delete_member(group_id, user_id),
    do:
      Repo.delete_all(from m in Member, where: m.group_id == ^group_id and m.user_id == ^user_id)

  @doc "`PATCH /groups/{id}/members/{user_id}` (admin)."
  def set_role(me, device_id, id, target, params) do
    with {:ok, dev} <- caller_device(me, device_id),
         {:ok, target} <- cast_uuid(target),
         %{"role" => role} when role in ["admin", "member"] <- params do
      locked(id, fn ->
        with {:ok, g, m} <- visible_active(me, id),
             :ok <- require_admin(m),
             %Member{state: "active"} = t <- member(id, target) || {:error, :not_found} do
          pending = Ops.role_op(g.id, target)

          cond do
            t.kind == "agent" and role == "admin" ->
              {:error, :invalid_role}

            pending && pending.payload["role"] == role ->
              {:ok, group_json(g, me)}

            pending == nil and t.role == role ->
              {:ok, group_json(g, me)}

            role == "member" and target != me and g.created_by != me ->
              {:error, :not_admin}

            role == "member" and target == me and admin_ids(id) == [me] ->
              {:error, :last_admin}

            true ->
              if pending, do: Repo.delete!(pending)
              Ops.create(g, "role", me, %{user_ids: [target], role: role}, {me, dev})
              {:ok, group_json(g, me)}
          end
        else
          %Member{} -> {:error, :not_found}
          e -> e
        end
      end)
    else
      %{"role" => _} -> {:error, :invalid_role}
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  @doc "`POST /groups/{id}/rejoin`: a `devices` op that removes and re-adds the calling device."
  def rejoin(me, device_id, id) do
    with {:ok, dev} <- caller_device(me, device_id) do
      locked(id, fn ->
        with {:ok, g, _m} <- visible_active(me, id) do
          ref = {me, dev}

          cond do
            epoch(id) == nil ->
              :ok

            Ops.rejoin_op(g.id, ref) != nil ->
              :ok

            true ->
              removed = if MapSet.member?(in_group(id), ref), do: [ref], else: []
              Ops.create(g, "devices", me, %{added: [ref], removed: removed}, :auto)
          end

          {:ok, group_json(g, me)}
        end
      end)
    end
  end

  ## Device churn (§12.4 `devices` ops)

  @doc """
  A user's groups-capable device was `:added`, `:removed` or `:replaced` (key change). Creates a
  `devices` op in each active group of the user where the leaf set has to change.
  """
  def device_changed(user_id, device_id, change) do
    group_ids =
      Repo.all(
        from m in Member,
          join: g in Group,
          on: g.id == m.group_id,
          where: m.user_id == ^user_id and m.state == "active" and g.state == "active",
          select: g.id
      )

    for id <- group_ids do
      locked(id, fn ->
        g = get_group(id)
        ref = {user_id, device_id}
        in? = MapSet.member?(in_group(id), ref)

        cond do
          g == nil or epoch(id) == nil ->
            :ok

          change == :added and not in? ->
            Ops.create(g, "devices", user_id, %{added: [ref]}, :auto)

          change == :removed ->
            Ops.forget_device(g, ref)
            if in?, do: Ops.create(g, "devices", user_id, %{removed: [ref]}, :auto)

          change == :replaced ->
            Ops.create(
              g,
              "devices",
              user_id,
              %{added: [ref], removed: if(in?, do: [ref], else: [])},
              :auto
            )

          true ->
            :ok
        end

        {:ok, nil}
      end)
    end

    :ok
  end

  ## Events (§12.7)

  @doc """
  Builds `group_event` inbox events for every active member plus `targets` and `extra` users.
  Options: `:epoch`, `:role`, `:members` (true = the full, per-viewer member list),
  `:rebuilder`, `:generation`, `:push_targets` (wake the targets by push). Returns
  `[{user_id, event, opts}]` for `RisiMe.Messaging.publish_batch/1`.
  """
  def group_events(%Group{} = g, action, actor, targets, opts \\ []) do
    recipients =
      Enum.uniq(active_member_ids(g.id) ++ targets ++ Keyword.get(opts, :extra, []))

    base = %{
      "group_id" => g.id,
      "generation" => Keyword.get(opts, :generation, g.generation),
      "epoch" => Keyword.get(opts, :epoch),
      "action" => action,
      "actor" => actor,
      "targets" => targets,
      "role" => Keyword.get(opts, :role),
      "members" => nil,
      "rebuilder" =>
        case Keyword.get(opts, :rebuilder) do
          {u, d} -> %{"user_id" => u, "device_id" => d}
          nil -> nil
        end,
      "server_ts" => Messaging.iso(DateTime.utc_now())
    }

    members_for =
      if Keyword.get(opts, :members) do
        members = g.id |> members() |> Enum.filter(&(&1.state == "active"))
        ids = Enum.map(members, & &1.user_id)
        users = users(ids)
        pairs = friend_pairs(ids)
        fn viewer -> members_json(members, users, pairs, viewer) end
      else
        fn _ -> nil end
      end

    push_targets = Keyword.get(opts, :push_targets, false)

    for r <- recipients do
      data = Map.put(base, "members", members_for.(r))

      {r, %{event_id: TimeUUID.generate(), kind: "group_event", data: data},
       [push: push_targets and r in targets]}
    end
  end

  ## Helpers

  defp visible_active(me, id) do
    case visible(me, id) do
      {:ok, %Group{state: "active"}, _} = ok -> ok
      {:ok, _, _} -> {:error, :bad_request}
      e -> e
    end
  end

  defp require_admin(%Member{role: "admin"}), do: :ok
  defp require_admin(_), do: {:error, :not_admin}

  defp ok_nil({:ok, _}), do: :ok
  defp ok_nil(e), do: e

  @doc false
  def cast_uuid(id) when is_binary(id) do
    case Ecto.UUID.cast(id) do
      {:ok, u} -> {:ok, u}
      :error -> {:error, :bad_request}
    end
  end

  def cast_uuid(_), do: {:error, :bad_request}

  @doc false
  def cast_ids(ids) do
    casted = for id <- ids, {:ok, c} <- [cast_uuid(id)], do: c
    if length(casted) == length(ids), do: {:ok, casted}, else: {:error, :bad_request}
  end
end
