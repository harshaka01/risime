defmodule RisiMe.RateLimiter do
  @moduledoc """
  Small in-node rate limiter on ETS (see docs/decisions/001-rate-limiter.md).

  Sliding-window counter: the current fixed window's count plus the previous window's count
  weighted by how much of it still overlaps the sliding window. Counting uses
  `:ets.update_counter/4`, so it is atomic without a process round-trip. Every hit counts,
  including rejected ones.
  """
  use GenServer

  @table __MODULE__
  @sweep_every :timer.minutes(1)

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "Counts one hit for `{bucket, key}`; at most `limit` hits per `window_ms`."
  @spec hit(atom, term, pos_integer, pos_integer) :: :ok | {:error, :rate_limited}
  def hit(bucket, key, limit, window_ms) do
    now = System.system_time(:millisecond)
    window = div(now, window_ms)
    cur_key = {bucket, key, window}
    expires_at = (window + 2) * window_ms

    current = :ets.update_counter(@table, cur_key, {2, 1}, {cur_key, 0, expires_at})

    previous =
      case :ets.lookup(@table, {bucket, key, window - 1}) do
        [{_, count, _}] -> count
        [] -> 0
      end

    overlap = (window_ms - rem(now, window_ms)) / window_ms

    if previous * overlap + current > limit, do: {:error, :rate_limited}, else: :ok
  end

  @doc "Seconds until the current fixed window ends (a `Retry-After` value, at least 1)."
  def retry_after_s(window_ms) do
    now = System.system_time(:millisecond)
    max(1, div(window_ms - rem(now, window_ms) + 999, 1000))
  end

  @impl true
  def init(:ok) do
    :ets.new(@table, [:named_table, :public, :set, write_concurrency: true])
    Process.send_after(self(), :sweep, @sweep_every)
    {:ok, nil}
  end

  @impl true
  def handle_info(:sweep, state) do
    now = System.system_time(:millisecond)
    :ets.select_delete(@table, [{{:_, :_, :"$1"}, [{:<, :"$1", now}], [true]}])
    Process.send_after(self(), :sweep, @sweep_every)
    {:noreply, state}
  end
end
