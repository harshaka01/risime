defmodule RisiMe.Messaging do
  @moduledoc """
  One-to-one messaging with store-and-forward per-user inboxes (PROTOCOL.md §2).

  Every change a user must see is appended to their inbox as an event and broadcast on
  `topic/1`. Clients fetch from a cursor (`since`) and receive live events while connected.
  A message event's `event_id` equals its `message_id`, which makes re-delivery after a
  partial failure idempotent. Clients must not rely on that.
  """

  alias RisiMe.{Accounts, RateLimiter, TimeUUID}
  alias RisiMe.Messaging.{GroupReceipts, Store}

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

  # v1.9 §12.9: a group send carries `conversation_id` (and never `to`).
  defp do_send(sender_id, %{"conversation_id" => _} = params, device_id),
    do: send_group(sender_id, params, device_id)

  # v1.7 §10.3 order: idempotent resend → not_friends → e2ee checks → rate limit → store.
  defp do_send(sender_id, params, device_id) do
    with {:ok, silent} <- parse_silent(params),
         {:ok, req} <- parse_send(params),
         :ok <- validate_body(req) do
      req = Map.put(req, :silent, silent)
      req = Map.put(req, :from_device, device_id)

      case store().get_sent(sender_id, req.client_msg_id) do
        # v1.12 §15.2: a client_msg_id already used by another push (msg:delete).
        {:ok, %{kind: k}} when k != nil -> {:error, :bad_request}
        {:ok, prior} -> {:ok, resend(sender_id, req, prior)}
        :not_found -> send_new(sender_id, req)
      end
    end
  end

  # v1.17 §18.4: the optional `silent` (absent or `false` = not silent) is a boolean, and `true`
  # only on an e2ee send (never with `body` or `reaction`).
  defp parse_silent(p) when is_map(p) do
    case Map.fetch(p, "silent") do
      :error -> {:ok, false}
      {:ok, false} -> {:ok, false}
      {:ok, true} -> if plaintext_fields?(p), do: {:error, :bad_request}, else: {:ok, true}
      {:ok, _} -> {:error, :bad_request}
    end
  end

  defp parse_silent(_), do: {:error, :bad_request}

  defp plaintext_fields?(p), do: Map.has_key?(p, "body") or Map.has_key?(p, "reaction")

  # PROTOCOL §10.3: 24 KiB decoded, enough for a max-size text in the envelope plus MLS framing.
  @max_ciphertext 24 * 1024

  @content_fields ~w(body reaction ciphertext)
  @max_body_bytes 16 * 1024

  # v1.8 §11.2: exactly one content field.
  defp parse_send(p) when is_map(p) do
    case Enum.count(@content_fields, &Map.has_key?(p, &1)) do
      n when n > 1 -> {:error, :bad_request}
      _ -> parse_content(p)
    end
  end

  defp parse_send(_), do: {:error, :bad_request}

  # v1.8: a plaintext reaction carries `reaction: {target, emoji, op}` and no `body`.
  defp parse_content(%{"client_msg_id" => cmid, "to" => to, "reaction" => r})
       when is_binary(cmid) and is_binary(to) do
    cmid = String.downcase(cmid)
    to = String.downcase(to)

    cond do
      not uuid?(cmid) -> {:error, :bad_request}
      not uuid?(to) -> {:error, :unknown_recipient}
      not is_map(r) -> {:error, :bad_request}
      r["op"] not in ["add", "remove"] -> {:error, :bad_request}
      true -> {:ok, %{client_msg_id: cmid, to: to, body: nil, reaction: r}}
    end
  end

  # v1.7: an e2ee send carries `ciphertext`, `generation` and `epoch` and no `body`.
  defp parse_content(%{"client_msg_id" => cmid, "to" => to, "ciphertext" => ct} = p)
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
           body: nil,
           reaction: nil
         }}
    end
  end

  defp parse_content(%{"client_msg_id" => cmid, "to" => to, "body" => body})
       when is_binary(cmid) and is_binary(to) and is_binary(body) do
    cmid = String.downcase(cmid)
    to = String.downcase(to)

    cond do
      not uuid?(cmid) -> {:error, :bad_request}
      not uuid?(to) -> {:error, :unknown_recipient}
      true -> {:ok, %{client_msg_id: cmid, to: to, body: body, reaction: nil}}
    end
  end

  defp parse_content(%{"to" => _, "client_msg_id" => _}), do: {:error, :empty_body}
  defp parse_content(_), do: {:error, :bad_request}

  defp validate_body(%{ciphertext: ct}) do
    case Base.decode64(ct) do
      {:ok, bin} when byte_size(bin) > @max_ciphertext -> {:error, :too_long}
      # v1.12 §15.3: only a `delete` control (msg:delete) carries authenticated_data.
      {:ok, bin} when byte_size(bin) > 0 -> if aad?(bin), do: {:error, :bad_request}, else: :ok
      _ -> {:error, :bad_request}
    end
  end

  # Reactions are checked later, after not_friends and e2ee (§11.2 order of checks).
  defp validate_body(%{reaction: r}) when is_map(r), do: :ok

  # v1.8 §11.1: the byte cap first, then the grapheme count (authoritative).
  defp validate_body(%{body: body}) do
    cond do
      byte_size(body) > @max_body_bytes -> {:error, :too_long}
      String.trim(body) == "" -> {:error, :empty_body}
      String.length(body) > @max_body -> {:error, :too_long}
      true -> :ok
    end
  end

  @doc false
  # v1.13 §16.3: the `call:signal` ciphertext follows the msg:send rules (24 KiB decoded, no AAD).
  def validate_ciphertext(ct) when is_binary(ct), do: validate_body(%{ciphertext: ct})
  def validate_ciphertext(_ct), do: {:error, :bad_request}

  defp aad?(bin) do
    case RisiMe.MLS.Wire.private_message(bin) do
      {:ok, %{authenticated_data: aad}} -> aad != ""
      :error -> false
    end
  end

  defp send_new(sender_id, req) do
    with :ok <- check_recipient(sender_id, req.to),
         :ok <- check_e2ee(sender_id, req),
         :ok <- check_reaction(sender_id, req),
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

        {:exists, %{kind: k}} when k != nil ->
          {:error, :bad_request}

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

    plaintext? = req.body != nil or req.reaction != nil

    cond do
      plaintext? and group != nil -> {:error, :e2ee_required}
      plaintext? -> :ok
      group == nil or req.from_device == nil -> {:error, :bad_request}
      req.generation != group.generation or req.epoch != group.epoch -> {:error, :stale_epoch}
      true -> :ok
    end
  end

  # v1.8 §11.2: the target is a normal message of this conversation (one primary-key read; every
  # failure is the same `unknown_target`), and the emoji is one grapheme of at most 32 bytes.
  defp check_reaction(_sender_id, %{reaction: nil}), do: :ok

  defp check_reaction(sender_id, %{reaction: r, to: to}) do
    conv = conversation_id(sender_id, to)
    target = if is_binary(r["target"]), do: String.downcase(r["target"])

    cond do
      not valid_emoji?(r["emoji"]) ->
        {:error, :invalid_emoji}

      not (is_binary(target) and timeuuid?(target)) ->
        {:error, :unknown_target}

      true ->
        # v1.12 §15.8: a deleted (tombstoned) target is unknown too.
        case store().get_message(target) do
          {:ok, %{conversation_id: ^conv, kind: nil, deleted_at: nil}} -> :ok
          _ -> {:error, :unknown_target}
        end
    end
  end

  # Control, separator, private-use and surrogate code points are refused; format characters
  # only as ZWJ (U+200D) or emoji tag characters (U+E0020–U+E007F, subdivision flags).
  # Variation selectors are marks, so they pass.
  @doc false
  def valid_emoji?(e) when is_binary(e) do
    String.valid?(e) and byte_size(e) in 1..32 and String.length(e) == 1 and
      not Regex.match?(~r/[\p{Cc}\p{Z}\p{Co}\p{Cs}\s]/u, e) and
      e
      |> String.to_charlist()
      |> Enum.reject(&(&1 == 0x200D or &1 in 0xE0020..0xE007F))
      |> List.to_string()
      |> then(&(not Regex.match?(~r/\p{Cf}/u, &1)))
  end

  def valid_emoji?(_), do: false

  # A repeat of an earlier send. If that send stopped before indexing the message, finish it.
  defp resend(sender_id, req, prior) do
    if store().get_message(prior.message_id) == :not_found, do: deliver(sender_id, req, prior)
    send_reply(prior)
  end

  defp deliver(sender_id, %{reaction: r} = req, sent) when is_map(r) do
    target = String.downcase(r["target"])

    # v1.12 §15.8 Q8: the refs rows go before the events they describe (never in one batch),
    # so a crash leaves at most a dangling ref.
    for u <- [sender_id, req.to],
        do: :ok = store().put_message_ref(target, u, sent.message_id, sent.message_id)

    :ok =
      store().put_message(%{
        message_id: sent.message_id,
        sender_id: sender_id,
        recipient_id: req.to,
        client_msg_id: req.client_msg_id,
        conversation_id: sent.conversation_id,
        status: "sent",
        kind: "reaction"
      })

    event = %{
      event_id: sent.message_id,
      kind: "reaction",
      data: %{
        "message_id" => sent.message_id,
        "client_msg_id" => req.client_msg_id,
        "conversation_id" => sent.conversation_id,
        "from" => sender_id,
        "to" => req.to,
        "target" => target,
        "emoji" => r["emoji"],
        "op" => r["op"],
        "server_ts" => iso(sent.server_ts)
      }
    }

    # Both inboxes, the same event_id, the sender's first (§13.1); reactions never push (§11.2).
    publish_dm(sender_id, req.to, event, false)
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

    data =
      if req.body do
        Map.put(base, "body", req.body)
      else
        # v1.7: ciphertext only (the sending device skips its own copy by from_device).
        Map.merge(base, %{
          "from_device" => req.from_device,
          "ciphertext" => req.ciphertext,
          "generation" => req.generation,
          "epoch" => req.epoch
        })
      end

    # v1.17 §18.4: a silent message is stored and delivered as usual but never pushed.
    silent? = req[:silent] == true
    data = if silent?, do: Map.put(data, "silent", true), else: data
    event = %{event_id: sent.message_id, kind: "message", data: data}

    # v1.10 §13.1: plaintext and e2ee alike go to both inboxes under the same event_id.
    publish_dm(sender_id, req.to, event, not silent?)
  end

  # Both inbox rows are written in one store request, then the sender's copy is broadcast first
  # and never pushed, so a recipient's live ack can never put a `status` ahead of the copy on
  # the sender's other devices (§13.1).
  defp publish_dm(sender_id, to, event, push_recipient?) do
    :ok = store().append_event_to_all([sender_id, to], event)
    broadcast(sender_id, event)
    broadcast(to, event)
    if push_recipient?, do: RisiMe.Push.notify(to)
    :ok
  end

  ## Group send (v1.9 §12.9)

  # Order: idempotent resend → not_member → e2ee checks → rate limit → store.
  defp send_group(sender_id, p, device_id) do
    with {:ok, silent} <- parse_silent(p),
         {:ok, req} <- parse_group_send(p) do
      req = req |> Map.put(:from_device, device_id) |> Map.put(:silent, silent)

      case store().get_sent(sender_id, req.client_msg_id) do
        {:ok, %{kind: k}} when k != nil ->
          {:error, :bad_request}

        {:ok, prior} ->
          if (req.ciphertext && store().get_message(prior.message_id) == :not_found) and
               RisiMe.Groups.active_member?(req.conversation_id, sender_id),
             do: deliver_group(sender_id, req, prior)

          {:ok, send_reply(prior)}

        :not_found ->
          send_group_new(sender_id, req)
      end
    end
  end

  defp parse_group_send(p) do
    cmid = p["client_msg_id"]
    conv = p["conversation_id"]

    cond do
      Map.has_key?(p, "to") ->
        {:error, :bad_request}

      Enum.count(@content_fields, &Map.has_key?(p, &1)) != 1 ->
        {:error, :bad_request}

      not (is_binary(cmid) and uuid?(String.downcase(cmid))) ->
        {:error, :bad_request}

      not (is_binary(conv) and String.starts_with?(conv, "grp:")) ->
        {:error, :bad_request}

      true ->
        {:ok,
         %{
           client_msg_id: String.downcase(cmid),
           conversation_id: conv,
           ciphertext: p["ciphertext"],
           plaintext?: Map.has_key?(p, "body") or Map.has_key?(p, "reaction"),
           generation: p["generation"],
           epoch: p["epoch"]
         }}
    end
  end

  defp send_group_new(sender_id, req) do
    alias RisiMe.Groups

    with true <- Groups.active_member?(req.conversation_id, sender_id) || {:error, :not_member},
         # v1.24 §24.8: official_off (and a 1:1 Official's not_friends) before the e2ee checks.
         :ok <- RisiMe.Chats.send_check(req.conversation_id),
         :ok <- check_group_e2ee(req),
         :ok <- RateLimiter.hit(:msg_send, sender_id, @send_limit, @send_window) do
      sent = %{
        message_id: TimeUUID.generate(),
        conversation_id: req.conversation_id,
        server_ts: now()
      }

      case store().claim_send(sender_id, req.client_msg_id, sent) do
        :ok ->
          deliver_group(sender_id, req, sent)
          {:ok, send_reply(sent)}

        {:exists, %{kind: k}} when k != nil ->
          {:error, :bad_request}

        {:exists, prior} ->
          {:ok, send_reply(prior)}
      end
    end
  end

  defp check_group_e2ee(req) do
    g = RisiMe.Groups.get_group(req.conversation_id)

    cond do
      req.plaintext? ->
        {:error, :e2ee_required}

      not (is_binary(req.ciphertext) and is_integer(req.generation) and is_integer(req.epoch)) ->
        {:error, :bad_request}

      validate_body(%{ciphertext: req.ciphertext}) != :ok ->
        validate_body(%{ciphertext: req.ciphertext})

      req.from_device == nil ->
        {:error, :bad_request}

      req.generation != g.generation or req.epoch != RisiMe.Groups.epoch(g.id) ->
        {:error, :stale_epoch}

      true ->
        :ok
    end
  end

  # One `message` event per active member user, the sender included (their other devices; the
  # sending device skips it by from_device), all with the message id as event id (§12.7).
  defp deliver_group(sender_id, req, sent) do
    members = RisiMe.Groups.active_member_ids(req.conversation_id)
    others = members -- [sender_id]

    :ok =
      store().put_message(%{
        message_id: sent.message_id,
        sender_id: sender_id,
        recipient_id: nil,
        recipients: others,
        client_msg_id: req.client_msg_id,
        conversation_id: req.conversation_id,
        status: "sent"
      })

    data = %{
      "message_id" => sent.message_id,
      "client_msg_id" => req.client_msg_id,
      "conversation_id" => req.conversation_id,
      "from" => sender_id,
      "from_device" => req.from_device,
      "ciphertext" => req.ciphertext,
      "generation" => req.generation,
      "epoch" => req.epoch,
      "server_ts" => iso(sent.server_ts)
    }

    # v1.17 §18.4: a silent message reaches every member as usual, with no push to anyone.
    silent? = req[:silent] == true
    data = if silent?, do: Map.put(data, "silent", true), else: data
    event = %{event_id: sent.message_id, kind: "message", data: data}

    publish_batch(
      [{sender_id, event, [push: false]}] ++ for(u <- others, do: {u, event, [push: not silent?]})
    )
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
          :ok | {:error, :unknown_recipient | :not_friends | :not_member | :bad_request}
  # v1.9 §12.9: group typing, to the other active members' (groups-capable) channels.
  def typing(sender_id, %{"conversation_id" => conv, "typing" => typing} = p)
      when is_boolean(typing) do
    cond do
      Map.has_key?(p, "to") or not (is_binary(conv) and String.starts_with?(conv, "grp:")) ->
        {:error, :bad_request}

      not RisiMe.Groups.active_member?(conv, sender_id) ->
        {:error, :not_member}

      typing and RateLimiter.hit(:typing_group, {sender_id, conv}, 1, 3_000) != :ok ->
        :ok

      true ->
        signal = %{
          kind: "typing",
          data: %{"from" => sender_id, "conversation_id" => conv, "typing" => typing}
        }

        for u <- RisiMe.Groups.active_member_ids(conv), u != sender_id, do: signal(u, signal)
        :ok
    end
  end

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
        # v1.8: reactions have no acks or status events; acks naming them are ignored. v1.12
        # §15.8: nor do `delete` events, and a deleted (tombstoned) message is absent.
        {:ok, %{kind: kind}} when kind in ["reaction", "delete"] ->
          :ok

        {:ok, %{deleted_at: %DateTime{}}} ->
          :ok

        # v1.10 §13.1: own copies are outgoing; a sender's ack of their own message (DM or
        # group) is ignored: no status, no receipt.
        {:ok, %{sender_id: ^user_id}} ->
          :ok

        {:ok, %{recipient_id: ^user_id} = message} ->
          advance(message, status, user_id)

        # v1.9: group acks feed the aggregated receipts; never a status event.
        {:ok, %{kind: nil, recipients: [_ | _]} = message} ->
          GroupReceipts.ack(message, user_id, status)

        _ ->
          :ok
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
  maximum #{@max_page}). Returns `{:ok, events, has_more}`. `include_calls?` (a `calls` socket,
  v1.13 §16.3) merges in the user's `call_signal` events.
  """
  @spec fetch_events(String.t(), term, term, boolean) ::
          {:ok, [map], boolean} | {:error, :bad_request}
  def fetch_events(user_id, since, limit \\ nil, include_calls? \\ false) do
    limit = if is_integer(limit) and limit > 0, do: min(limit, @max_page), else: @max_page
    since = if is_binary(since), do: String.downcase(since), else: since

    if since == nil or (is_binary(since) and timeuuid?(since)) do
      # v1.13 §16.3: a `calls` socket also reads the call-signal store, merged by event_id.
      events = store().list_events(user_id, since, limit + 1, include_calls?)
      {:ok, Enum.take(events, limit), length(events) > limit}
    else
      {:error, :bad_request}
    end
  end

  defp publish(user_id, event, opts \\ []) do
    :ok = store().append_event(user_id, event)
    broadcast(user_id, event)
    # Contract v1.5: a data-only wake-up if the user has no live inbox channel.
    if Keyword.get(opts, :push, true),
      do: RisiMe.Push.notify(user_id, Keyword.get_lazy(opts, :scope, fn -> push_scope(event) end))
  end

  # v1.24 §24.7: an Official event or a `chat_event` wakes only `tabs` devices.
  # v1.25 §25.2: a Risi-chat event (or `risi_tool_call`) wakes only `risi_tools` devices.
  defp push_scope(event), do: RisiMe.Groups.Tabs.scope(event)

  @doc false
  def broadcast(user_id, event),
    do: Phoenix.PubSub.broadcast(RisiMe.PubSub, topic(user_id), {:inbox_event, event})

  @doc """
  Publishes `[{user_id, event, opts}]`: per user in the given order, users in parallel (group
  fan-out, §12.7). `opts` as for a single publish (`push:`).
  """
  def publish_batch(items) do
    # v1.24 §24.7: the push scope is decided here, in the caller (inside the group transaction,
    # where a group created by it is visible), never in the fan-out tasks.
    scopes =
      items
      |> Enum.map(fn {_, event, _} -> event end)
      |> Enum.uniq_by(&scope_key/1)
      |> Map.new(&{scope_key(&1), push_scope(&1)})

    items = for {u, e, o} <- items, do: {u, e, Keyword.put_new(o, :scope, scopes[scope_key(e)])}

    case Enum.group_by(items, &elem(&1, 0)) do
      by_user when map_size(by_user) <= 1 ->
        for {u, event, opts} <- items, do: publish(u, event, opts)

      by_user ->
        by_user
        |> Task.async_stream(
          fn {u, list} -> for {_, event, opts} <- list, do: publish(u, event, opts) end,
          max_concurrency: 32,
          ordered: false,
          timeout: 30_000
        )
        |> Stream.run()
    end

    :ok
  end

  defp scope_key(%{kind: "chat_event"}), do: :chat_event
  defp scope_key(%{kind: "risi_tool_call"}), do: :risi_tool_call
  defp scope_key(%{data: data}) when is_map(data), do: data["conversation_id"] || data["group_id"]
  defp scope_key(_), do: nil

  @doc "Stores and pushes an MLS inbox event (`mls_commit`, `mls_welcome`, `mls_membership`); never a push (§10.3)."
  def publish_mls(user_id, kind, data) do
    publish(user_id, %{event_id: TimeUUID.generate(), kind: kind, data: data}, push: false)
  end

  @doc """
  v1.29 §29.6: stores and broadcasts a content-free inbox event (`risi_calendar_changed`); never
  a push wake (the inbox channel delivers it only to the sockets allowed to see it).
  """
  def publish_quiet(user_id, kind, data) do
    publish(user_id, %{event_id: TimeUUID.generate(), kind: kind, data: data}, push: false)
  end

  # v1.15 §17.5: history events live 48 h.
  @history_ttl_s 172_800

  @doc """
  v1.15 §17.5: stores a `history_*` event in `user_id`'s inbox with the 48 h TTL, broadcasts it
  and sends the content-free inbox wake (§8.2). Never a `message`: no index row, no acks.
  """
  def publish_history(user_id, kind, data) do
    event = %{event_id: TimeUUID.generate(), kind: kind, data: data}
    :ok = store().append_event(user_id, event, ttl: @history_ttl_s)
    broadcast(user_id, event)
    RisiMe.Push.notify(user_id, push_scope(event))
    :ok
  end

  def history_ttl_s, do: @history_ttl_s

  @doc """
  v1.25 §25.3: stores a per-device event (a `risi_tool_call`, `to_devices: [device_id]`) in
  `user_id`'s inbox with `ttl_s`, broadcasts it (the inbox channel delivers it only to that
  device's sockets) and, if that device has no live inbox channel, sends the content-free wake
  to **that device's token only**. Never a `message`: no index row, no acks.
  """
  def publish_device(user_id, device_id, event, ttl_s) do
    :ok = store().append_event(user_id, event, ttl: ttl_s)
    broadcast(user_id, event)

    unless RisiMe.Presence.device_online?(device_id),
      do: RisiMe.Push.Dispatcher.push_device(user_id, device_id)

    :ok
  end

  @doc "Deletes stored inbox events of `user_id` (a `risi_tool_call` once answered)."
  def delete_events(user_id, event_ids), do: store().delete_events(user_id, event_ids)

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
