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

  alias RisiMe.{MLS, RateLimiter, Repo}
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
            mls_key,
            attestation,
            existing
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

        for d <- evicted,
            d.mls_signature_key,
            do: MLS.device_changed(d.user_id, d.device_id, :removed)

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
         mls_key,
         attestation,
         existing
       ) do
    now = DateTime.utc_now()

    {:ok, evicted} =
      Repo.transaction(fn ->
        if token, do: release_push_token(token, user_id, device_id)

        mls_fields =
          if mls_key,
            do: [mls_signature_key: mls_key, mls_attestation: attestation, mls_attested_at: now],
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

  @doc "Removes the devices registered with a dev token (dev logout)."
  def delete_for_token(user_token_id),
    do: removed(from d in Device, where: d.user_token_id == ^user_token_id)

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

  @doc "The push tokens of a user's devices."
  def push_tokens(user_id) do
    Repo.all(
      from d in Device,
        where: d.user_id == ^user_id and not is_nil(d.push_token),
        select: d.push_token
    )
  end

  @doc "Deletes devices not seen (no PUT) for #{@unseen_days} days. Returns the count."
  def prune(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@unseen_days, :day)
    removed(from d in Device, where: d.last_seen_at < ^cutoff)
  end

  # Deletes and emits mls_membership `removed` for MLS devices. Returns the count.
  defp removed(query) do
    {n, rows} = Repo.delete_all(from(d in query, select: d))
    for d <- rows, d.mls_signature_key, do: MLS.device_changed(d.user_id, d.device_id, :removed)
    n
  end
end
