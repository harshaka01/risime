defmodule RisiMe.Agent.CallTranscribe.Calls do
  @moduledoc """
  The server's own record of a 1:1 call (contract v1.39 §36.2): the `from`/`to` of its
  `ring: true` `call_signal`, its `dm:` conversation and cleartext `media`, kept **in memory
  only** for 4 h (an ETS table owned by this process; swept every 10 min). The first ring of a
  `call_id` wins: a later ring of the same id (a re-ring) never changes its participants.
  """
  use GenServer

  @table :risime_call_transcribe_calls
  @ttl_ms 4 * 3600 * 1000
  @sweep_ms 600_000

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "Records the ring of `call_id` (from `RisiMe.Calls`). Best effort."
  def put(call_id, %{from: _, to: _, conv: _} = rec) when is_binary(call_id) do
    now = System.monotonic_time(:millisecond)
    rec = rec |> Map.put(:at_ms, now) |> Map.put_new(:media, "audio")
    key = String.downcase(call_id)

    case :ets.lookup(@table, key) do
      [{_, %{at_ms: at}}] when now - at <= @ttl_ms -> :ok
      _ -> :ets.insert(@table, {key, rec})
    end

    :ok
  rescue
    ArgumentError -> :ok
  end

  @doc "The record of `call_id` from the last 4 h, or nil."
  def get(call_id) when is_binary(call_id) do
    now = System.monotonic_time(:millisecond)

    case :ets.lookup(@table, String.downcase(call_id)) do
      [{_, %{at_ms: at} = rec}] when now - at <= @ttl_ms -> rec
      _ -> nil
    end
  rescue
    ArgumentError -> nil
  end

  def get(_), do: nil

  @doc false
  def delete(call_id), do: :ets.delete(@table, call_id)

  @impl true
  def init(:ok) do
    if :ets.whereis(@table) == :undefined,
      do: :ets.new(@table, [:named_table, :public, :set, read_concurrency: true])

    Process.send_after(self(), :sweep, @sweep_ms)
    {:ok, %{}}
  end

  @impl true
  def handle_info(:sweep, state) do
    cutoff = System.monotonic_time(:millisecond) - @ttl_ms
    :ets.select_delete(@table, [{{:_, %{at_ms: :"$1"}}, [{:<, :"$1", cutoff}], [true]}])
    Process.send_after(self(), :sweep, @sweep_ms)
    {:noreply, state}
  end
end
