defmodule RisiMeWeb.InboxChannel do
  @moduledoc """
  `inbox:<own user id>` (PROTOCOL.md §2). The join reply and `sync` replies page through stored
  events; new events are pushed live as `"event"`.
  Ephemeral presence and typing state is pushed as `"signal"` (v1.2 §2.3, §2.5, §2.6); it is
  never stored or replayed.
  """
  use RisiMeWeb, :channel

  require Logger

  alias RisiMe.{Accounts, Messaging, Presence, SocketTracker}

  @max_watch 200

  @impl true
  def join("inbox:" <> user_id, payload, socket) do
    if user_id == socket.assigns.user_id do
      # Subscribe before reading the backlog so nothing falls between the two.
      :ok = Messaging.subscribe(user_id)
      payload = if is_map(payload), do: payload, else: %{}

      case page(user_id, payload) do
        {:ok, reply} ->
          :telemetry.execute([:risime, :inbox, :join], %{count: 1}, %{result: :ok})
          :ok = Presence.track(user_id)

          if socket_id = socket.assigns[:socket_id],
            do: Phoenix.PubSub.subscribe(RisiMe.PubSub, SocketTracker.control_topic(socket_id))

          touch_last_seen(user_id)
          {:ok, reply, assign(socket, :watching, MapSet.new())}

        {:error, reason} ->
          :telemetry.execute([:risime, :inbox, :join], %{count: 1}, %{result: reason})
          {:error, %{reason: to_string(reason)}}
      end
    else
      :telemetry.execute([:risime, :inbox, :join], %{count: 1}, %{result: :unauthorized})
      {:error, %{reason: "unauthorized"}}
    end
  end

  @impl true
  def handle_in("sync", payload, socket) when is_map(payload) do
    case page(socket.assigns.user_id, payload) do
      {:ok, reply} -> {:reply, {:ok, reply}, socket}
      {:error, reason} -> {:reply, {:error, %{reason: to_string(reason)}}, socket}
    end
  end

  def handle_in("msg:send", payload, socket) when is_map(payload) do
    case Messaging.send(socket.assigns.user_id, payload, device_id: socket.assigns[:device_id]) do
      {:ok, reply} -> {:reply, {:ok, reply}, socket}
      {:error, reason} -> {:reply, {:error, %{reason: to_string(reason)}}, socket}
    end
  end

  def handle_in("msg:ack", payload, socket) when is_map(payload) do
    case Messaging.ack(socket.assigns.user_id, payload["message_ids"], payload["status"]) do
      :ok -> {:reply, {:ok, %{}}, socket}
      {:error, reason} -> {:reply, {:error, %{reason: to_string(reason)}}, socket}
    end
  end

  def handle_in("presence:watch", %{"user_ids" => ids}, socket)
      when is_list(ids) and length(ids) <= @max_watch do
    if Enum.all?(ids, &is_binary/1) do
      # v1.6 §9.3: friends only (and yourself); other ids are left out like unknown ones.
      me = socket.assigns.user_id
      allowed = MapSet.new([me | RisiMe.Social.friend_ids(me)])

      ids =
        ids
        |> Enum.map(&String.downcase/1)
        |> Enum.uniq()
        |> Enum.filter(&MapSet.member?(allowed, &1))

      new = MapSet.new(ids)
      old = socket.assigns.watching

      # Subscribe before reading state so no change falls in between.
      for id <- MapSet.difference(old, new), do: Presence.unsubscribe(id)
      for id <- MapSet.difference(new, old), do: Presence.subscribe(id)

      {:reply, {:ok, %{presences: Presence.presences(ids)}}, assign(socket, :watching, new)}
    else
      {:reply, {:error, %{reason: "bad_request"}}, socket}
    end
  end

  def handle_in("typing", payload, socket) when is_map(payload) do
    case Messaging.typing(socket.assigns.user_id, payload) do
      :ok -> {:reply, {:ok, %{}}, socket}
      {:error, reason} -> {:reply, {:error, %{reason: to_string(reason)}}, socket}
    end
  end

  # Contract v1.3 §6.2: a fresh access token for the same user moves the expiry deadline.
  def handle_in("auth:refresh", %{"token" => token}, socket) when is_binary(token) do
    user_id = socket.assigns.user_id

    case RisiMe.Auth.authenticate(token) do
      {:ok, %{kind: :jwt, user: %{id: ^user_id} = user, exp: exp}} ->
        if Accounts.phone_verified?(user, :jwt) do
          :ok = SocketTracker.refresh(socket.transport_pid, exp)
          expires_at = exp |> DateTime.from_unix!() |> Messaging.iso()
          {:reply, {:ok, %{expires_at: expires_at}}, assign(socket, :auth_kind, :jwt)}
        else
          {:reply, {:error, %{reason: "phone_unverified"}}, socket}
        end

      {:ok, %{kind: :jwt}} ->
        {:reply, {:error, %{reason: "identity_mismatch"}}, socket}

      {:error, :identity_conflict} ->
        {:reply, {:error, %{reason: "identity_mismatch"}}, socket}

      {:error, :not_allowlisted} ->
        {:reply, {:error, %{reason: "not_allowlisted"}}, socket}

      _ ->
        {:reply, {:error, %{reason: "invalid_token"}}, socket}
    end
  end

  def handle_in("auth:refresh", _payload, socket) do
    {:reply, {:error, %{reason: "bad_request"}}, socket}
  end

  def handle_in(_event, _payload, socket) do
    {:reply, {:error, %{reason: "bad_request"}}, socket}
  end

  @impl true
  def handle_info({:inbox_event, event}, socket) do
    push(socket, "event", event)
    {:noreply, socket}
  end

  def handle_info({:auth_expired}, socket) do
    push(socket, "auth:expired", %{})
    # Same sender as the push, so the client gets auth:expired before the socket closes.
    RisiMeWeb.Endpoint.broadcast(socket.id, "disconnect", %{})
    {:noreply, socket}
  end

  # v1.6: unfriend or block drops the other user from this channel's watch set at once.
  def handle_info({:drop_watch, other_id}, socket) do
    if MapSet.member?(socket.assigns.watching, other_id) do
      Presence.unsubscribe(other_id)
      {:noreply, assign(socket, :watching, MapSet.delete(socket.assigns.watching, other_id))}
    else
      {:noreply, socket}
    end
  end

  def handle_info({:signal, signal}, socket) do
    push(socket, "signal", signal)
    {:noreply, socket}
  end

  def handle_info({:presence_signal, %{"user_id" => id} = presence}, socket) do
    # A change can still arrive just after its user was dropped from the watch list.
    if MapSet.member?(socket.assigns.watching, id),
      do: push(socket, "signal", %{kind: "presence", data: presence})

    {:noreply, socket}
  end

  @impl true
  def terminate(_reason, socket) do
    if socket.joined, do: touch_last_seen(socket.assigns.user_id)
    :ok
  end

  # last_seen is best effort: a failed write must never break a join or a leave.
  defp touch_last_seen(user_id) do
    Accounts.touch_last_seen(user_id, DateTime.utc_now())
  rescue
    e -> Logger.debug("last_seen write failed: #{Exception.message(e)}")
  catch
    :exit, _ -> :ok
  end

  defp page(user_id, payload) do
    with {:ok, events, has_more} <-
           Messaging.fetch_events(user_id, payload["since"], payload["limit"]) do
      {:ok,
       %{
         events: events,
         has_more: has_more,
         server_time: Messaging.iso(DateTime.utc_now())
       }}
    end
  end
end
