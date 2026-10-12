defmodule RisiMe.Agents.Flags do
  @moduledoc """
  Runtime flags of the deep agents (decision 084). **Flipping one needs no deploy.**

  * Per agent: `RISI_AGENT_<NAME>` = `off` | `shadow` | `canary` | `on`.
  * Master: `RISI_AGENTS` = `on` (default: the per-agent flags apply) | `legacy` (every agent
    `off` at once: today's path only, no shadow work).

  A flag resolves as: the row in Postgres `risi_agent_flags` **>** the OS environment (e.g.
  `infra/pilot/pilot.env`) **>** the built-in default (`@defaults`). The table is cached per
  node for 5 s (ETS `:risime_agent_flags`, owned by `RisiMe.Agents.Supervisor`; without it every
  read goes to the table), so a change reaches every node within 5 s and survives restarts;
  `put/2` and `clear/1` refresh the calling node at once. An unknown value counts as
  `off`; a database error reads as env/default (never a crash).

      ERL_EPMD_ADDRESS=127.0.0.1 bin/risime rpc 'RisiMe.Agents.Flags.put("RISI_AGENTS", "legacy")'

  Tests may set `config :risime, :risi_agent_flags, %{"RISI_AGENT_ROUTER" => "on"}`, which wins
  over everything (and skips the table).
  """
  import Ecto.Query

  alias RisiMe.Repo

  @modes ~w(off shadow canary on)
  @masters ~w(on legacy)
  @ttl_ms 5_000
  @ets :risime_agent_flags

  # Today's path everywhere; the router only observes (rules, no model call).
  @defaults %{"RISI_AGENTS" => "on", "RISI_AGENT_ROUTER" => "shadow"}

  @doc "The valid per-agent modes."
  def modes, do: @modes

  @doc "The master switch: `:on` or `:legacy`."
  def master do
    case raw("RISI_AGENTS") do
      "legacy" -> :legacy
      _ -> :on
    end
  end

  @doc "True when `RISI_AGENTS=legacy` (every agent off)."
  def legacy?, do: master() == :legacy

  @doc """
  The mode of agent `name` (`"router"`): `:off | :shadow | :canary | :on`. Always `:off` while the
  master is `legacy`.
  """
  def mode(name) when is_binary(name) do
    if legacy?() do
      :off
    else
      case raw(RisiMe.Agents.Agent.flag_name(name)) do
        v when v in @modes -> String.to_existing_atom(v)
        _ -> :off
      end
    end
  end

  @doc """
  The mode that applies to `user` (canary: `:on` for `RISI_DEV_USERS`, `:shadow` for everyone
  else).
  """
  def mode_for(name, user) do
    case mode(name) do
      :canary -> if dev_user?(user), do: :on, else: :shadow
      m -> m
    end
  end

  @doc "True when `user` is in `RISI_DEV_USERS` (the canary group)."
  def dev_user?(user) when is_binary(user),
    do: String.downcase(user) in Application.get_env(:risime, :risi_dev_users, [])

  def dev_user?(_), do: false

  @doc "The raw value of a flag (table > env > default), or nil."
  def raw(name) do
    case Application.get_env(:risime, :risi_agent_flags) do
      %{} = forced when is_map_key(forced, name) ->
        forced[name]

      _ ->
        Map.get(table(), name) || env(name) || Map.get(@defaults, name)
    end
  end

  @doc """
  Sets a flag in `risi_agent_flags` (live within 5 s on every node; at once here). Validates the
  value: `RISI_AGENTS` takes `on|legacy`, `RISI_AGENT_<NAME>` takes `off|shadow|canary|on`.
  """
  def put(name, value, by \\ "rpc") when is_binary(name) and is_binary(value) do
    with :ok <- validate(name, value) do
      now = DateTime.utc_now()

      Repo.insert_all(
        "risi_agent_flags",
        [%{name: name, value: value, updated_by: by, updated_at: now}],
        on_conflict: [set: [value: value, updated_by: by, updated_at: now]],
        conflict_target: [:name]
      )

      refresh()
      :ok
    end
  end

  @doc "Removes a flag's row (back to env/default)."
  def clear(name) do
    Repo.delete_all(from f in "risi_agent_flags", where: f.name == ^name)
    refresh()
    :ok
  end

  @doc "Every agent flag with its value and where it came from (for the admin view)."
  def all do
    names =
      [
        "RISI_AGENTS",
        "RISI_ROUTER_MODEL" | Enum.map(RisiMe.Agents.names(), &RisiMe.Agents.Agent.flag_name/1)
      ]

    t = table()

    for n <- names do
      {value, from} =
        cond do
          Map.has_key?(t, n) -> {t[n], "table"}
          env(n) -> {env(n), "env"}
          true -> {Map.get(@defaults, n, "off"), "default"}
        end

      %{name: n, value: value, from: from}
    end
  end

  @doc "Re-reads the table now (this node)."
  def refresh do
    if cache?(), do: :ets.delete(@ets, :table)
    table()
    :ok
  rescue
    ArgumentError -> :ok
  end

  @doc "Creates the cache table (called by `RisiMe.Agents.Supervisor`, which owns it)."
  def init_cache do
    if :ets.whereis(@ets) == :undefined,
      do: :ets.new(@ets, [:named_table, :public, :set, read_concurrency: true])

    :ok
  end

  defp cache?, do: :ets.whereis(@ets) != :undefined

  defp validate("RISI_AGENTS", v) when v in @masters, do: :ok

  defp validate("RISI_AGENT_" <> _, v) when v in @modes, do: :ok

  # The router's model alias (`off` = rules only), e.g. `risi-l1`, later `risi-l2`.
  defp validate("RISI_ROUTER_MODEL", v),
    do:
      if(Regex.match?(~r/^[a-z0-9][a-z0-9._-]{0,39}$/, v), do: :ok, else: {:error, :invalid_flag})

  defp validate(_, _), do: {:error, :invalid_flag}

  defp env(name) do
    case System.get_env(name) do
      nil -> nil
      "" -> nil
      v -> v |> String.trim() |> String.downcase()
    end
  end

  defp table do
    now = System.monotonic_time(:millisecond)

    if cache?() do
      case :ets.lookup(@ets, :table) do
        [{:table, at, map}] when now - at < @ttl_ms ->
          map

        _ ->
          map = load()
          :ets.insert(@ets, {:table, now, map})
          map
      end
    else
      load()
    end
  rescue
    ArgumentError -> load()
  end

  defp load do
    Repo.all(from f in "risi_agent_flags", select: {f.name, f.value})
    |> Map.new(fn {k, v} -> {k, String.downcase(v)} end)
  rescue
    _ -> %{}
  catch
    :exit, _ -> %{}
  end
end
