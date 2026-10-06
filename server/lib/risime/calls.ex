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

  @type error ::
          :rate_limited
          | :not_friends
          | :unknown_recipient
          | :not_e2ee
          | :stale_epoch
          | :calls_not_ready
          | :too_long
          | :bad_request

  @doc """
  Handles a `call:signal` push (§16.3) from `sender_id` (`opts[:device_id]` = the sending
  device). Returns `{:ok, %{message_id, server_ts}}` or `{:error, reason}`.

  Order of checks: total rate limit → parse → idempotent resend → `not_friends` /
  `unknown_recipient` → e2ee (`not_e2ee`, `stale_epoch`) → `calls_not_ready` (ring only) → the
  ring and per-pair limits → store.
  """
  @spec signal(String.t(), map, keyword) :: {:ok, map} | {:error, error}
  def signal(sender_id, params, opts \\ []) do
    with :ok <- RateLimiter.hit(:call_signal, sender_id, @total_limit, @total_window),
         {:ok, req} <- parse(params) do
      req = Map.put(req, :from_device, opts[:device_id])

      case State.reply(sender_id, req.client_msg_id) do
        {:ok, reply} -> {:ok, reply}
        :not_found -> signal_new(sender_id, req)
      end
    end
  end

  defp parse(%{"client_msg_id" => cmid, "to" => to, "call_id" => call_id} = p)
       when is_binary(cmid) and is_binary(to) and is_binary(call_id) do
    cmid = String.downcase(cmid)
    to = String.downcase(to)

    cond do
      Map.has_key?(p, "conversation_id") or String.starts_with?(to, "grp:") ->
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

      true ->
        with :ok <- Messaging.validate_ciphertext(p["ciphertext"]) do
          {:ok,
           %{
             client_msg_id: cmid,
             to: to,
             call_id: call_id,
             ring: p["ring"],
             ciphertext: p["ciphertext"],
             generation: p["generation"],
             epoch: p["epoch"]
           }}
        end
    end
  end

  defp parse(_), do: {:error, :bad_request}

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
      if req.ring, do: ring(req.to, req.call_id)

      :telemetry.execute([:risime, :call, :signal], %{count: 1}, %{ring: req.ring})
      {:ok, reply}
    end
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

  defp check_ready(%{ring: true, to: to}) do
    if Devices.calls_device?(to), do: :ok, else: {:error, :calls_not_ready}
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
  defp ring(callee_id, call_id) do
    targets = Devices.calls_push_targets(callee_id)
    {offline, live} = Enum.split_with(targets, fn {d, _} -> not Presence.device_online?(d) end)
    RisiMe.Push.Dispatcher.push_call(Enum.map(offline, &elem(&1, 1)))
    if live != [], do: State.arm_fallback(callee_id, call_id, Enum.map(live, &elem(&1, 1)))
    :ok
  end

  defp uuid?(s), do: Regex.match?(@uuid, s)
end
