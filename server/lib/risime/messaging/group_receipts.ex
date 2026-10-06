defmodule RisiMe.Messaging.GroupReceipts do
  @moduledoc """
  Aggregated group receipts (contract v1.9 §12.7, decision 041 R10).

  Members ack group messages as usual; the server keeps each member's delivered and read times
  (in the message store, `group_receipts`) and sends the sender's user `group_receipt` events:
  at once when `all_delivered` or `all_read` turns true, otherwise at most one per message per
  10 s. `read` implies `delivered`; `of` counts the recipients at send time who are still active.

  Aggregation state is cached per message in a partitioned set of GenServers (by message id),
  loaded from the store on a miss and dropped after 30 minutes idle, so an ack costs one cache
  update plus one row write instead of a partition read. Per node, like presence: a restart
  reloads from the store and may resend an unchanged receipt (harmless).
  """
  use GenServer

  require Logger

  alias RisiMe.{Groups, Messaging, TimeUUID}
  alias RisiMe.Messaging.Store

  @idle_ms :timer.minutes(30)
  @sweep_ms :timer.minutes(1)

  def child_spec(_opts) do
    %{
      id: __MODULE__,
      start:
        {PartitionSupervisor, :start_link,
         [
           [
             child_spec: %{id: :part, start: {GenServer, :start_link, [__MODULE__, :ok]}},
             name: __MODULE__.Partitions
           ]
         ]},
      type: :supervisor
    }
  end

  defp server(message_id), do: {:via, PartitionSupervisor, {__MODULE__.Partitions, message_id}}

  defp coalesce_ms, do: Application.get_env(:risime, :group_receipt_coalesce_ms, 10_000)

  @doc """
  Applies a member's ack (`delivered` | `read`) to a group message (a `Store.message` with
  `recipients`). Unknown members and repeats are no-ops.
  """
  def ack(%{recipients: recipients} = message, user_id, status) when is_list(recipients) do
    srv = server(message.message_id)
    now = DateTime.utc_now() |> DateTime.truncate(:millisecond)

    writes =
      case GenServer.call(srv, {:ack, message.message_id, user_id, status, now}) do
        :miss ->
          :ok = GenServer.call(srv, {:load, message.message_id, load(message)})
          GenServer.call(srv, {:ack, message.message_id, user_id, status, now})

        writes ->
          writes
      end

    case writes do
      {d, r} -> store().put_group_receipt(message.message_id, user_id, d, r)
      nil -> :ok
    end

    :ok
  end

  defp load(message) do
    rows = store().list_group_receipts(message.message_id)

    %{
      sender: message.sender_id,
      client_msg_id: message.client_msg_id,
      conv: message.conversation_id,
      recipients: MapSet.new(message.recipients),
      active: active(message.conversation_id, message.recipients),
      delivered: for(%{delivered_at: t} = r <- rows, t != nil, into: %{}, do: {r.user_id, t}),
      read: for(%{read_at: t} = r <- rows, t != nil, into: %{}, do: {r.user_id, t}),
      last: nil,
      last_emit: nil,
      timer: nil
    }
  end

  defp active(conv, recipients) do
    conv
    |> Groups.active_member_ids()
    |> MapSet.new()
    |> MapSet.intersection(MapSet.new(recipients))
  end

  @doc """
  `GET /groups/{id}/messages/{message_id}/receipts` for the sender:
  `{:ok, %{of, receipts}}` or `{:error, :not_found}`.
  """
  def list(me, group_id, message_id) do
    with true <- is_binary(message_id) and Regex.match?(~r/^[0-9a-f-]{36}$/, message_id),
         {:ok,
          %{
            kind: nil,
            conversation_id: ^group_id,
            sender_id: ^me,
            recipients: rs,
            deleted_at: nil
          }}
         when is_list(rs) <- store().get_message(message_id) do
      rows = Map.new(store().list_group_receipts(message_id), &{&1.user_id, &1})
      active = active(group_id, rs)

      receipts =
        for u <- Enum.sort(rs), MapSet.member?(active, u) do
          r = rows[u] || %{}
          read = r[:read_at]
          delivered = r[:delivered_at] || read

          %{
            user_id: u,
            delivered_at: delivered && Messaging.iso(delivered),
            read_at: read && Messaging.iso(read)
          }
        end

      {:ok, %{of: MapSet.size(active), receipts: receipts}}
    else
      _ -> {:error, :not_found}
    end
  end

  @doc """
  v1.12 §15.8: forgets a deleted message's cached aggregation on this node and cancels its
  coalescing timer, so a pending `group_receipt` is never emitted for it.
  """
  def drop(message_id), do: GenServer.call(server(message_id), {:drop, message_id})

  ## Server: state = %{message_id => entry}

  @impl true
  def init(:ok) do
    Process.send_after(self(), :sweep, @sweep_ms)
    {:ok, %{}}
  end

  @impl true
  def handle_call({:load, id, entry}, _from, state) do
    {:reply, :ok, Map.put_new(state, id, Map.put(entry, :touched, now_ms()))}
  end

  def handle_call({:drop, id}, _from, state) do
    case Map.pop(state, id) do
      {%{timer: t}, state} when t != nil ->
        Process.cancel_timer(t)
        {:reply, :ok, state}

      {_, state} ->
        {:reply, :ok, state}
    end
  end

  def handle_call({:ack, id, user, status, at}, _from, state) do
    case state[id] do
      nil ->
        {:reply, :miss, state}

      e ->
        {e, writes} = apply_ack(e, user, status, at)
        e = %{e | touched: now_ms()}
        e = if writes, do: after_change(id, e), else: e
        {:reply, writes, Map.put(state, id, e)}
    end
  end

  @impl true
  def handle_info({:coalesced, id}, state) do
    case state[id] do
      nil -> {:noreply, state}
      e -> {:noreply, Map.put(state, id, emit(id, %{e | timer: nil}, false))}
    end
  end

  def handle_info(:sweep, state) do
    cutoff = now_ms() - @idle_ms
    Process.send_after(self(), :sweep, @sweep_ms)
    {:noreply, Map.reject(state, fn {_, e} -> e.timer == nil and e.touched < cutoff end)}
  end

  # Returns the updated entry and the `{delivered_at, read_at}` to persist (nil = no change).
  defp apply_ack(e, user, status, at) do
    delivered? = Map.has_key?(e.delivered, user)
    read? = Map.has_key?(e.read, user)

    cond do
      not MapSet.member?(e.recipients, user) ->
        {e, nil}

      status == "delivered" and not delivered? and not read? ->
        {%{e | delivered: Map.put(e.delivered, user, at)}, {at, nil}}

      status == "read" and not read? ->
        d = if delivered?, do: nil, else: at

        {%{e | read: Map.put(e.read, user, at), delivered: Map.put_new(e.delivered, user, at)},
         {d, at}}

      true ->
        {e, nil}
    end
  end

  defp counts(e) do
    of = MapSet.size(e.active)
    read = Enum.count(e.active, &Map.has_key?(e.read, &1))
    delivered = Enum.count(e.active, &(Map.has_key?(e.delivered, &1) or Map.has_key?(e.read, &1)))
    {delivered, read, of, of > 0 and delivered == of, of > 0 and read == of}
  end

  defp after_change(id, e) do
    {_, _, _, all_d, all_r} = counts(e)
    {_, _, _, was_d, was_r} = e.last || {0, 0, 0, false, false}
    now = now_ms()

    cond do
      (all_d and not was_d) or (all_r and not was_r) ->
        emit(id, e, true)

      e.timer != nil ->
        e

      e.last_emit == nil or now - e.last_emit >= coalesce_ms() ->
        emit(id, e, false)

      true ->
        %{
          e
          | timer: Process.send_after(self(), {:coalesced, id}, e.last_emit + coalesce_ms() - now)
        }
    end
  end

  defp emit(id, e, refresh?) do
    if e.timer, do: Process.cancel_timer(e.timer)
    e = %{e | timer: nil}

    e =
      if refresh?, do: %{e | active: active(e.conv, MapSet.to_list(e.recipients))}, else: e

    {d, r, of, all_d, all_r} = c = counts(e)

    if c == e.last do
      e
    else
      event = %{
        event_id: TimeUUID.generate(),
        kind: "group_receipt",
        data: %{
          "conversation_id" => e.conv,
          "message_id" => id,
          "client_msg_id" => e.client_msg_id,
          "delivered" => d,
          "read" => r,
          "of" => of,
          "all_delivered" => all_d,
          "all_read" => all_r,
          "at" => Messaging.iso(DateTime.utc_now())
        }
      }

      Messaging.publish_batch([{e.sender, event, [push: false]}])
      %{e | last: c, last_emit: now_ms()}
    end
  rescue
    error ->
      Logger.warning("group_receipt for #{id} failed: #{Exception.message(error)}")
      e
  end

  defp now_ms, do: System.monotonic_time(:millisecond)
  defp store, do: Store.impl()
end
