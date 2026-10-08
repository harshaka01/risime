defmodule RisiMe.Calls do
  @moduledoc """
  1:1 voice-call signalling (contract v1.13 §16, decisions 046 and 051).

  The server relays MLS ciphertext only. It sees a cleartext `call_id` and a `ring` flag, and
  keeps **no call table**: `call:signal` events live in the short-lived call-signal store
  (`RisiMe.Messaging.Store.append_call_signal/2`, 60 s for a ring, 120 s otherwise), never in
  `inbox_events`, `message_index` or `sent_dedupe`, and never trigger the inbox push. The only
  per-call state is in memory (`RisiMe.Calls.State`): the best-effort idempotency map (5 min)
  and the 3-s fallback ring push (dropped after 45 s).

  The end of a call (`call_end`) is a normal e2ee `msg:send` (§16.2): the server can't tell it
  from a text, stores it 30 days with a sender copy and sends the normal inbox push, from which
  the device builds the missed-call line itself.
  """
  require Logger

  alias RisiMe.{Accounts, Devices, Messaging, MLS, Presence, RateLimiter, TimeUUID}
  alias RisiMe.Calls.State
  alias RisiMe.Messaging.Store

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

  # §16.3 rate limits (per user; they don't count against the msg:send limit).
  @total_limit 60
  @total_window :timer.seconds(10)
  @ring_limit 6
  @ring_window :timer.minutes(1)
  @ring_callee_window :timer.seconds(5)
  @ring_pair_limit 20
  @ring_pair_window :timer.hours(1)
  @signal_pair_limit 30
  @signal_pair_window :timer.seconds(10)
  # v1.19 §20.3 (server S5), per user inside the total.
  @group_ring_window :timer.seconds(30)
  @group_ring_hour_limit 10
  @group_signal_limit 30
  @group_signal_window :timer.seconds(10)

  @type error ::
          :rate_limited
          | :not_friends
          | :not_member
          | :unknown_recipient
          | :not_e2ee
          | :stale_epoch
          | :calls_not_ready
          | :video_not_ready
          | :too_long
          | :bad_request

  @doc """
  Handles a `call:signal` push (§16.3) from `sender_id` (`opts[:device_id]` = the sending
  device). Returns `{:ok, %{message_id, server_ts}}` or `{:error, reason}`.

  Order of checks: total rate limit → parse → idempotent resend → `not_friends` /
  `unknown_recipient` → e2ee (`not_e2ee`, `stale_epoch`) → `calls_not_ready` (ring only) →
  `video_not_ready` (a video ring, v1.18 §19.2) → the ring and per-pair limits → store.

  v1.18 §19.2: the cleartext `media` (`"audio"` when absent) is copied onto the event; a
  `video` signal reaches only `video` sockets and its ring push only `video` devices.
  """
  @spec signal(String.t(), map, keyword) :: {:ok, map} | {:error, error}
  def signal(sender_id, params, opts \\ []) do
    result =
      with :ok <- RateLimiter.hit(:call_signal, sender_id, @total_limit, @total_window),
           {:ok, req} <- parse(params) do
        req = Map.put(req, :from_device, opts[:device_id])

        case State.reply(sender_id, req.client_msg_id) do
          {:ok, reply} -> {:ok, reply}
          :not_found when is_map_key(req, :conv) -> group_signal_new(sender_id, req)
          :not_found -> signal_new(sender_id, req)
        end
      end

    log_signal(sender_id, params, opts[:device_id], result)
    result
  end

  # Decision 054: one line per `call:signal` (ids, the ring flag and the outcome only; never the
  # ciphertext), so a failed call can be reconstructed from server.log. nightly.16 logged nothing.
  defp log_signal(sender_id, params, device_id, result) do
    outcome =
      case result do
        {:ok, _} -> "ok"
        {:error, reason} -> "error=#{reason}"
      end

    call_id = if is_map(params) and is_binary(params["call_id"]), do: params["call_id"], else: "-"

    to =
      cond do
        is_map(params) and is_binary(params["to"]) -> params["to"]
        is_map(params) and is_binary(params["conversation_id"]) -> params["conversation_id"]
        true -> "-"
      end

    ring = if is_map(params), do: params["ring"] == true, else: false

    Logger.info(
      "call:signal call=#{call_id} from=#{sender_id} dev=#{device_id || "-"} to=#{to} ring=#{ring} #{outcome}"
    )
  end

  # v1.19 §20.3: a group signal names `conversation_id` (a `grp:` id) instead of `to`. Its
  # ciphertext is checked after `not_member` (the e2ee checks of the group order).
  defp parse(%{"client_msg_id" => cmid, "conversation_id" => conv, "call_id" => call_id} = p)
       when is_binary(cmid) and is_binary(call_id) do
    cmid = String.downcase(cmid)

    cond do
      Map.has_key?(p, "to") ->
        {:error, :bad_request}

      not (is_binary(conv) and RisiMe.Groups.group_id?(conv)) ->
        {:error, :bad_request}

      not uuid?(cmid) ->
        {:error, :bad_request}

      not uuid?(call_id) ->
        {:error, :bad_request}

      not is_boolean(p["ring"]) ->
        {:error, :bad_request}

      not (is_integer(p["generation"]) and is_integer(p["epoch"])) ->
        {:error, :bad_request}

      media(p) == :error ->
        {:error, :bad_request}

      true ->
        {:ok,
         %{
           client_msg_id: cmid,
           conv: conv,
           call_id: call_id,
           ring: p["ring"],
           media: media(p),
           ciphertext: p["ciphertext"],
           generation: p["generation"],
           epoch: p["epoch"]
         }}
    end
  end

  defp parse(%{"client_msg_id" => cmid, "to" => to, "call_id" => call_id} = p)
       when is_binary(cmid) and is_binary(to) and is_binary(call_id) do
    cmid = String.downcase(cmid)
    to = String.downcase(to)

    cond do
      String.starts_with?(to, "grp:") ->
        {:error, :bad_request}

      not uuid?(cmid) ->
        {:error, :bad_request}

      not uuid?(to) ->
        {:error, :unknown_recipient}

      # `call_id` is lowercase on the wire (§16.2): no normalising, the receivers bind it.
      not uuid?(call_id) ->
        {:error, :bad_request}

      not is_boolean(p["ring"]) ->
        {:error, :bad_request}

      not (is_integer(p["generation"]) and is_integer(p["epoch"])) ->
        {:error, :bad_request}

      media(p) == :error ->
        {:error, :bad_request}

      true ->
        with :ok <- Messaging.validate_ciphertext(p["ciphertext"]) do
          {:ok,
           %{
             client_msg_id: cmid,
             to: to,
             call_id: call_id,
             ring: p["ring"],
             media: media(p),
             ciphertext: p["ciphertext"],
             generation: p["generation"],
             epoch: p["epoch"]
           }}
        end
    end
  end

  defp parse(_), do: {:error, :bad_request}

  # v1.18 §19.2: `media` is "audio" (also when absent) or "video".
  defp media(p) do
    case Map.fetch(p, "media") do
      :error -> "audio"
      {:ok, m} when m in ["audio", "video"] -> m
      {:ok, _} -> :error
    end
  end

  defp signal_new(sender_id, req) do
    with :ok <- check_recipient(sender_id, req.to),
         {:ok, conv} <- check_e2ee(sender_id, req),
         :ok <- check_ready(req),
         :ok <- check_limits(sender_id, req) do
      server_ts = DateTime.utc_now() |> DateTime.truncate(:millisecond)
      event_id = TimeUUID.generate()

      event = %{
        event_id: event_id,
        kind: "call_signal",
        data: %{
          "message_id" => event_id,
          "conversation_id" => conv,
          "from" => sender_id,
          "to" => req.to,
          "from_device" => req.from_device,
          "call_id" => req.call_id,
          "ring" => req.ring,
          "media" => req.media,
          "ciphertext" => req.ciphertext,
          "generation" => req.generation,
          "epoch" => req.epoch,
          "server_ts" => Messaging.iso(server_ts)
        }
      }

      # Both users, one event_id; the sending device skips its own copy by from_device.
      :ok = Store.impl().append_call_signal([sender_id, req.to], event)
      Messaging.broadcast(sender_id, event)
      Messaging.broadcast(req.to, event)

      reply = %{message_id: event_id, server_ts: Messaging.iso(server_ts)}
      State.put_reply(sender_id, req.client_msg_id, reply)

      # Any signal of this call from the callee's user answers the ring: no fallback push.
      State.answered(sender_id, req.call_id)
      if req.ring, do: ring(req.to, req.call_id, req.media)

      :telemetry.execute([:risime, :call, :signal], %{count: 1}, %{ring: req.ring})
      {:ok, reply}
    end
  end

  ## Group signals (v1.19 §20.3)

  # Order: (total rate limit → idempotent resend, in signal/3) → not_member → e2ee (stale_epoch,
  # too_long) → for a ring, calls_not_ready when no other active member has a `group_calls`
  # device → the group limits → store one row per active member user (the sender's included).
  defp group_signal_new(sender_id, req) do
    alias RisiMe.Groups

    with true <- Groups.active_member?(req.conv, sender_id) || {:error, :not_member},
         :ok <- check_group_e2ee(req),
         members = Groups.active_member_ids(req.conv),
         :ok <- check_group_ready(sender_id, req, members),
         :ok <- check_group_limits(sender_id, req) do
      server_ts = DateTime.utc_now() |> DateTime.truncate(:millisecond)
      event_id = TimeUUID.generate()

      event = %{
        event_id: event_id,
        kind: "call_signal",
        data: %{
          "message_id" => event_id,
          "conversation_id" => req.conv,
          "from" => sender_id,
          "from_device" => req.from_device,
          "call_id" => req.call_id,
          "ring" => req.ring,
          "media" => req.media,
          "ciphertext" => req.ciphertext,
          "generation" => req.generation,
          "epoch" => req.epoch,
          "server_ts" => Messaging.iso(server_ts)
        }
      }

      :ok = Store.impl().append_call_signal(members, event)
      for u <- members, do: Messaging.broadcast(u, event)

      reply = %{message_id: event_id, server_ts: Messaging.iso(server_ts)}
      State.put_reply(sender_id, req.client_msg_id, reply)
      if req.ring, do: group_ring(req, members -- [sender_id])

      :telemetry.execute([:risime, :call, :signal], %{count: 1}, %{ring: req.ring})
      {:ok, reply}
    end
  end

  defp check_group_e2ee(req) do
    g = RisiMe.Groups.get_group(req.conv)

    cond do
      req.from_device == nil ->
        {:error, :bad_request}

      g == nil ->
        {:error, :not_member}

      req.generation != g.generation or req.epoch != RisiMe.Groups.epoch(g.id) ->
        {:error, :stale_epoch}

      true ->
        Messaging.validate_ciphertext(req.ciphertext)
    end
  end

  defp check_group_ready(_sender_id, %{ring: false}, _members), do: :ok

  defp check_group_ready(sender_id, _req, members) do
    if Devices.group_calls_users(members -- [sender_id]) == [],
      do: {:error, :calls_not_ready},
      else: :ok
  end

  # Server S5: ring 1 per group per 30 s and 10 per hour; ring: false 30 per group per 10 s.
  # A refused ring doesn't count against the ring buckets.
  defp check_group_limits(sender_id, %{ring: true, conv: conv}) do
    result =
      with :ok <-
             RateLimiter.hit_if_allowed(
               :call_group_ring,
               {sender_id, conv},
               1,
               @group_ring_window
             ) do
        RateLimiter.hit_if_allowed(
          :call_group_ring_hour,
          sender_id,
          @group_ring_hour_limit,
          :timer.hours(1)
        )
      end

    if result != :ok,
      do: Logger.warning("call ring refused: rate_limited from=#{sender_id} conv=#{conv}")

    result
  end

  defp check_group_limits(sender_id, %{ring: false, conv: conv}) do
    RateLimiter.hit(
      :call_group_signal,
      {sender_id, conv},
      @group_signal_limit,
      @group_signal_window
    )
  end

  # Server S6: the call push at once to every `group_calls` device of the other members without
  # a live inbox channel; no 3-s fallback in groups.
  # A "live" device's socket is probed (`Push.Dispatcher.watch_call/2`): no pong within 4 s
  # (a half-open socket) → its call push after all.
  # v1.24 §24.5/§24.7: an Official call rings only `tabs` devices (an old app never learns of an
  # Official conversation), and agents are never rung.
  defp group_ring(req, others) do
    official? = RisiMe.Groups.Tabs.official?(req.conv)
    others = others -- RisiMe.Risi.agents(others)

    allowed? = fn u, d -> not official? or d in Devices.tabs_device_ids(u) end

    targets =
      for u <- others,
          {d, tok} <- Devices.calls_push_targets(u, "group_calls"),
          allowed?.(u, d),
          do: {u, d, tok}

    {live, offline} = Enum.split_with(targets, fn {_, d, _} -> Presence.device_online?(d) end)

    Logger.info(
      "call ring: call=#{req.call_id} conv=#{req.conv} members=#{length(others)} push_now=#{length(offline)} live=#{length(live)}"
    )

    RisiMe.Push.Dispatcher.push_call(for {_, _, tok} <- offline, do: tok)

    for {u, list} <- Enum.group_by(live, &elem(&1, 0), &{elem(&1, 1), elem(&1, 2)}),
        do: RisiMe.Push.Dispatcher.watch_call(u, list)

    :ok
  end

  # As msg:send (§9.3): yourself is `unknown_recipient`; every other non-friend is `not_friends`.
  defp check_recipient(sender_id, to) do
    cond do
      to == sender_id -> {:error, :unknown_recipient}
      RisiMe.Social.friends?(sender_id, to) and Accounts.messageable?(to) -> :ok
      true -> {:error, :not_friends}
    end
  end

  defp check_e2ee(sender_id, req) do
    conv = Messaging.conversation_id(sender_id, req.to)

    case MLS.group(conv) do
      nil -> {:error, :not_e2ee}
      _ when req.from_device == nil -> {:error, :bad_request}
      %{generation: g, epoch: e} when g == req.generation and e == req.epoch -> {:ok, conv}
      _ -> {:error, :stale_epoch}
    end
  end

  # Server S6: a ring needs a callee device that advertises `calls` and has a signature key.
  defp check_ready(%{ring: false}), do: :ok

  defp check_ready(%{ring: true, to: to} = req) do
    cond do
      not Devices.calls_device?(to) -> {:error, :calls_not_ready}
      # v1.18 §19.2: a video ring needs a `video` device of the callee (with a key).
      req.media == "video" and not Devices.video_device?(to) -> {:error, :video_not_ready}
      true -> :ok
    end
  end

  # A refused ring doesn't count against the ring buckets (a retrying client doesn't extend its
  # own lockout); every refused ring is logged with the pair (ids only).
  defp check_limits(sender_id, %{ring: true, to: to}) do
    pair = {sender_id, to}

    result =
      with :ok <- RateLimiter.hit_if_allowed(:call_ring_callee, pair, 1, @ring_callee_window),
           :ok <- RateLimiter.hit_if_allowed(:call_ring, sender_id, @ring_limit, @ring_window) do
        RateLimiter.hit_if_allowed(:call_ring_pair, pair, @ring_pair_limit, @ring_pair_window)
      end

    if result != :ok,
      do: Logger.warning("call ring refused: rate_limited from=#{sender_id} to=#{to}")

    result
  end

  defp check_limits(sender_id, %{ring: false, to: to}) do
    RateLimiter.hit(:call_signal_pair, {sender_id, to}, @signal_pair_limit, @signal_pair_window)
  end

  ## The call wake-up (§16.8)

  # 1. At once to every `calls` device of the callee without a live inbox channel;
  # 2. after the fallback delay, unless the callee's user sent a signal of this call, to the
  #    remaining `calls` devices (half-open sockets look live for about 60 s).
  defp ring(callee_id, call_id, media) do
    # v1.18 §19.2: a video call wakes only `video` devices (first push and fallback).
    targets =
      Devices.calls_push_targets(callee_id, if(media == "video", do: "video", else: "calls"))

    {offline, live} = Enum.split_with(targets, fn {d, _} -> not Presence.device_online?(d) end)

    Logger.info(
      "call ring: call=#{call_id} to=#{callee_id} push_now=#{length(offline)} live=#{length(live)}"
    )

    RisiMe.Push.Dispatcher.push_call(Enum.map(offline, &elem(&1, 1)))
    if live != [], do: State.arm_fallback(callee_id, call_id, Enum.map(live, &elem(&1, 1)))
    :ok
  end

  defp uuid?(s), do: Regex.match?(@uuid, s)
end
