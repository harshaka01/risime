defmodule RisiMe.Calls.Rooms do
  @moduledoc """
  Group-call rooms (contract v1.19 §20.2): `POST /api/v1/calls/rooms` (`start`, `join`,
  `status`) and the removal hook that cuts a removed member (or device) out of a running call.

  **No call table**: LiveKit's in-memory room list is the only call state. A room is created
  only by `start` (`room.auto_create` is off on the LiveKit server), with `max_participants`
  32 (voice) or 8 (video), `empty_timeout` 60 s, `departure_timeout` 20 s and the metadata
  `{"c": conversation_id, "m": media}`, under the deterministic HMAC name of
  `RisiMe.Calls.LiveKit.room_name/4`. v1.23 (§23.6) adds `upgrade`: a voice room turns video
  (`m: "video"` in the metadata, live grants widened with `UpdateParticipant`); the per-room lock
  and the 600-s memory of minted tokens are in `RisiMe.Calls.RoomMemory`. Nothing is persisted and the logs carry ids and counts only.
  """
  require Logger

  alias RisiMe.{Devices, Groups, Messaging, RateLimiter, Repo}
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Calls.{LiveKit, RoomMemory}
  alias RisiMe.Devices.Device

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  @empty_timeout 60
  @departure_timeout 20
  @start_limit 10
  @start_window :timer.hours(1)
  @lookup_limit 60
  @lookup_window :timer.minutes(1)

  @upgrade_limit 10
  @upgrade_window :timer.hours(1)
  @video_cap 8

  @type error ::
          :calls_unavailable
          | :bad_request
          | :not_found
          | :invalid_device
          | :call_ended
          | :call_full
          | :not_in_call
          | :too_many_for_video
          | {:rate_limited, pos_integer}

  @doc """
  Handles `POST /calls/rooms` for `user_id` from `device_id` (`X-Device-Id`). Order: config
  (`calls_unavailable`) → body (`bad_request`) → an active member of an active group
  (`not_found`) → the device: a `group_calls` device of the user that is in the group's MLS
  device list, and for `upgrade` one that advertises `call_switch` (`invalid_device`) → the rate
  limit → LiveKit under the room's lock (`call_ended`, `call_full`, `not_in_call`,
  `too_many_for_video`, `calls_unavailable` when unreachable).

  `media` in a request is the call's **start** media (it names the room); the room's **current**
  media is the metadata `m` (v1.23 §23.6), which `upgrade` turns to `video`.
  """
  @spec handle(String.t(), String.t() | nil, map) :: {:ok, map} | {:error, error}
  def handle(user_id, device_id, params) do
    with {:ok, config} <- LiveKit.config(),
         {:ok, req} <- parse(params),
         true <- Groups.active_member?(req.conv, user_id) || {:error, :not_found},
         {:ok, device_id, caps} <- check_device(user_id, device_id, req),
         :ok <- check_rate(user_id, req.action) do
      room = LiveKit.room_name(config.api_secret, req.conv, req.call_id, req.media)
      me = %{identity: LiveKit.identity(user_id, device_id), screen?: caps.screen_share}
      result = locked(req.action, room, fn -> run(req.action, config, req, room, me) end)
      log(req, room, user_id, device_id, result)
      strip(result)
    end
  end

  defp parse(%{"conversation_id" => conv, "call_id" => call_id, "media" => media, "action" => a})
       when is_binary(conv) and is_binary(call_id) do
    cond do
      not Groups.group_id?(conv) -> {:error, :bad_request}
      not Regex.match?(@uuid, call_id) -> {:error, :bad_request}
      media not in ["audio", "video"] -> {:error, :bad_request}
      a not in ["start", "join", "status", "upgrade"] -> {:error, :bad_request}
      true -> {:ok, %{conv: conv, call_id: call_id, media: media, action: a}}
    end
  end

  defp parse(_), do: {:error, :bad_request}

  # Server S4: the token identity must be a `group_calls` leaf of this group; `upgrade` also
  # needs `call_switch` (v1.23 server S5).
  defp check_device(user_id, device_id, req) do
    with {:ok, d} <- Ecto.UUID.cast(device_id || ""),
         %Device{} = dev <- Repo.get_by(Device, user_id: user_id, device_id: d),
         caps = Devices.call_caps(dev),
         true <- caps.group_calls,
         true <- req.action != "upgrade" or caps.call_switch,
         true <- MapSet.member?(Groups.in_group(req.conv), {user_id, d}) do
      {:ok, d, caps}
    else
      _ -> {:error, :invalid_device}
    end
  end

  defp check_rate(user_id, "start"),
    do: rate(:calls_room_start, user_id, @start_limit, @start_window)

  defp check_rate(user_id, "upgrade"),
    do: rate(:calls_room_upgrade, user_id, @upgrade_limit, @upgrade_window)

  defp check_rate(user_id, _join_or_status),
    do: rate(:calls_room_lookup, user_id, @lookup_limit, @lookup_window)

  defp rate(bucket, key, limit, window) do
    case RateLimiter.hit_if_allowed(bucket, key, limit, window) do
      :ok -> :ok
      _ -> {:error, {:rate_limited, RateLimiter.retry_after_s(window)}}
    end
  end

  # `status` mints nothing and needs no lock.
  defp locked("status", _room, fun), do: fun.()
  defp locked(_action, room, fun), do: RoomMemory.with_lock(room, fun)

  defp run("start", config, req, room, me) do
    opts = %{
      max_participants: LiveKit.max_participants(req.media),
      empty_timeout: @empty_timeout,
      departure_timeout: @departure_timeout,
      metadata: Jason.encode!(%{"c" => req.conv, "m" => req.media})
    }

    case LiveKit.api().create_room(config, room, opts) do
      # An idempotent `start` of a room upgraded since answers with the current media.
      {:ok, r} -> {:ok, token_reply(config, room, me, media_of(r, req.media))}
      {:error, _} -> {:error, :calls_unavailable}
    end
  end

  # v1.23 server S2, S3: the cap comes from the room's current media; an identity already in the
  # room is a refresh (never `call_full`, re-granted in a video room).
  defp run("join", config, req, room, me) do
    with {:ok, r} <- lookup(config, room),
         true <- r != nil || {:error, :call_ended},
         {:ok, present} <- present(config, room) do
      media = media_of(r, req.media)

      cond do
        me.identity in present ->
          if media == "video", do: grant(config, room, me.identity, me.screen?, :refresh)
          {:ok, token_reply(config, room, me, media)}

        max(r.num_participants, length(present)) >= LiveKit.max_participants(media) ->
          {:error, :call_full}

        true ->
          {:ok, token_reply(config, room, me, media)}
      end
    end
  end

  defp run("status", config, req, room, _me) do
    case lookup(config, room) do
      {:ok, nil} ->
        {:ok,
         %{
           active: false,
           participants: 0,
           max_participants: LiveKit.max_participants(req.media),
           media: req.media
         }}

      {:ok, r} ->
        media = media_of(r, req.media)

        {:ok,
         %{
           active: true,
           participants: r.num_participants,
           max_participants: LiveKit.max_participants(media),
           media: media
         }}

      error ->
        error
    end
  end

  # v1.23 §23.6 (server S4): lock (held) → ListRooms → the requester present → the cap →
  # UpdateRoomMetadata → UpdateParticipant (requester, then the others) → the token.
  defp run("upgrade", config, req, room, me) do
    with {:ok, r} <- lookup(config, room),
         true <- r != nil || {:error, :call_ended},
         {:ok, present} <- present(config, room),
         true <- me.identity in present || {:error, :not_in_call} do
      if media_of(r, req.media) == "video",
        do: {:ok, token_reply(config, room, me, "video"), %{idempotent: true}},
        else: upgrade(config, req, room, me, present)
    end
  end

  defp upgrade(config, req, room, me, present) do
    counted = Enum.uniq(present ++ RoomMemory.recent(room))
    metadata = Jason.encode!(%{"c" => req.conv, "m" => "video"})

    with true <- length(counted) <= @video_cap || {:error, :too_many_for_video},
         :ok <- retry(fn -> LiveKit.api().update_room_metadata(config, room, metadata) end),
         :ok <- grant(config, room, me.identity, me.screen?, :requester) do
      others = present -- [me.identity]
      screens = screen_share_identities(others)
      failed = Enum.count(others, &(grant(config, room, &1, &1 in screens, :other) != :ok))

      {:ok, token_reply(config, room, me, "video"),
       %{present: length(present), counted: length(counted), failed: failed}}
    else
      {:error, :too_many_for_video} = e -> e
      _ -> {:error, :calls_unavailable}
    end
  end

  # UpdateParticipant with the video permission, one retry. Failures for others (and refreshes)
  # are logged; the requester's become `503`.
  defp grant(config, room, identity, screen?, who) do
    perm = LiveKit.video_permission(screen?)

    case retry(fn -> LiveKit.api().update_participant(config, room, identity, perm) end) do
      :ok ->
        :ok

      error ->
        if who != :requester,
          do: Logger.warning("calls:rooms update_participant failed (#{who}) room=#{room}")

        error
    end
  end

  defp retry(fun) do
    case fun.() do
      :ok -> :ok
      _ -> fun.()
    end
  end

  # The identities (`user/device`) whose device advertises `screen_share`.
  defp screen_share_identities([]), do: []

  defp screen_share_identities(identities) do
    pairs =
      for i <- identities,
          [u, d] <- [String.split(i, "/")],
          {:ok, u} <- [Ecto.UUID.cast(u)],
          {:ok, d} <- [Ecto.UUID.cast(d)],
          do: {u, d}

    devices = Enum.map(pairs, &elem(&1, 1))

    from(dev in Device, where: dev.device_id in ^devices)
    |> Repo.all()
    |> Enum.filter(&(Devices.call_caps(&1).screen_share and {&1.user_id, &1.device_id} in pairs))
    |> Enum.map(&LiveKit.identity(&1.user_id, &1.device_id))
  end

  defp lookup(config, room) do
    case LiveKit.api().list_rooms(config, [room]) do
      {:ok, rooms} -> {:ok, Enum.find(rooms, &(&1.name == room))}
      {:error, _} -> {:error, :calls_unavailable}
    end
  end

  defp present(config, room) do
    case LiveKit.api().list_participants(config, room) do
      {:ok, ps} -> {:ok, for(%{identity: i} <- ps, do: i)}
      {:error, _} -> {:error, :calls_unavailable}
    end
  end

  # The room's current media: the metadata `m`, else the start media.
  defp media_of(%{metadata: m}, start) when is_binary(m) do
    case Jason.decode(m) do
      {:ok, %{"m" => media}} when media in ["audio", "video"] -> media
      _ -> start
    end
  end

  defp media_of(_room, start), do: start

  defp token_reply(config, room, me, media) do
    now = System.os_time(:second)
    {token, exp} = LiveKit.participant_token(config, me.identity, room, media, now, me.screen?)
    RoomMemory.minted(room, me.identity)

    %{
      url: config.url,
      room: room,
      identity: me.identity,
      token: token,
      expires_at: exp |> DateTime.from_unix!() |> Messaging.iso(),
      max_participants: LiveKit.max_participants(media),
      media: media
    }
  end

  defp strip({:ok, reply, _info}), do: {:ok, reply}
  defp strip(result), do: result

  defp log(req, room, user_id, device_id, result) do
    outcome =
      case result do
        {:ok, _, %{idempotent: true}} ->
          "ok already_video"

        {:ok, _, info} ->
          "ok present=#{info.present} counted=#{info.counted} grant_failed=#{info.failed}"

        {:ok, _} ->
          "ok"

        {:error, reason} ->
          "error=#{reason}"
      end

    Logger.info(
      "calls:rooms #{req.action} conv=#{req.conv} call=#{req.call_id} media=#{req.media} " <>
        "room=#{room} user=#{user_id} dev=#{device_id} #{outcome}"
    )
  end

  ## Removal cuts access at once (§20.2, server S3, crypto K5)

  @doc "A member turned `pending_remove` (removed or left): remove their devices from the group's rooms."
  def member_removed(conv, user_id),
    do: kick(fn r -> room_conv(r) == conv end, &String.starts_with?(&1, user_id <> "/"))

  @doc "A device was removed (§10.1): remove that identity from every group-call room."
  def device_removed(user_id, device_id) do
    identity = LiveKit.identity(user_id, device_id)
    kick(fn r -> match?("grp:" <> _, room_conv(r)) end, &(&1 == identity))
  end

  @doc "A group reset (§12.8): every participant of the group's rooms is removed."
  def group_reset(conv), do: kick(fn r -> room_conv(r) == conv end, fn _ -> true end)

  defp room_conv(%{metadata: m}) when is_binary(m) do
    case Jason.decode(m) do
      {:ok, %{"c" => c}} when is_binary(c) -> c
      _ -> nil
    end
  end

  defp room_conv(_), do: nil

  # Best effort and off the caller's path (it often holds a group lock): a Task, or inline when
  # `:livekit_sync` is set (tests). Nothing happens when LiveKit isn't configured.
  defp kick(room?, identity?) do
    case LiveKit.config() do
      {:ok, config} ->
        job = fn -> do_kick(config, room?, identity?) end

        if Application.get_env(:risime, :livekit_sync, false) do
          job.()
        else
          {:ok, _} = Task.Supervisor.start_child(RisiMe.Calls.TaskSupervisor, job)
        end

        :ok

      _ ->
        :ok
    end
  end

  defp do_kick(config, room?, identity?) do
    api = LiveKit.api()

    with {:ok, rooms} <- api.list_rooms(config, nil) do
      for r <- rooms, room?.(r), {:ok, ps} <- [api.list_participants(config, r.name)] do
        gone = for %{identity: i} <- ps, identity?.(i), do: i
        for i <- gone, do: api.remove_participant(config, r.name, i)
        if gone != [], do: Logger.info("calls:rooms removed #{length(gone)} from room=#{r.name}")
      end
    end

    :ok
  rescue
    e -> Logger.warning("calls:rooms removal failed: #{Exception.message(e)}")
  end
end
