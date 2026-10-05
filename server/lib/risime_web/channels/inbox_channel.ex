defmodule RisiMeWeb.InboxChannel do
  @moduledoc """
  `inbox:<own user id>` (PROTOCOL.md §2). The join reply and `sync` replies page through stored
  events; new events are pushed live as `"event"`.
  """
  use RisiMeWeb, :channel

  alias RisiMe.Messaging

  @impl true
  def join("inbox:" <> user_id, payload, socket) do
    if user_id == socket.assigns.user_id do
      # Subscribe before reading the backlog so nothing falls between the two.
      :ok = Messaging.subscribe(user_id)
      payload = if is_map(payload), do: payload, else: %{}

      case page(user_id, payload) do
        {:ok, reply} -> {:ok, reply, socket}
        {:error, reason} -> {:error, %{reason: to_string(reason)}}
      end
    else
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
    case Messaging.send(socket.assigns.user_id, payload) do
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

  def handle_in(_event, _payload, socket) do
    {:reply, {:error, %{reason: "bad_request"}}, socket}
  end

  @impl true
  def handle_info({:inbox_event, event}, socket) do
    push(socket, "event", event)
    {:noreply, socket}
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
