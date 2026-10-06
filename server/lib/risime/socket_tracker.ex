defmodule RisiMe.SocketTracker do
  @moduledoc """
  Tracks open realtime sockets: the open count, `[:risime, :socket, :disconnect]` telemetry and
  JWT expiry deadlines (contract v1.3 §6.2).

  `RisiMeWeb.UserSocket.connect/3` runs in the connection process, which (with Bandit) carries
  on as the WebSocket process. This process monitors it, so a disconnect is seen whatever its
  cause. The open-socket count is in ETS and is reported by the telemetry poller as
  `[:risime, :socket, :open]`.

  **Expiry.** A socket authenticated with a JWT gets a deadline of `exp + 60 s`. When it passes
  without a successful `auth:refresh`, the socket's inbox channels get `{:auth_expired}` on
  `control_topic/1`: they push `auth:expired` and disconnect the socket. A disconnect is also
  broadcast to the socket id 1 s later, so a socket without a joined channel is closed too.
  """
  use GenServer

  @table __MODULE__
  @fallback_disconnect_ms 1_000

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc """
  Starts tracking the calling connection process. Options: `:socket_id` (the socket's id,
  needed for expiry) and `:exp` (the token's `exp` in Unix seconds; nil = no deadline).
  """
  def track(user_id, opts \\ []) do
    GenServer.call(__MODULE__, {:track, self(), user_id, now(), opts})
  end

  @doc "Moves the deadline of the socket whose transport is `pid` to `exp + grace`."
  @spec refresh(pid, integer) :: :ok | :error
  def refresh(pid, exp), do: GenServer.call(__MODULE__, {:refresh, pid, exp})

  @doc "Disconnects every tracked socket of `user_id`. Returns how many."
  def disconnect_user(user_id), do: GenServer.call(__MODULE__, {:disconnect_user, user_id})

  @doc "PubSub topic the socket's channels listen on for `{:auth_expired}`."
  def control_topic(socket_id), do: "socket_ctl:" <> socket_id

  @doc "Number of open sockets on this node."
  def open_count do
    case :ets.lookup(@table, :open) do
      [{:open, n}] -> n
      [] -> 0
    end
  rescue
    ArgumentError -> 0
  end

  @doc false
  def emit_open_count do
    :telemetry.execute([:risime, :socket, :open], %{count: open_count()}, %{})
  end

  ## Server
  # state: %{ref => %{pid, user_id, connected_at, socket_id, timer}, {:pid, pid} => ref}

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :protected, :set, read_concurrency: true])
    :ets.insert(@table, {:open, 0})
    {:ok, %{}}
  end

  @impl true
  def handle_call({:track, pid, user_id, connected_at, opts}, _from, state) do
    ref = Process.monitor(pid)
    :ets.update_counter(@table, :open, 1)

    entry = %{
      pid: pid,
      user_id: user_id,
      connected_at: connected_at,
      socket_id: opts[:socket_id],
      timer: schedule(ref, opts[:exp])
    }

    {:reply, :ok, state |> Map.put(ref, entry) |> Map.put({:pid, pid}, ref)}
  end

  def handle_call({:refresh, pid, exp}, _from, state) do
    with ref when is_reference(ref) <- state[{:pid, pid}],
         %{} = entry <- state[ref] do
      if entry.timer, do: Process.cancel_timer(entry.timer)
      {:reply, :ok, Map.put(state, ref, %{entry | timer: schedule(ref, exp)})}
    else
      _ -> {:reply, :error, state}
    end
  end

  def handle_call({:disconnect_user, user_id}, _from, state) do
    ids =
      for {ref, %{user_id: ^user_id, socket_id: id}} when is_reference(ref) and is_binary(id) <-
            state,
          uniq: true,
          do: id

    for id <- ids, do: RisiMeWeb.Endpoint.broadcast(id, "disconnect", %{})
    {:reply, length(ids), state}
  end

  @impl true
  def handle_info({:DOWN, ref, :process, _pid, reason}, state) do
    case Map.pop(state, ref) do
      {%{} = entry, state} ->
        :ets.update_counter(@table, :open, -1)
        if entry.timer, do: Process.cancel_timer(entry.timer)

        :telemetry.execute(
          [:risime, :socket, :disconnect],
          %{duration: now() - entry.connected_at},
          %{user_id: entry.user_id, reason: reason_tag(reason)}
        )

        {:noreply, Map.delete(state, {:pid, entry.pid})}

      {nil, state} ->
        {:noreply, state}
    end
  end

  def handle_info({:expired, ref}, state) do
    case state[ref] do
      %{socket_id: socket_id} = entry when is_binary(socket_id) ->
        Phoenix.PubSub.broadcast(RisiMe.PubSub, control_topic(socket_id), {:auth_expired})
        Process.send_after(self(), {:disconnect, socket_id}, @fallback_disconnect_ms)
        {:noreply, Map.put(state, ref, %{entry | timer: nil})}

      _ ->
        {:noreply, state}
    end
  end

  def handle_info({:disconnect, socket_id}, state) do
    RisiMeWeb.Endpoint.broadcast(socket_id, "disconnect", %{})
    {:noreply, state}
  end

  defp schedule(_ref, nil), do: nil

  defp schedule(ref, exp) do
    deadline_ms = exp * 1000 + RisiMe.Auth.Config.expiry_grace_ms()
    delay = max(deadline_ms - System.os_time(:millisecond), 0)
    Process.send_after(self(), {:expired, ref}, delay)
  end

  defp reason_tag(reason) when reason in [:normal, :shutdown], do: reason
  defp reason_tag({:shutdown, _}), do: :shutdown
  defp reason_tag(_), do: :error

  defp now, do: System.monotonic_time()
end
