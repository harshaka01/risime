defmodule RisiMe.Calls.RoomMemory do
  @moduledoc """
  The in-memory state of group-call rooms the server keeps (contract v1.23 §23.6, server S2),
  on this node only and lost on a restart, by design (the window is one token lifetime):

    * **Minted tokens:** `{room, identity} → minted_at` for every `start`/`join`/`upgrade` token
      in the last 600 s, so `upgrade`'s video cap counts identities that hold a token but haven't
      connected yet. Swept every minute.
    * **A per-room lock:** `start`, `join` and `upgrade` of one room run one at a time
      (`:global.trans/4` on this node only).

  No identities are logged; nothing is persisted.
  """
  use GenServer

  alias RisiMe.Calls.LiveKit

  @table __MODULE__.Minted
  @sweep_every :timer.minutes(1)

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "Runs `fun` holding the lock of `room` (waits for it)."
  def with_lock(room, fun) when is_function(fun, 0),
    do: :global.trans({{__MODULE__, room}, self()}, fun, [node()], :infinity)

  @doc "Records a token minted for `identity` in `room` now."
  def minted(room, identity) do
    :ets.insert(@table, {{room, identity}, now_s()})
    :ok
  rescue
    ArgumentError -> :ok
  end

  @doc "The identities with a token for `room` minted in the last 600 s."
  def recent(room) do
    since = now_s() - LiveKit.token_ttl_s()
    :ets.select(@table, [{{{room, :"$1"}, :"$2"}, [{:>, :"$2", since}], [:"$1"]}])
  rescue
    ArgumentError -> []
  end

  defp now_s, do: System.monotonic_time(:second)

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :public, :set, write_concurrency: true])
    Process.send_after(self(), :sweep, @sweep_every)
    {:ok, nil}
  end

  @impl true
  def handle_info(:sweep, state) do
    since = now_s() - LiveKit.token_ttl_s()
    :ets.select_delete(@table, [{{:_, :"$1"}, [{:"=<", :"$1", since}], [true]}])
    Process.send_after(self(), :sweep, @sweep_every)
    {:noreply, state}
  end
end
