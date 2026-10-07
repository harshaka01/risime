defmodule RisiMe.Calls.State do
  @moduledoc """
  The only per-call state the server keeps (contract v1.13 §16.3, §16.8), in memory on this node
  and lost on a restart, by design:

    * **Idempotency** (server R4): `{sender, client_msg_id} → reply` for 5 minutes, in a public
      ETS table (reads and writes don't go through this process). Best effort: a duplicate
      after a restart gets a new `event_id`, and the receiver drops it as an MLS replay.
    * **The 3-s fallback ring push** (server R5): `{callee, call_id}` → a timer and the push
      tokens of the callee's `calls` devices that looked live when the ring arrived. Any
      `call:signal` of that call from the callee's user disarms it; otherwise those devices get
      the call push when it fires. Entries are dropped after 45 s at the latest.

  Never a call table, never "who is in a call".
  """
  use GenServer
  require Logger

  @replies __MODULE__.Replies
  @reply_ttl_ms :timer.minutes(5)
  @sweep_every :timer.minutes(1)
  @ring_state_ms 45_000

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "The original reply for (sender, client_msg_id) within 5 minutes, or `:not_found`."
  def reply(sender_id, client_msg_id) do
    now = System.monotonic_time(:millisecond)

    case :ets.lookup(@replies, {sender_id, client_msg_id}) do
      [{_, reply, expires}] when expires > now -> {:ok, reply}
      _ -> :not_found
    end
  rescue
    ArgumentError -> :not_found
  end

  @doc "Records the reply for (sender, client_msg_id)."
  def put_reply(sender_id, client_msg_id, reply) do
    expires = System.monotonic_time(:millisecond) + @reply_ttl_ms
    :ets.insert(@replies, {{sender_id, client_msg_id}, reply, expires})
    :ok
  end

  @doc "Arms the fallback push of a ring to `callee_id` for these push tokens."
  def arm_fallback(callee_id, call_id, tokens),
    do: GenServer.cast(__MODULE__, {:arm, {callee_id, call_id}, tokens})

  @doc "A signal of `call_id` arrived from `user_id`: if they are its callee, no fallback push."
  def answered(user_id, call_id), do: GenServer.cast(__MODULE__, {:answered, {user_id, call_id}})

  @doc false
  def pending?(callee_id, call_id),
    do: GenServer.call(__MODULE__, {:pending?, {callee_id, call_id}})

  defp fallback_ms, do: Application.get_env(:risime, :call_fallback_ms, 3_000)

  ## Server

  @impl true
  def init(:ok) do
    :ets.new(@replies, [:named_table, :public, :set, write_concurrency: true])
    Process.send_after(self(), :sweep, @sweep_every)
    # %{{callee, call_id} => {timer | nil, tokens}}
    {:ok, %{}}
  end

  @impl true
  def handle_cast({:arm, key, tokens}, rings) do
    rings = cancel(rings, key)
    timer = Process.send_after(self(), {:fallback, key}, fallback_ms())
    Process.send_after(self(), {:drop, key, timer}, @ring_state_ms)
    {:noreply, Map.put(rings, key, {timer, tokens})}
  end

  def handle_cast({:answered, key}, rings) do
    case rings do
      %{^key => {timer, tokens}} when timer != nil ->
        Process.cancel_timer(timer)
        {:noreply, Map.put(rings, key, {nil, tokens})}

      _ ->
        {:noreply, rings}
    end
  end

  @impl true
  def handle_call({:pending?, key}, _from, rings),
    do: {:reply, match?(%{^key => {t, _}} when t != nil, rings), rings}

  @impl true
  def handle_info({:fallback, key}, rings) do
    case rings do
      %{^key => {timer, tokens}} when timer != nil ->
        {callee, call_id} = key

        Logger.info(
          "call ring fallback push: call=#{call_id} to=#{callee} tokens=#{length(tokens)}"
        )

        RisiMe.Push.Dispatcher.push_call(tokens)
        {:noreply, Map.put(rings, key, {nil, tokens})}

      _ ->
        {:noreply, rings}
    end
  end

  # Only the entry armed with this timer (a re-armed ring keeps its own 45 s).
  def handle_info({:drop, key, timer}, rings) do
    case rings do
      %{^key => {^timer, _}} -> {:noreply, Map.delete(rings, key)}
      %{^key => {nil, _}} -> {:noreply, Map.delete(rings, key)}
      _ -> {:noreply, rings}
    end
  end

  def handle_info(:sweep, rings) do
    now = System.monotonic_time(:millisecond)
    :ets.select_delete(@replies, [{{:_, :_, :"$1"}, [{:<, :"$1", now}], [true]}])
    Process.send_after(self(), :sweep, @sweep_every)
    {:noreply, rings}
  end

  defp cancel(rings, key) do
    case rings do
      %{^key => {timer, _}} when timer != nil -> Process.cancel_timer(timer)
      _ -> :ok
    end

    Map.delete(rings, key)
  end
end
