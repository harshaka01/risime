defmodule RisiMe.Devices do
  @moduledoc """
  App installs: push tokens (contract v1.5 §8.1) and MLS identities (v1.7 §10.1, decisions 027
  and 034).

  * `push_token` is optional when `mls` is present (at least one of the two).
  * Losing push isn't losing the MLS identity: FCM cleanup and a token moving to another install
    only clear `push_token` on MLS devices.
  * Real removals (DELETE, dev logout, eviction beyond 10, the 60-day prune, a changed MLS key)
    emit `mls_membership` events through `RisiMe.MLS.device_changed/3`.
  """
  import Ecto.Query

  alias RisiMe.{Groups, MLS, RateLimiter, Repo}
  alias RisiMe.Devices.Device

  @max_per_user 10
  @platforms ~w(android)
  @unseen_days 60
  @key_changes_per_day 5

  @doc """
  Registers or updates a device (`PUT /me/devices/{device_id}`). Returns `{:ok, attestation |
  nil}` or `{:error, :invalid_device | :mls_unavailable | :rate_limited}`.
  """
  def register(user_id, device_id, params, user_token_id \\ nil) do
    with {:ok, device_id} <- Ecto.UUID.cast(device_id),
         %{"platform" => platform} when platform in @platforms <- params,
         {:ok, token} <- push_token(params["push_token"]),
         {:ok, mls_key} <- mls_key(params["mls"]),
         {:ok, caps} <- capabilities(params["mls"]),
         true <- token != nil or mls_key != nil,
         version when is_nil(version) or (is_binary(version) and byte_size(version) <= 64) <-
           params["app_version"],
         {:ok, attestation} <- attest(user_id, device_id, mls_key) do
      existing = Repo.get_by(Device, user_id: user_id, device_id: device_id)

      key_changed? =
        mls_key != nil and existing != nil and existing.mls_signature_key != nil and
          existing.mls_signature_key != mls_key

      if key_changed? and
           RateLimiter.hit(
             :mls_key_change,
             {user_id, device_id},
             @key_changes_per_day,
             :timer.hours(24)
           ) !=
             :ok do
        {:error, :rate_limited}
      else
        evicted =
          store(
            user_id,
            device_id,
            platform,
            token,
            version,
            user_token_id,
            {mls_key, caps},
            attestation,
            existing
          )

        strikes_changed(device_id, existing, mls_key, caps)
        groups_changed(user_id, device_id, existing, mls_key, caps, key_changed?)
        member_devices_changed(user_id, existing, mls_key, caps)
        history_changed(user_id, device_id, existing, mls_key, caps, key_changed?)

        calls_changed(
          user_id,
          device_id,
          if(mls_key, do: call_caps(caps), else: call_caps(existing))
        )

        # v1.10 §13.2: a removal (here a changed key, or an eviction) resets first_seen_at.
        MLS.reset_first_seen(
          if(key_changed?, do: [{user_id, device_id}], else: []) ++
            for(d <- evicted, do: {d.user_id, d.device_id})
        )

        cond do
          key_changed? ->
            MLS.device_changed(user_id, device_id, :removed)
            MLS.device_changed(user_id, device_id, :added)

          mls_key && (existing == nil or existing.mls_signature_key == nil) ->
            MLS.device_changed(user_id, device_id, :added)

          true ->
            :ok
        end

        # v1.19 §20.2: a removed (or re-keyed) device leaves every group call at once.
        if key_changed?, do: RisiMe.Calls.Rooms.device_removed(user_id, device_id)

        for d <- evicted, d.mls_signature_key do
          RisiMe.Calls.Rooms.device_removed(d.user_id, d.device_id)
          RisiMe.History.device_gone(d.user_id, d.device_id)
          MLS.device_changed(d.user_id, d.device_id, :removed)
          if groups?(d), do: Groups.device_changed(d.user_id, d.device_id, :removed)
        end

        {:ok, attestation}
      end
    else
      {:error, :mls_unavailable} -> {:error, :mls_unavailable}
      _ -> {:error, :invalid_device}
    end
  end

  defp push_token(nil), do: {:ok, nil}

  defp push_token(t) when is_binary(t) do
    if String.trim(t) != "" and byte_size(t) <= 4096, do: {:ok, t}, else: :error
  end

  defp push_token(_), do: :error

  defp mls_key(nil), do: {:ok, nil}

  defp mls_key(%{"signature_key" => b64}) when is_binary(b64) do
    case Base.decode64(b64) do
      {:ok, <<_::binary-size(32)>> = key} -> {:ok, key}
      _ -> :error
    end
  end

  defp mls_key(_), do: :error

  # v1.9 §12.1, v1.11 §14.1, v1.12 §15.1: `mls.capabilities`; only known ones are kept
  # (unknown ones are ignored).
  # v1.13 §16.1: `calls`.
  # v1.15 §17.1: `history_share`.
  # v1.18 §19.1: `video`. v1.19 §20.1: `group_calls`.
  @known_capabilities ~w(groups images deletes calls member_devices history_share video
                         group_calls call_switch screen_share)

  defp capabilities(%{"capabilities" => caps}) when is_list(caps) do
    if length(caps) <= 32 and Enum.all?(caps, &is_binary/1),
      do: {:ok, caps |> Enum.filter(&(&1 in @known_capabilities)) |> Enum.uniq()},
      else: :error
  end

  defp capabilities(%{"capabilities" => nil}), do: {:ok, []}
  defp capabilities(%{"capabilities" => _}), do: :error
  defp capabilities(_), do: {:ok, []}

  # Live sockets of this device start or stop receiving `grp:` traffic (§12.1 filter).
  defp caps_changed(user_id, device_id, groups?) do
    Phoenix.PubSub.broadcast(
      RisiMe.PubSub,
      RisiMe.Messaging.topic(user_id),
      {:device_groups, device_id, groups?}
    )
  end

  # v1.13 §16.1, v1.18 §19.2, v1.19 §20.3: live sockets of this device start or stop receiving
  # `call_signal` events (any, video, group).
  defp calls_changed(user_id, device_id, caps) do
    Phoenix.PubSub.broadcast(
      RisiMe.PubSub,
      RisiMe.Messaging.topic(user_id),
      {:device_calls, device_id, caps}
    )
  end

  @doc """
  The call capabilities of a device (or a capability list) as
  `%{calls, video, group_calls, call_switch, screen_share}` (booleans; v1.23 §23.1 added the
  last two); all false for a device without an MLS key.
  """
  def call_caps(caps) when is_list(caps),
    do: %{
      calls: "calls" in caps,
      video: "video" in caps,
      group_calls: "group_calls" in caps,
      call_switch: "call_switch" in caps,
      screen_share: "screen_share" in caps
    }

  def call_caps(%Device{mls_signature_key: k, capabilities: caps}) when is_binary(k),
    do: call_caps(caps || [])

  def call_caps(_), do: call_caps([])

  @doc "True if the device is a current MLS device with the `calls` capability (§16.1)."
  def calls?(device), do: call_caps(device).calls

  @doc "v1.13 §16.1 (server S6): true if the user has a device with `calls` and a signature key."
  def calls_device?(user_id), do: capable_device?(user_id, "calls")

  @doc "v1.18 §19.2: true if the user has a device with `video` and a signature key."
  def video_device?(user_id), do: capable_device?(user_id, "video")

  @doc "v1.19 §20.3: the users among `user_ids` with a `group_calls` device that has a key."
  def group_calls_users([]), do: []

  def group_calls_users(user_ids) do
    Repo.all(
      from d in Device,
        where:
          d.user_id in ^user_ids and not is_nil(d.mls_signature_key) and
            "group_calls" in d.capabilities,
        distinct: true,
        select: d.user_id
    )
  end

  defp capable_device?(user_id, cap) do
    Repo.exists?(
      from d in Device,
        where:
          d.user_id == ^user_id and not is_nil(d.mls_signature_key) and
            ^cap in d.capabilities
    )
  end

  @doc """
  v1.13 §16.8: `{device_id, push_token}` of the user's `calls` devices that have a token; for a
  video call (v1.18 §19.2) only `video` devices, for a group call (v1.19 §20.3) only
  `group_calls` devices.
  """
  def calls_push_targets(user_id, cap \\ "calls") do
    Repo.all(
      from d in Device,
        where:
          d.user_id == ^user_id and not is_nil(d.mls_signature_key) and
            not is_nil(d.push_token) and
            "calls" in d.capabilities and ^cap in d.capabilities,
        select: {d.device_id, d.push_token}
    )
  end

  @doc "True if the device is a current MLS device with `history_share` (v1.15 §17.1)."
  def history?(%Device{mls_signature_key: k, capabilities: caps}) when is_binary(k),
    do: "history_share" in (caps || [])

  def history?(_), do: false

  # v1.15 §17.4: live sockets of the device start or stop getting `history_*` events; a device
  # that loses the capability or changes its key (a new leaf) is un-named and its requests close.
  defp history_changed(user_id, device_id, existing, mls_key, caps, key_changed?) do
    was = history?(existing)
    now = if mls_key, do: "history_share" in caps, else: was

    if was != now do
      Phoenix.PubSub.broadcast(
        RisiMe.PubSub,
        RisiMe.Messaging.topic(user_id),
        {:device_history, device_id, now}
      )
    end

    if was and (not now or key_changed?), do: RisiMe.History.device_gone(user_id, device_id)
    :ok
  end

  @doc "True if the device is a current MLS device with the `groups` capability (§12.1)."
  def groups?(%Device{mls_signature_key: k, capabilities: caps}) when is_binary(k),
    do: "groups" in (caps || [])

  def groups?(_), do: false

  # v1.9 §12.4: a device gaining `groups` (or a new groups device) is added to the user's groups
  # by a `devices` op; one losing it, or changing its key, is removed (and re-added).
  defp groups_changed(user_id, device_id, existing, mls_key, caps, key_changed?) do
    was = groups?(existing)
    now = if mls_key, do: "groups" in caps, else: was
    caps_changed(user_id, device_id, now)

    cond do
      was and now and key_changed? -> Groups.device_changed(user_id, device_id, :replaced)
      not was and now -> Groups.device_changed(user_id, device_id, :added)
      was and mls_key != nil and not now -> Groups.device_changed(user_id, device_id, :removed)
      true -> :ok
    end
  end

  # v1.21 §12.12.4: a device that re-advertises different capabilities may have updated: its
  # naming strikes on every op are cleared.
  defp strikes_changed(device_id, %Device{capabilities: old}, mls_key, caps)
       when is_binary(mls_key) do
    if Enum.sort(old || []) != Enum.sort(caps),
      do: RisiMe.Groups.Strikes.clear_device(device_id)

    :ok
  end

  defp strikes_changed(_device_id, _existing, _mls_key, _caps), do: :ok

  # v1.14 §12.11: a device that newly advertises `member_devices` may open the §12.4a gate in
  # its user's groups: name committers for their waiting `devices` ops again.
  defp member_devices_changed(user_id, existing, mls_key, caps) do
    had = existing != nil and "member_devices" in (existing.capabilities || [])

    if mls_key != nil and "member_devices" in caps and not had,
      do: Groups.Ops.name_waiting_for_user(user_id)

    :ok
  end

  defp attest(_user_id, _device_id, nil), do: {:ok, nil}

  defp attest(user_id, device_id, key) do
    RisiMe.MLS.Attestation.sign(user_id, device_id, Base.encode64(key))
  end

  defp store(
         user_id,
         device_id,
         platform,
         token,
         version,
         user_token_id,
         {mls_key, caps},
         attestation,
         existing
       ) do
    now = DateTime.utc_now()

    {:ok, evicted} =
      Repo.transaction(fn ->
        if token, do: release_push_token(token, user_id, device_id)

        mls_fields =
          if mls_key,
            do: [
              mls_signature_key: mls_key,
              mls_attestation: attestation,
              mls_attested_at: now,
              capabilities: caps
            ],
            else: []

        # A PUT without `mls` keeps an existing MLS identity (push-only refresh).
        base = [
          platform: platform,
          push_token: token,
          app_version: version,
          user_token_id: user_token_id,
          last_seen_at: now,
          updated_at: now
        ]

        base =
          if (token == nil and existing) && existing.push_token,
            do: Keyword.delete(base, :push_token),
            else: base

        Repo.insert!(
          struct(
            Device,
            [
              user_id: user_id,
              device_id: device_id,
              platform: platform,
              push_token: token,
              app_version: version,
              user_token_id: user_token_id,
              last_seen_at: now
            ] ++
              mls_fields
          ),
          on_conflict: [set: base ++ mls_fields],
          conflict_target: [:user_id, :device_id]
        )

        evict(user_id)
      end)

    evicted
  end

  # An FCM token moves with its install: other rows lose it (MLS rows keep their identity).
  defp release_push_token(token, user_id, device_id) do
    others =
      from d in Device,
        where:
          d.push_token == ^token and not (d.user_id == ^user_id and d.device_id == ^device_id)

    Repo.update_all(from(d in others, where: not is_nil(d.mls_signature_key)),
      set: [push_token: nil]
    )

    Repo.delete_all(from(d in others, where: is_nil(d.mls_signature_key)))
  end

  defp evict(user_id) do
    keep =
      from d in Device,
        where: d.user_id == ^user_id,
        order_by: [desc: d.last_seen_at],
        limit: @max_per_user,
        select: d.id

    {_, evicted} =
      Repo.delete_all(
        from(d in Device, where: d.user_id == ^user_id and d.id not in subquery(keep), select: d)
      )

    evicted
  end

  @doc "Removes a device (`DELETE /me/devices/{device_id}`). Idempotent; a bad id is `:invalid_device`."
  def delete(user_id, device_id) do
    case Ecto.UUID.cast(device_id) do
      {:ok, id} ->
        removed(from d in Device, where: d.user_id == ^user_id and d.device_id == ^id)
        :ok

      :error ->
        {:error, :invalid_device}
    end
  end

  @doc """
  Dev logout (decision 050): the token's devices stop getting push, but MLS devices stay
  registered members of their groups (a plain logout keeps the chats; the same account signs back
  in with no rejoin). Push-only devices have nothing left and are removed. Returns the count.
  """
  def clear_push_for_token(user_token_id),
    do: clear_push(from(d in Device, where: d.user_token_id == ^user_token_id))

  @doc """
  `DELETE /me/devices/{device_id}/push_token` (decision 050, plain logout): unregisters push only.
  An MLS device keeps its registration, groups and inbox; a push-only device is removed.
  Idempotent; a bad id is `:invalid_device`.
  """
  def unregister_push(user_id, device_id) do
    case Ecto.UUID.cast(device_id) do
      {:ok, id} ->
        _ = clear_push(from(d in Device, where: d.user_id == ^user_id and d.device_id == ^id))
        :ok

      :error ->
        {:error, :invalid_device}
    end
  end

  defp clear_push(query) do
    {cleared, _} =
      Repo.update_all(from(d in query, where: not is_nil(d.mls_signature_key)),
        set: [push_token: nil]
      )

    cleared + removed(from(d in query, where: is_nil(d.mls_signature_key)))
  end

  @doc """
  FCM said `push_token` is no longer valid: MLS devices just lose the token, push-only devices
  are deleted.
  """
  def delete_push_token(push_token) do
    {cleared, _} =
      Repo.update_all(
        from(d in Device, where: d.push_token == ^push_token and not is_nil(d.mls_signature_key)),
        set: [push_token: nil]
      )

    {deleted, _} =
      Repo.delete_all(
        from d in Device, where: d.push_token == ^push_token and is_nil(d.mls_signature_key)
      )

    cleared + deleted
  end

  @doc "The push token of one device (any user), or nil (§12.4a wake-ups)."
  def device_push_token(device_id) do
    Repo.one(
      from d in Device,
        where: d.device_id == ^device_id and not is_nil(d.push_token),
        select: d.push_token,
        limit: 1
    )
  end

  @doc "The push tokens of a user's devices."
  def push_tokens(user_id) do
    Repo.all(
      from d in Device,
        where: d.user_id == ^user_id and not is_nil(d.push_token),
        select: d.push_token
    )
  end

  @doc "`{device_id, push_token}` pairs of a user's devices that have a token (push audit log)."
  def push_targets(user_id) do
    Repo.all(
      from d in Device,
        where: d.user_id == ^user_id and not is_nil(d.push_token),
        select: {d.device_id, d.push_token}
    )
  end

  @doc "All device ids of a user (push audit log: how many are online)."
  def device_ids(user_id) do
    Repo.all(from d in Device, where: d.user_id == ^user_id, select: d.device_id)
  end

  @doc "`%{token => {user_id, device_id}}` for these push tokens (push audit log)."
  def token_owners(tokens) do
    Repo.all(
      from d in Device,
        where: d.push_token in ^tokens,
        select: {d.push_token, {d.user_id, d.device_id}}
    )
    |> Map.new()
  end

  @doc "Deletes devices not seen (no PUT) for #{@unseen_days} days. Returns the count."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@unseen_days, :day)
    removed(from d in Device, where: d.last_seen_at < ^cutoff)
  end

  # Deletes and emits mls_membership `removed` for MLS devices. Returns the count.
  defp removed(query) do
    {n, rows} = Repo.delete_all(from(d in query, select: d))

    MLS.reset_first_seen(for d <- rows, do: {d.user_id, d.device_id})

    for d <- rows do
      caps_changed(d.user_id, d.device_id, false)
      calls_changed(d.user_id, d.device_id, call_caps(nil))
      # v1.15 §17.4: a removed device is un-named and its requests are cancelled.
      if d.mls_signature_key, do: RisiMe.History.device_gone(d.user_id, d.device_id)
    end

    for d <- rows, d.mls_signature_key do
      RisiMe.Calls.Rooms.device_removed(d.user_id, d.device_id)
      MLS.device_changed(d.user_id, d.device_id, :removed)
      if groups?(d), do: Groups.device_changed(d.user_id, d.device_id, :removed)
    end

    n
  end
end
