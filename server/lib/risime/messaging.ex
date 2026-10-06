defmodule RisiMe.Messaging do
  @moduledoc """
  One-to-one messaging with store-and-forward per-user inboxes (PROTOCOL.md §2).

  Every change a user must see is appended to their inbox as an event and broadcast on
  `topic/1`. Clients fetch from a cursor (`since`) and receive live events while connected.
  A message event's `event_id` equals its `message_id`, which makes re-delivery after a
  partial failure idempotent. Clients must not rely on that.
  """

  alias RisiMe.{Accounts, RateLimiter, TimeUUID}
  alias RisiMe.Messaging.Store

  @max_body 4096
  @max_page 500
  @send_limit 20
  @send_window :timer.seconds(10)
  @status_rank %{"sent" => 0, "delivered" => 1, "read" => 2}

  @uuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  @timeuuid ~r/^[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

  @doc "Internal PubSub topic for a user's live inbox events."
  def topic(user_id), do: "inbox_live:" <> user_id

  def subscribe(user_id), do: Phoenix.PubSub.subscribe(RisiMe.PubSub, topic(user_id))

  @doc "`dm:<a>_<b>` with the two lowercase user ids sorted (PROTOCOL.md §2.4)."
  def conversation_id(a, b) do
    [x, y] = Enum.sort([String.downcase(a), String.downcase(b)])
    "dm:#{x}_#{y}"
  end

  def max_page, do: @max_page

  ## Send

  @type send_error ::
          :unknown_recipient
          | :not_friends
          | :empty_body
          | :too_long
          | :rate_limited
          | :bad_request

  @doc """
  Sends a DM. `params` is the `msg:send` payload. Idempotent per (sender, client_msg_id):
  a repeat returns the original reply and creates nothing new.
  """
  @spec send(String.t(), map) :: {:ok, map} | {:error, send_error}
  def send(sender_id, params, opts \\ []) do
    # Emits [:risime, :message, :send, :start | :stop | :exception]; :stop has the duration
    # and `result` (:ok or the error reason). Never the body.
    :telemetry.span([:risime, :message, :send], %{}, fn ->
      result = do_send(sender_id, params, opts[:device_id])
      {result, %{result: result_tag(result)}}
    end)
  end

  defp result_tag({:ok, _}), do: :ok
  defp result_tag({:error, reason}), do: reason

  # v1.7 §10.3 order: idempotent resend → not_friends → e2ee checks → rate limit → store.
  defp do_send(sender_id, params, device_id) do
    with {:ok, req} <- parse_send(params),
         :ok <- validate_body(req) do
      req = Map.put(req, :from_device, device_id)

      case store().get_sent(sender_id, req.client_msg_id) do
        {:ok, prior} -> {:ok, resend(sender_id, req, prior)}
        :not_found -> send_new(sender_id, req)
      end
    end
  end

  @max_ciphertext 16 * 1024

  # v1.7: an e2ee send carries `ciphertext`, `generation` and `epoch` and no `body`.
  defp parse_send(%{"client_msg_id" => cmid, "to" => to, "ciphertext" => ct} = p)
       when is_binary(cmid) and is_binary(to) and not is_map_key(p, "body") do
    cmid = String.downcase(cmid)
    to = String.downcase(to)

    cond do
      not uuid?(cmid) ->
        {:error, :bad_request}

      not uuid?(to) ->
        {:error, :unknown_recipient}

      not (is_integer(p["generation"]) and is_integer(p["epoch"])) ->
        {:error, :bad_request}

      not is_binary(ct) ->
        {:error, :bad_request}

      true ->
        {:ok,
         %{
           client_msg_id: cmid,
           to: to,
           ciphertext: ct,
           generation: p["generation"],
           epoch: p["epoch"],
           body: nil
         }}
    end
  end

  defp parse_send(%{"client_msg_id" => cmid, "to" => to, "body" => body})
       when is_binary(cmid) and is_binary(to) and is_binary(body) do
    cmid = String.downcase(cmid)
    to = String.downcase(to)

    cond do
      not uuid?(cmid) -> {:error, :bad_request}
      not uuid?(to) -> {:error, :unknown_recipient}
      true -> {:ok, %{client_msg_id: cmid, to: to, body: body}}
    end
  end

  defp parse_send(%{"to" => _, "client_msg_id" => _}), do: {:error, :empty_body}
  defp parse_send(_), do: {:error, :bad_request}

  defp validate_body(%{ciphertext: ct}) do
    case Base.decode64(ct) do
      {:ok, bin} when byte_size(bin) > @max_ciphertext -> {:error, :too_long}
      {:ok, bin} when byte_size(bin) > 0 -> :ok
      _ -> {:error, :bad_request}
    end
  end

  defp validate_body(%{body: body}) do
    cond do
      String.trim(body) == "" -> {:error, :empty_body}
      String.length(body) > @max_body -> {:error, :too_long}
      true -> :ok
    end
  end

  defp send_new(sender_id, req) do
    with :ok <- check_recipient(sender_id, req.to),
         :ok <- check_e2ee(sender_id, req),
         :ok <- RateLimiter.hit(:msg_send, sender_id, @send_limit, @send_window) do
      sent = %{
        message_id: TimeUUID.generate(),
        conversation_id: conversation_id(sender_id, req.to),
        server_ts: now()
      }

      case store().claim_send(sender_id, req.client_msg_id, sent) do
        :ok ->
          deliver(sender_id, req, sent)
          {:ok, send_reply(sent)}

        {:exists, prior} ->
          {:ok, resend(sender_id, req, prior)}
      end
    end
  end

  # v1.6 §9.3: `unknown_recipient` only for yourself (malformed ids are caught in parsing);
  # every other non-friend, unknown ids included, is `not_friends`. While the phone gate is on,
  # an unverified friend can't be messaged either.
  defp check_recipient(sender_id, to) do
    cond do
      to == sender_id -> {:error, :unknown_recipient}
      RisiMe.Social.friends?(sender_id, to) and Accounts.messageable?(to) -> :ok
      true -> {:error, :not_friends}
    end
  end

  # v1.7 §10.3: plaintext to an e2ee conversation, ciphertext to a plaintext one, stale
  # generation/epoch, or ciphertext from a socket without a device id.
  defp check_e2ee(sender_id, req) do
    group = RisiMe.MLS.group(conversation_id(sender_id, req.to))

    cond do
      req.body != nil and group != nil -> {:error, :e2ee_required}
      req.body != nil -> :ok
      group == nil or req.from_device == nil -> {:error, :bad_request}
      req.generation != group.generation or req.epoch != group.epoch -> {:error, :stale_epoch}
      true -> :ok
    end
  end

  # A repeat of an earlier send. If that send stopped before indexing the message, finish it.
  defp resend(sender_id, req, prior) do
    if store().get_message(prior.message_id) == :not_found, do: deliver(sender_id, req, prior)
    send_reply(prior)
  end

  defp deliver(sender_id, req, sent) do
    :ok =
      store().put_message(%{
        message_id: sent.message_id,
        sender_id: sender_id,
        recipient_id: req.to,
        client_msg_id: req.client_msg_id,
        conversation_id: sent.conversation_id,
        status: "sent"
      })

    base = %{
      "message_id" => sent.message_id,
      "client_msg_id" => req.client_msg_id,
      "conversation_id" => sent.conversation_id,
      "from" => sender_id,
      "to" => req.to,
      "server_ts" => iso(sent.server_ts)
    }

    if req.body do
      publish(req.to, %{
        event_id: sent.message_id,
        kind: "message",
        data: Map.put(base, "body", req.body)
      })
    else
      # v1.7: ciphertext only, and the sender's inbox too (their other devices; the sending
      # device skips it by from_device).
      data =
        Map.merge(base, %{
          "from_device" => req.from_device,
          "ciphertext" => req.ciphertext,
          "generation" => req.generation,
          "epoch" => req.epoch
        })

      event = %{event_id: sent.message_id, kind: "message", data: data}
      publish(req.to, event)
      publish(sender_id, event, push: false)
    end
  end

  defp send_reply(sent) do
    %{
      message_id: sent.message_id,
      conversation_id: sent.conversation_id,
      server_ts: iso(sent.server_ts)
    }
  end

  ## Typing (PROTOCOL.md v1.2 §2.6)

  @typing_limit 2
  @typing_window 1_000

  @doc """
  Forwards a `typing` push to the recipient's connected inbox channels as an ephemeral
  `signal`: never stored, dropped if nobody is connected. `typing: true` above 2 per second
  per sender is dropped silently (still `:ok`); `typing: false` is never limited.
  """
  @spec typing(String.t(), map) ::
          :ok | {:error, :unknown_recipient | :not_friends | :bad_request}
  def typing(sender_id, %{"to" => to, "typing" => typing})
      when is_binary(to) and is_boolean(typing) do
    to = String.downcase(to)

    cond do
      not uuid?(to) or to == sender_id ->
        {:error, :unknown_recipient}

      not (RisiMe.Social.friends?(sender_id, to) and Accounts.messageable?(to)) ->
        {:error, :not_friends}

      typing and RateLimiter.hit(:typing, sender_id, @typing_limit, @typing_window) != :ok ->
        :ok

      true ->
        signal(to, %{
          kind: "typing",
          data: %{
            "from" => sender_id,
            "conversation_id" => conversation_id(sender_id, to),
            "typing" => typing
          }
        })
    end
  end

  def typing(_sender_id, _params), do: {:error, :bad_request}

  @doc "Sends an ephemeral signal to a user's live inbox channels (not stored)."
  def signal(user_id, signal) do
    Phoenix.PubSub.broadcast(RisiMe.PubSub, topic(user_id), {:signal, signal})
  end

  ## Ack

  @doc """
  Applies a `delivered` or `read` ack from the recipient. Unknown ids and messages the user
  did not receive are ignored. Status only moves forward; each change is sent to the sender
  as a `status` event.
  """
  @spec ack(String.t(), term, term) :: :ok | {:error, :bad_request}
  def ack(user_id, message_ids, status)
      when is_list(message_ids) and status in ["delivered", "read"] and
             length(message_ids) <= @max_page do
    ids = for id <- message_ids, is_binary(id), do: String.downcase(id)

    ids = for id <- Enum.uniq(ids), timeuuid?(id), do: id

    for id <- ids do
      case store().get_message(id) do
        {:ok, %{recipient_id: ^user_id} = message} -> advance(message, status, user_id)
        _ -> :ok
      end
    end

    :telemetry.execute([:risime, :message, :ack], %{count: length(ids)}, %{
      status: if(status == "read", do: :read, else: :delivered)
    })

    :ok
  end

  def ack(_user_id, _message_ids, _status), do: {:error, :bad_request}

  defp advance(message, status, by) do
    if @status_rank[status] > @status_rank[message.status] do
      case store().compare_and_set_status(message.message_id, message.status, status) do
        :ok -> publish_status(message, status, by)
        {:conflict, current} -> advance(%{message | status: current}, status, by)
      end
    end

    :ok
  end

  defp publish_status(message, status, by) do
    publish(message.sender_id, %{
      event_id: TimeUUID.generate(),
      kind: "status",
      data: %{
        "message_id" => message.message_id,
        "client_msg_id" => message.client_msg_id,
        "conversation_id" => message.conversation_id,
        "status" => status,
        "by" => by,
        "at" => iso(now())
      }
    })
  end

  ## Inbox

  @doc """
  Events after `since` (nil = from the start), oldest first, at most `limit` (default and
  maximum #{@max_page}). Returns `{:ok, events, has_more}`.
  """
  @spec fetch_events(String.t(), term, term) :: {:ok, [map], boolean} | {:error, :bad_request}
  def fetch_events(user_id, since, limit \\ nil) do
    limit = if is_integer(limit) and limit > 0, do: min(limit, @max_page), else: @max_page
    since = if is_binary(since), do: String.downcase(since), else: since

    if since == nil or (is_binary(since) and timeuuid?(since)) do
      events = store().list_events(user_id, since, limit + 1)
      {:ok, Enum.take(events, limit), length(events) > limit}
    else
      {:error, :bad_request}
    end
  end

  defp publish(user_id, event, opts \\ []) do
    :ok = store().append_event(user_id, event)
    Phoenix.PubSub.broadcast(RisiMe.PubSub, topic(user_id), {:inbox_event, event})
    # Contract v1.5: a data-only wake-up if the user has no live inbox channel.
    if Keyword.get(opts, :push, true), do: RisiMe.Push.notify(user_id)
  end

  @doc "Stores and pushes an MLS inbox event (`mls_commit`, `mls_welcome`, `mls_membership`); never a push (§10.3)."
  def publish_mls(user_id, kind, data) do
    publish(user_id, %{event_id: TimeUUID.generate(), kind: kind, data: data}, push: false)
  end

  @doc "Health of the message store (`GET /health`)."
  def store_health, do: store().health()

  ## Helpers

  @doc "ISO-8601 UTC with milliseconds, e.g. `2026-10-06T08:15:30.123Z`."
  def iso(%DateTime{} = dt) do
    {us, _} = dt.microsecond
    %{dt | microsecond: {div(us, 1000) * 1000, 3}} |> DateTime.to_iso8601()
  end

  defp now, do: DateTime.utc_now() |> DateTime.truncate(:millisecond)

  defp uuid?(s), do: Regex.match?(@uuid, s)
  defp timeuuid?(s), do: Regex.match?(@timeuuid, s)

  defp store, do: Store.impl()
end
