defmodule RisiMe.SocketTracker do
  @moduledoc """
  Counts open realtime sockets and emits `[:risime, :socket, :disconnect]` when one closes.

  `RisiMeWeb.UserSocket.connect/3` runs in the connection process, which (with Bandit) carries
  on as the WebSocket process. This process monitors it, so a disconnect is seen whatever its
  cause. The open-socket count is in ETS and is reported by the telemetry poller as
  `[:risime, :socket, :open]`.
  """
  use GenServer

  @table __MODULE__

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "Starts tracking the calling connection process for `user_id`."
  def track(user_id), do: GenServer.cast(__MODULE__, {:track, self(), user_id, now()})

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

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :protected, :set, read_concurrency: true])
    :ets.insert(@table, {:open, 0})
    {:ok, %{}}
  end

  @impl true
  def handle_cast({:track, pid, user_id, connected_at}, monitors) do
    ref = Process.monitor(pid)
    :ets.update_counter(@table, :open, 1)
    {:noreply, Map.put(monitors, ref, {user_id, connected_at})}
  end

  @impl true
  def handle_info({:DOWN, ref, :process, _pid, reason}, monitors) do
    case Map.pop(monitors, ref) do
      {{user_id, connected_at}, monitors} ->
        :ets.update_counter(@table, :open, -1)

        :telemetry.execute(
          [:risime, :socket, :disconnect],
          %{duration: now() - connected_at},
          %{user_id: user_id, reason: reason_tag(reason)}
        )

        {:noreply, monitors}

      {nil, monitors} ->
        {:noreply, monitors}
    end
  end

  defp reason_tag(reason) when reason in [:normal, :shutdown], do: reason
  defp reason_tag({:shutdown, _}), do: :shutdown
  defp reason_tag(_), do: :error

  defp now, do: System.monotonic_time()
end
