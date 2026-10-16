defmodule RisiRegistry.Cards.Cache do
  @moduledoc """
  The verified-card cache: at most 5 minutes per agent (config `:card_cache_ttl_ms`).

  Only successful verifications are cached, keyed by agent and approved `card_hash`, so a new
  approval or a status change starts fresh. Status is never cached: it is read on every call.
  """
  use GenServer

  @table __MODULE__

  def start_link(_), do: GenServer.start_link(__MODULE__, nil, name: __MODULE__)

  @impl true
  def init(nil) do
    :ets.new(@table, [:named_table, :public, :set, read_concurrency: true])
    {:ok, nil}
  end

  @doc """
  Returns the cached verification for `{agent_id, card_hash}` while it is fresh; otherwise runs
  `fun`, caching its `{:ok, _}` result.
  """
  def fetch(agent_id, card_hash, fun) do
    key = {agent_id, card_hash}
    now = System.monotonic_time(:millisecond)

    case :ets.lookup(@table, key) do
      [{^key, value, expires}] when expires > now ->
        {:ok, value, :cached}

      _ ->
        case fun.() do
          {:ok, value} ->
            :ets.insert(@table, {key, value, now + ttl()})
            {:ok, value, :fresh}

          other ->
            invalidate(agent_id)
            other
        end
    end
  end

  def invalidate(agent_id), do: :ets.match_delete(@table, {{agent_id, :_}, :_, :_})

  def clear, do: :ets.delete_all_objects(@table)

  defp ttl, do: Application.get_env(:risi_registry, :registry)[:card_cache_ttl_ms]
end
