defmodule RisiMe.Calls.Rooms do
  @moduledoc """
  Group-call rooms (contract v1.19 §20.2): `POST /api/v1/calls/rooms` (`start`, `join`,
  `status`) and the removal hook that cuts a removed member (or device) out of a running call.

  **No call table**: LiveKit's in-memory room list is the only call state. A room is created
  only by `start` (`room.auto_create` is off on the LiveKit server), with `max_participants`
  32 (voice) or 8 (video), `empty_timeout` 60 s, `departure_timeout` 20 s and the metadata
  `{"c": conversation_id, "m": media}`, under the deterministic HMAC name of
  `RisiMe.Calls.LiveKit.room_name/4`. Nothing is persisted and the logs carry ids and counts only.
  """
  require Logger

  alias RisiMe.{Devices, Groups, Messaging, RateLimiter, Repo}
  alias RisiMe.Calls.LiveKit
  alias RisiMe.Devices.Device

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  @empty_timeout 60
  @departure_timeout 20
  @start_limit 10
  @start_window :timer.hours(1)
  @lookup_limit 60
  @lookup_window :timer.minutes(1)

  @type error ::
          :calls_unavailable
          | :bad_request
          | :not_found
          | :invalid_device
          | :call_ended
          | :call_full
          | {:rate_limited, pos_integer}

  @doc """
  Handles `POST /calls/rooms` for `user_id` from `device_id` (`X-Device-Id`). Order: config
  (`calls_unavailable`) → body (`bad_request`) → an active member of an active group
  (`not_found`) → the device: a `group_calls` device of the user that is in the group's MLS
  device list (`invalid_device`) → the rate limit → LiveKit (`call_ended`, `call_full`,
  `calls_unavailable` when unreachable).
  """
  @spec handle(String.t(), String.t() | nil, map) :: {:ok, map} | {:error, error}
  def handle(user_id, device_id, params) do
    with {:ok, config} <- LiveKit.config(),
         {:ok, req} <- parse(params),
         true <- Groups.active_member?(req.conv, user_id) || {:error, :not_found},
         {:ok, device_id} <- check_device(user_id, device_id, req.conv),
         :ok <- check_rate(user_id, req.action) do
      room = LiveKit.room_name(config.api_secret, req.conv, req.call_id, req.media)
      result = run(req.action, config, req, room, LiveKit.identity(user_id, device_id))
      log(req, room, user_id, device_id, result)
      result
    end
  end

  defp parse(%{"conversation_id" => conv, "call_id" => call_id, "media" => media, "action" => a})
       when is_binary(conv) and is_binary(call_id) do
    cond do
      not Groups.group_id?(conv) -> {:error, :bad_request}
      not Regex.match?(@uuid, call_id) -> {:error, :bad_request}
      media not in ["audio", "video"] -> {:error, :bad_request}
      a not in ["start", "join", "status"] -> {:error, :bad_request}
      true -> {:ok, %{conv: conv, call_id: call_id, media: media, action: a}}
    end
  end

  defp parse(_), do: {:error, :bad_request}

  # Server S4: the token identity must be a `group_calls` leaf of this group.
  defp check_device(user_id, device_id, conv) do
    with {:ok, d} <- Ecto.UUID.cast(device_id || ""),
         %Device{} = dev <- Repo.get_by(Device, user_id: user_id, device_id: d),
         true <- Devices.call_caps(dev).group_calls,
         true <- MapSet.member?(Groups.in_group(conv), {user_id, d}) do
      {:ok, d}
    else
      _ -> {:error, :invalid_device}
    end
  end

  defp check_rate(user_id, "start"),
    do: rate(:calls_room_start, user_id, @start_limit, @start_window)

  defp check_rate(user_id, _join_or_status),
    do: rate(:calls_room_lookup, user_id, @lookup_limit, @lookup_window)

  defp rate(bucket, key, limit, window) do
    case RateLimiter.hit_if_allowed(bucket, key, limit, window) do
      :ok -> :ok
      _ -> {:error, {:rate_limited, RateLimiter.retry_after_s(window)}}
    end
  end

  defp run("start", config, req, room, identity) do
    opts = %{
      max_participants: LiveKit.max_participants(req.media),
      empty_timeout: @empty_timeout,
      departure_timeout: @departure_timeout,
      metadata: Jason.encode!(%{"c" => req.conv, "m" => req.media})
    }

    case LiveKit.api().create_room(config, room, opts) do
      {:ok, _room} -> {:ok, token_reply(config, req, room, identity)}
      {:error, _} -> {:error, :calls_unavailable}
    end
  end

  defp run("join", config, req, room, identity) do
    case lookup(config, room) do
      {:ok, nil} ->
        {:error, :call_ended}

      {:ok, r} ->
        max = max_of(r, req.media)

        if r.num_participants >= max,
          do: {:error, :call_full},
          else: {:ok, token_reply(config, req, room, identity)}

      error ->
        error
    end
  end

  defp run("status", config, req, room, _identity) do
    case lookup(config, room) do
      {:ok, nil} ->
        {:ok,
         %{active: false, participants: 0, max_participants: LiveKit.max_participants(req.media)}}

      {:ok, r} ->
        {:ok,
         %{active: true, participants: r.num_participants, max_participants: max_of(r, req.media)}}

      error ->
        error
    end
  end

  defp lookup(config, room) do
    case LiveKit.api().list_rooms(config, [room]) do
      {:ok, rooms} -> {:ok, Enum.find(rooms, &(&1.name == room))}
      {:error, _} -> {:error, :calls_unavailable}
    end
  end

  defp max_of(%{max_participants: m}, _media) when is_integer(m) and m > 0, do: m
  defp max_of(_room, media), do: LiveKit.max_participants(media)

  defp token_reply(config, req, room, identity) do
    now = System.os_time(:second)
    {token, exp} = LiveKit.participant_token(config, identity, room, req.media, now)

    %{
      url: config.url,
      room: room,
      identity: identity,
      token: token,
      expires_at: exp |> DateTime.from_unix!() |> Messaging.iso(),
      max_participants: LiveKit.max_participants(req.media)
    }
  end

  defp log(req, room, user_id, device_id, result) do
    outcome =
      case result do
        {:ok, _} -> "ok"
        {:error, reason} -> "error=#{reason}"
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
