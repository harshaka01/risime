defmodule RisiMe.MLS do
  @moduledoc """
  E2EE routing state (contract v1.7 §10, decision 034). The server never sees content: it
  attests device keys, stores key packages, orders commits (epoch compare-and-set) and fans
  out opaque MLS messages through the per-user inbox.

  Everything here answers `{:error, :mls_unavailable}` while no attestation key is loaded.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.{Messaging, RateLimiter, Repo, Social}
  alias RisiMe.Devices.Device
  alias RisiMe.MLS.Attestation

  @kp_max_per_request 100
  @kp_max_stored 100
  @kp_max_bytes 4096
  @kp_low 20
  @census_window_days 30
  @commit_max 64 * 1024
  @welcome_max 256 * 1024
  @commit_log_keep 1000
  @commit_log_days 30

  def available?, do: Attestation.available?()

  ## Conversations

  @doc "The two member user ids of a DM conversation id, or :error."
  def members("dm:" <> rest) do
    with [a, b] <- String.split(rest, "_"),
         {:ok, a} <- Ecto.UUID.cast(a),
         {:ok, b} <- Ecto.UUID.cast(b),
         true <- a < b,
         true <- "dm:" <> rest == Messaging.conversation_id(a, b) do
      {:ok, [a, b]}
    else
      _ -> :error
    end
  end

  def members(_), do: :error

  @doc "The group row, or nil."
  def group(conversation_id),
    do:
      Repo.one(
        from g in "mls_groups",
          where: g.conversation_id == ^conversation_id,
          select: %{generation: g.generation, epoch: g.epoch, e2ee_since: g.e2ee_since}
      )

  @doc "True once a conversation is end-to-end encrypted (never reverts)."
  def e2ee?(conversation_id), do: group(conversation_id) != nil

  ## Census (§10.1)

  @doc """
  Records an app instance seen on the socket. `device_id` nil = a pre-v1.7 app, counted as one
  legacy instance per `legacy_key` (the dev token id, or "jwt" per user).
  """
  def record_instance(user_id, device_id, legacy_key, app_version) do
    _ = census(user_id, device_id, legacy_key, app_version)
    :ok
  end

  @doc """
  `record_instance/4`, returning the instance's `first_seen_at` (v1.10 §13.2, `history_before`): set when the row is
  inserted, or on the first connect after the device was removed; never moved otherwise. Rows
  from before v1.10 keep null. Always nil without a `device_id`.
  """
  def census(user_id, device_id, legacy_key, app_version) do
    key = if device_id, do: "device:" <> device_id, else: "legacy:" <> legacy_key

    version =
      if is_binary(app_version) and byte_size(app_version) <= 64, do: app_version, else: nil

    # The app clock, the same clock as `server_ts`.
    now = DateTime.utc_now()

    {1, [%{first_seen_at: first_seen}]} =
      Repo.insert_all(
        "app_instances",
        [
          %{
            user_id: Ecto.UUID.dump!(user_id),
            instance_key: key,
            device_id: device_id && Ecto.UUID.dump!(device_id),
            app_version: version,
            last_seen_at: now,
            first_seen_at: now
          }
        ],
        on_conflict:
          from(i in "app_instances",
            update: [
              set: [
                app_version: fragment("EXCLUDED.app_version"),
                last_seen_at: fragment("EXCLUDED.last_seen_at"),
                first_seen_at:
                  fragment(
                    "CASE WHEN ? THEN EXCLUDED.first_seen_at ELSE ? END",
                    i.history_reset,
                    i.first_seen_at
                  ),
                history_reset: false
              ]
            ]
          ),
        conflict_target: [:user_id, :instance_key],
        returning: [:first_seen_at]
      )

    if device_id && first_seen, do: to_utc(first_seen)
  end

  defp to_utc(%DateTime{} = dt), do: dt
  defp to_utc(%NaiveDateTime{} = n), do: DateTime.from_naive!(n, "Etc/UTC")

  @doc """
  v1.10 §13.2: the devices were removed (DELETE, logout, eviction, prune, a changed key), so
  each one's next connect sets a new `first_seen_at`. `devices` is `[{user_id, device_id}]`.
  """
  def reset_first_seen([]), do: :ok

  def reset_first_seen(devices) do
    for {user_id, device_id} <- Enum.uniq(devices) do
      Repo.update_all(
        from(i in "app_instances",
          where:
            i.user_id == type(^user_id, :binary_id) and
              i.instance_key == ^("device:" <> device_id)
        ),
        set: [history_reset: true]
      )
    end

    :ok
  end

  @doc """
  Readiness (§10.2): every app instance of both members seen in the last 30 days is
  MLS-capable, and each member has at least one MLS device. Returns `{ready?, missing}`.
  """
  def readiness(user_ids) do
    since = DateTime.add(DateTime.utc_now(), -@census_window_days, :day)

    instances =
      Repo.all(
        from i in "app_instances",
          where: i.user_id in type(^user_ids, {:array, :binary_id}) and i.last_seen_at > ^since,
          select: {type(i.user_id, :binary_id), type(i.device_id, :binary_id)}
      )

    mls = current_mls_devices(user_ids)
    mls_ids = MapSet.new(mls, & &1.device_id)

    missing =
      Enum.flat_map(instances, fn
        {u, nil} ->
          [%{user_id: u, device_id: nil, reason: "legacy_app"}]

        {u, d} ->
          if MapSet.member?(mls_ids, d),
            do: [],
            else: [%{user_id: u, device_id: d, reason: "no_mls"}]
      end)

    without_device =
      for u <- user_ids,
          not Enum.any?(mls, &(&1.user_id == u)),
          do: %{user_id: u, device_id: nil, reason: "no_mls"}

    missing = Enum.uniq(missing ++ without_device)
    {missing == [] and available?(), missing}
  end

  @doc "Current MLS devices (attested, not pruned) of the given users."
  def current_mls_devices(user_ids) do
    Repo.all(
      from d in Device,
        where: d.user_id in ^user_ids and not is_nil(d.mls_signature_key),
        select: %{
          user_id: d.user_id,
          device_id: d.device_id,
          attestation: d.mls_attestation,
          id: d.id
        }
    )
  end

  ## Key packages (§10.1)

  defp my_mls_device(user_id, device_id) do
    with {:ok, device_id} <- Ecto.UUID.cast(device_id),
         %Device{mls_signature_key: k} = d when is_binary(k) <-
           Repo.get_by(Device, user_id: user_id, device_id: device_id) do
      {:ok, d}
    else
      _ -> {:error, :invalid_device}
    end
  end

  @doc "Stores key packages for one of my MLS devices."
  def upload_key_packages(user_id, device_id, params) do
    with true <- available?() || {:error, :mls_unavailable},
         {:ok, device} <- my_mls_device(user_id, device_id),
         kps when is_list(kps) and length(kps) <= @kp_max_per_request <- params["key_packages"],
         {:ok, kps} <- decode_all(kps),
         {:ok, last_resort} <- decode_optional(params["last_resort"]) do
      now = DateTime.utc_now()

      Repo.transaction(fn ->
        # v1.9 §12.1: `replace: true` drops the stored normal packages first (they may lack
        # the group_meta capability).
        if params["replace"] == true,
          do:
            Repo.delete_all(
              from k in "mls_key_packages",
                where: k.device_ref == type(^device.id, :binary_id) and not k.last_resort
            )

        rows =
          for kp <- kps,
              do: %{
                device_ref: Ecto.UUID.dump!(device.id),
                key_package: kp,
                last_resort: false,
                inserted_at: now
              }

        Repo.insert_all("mls_key_packages", rows)

        if last_resort do
          Repo.delete_all(
            from k in "mls_key_packages",
              where: k.device_ref == type(^device.id, :binary_id) and k.last_resort
          )

          Repo.insert_all("mls_key_packages", [
            %{
              device_ref: Ecto.UUID.dump!(device.id),
              key_package: last_resort,
              last_resort: true,
              inserted_at: now
            }
          ])
        end

        # Keep the newest 100 normal packages.
        keep =
          from k in "mls_key_packages",
            where: k.device_ref == type(^device.id, :binary_id) and not k.last_resort,
            order_by: [desc: k.id],
            limit: @kp_max_stored,
            select: k.id

        Repo.delete_all(
          from k in "mls_key_packages",
            where:
              k.device_ref == type(^device.id, :binary_id) and not k.last_resort and
                k.id not in subquery(keep)
        )
      end)

      :ok
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  defp decode_all(list) do
    Enum.reduce_while(list, {:ok, []}, fn kp, {:ok, acc} ->
      case decode_optional(kp) do
        {:ok, bin} when is_binary(bin) -> {:cont, {:ok, [bin | acc]}}
        _ -> {:halt, {:error, :bad_request}}
      end
    end)
    |> case do
      {:ok, l} -> {:ok, Enum.reverse(l)}
      e -> e
    end
  end

  defp decode_optional(nil), do: {:ok, nil}

  defp decode_optional(b64) when is_binary(b64) do
    case Base.decode64(b64) do
      {:ok, bin} when byte_size(bin) > 0 and byte_size(bin) <= @kp_max_bytes -> {:ok, bin}
      _ -> {:error, :bad_request}
    end
  end

  defp decode_optional(_), do: {:error, :bad_request}

  @doc "Number of normal (consumable) key packages of one of my devices."
  def key_package_count(user_id, device_id) do
    with true <- available?() || {:error, :mls_unavailable},
         {:ok, device} <- my_mls_device(user_id, device_id) do
      {:ok, count_packages(device.id)}
    end
  end

  defp count_packages(device_ref),
    do:
      Repo.aggregate(
        from(k in "mls_key_packages",
          where: k.device_ref == type(^device_ref, :binary_id) and not k.last_resort
        ),
        :count
      )

  @doc """
  Atomic, all-or-nothing claim for friends and myself (§10.2). `caller_device_id` (optional) is
  excluded from my own devices.
  """
  def claim(me, user_ids, caller_device_id, conversation_id \\ nil)

  def claim(me, user_ids, caller_device_id, nil) do
    with true <- available?() || {:error, :mls_unavailable},
         ids when is_list(ids) and ids != [] and length(ids) <= 50 <- user_ids,
         {:ok, ids} <- cast_ids(ids),
         true <- Enum.all?(ids, &(&1 == me or Social.friends?(me, &1))) || {:error, :not_friends},
         :ok <- claim_limit(me) do
      finish_claim(me, ids, caller_device_id, :mls)
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  # v1.9 §12.5: co-members of a group (active or pending_add, or myself), groups devices only.
  def claim(me, user_ids, caller_device_id, conv) do
    with true <- available?() || {:error, :mls_unavailable},
         ids when is_list(ids) and ids != [] and length(ids) <= 255 <- user_ids,
         {:ok, ids} <- cast_ids(ids),
         true <- claimable?(me, conv, ids) || {:error, :not_member},
         :ok <- claim_limit(me) do
      finish_claim(me, ids, caller_device_id, :groups)
    else
      {:error, _} = e -> e
      _ -> {:error, :bad_request}
    end
  end

  defp claimable?(me, conv, ids) do
    case RisiMe.Groups.visible(me, conv) do
      {:ok, _g, _m} ->
        states =
          conv
          |> RisiMe.Groups.members()
          |> Map.new(&{&1.user_id, &1.state})

        Enum.all?(ids, &(&1 == me or states[&1] in ["active", "pending_add"]))

      _ ->
        false
    end
  end

  defp finish_claim(me, ids, caller_device_id, kind) do
    {:ok, {devices, low}} =
      Repo.transaction(fn -> do_claim(me, ids, caller_device_id, kind) end)

    for {user_id, count} <- low,
        do: Messaging.signal(user_id, %{kind: "mls_key_packages_low", data: %{"count" => count}})

    {:ok, devices}
  end

  defp claim_limit(me) do
    case RateLimiter.hit(:mls_claim, me, 30, :timer.minutes(1)) do
      :ok -> :ok
      _ -> {:error, :rate_limited}
    end
  end

  defp cast_ids(ids) do
    casted = for id <- ids, {:ok, c} <- [Ecto.UUID.cast(id)], do: c
    if length(casted) == length(ids), do: {:ok, Enum.uniq(casted)}, else: {:error, :bad_request}
  end

  defp do_claim(me, ids, caller_device_id, kind) do
    since = DateTime.add(DateTime.utc_now(), -@census_window_days, :day)

    mls =
      if kind == :groups, do: RisiMe.Groups.groups_devices(ids), else: current_mls_devices(ids)

    claimed =
      for d <- mls, not (d.user_id == me and d.device_id == caller_device_id) do
        kp = take_key_package(d.id)

        %{
          user_id: d.user_id,
          device_id: d.device_id,
          mls: true,
          attestation: d.attestation,
          key_package: kp && Base.encode64(kp),
          device_ref: d.id
        }
      end

    # Non-MLS app instances (legacy apps, devices without MLS) are listed so the caller knows
    # the conversation can't be E2EE yet.
    mls_ids = MapSet.new(mls, & &1.device_id)

    others =
      if(kind == :groups,
        do: [],
        else:
          Repo.all(
            from i in "app_instances",
              where: i.user_id in type(^ids, {:array, :binary_id}) and i.last_seen_at > ^since,
              select: {type(i.user_id, :binary_id), type(i.device_id, :binary_id)}
          )
          |> Enum.reject(fn {_u, d} -> d && MapSet.member?(mls_ids, d) end)
          |> Enum.uniq()
          |> Enum.map(fn {u, d} ->
            %{user_id: u, device_id: d, mls: false, attestation: nil, key_package: nil}
          end)
      )

    low =
      for c <- claimed,
          c.key_package != nil,
          n = count_packages(c.device_ref),
          n < @kp_low,
          uniq: true,
          do: {c.user_id, n}

    {Enum.map(claimed, &Map.delete(&1, :device_ref)) ++ others, low}
  end

  # Consumes one normal key package; falls back to the last-resort one (never consumed).
  defp take_key_package(device_ref) do
    %{rows: rows} =
      Repo.query!(
        """
        DELETE FROM mls_key_packages WHERE id = (
          SELECT id FROM mls_key_packages
          WHERE device_ref = $1 AND NOT last_resort ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
        ) RETURNING key_package
        """,
        [Ecto.UUID.dump!(device_ref)]
      )

    case rows do
      [[kp]] ->
        kp

      [] ->
        Repo.one(
          from k in "mls_key_packages",
            where: k.device_ref == type(^device_ref, :binary_id) and k.last_resort,
            select: k.key_package
        )
    end
  end

  ## Groups (§10.2)

  @doc "`GET /mls/groups/{conversation_id}` for a member."
  def group_view(me, "grp:" <> _ = conv) do
    alias RisiMe.Groups

    with {:ok, g, _m} <- Groups.visible(me, conv) do
      {_ready, missing} = conv |> Groups.active_member_ids() |> Groups.readiness()

      {:ok,
       %{
         e2ee: true,
         generation: g.generation,
         epoch: Groups.epoch(conv),
         ready: missing == [] and available?(),
         missing: missing,
         devices:
           conv
           |> Groups.in_group()
           |> Enum.map(fn {u, d} -> %{user_id: u, device_id: d} end)
       }}
    end
  end

  def group_view(me, conversation_id) do
    with {:ok, members} <- members(conversation_id),
         true <- me in members || {:error, :not_found} do
      group = group(conversation_id)
      {ready, missing} = readiness(members)

      devices =
        if group,
          do:
            Repo.all(
              from gd in "mls_group_devices",
                where: gd.conversation_id == ^conversation_id,
                select: %{
                  user_id: type(gd.user_id, :binary_id),
                  device_id: type(gd.device_id, :binary_id)
                }
            ),
          else: []

      {:ok,
       %{
         e2ee: group != nil,
         generation: if(group, do: group.generation, else: 1),
         epoch: group && group.epoch,
         ready: ready,
         missing: missing,
         devices: devices
       }}
    else
      :error -> {:error, :not_found}
      e -> e
    end
  end

  @doc """
  `POST /mls/groups/{conversation_id}/commit` (§10.2): validation, then the epoch
  compare-and-set, the commit log and every inbox event inside one per-conversation critical
  section (an advisory lock), before the reply.
  """
  def commit(me, caller_device, "grp:" <> _ = conversation_id, params),
    do: RisiMe.Groups.Commit.commit(me, caller_device, conversation_id, params)

  def commit(me, caller_device, conversation_id, params) do
    with true <- available?() || {:error, :mls_unavailable},
         {:ok, members} <- members(conversation_id),
         true <- me in members || {:error, :not_found},
         {:ok, caller_device} <- my_mls_device(me, caller_device),
         {:ok, req} <- parse_commit(params),
         [a, b] = members,
         true <-
           (Social.friends?(a, b) and not Social.blocked_between?(a, b)) || {:error, :not_friends},
         :ok <- commit_limit(me) do
      Repo.transaction(fn ->
        Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", ["mls:" <> conversation_id])

        case locked_commit(me, caller_device.device_id, conversation_id, members, req) do
          {:ok, epoch} -> epoch
          {:error, e} -> Repo.rollback(e)
        end
      end)
    else
      :error -> {:error, :not_found}
      {:error, :invalid_device} -> {:error, :bad_request}
      e -> e
    end
  end

  defp commit_limit(me) do
    case RateLimiter.hit(:mls_commit, me, 60, :timer.minutes(1)) do
      :ok -> :ok
      _ -> {:error, :rate_limited}
    end
  end

  defp parse_commit(%{"generation" => g, "epoch" => e, "commit" => c} = p)
       when is_integer(g) and is_integer(e) and e >= 0 and is_binary(c) do
    with {:ok, commit} <- Base.decode64(c),
         true <- byte_size(commit) in 1..@commit_max,
         {:ok, welcome} <- decode_welcome(p["welcome"]),
         {:ok, added} <- device_refs(p["added"] || []),
         {:ok, removed} <- device_refs(p["removed"] || []),
         true <- added == [] == (welcome == nil) do
      {:ok,
       %{
         generation: g,
         epoch: e,
         commit: commit,
         welcome: welcome,
         added: added,
         removed: removed
       }}
    else
      _ -> {:error, :bad_request}
    end
  end

  defp parse_commit(_), do: {:error, :bad_request}

  defp decode_welcome(nil), do: {:ok, nil}

  defp decode_welcome(w) when is_binary(w) do
    case Base.decode64(w) do
      {:ok, bin} when byte_size(bin) in 1..@welcome_max -> {:ok, bin}
      _ -> :error
    end
  end

  defp decode_welcome(_), do: :error

  defp device_refs(list) when is_list(list) do
    refs =
      for %{"user_id" => u, "device_id" => d} <- list,
          {:ok, u} <- [Ecto.UUID.cast(u)],
          {:ok, d} <- [Ecto.UUID.cast(d)],
          do: {u, d}

    if length(refs) == length(list), do: {:ok, Enum.uniq(refs)}, else: :error
  end

  defp device_refs(_), do: :error

  defp locked_commit(me, caller_device, conv, members, req) do
    group = group(conv)
    current = MapSet.new(current_mls_devices(members), &{&1.user_id, &1.device_id})

    in_group =
      MapSet.new(
        Repo.all(
          from gd in "mls_group_devices",
            where: gd.conversation_id == ^conv,
            select: {type(gd.user_id, :binary_id), type(gd.device_id, :binary_id)}
        )
      )

    cond do
      group == nil and (req.epoch != 0 or req.generation != 1) ->
        {:error, {:epoch_conflict, 0}}

      group != nil and (req.generation != group.generation or req.epoch != group.epoch) ->
        {:error, {:epoch_conflict, group.epoch}}

      group == nil ->
        create_group(me, caller_device, conv, members, req, current)

      not MapSet.member?(in_group, {me, caller_device}) ->
        {:error, :bad_request}

      not Enum.all?(
        req.added,
        &(MapSet.member?(current, &1) and not MapSet.member?(in_group, &1))
      ) ->
        {:error, :bad_request}

      not Enum.all?(req.removed, fn {u, _} = ref ->
        MapSet.member?(in_group, ref) and (not MapSet.member?(current, ref) or u == me)
      end) ->
        {:error, :bad_request}

      true ->
        new_epoch = group.epoch + 1

        {1, _} =
          Repo.update_all(
            from(g in "mls_groups",
              where: g.conversation_id == ^conv and g.epoch == ^group.epoch
            ),
            set: [epoch: new_epoch, updated_at: DateTime.utc_now()]
          )

        set_group_devices(conv, req.added, req.removed)
        finish_commit(conv, members, caller_device, req, group.generation, new_epoch)
    end
  end

  defp create_group(me, caller_device, conv, members, req, current) do
    {ready, missing} = readiness(members)
    expected = MapSet.delete(current, {me, caller_device})

    cond do
      not ready ->
        {:error, {:not_ready, missing}}

      not MapSet.member?(current, {me, caller_device}) or MapSet.new(req.added) != expected or
          req.removed != [] ->
        {:error, :bad_request}

      true ->
        now = DateTime.utc_now()

        Repo.insert_all("mls_groups", [
          %{conversation_id: conv, generation: 1, epoch: 1, e2ee_since: now, updated_at: now}
        ])

        set_group_devices(conv, [{me, caller_device} | req.added], [])
        finish_commit(conv, members, caller_device, req, 1, 1)
    end
  end

  @doc false
  def set_group_devices(conv, added, removed) do
    rows =
      for {u, d} <- added,
          do: %{conversation_id: conv, user_id: Ecto.UUID.dump!(u), device_id: Ecto.UUID.dump!(d)}

    Repo.insert_all("mls_group_devices", rows, on_conflict: :nothing)

    for {_u, d} <- removed do
      Repo.delete_all(
        from gd in "mls_group_devices",
          where: gd.conversation_id == ^conv and gd.device_id == type(^d, :binary_id)
      )
    end
  end

  defp finish_commit(conv, members, caller_device, req, generation, new_epoch) do
    now = DateTime.utc_now()

    Repo.insert_all("mls_commits", [
      %{
        conversation_id: conv,
        generation: generation,
        epoch: req.epoch,
        commit: req.commit,
        from_device: Ecto.UUID.dump!(caller_device),
        inserted_at: now
      }
    ])

    prune_commit_log(conv)
    commit_b64 = Base.encode64(req.commit)

    # mls_commit to every member user (their other devices, removed devices, the committer's
    # user); the committing device skips it by from_device.
    for user_id <- members do
      Messaging.publish_mls(user_id, "mls_commit", %{
        "conversation_id" => conv,
        "generation" => generation,
        "epoch" => req.epoch,
        "commit" => commit_b64,
        "from_device" => caller_device
      })
    end

    if req.welcome do
      welcome_b64 = Base.encode64(req.welcome)

      for {user_id, refs} <- Enum.group_by(req.added, &elem(&1, 0)) do
        Messaging.publish_mls(user_id, "mls_welcome", %{
          "conversation_id" => conv,
          "generation" => generation,
          "epoch" => new_epoch,
          "welcome" => welcome_b64,
          "to_devices" => Enum.map(refs, &elem(&1, 1))
        })
      end
    end

    {:ok, new_epoch}
  end

  @doc false
  def prune_commit_log(conv) do
    cutoff = DateTime.add(DateTime.utc_now(), -@commit_log_days, :day)
    Repo.delete_all(from c in "mls_commits", where: c.inserted_at < ^cutoff)

    oldest_kept =
      Repo.one(
        from c in "mls_commits",
          where: c.conversation_id == ^conv,
          order_by: [desc: c.epoch],
          offset: ^(@commit_log_keep - 1),
          limit: 1,
          select: c.epoch
      )

    if oldest_kept,
      do:
        Repo.delete_all(
          from c in "mls_commits", where: c.conversation_id == ^conv and c.epoch < ^oldest_kept
        )
  end

  @doc """
  `GET /mls/groups/{id}/commits?since_epoch=e&limit=n` for a member (current generation):
  `{:ok, commits, has_more}` (v1.9 §12.8 paging; default 50, at most 200).
  """
  def commits_since(me, conv, since, limit \\ nil)

  def commits_since(me, "grp:" <> _ = conv, since, limit),
    do: RisiMe.Groups.Commit.commits_since(me, conv, since, limit)

  def commits_since(me, conv, since, limit) do
    limit = RisiMe.Groups.Commit.page(limit)

    with {:ok, members} <- members(conv),
         true <- me in members || {:error, :not_found},
         %{generation: gen} <- group(conv) || {:error, :not_found} do
      since = if is_integer(since) and since >= 0, do: since, else: 0

      rows =
        Repo.all(
          from c in "mls_commits",
            where: c.conversation_id == ^conv and c.generation == ^gen and c.epoch >= ^since,
            order_by: [asc: c.epoch],
            limit: ^(limit + 1),
            select: %{
              epoch: c.epoch,
              commit: c.commit,
              from_device: type(c.from_device, :binary_id)
            }
        )

      {:ok, rows |> Enum.take(limit) |> Enum.map(&%{&1 | commit: Base.encode64(&1.commit)}),
       length(rows) > limit}
    else
      :error -> {:error, :not_found}
      e -> e
    end
  end

  ## Device changes (§10.1, §10.3)

  @doc """
  A user's MLS device was added or removed: `mls_membership` to every member of each e2ee
  conversation of that user (including their own other devices). No-op without groups.
  """
  def device_changed(user_id, device_id, change) when change in [:added, :removed] do
    convs =
      Repo.all(
        from g in "mls_groups",
          where: like(g.conversation_id, ^"dm:%#{user_id}%"),
          select: g.conversation_id
      )

    for conv <- convs, {:ok, members} <- [members(conv)], member <- members do
      Messaging.publish_mls(member, "mls_membership", %{
        "conversation_id" => conv,
        "user_id" => user_id,
        "device_id" => device_id,
        "change" => Atom.to_string(change)
      })
    end

    :ok
  end
end
