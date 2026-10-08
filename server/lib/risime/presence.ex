defmodule RisiMe.Presence do
  @moduledoc """
  Online state and presence signals (PROTOCOL.md v1.2 §2.5). See
  docs/decisions/007-presence-and-typing.md.

  A user is online while at least one of their inbox channels is alive. Channels register with
  `track/1`; this process monitors them. When a user's last channel goes away, they stay online
  for a grace period (5 s); a new channel within it publishes nothing. When the grace period
  ends, an offline `Presence` is published with `last_seen` = the time the last channel left.

  Online state lives in a public ETS table (reads don't go through the process). State is
  per node; clustering needs Phoenix.Tracker (or similar) behind the same API.

  `last_seen` is persisted by the inbox channel itself (on join and on leave), not here.
  """
  use GenServer

  alias RisiMe.{Accounts, Messaging}

  @table __MODULE__

  ## API

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc """
  Registers the calling inbox channel process as `user_id` being online, and (v1.9) its
  `device_id` as connected, for committer naming (§12.4). `transport_pid` (the websocket
  process) lets the push watchdog probe the connection (`RisiMe.Push.Dispatcher`).
  """
  def track(user_id, device_id \\ nil, transport_pid \\ nil),
    do: GenServer.call(__MODULE__, {:track, user_id, device_id, self(), transport_pid})

  @doc """
  The user's joined inbox channels on this node as `{channel_pid, device_id, transport_pid}`.
  No grace period: `[]` while only the offline grace keeps the user "online".
  """
  def connections(user_id) do
    case :ets.lookup(@table, {:conns, user_id}) do
      [{_, conns}] -> conns
      [] -> []
    end
  rescue
    ArgumentError -> []
  end

  @doc "True while the device has a joined inbox channel on this node (no grace period)."
  def device_online?(nil), do: false

  def device_online?(device_id) do
    :ets.member(@table, {:device, device_id})
  rescue
    ArgumentError -> false
  end

  @doc "True while the user has a live inbox channel (or is within the offline grace period)."
  def online?(user_id) do
    case :ets.lookup(@table, user_id) do
      [{_, _count, _grace}] -> true
      [] -> false
    end
  rescue
    ArgumentError -> false
  end

  @doc "Internal PubSub topic for presence changes of `user_id`."
  def topic(user_id), do: "presence_live:" <> user_id

  def subscribe(user_id), do: Phoenix.PubSub.subscribe(RisiMe.PubSub, topic(user_id))
  def unsubscribe(user_id), do: Phoenix.PubSub.unsubscribe(RisiMe.PubSub, topic(user_id))

  @doc """
  `Presence` maps (wire format) for the registered users among `ids`, in input order.
  Unregistered ids are left out.
  """
  @spec presences([String.t()]) :: [map]
  def presences(ids) do
    last_seen = Accounts.last_seen_by_id(ids)

    for id <- ids, Map.has_key?(last_seen, id) do
      if online?(id), do: presence(id, true, nil), else: presence(id, false, last_seen[id])
    end
  end

  @doc "A `Presence` map as sent on the wire."
  def presence(user_id, online, last_seen) do
    %{
      "user_id" => user_id,
      "online" => online,
      "last_seen" => if(online or is_nil(last_seen), do: nil, else: Messaging.iso(last_seen))
    }
  end

  defp grace_ms, do: Application.get_env(:risime, :presence_grace_ms, 5_000)

  ## Server
  # ETS row: {user_id, live_channel_count, nil | {timer_ref, left_at}}

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :protected, :set, read_concurrency: true])
    {:ok, %{}}
  end

  @impl true
  def handle_call({:track, user_id, device_id, pid, transport_pid}, _from, monitors) do
    ref = Process.monitor(pid)
    conns = [{pid, device_id, transport_pid} | connections(user_id)]
    :ets.insert(@table, {{:conns, user_id}, conns})

    if device_id,
      do: :ets.update_counter(@table, {:device, device_id}, {2, 1}, {{:device, device_id}, 0})

    case :ets.lookup(@table, user_id) do
      [] ->
        :ets.insert(@table, {user_id, 1, nil})
        publish(user_id, presence(user_id, true, nil))

      [{_, count, nil}] ->
        :ets.insert(@table, {user_id, count + 1, nil})

      [{_, 0, {timer, _left_at}}] ->
        # Back within the grace period: still online as far as watchers know.
        Process.cancel_timer(timer)
        :ets.insert(@table, {user_id, 1, nil})
    end

    {:reply, :ok, Map.put(monitors, ref, {user_id, device_id})}
  end

  @impl true
  def handle_info({:DOWN, ref, :process, pid, _reason}, monitors) do
    {{user_id, device_id}, monitors} = Map.pop(monitors, ref, {nil, nil})
    if user_id, do: drop_connection(user_id, pid)

    if device_id do
      key = {:device, device_id}
      if :ets.update_counter(@table, key, {2, -1}) <= 0, do: :ets.delete(@table, key)
    end

    case user_id && :ets.lookup(@table, user_id) do
      [{_, 1, nil}] ->
        left_at = DateTime.utc_now()
        timer = Process.send_after(self(), {:grace_over, user_id, left_at}, grace_ms())
        :ets.insert(@table, {user_id, 0, {timer, left_at}})

      [{_, count, nil}] when count > 1 ->
        :ets.insert(@table, {user_id, count - 1, nil})

      _ ->
        :ok
    end

    {:noreply, monitors}
  end

  def handle_info({:grace_over, user_id, left_at}, monitors) do
    case :ets.lookup(@table, user_id) do
      [{_, 0, {_timer, ^left_at}}] ->
        :ets.delete(@table, user_id)
        publish(user_id, presence(user_id, false, left_at))

      _ ->
        :ok
    end

    {:noreply, monitors}
  end

  defp drop_connection(user_id, pid) do
    case Enum.reject(connections(user_id), &(elem(&1, 0) == pid)) do
      [] -> :ets.delete(@table, {:conns, user_id})
      conns -> :ets.insert(@table, {{:conns, user_id}, conns})
    end
  end

  defp publish(user_id, presence) do
    Phoenix.PubSub.broadcast(RisiMe.PubSub, topic(user_id), {:presence_signal, presence})
  end
end
