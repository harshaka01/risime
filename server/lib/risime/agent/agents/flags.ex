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

  **Feature switches with a canary** (PART 9, Harsha 2026-10-13): `RISI_VOICE_TRANSCRIBE`,
  `RISI_ACTION_ITEMS` and (decision 090) `RISI_CALL_RISI` take `off` | `canary` | `on` (`feature/1`, `feature_for/2`). `canary` = on
  only for `RISI_DEV_USERS` (Harsha first). They resolve as: the table row **>** the OS
  environment when it says `canary` **>** the application env set by `config/runtime.exs` from
  the same variable (`true` = on; tests set it directly). Defaults stay `off`.

      ERL_EPMD_ADDRESS=127.0.0.1 bin/risime rpc 'RisiMe.Agents.Flags.put("RISI_VOICE_TRANSCRIBE", "canary")'
  """
  import Ecto.Query

  alias RisiMe.Repo

  @modes ~w(off shadow canary on)
  @feature_modes ~w(off canary on)
  @features %{
    "RISI_VOICE_TRANSCRIBE" => :risi_voice_transcribe,
    "RISI_ACTION_ITEMS" => :risi_action_items,
    # Decision 090: "Call Risi" (a voice call Risi answers); canary = Harsha first.
    "RISI_CALL_RISI" => :risi_call_risi
  }
  @masters ~w(on legacy)
  @ttl_ms 5_000
  @ets :risime_agent_flags

  # Today's path everywhere; the router only observes (rules, no model call).
  @defaults %{
    "RISI_AGENTS" => "on",
    "RISI_AGENT_ROUTER" => "shadow",
    # Decision 088: repairs only for RISI_DEV_USERS' conversations, shadow (detect, record,
    # alert) elsewhere; today's path did nothing at all.
    "RISI_AGENT_HEALTH" => "canary"
  }

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

  @doc "The feature switches that take `off | canary | on`."
  def features, do: Map.keys(@features)

  @doc """
  The mode of a feature switch (`"RISI_VOICE_TRANSCRIBE"`, `"RISI_ACTION_ITEMS"`):
  `:off | :canary | :on`, read at runtime (table > env `canary` > application env).
  """
  def feature(name) when is_map_key(@features, name) do
    v =
      case Application.get_env(:risime, :risi_agent_flags) do
        %{} = forced when is_map_key(forced, name) -> forced[name]
        _ -> Map.get(table(), name) || if(env(name) == "canary", do: "canary")
      end

    case v do
      v when v in @feature_modes -> String.to_existing_atom(v)
      nil -> app_feature(@features[name])
      _ -> :off
    end
  end

  @doc "True when feature `name` is on for any of `users` (`canary`: a dev user among them)."
  def feature_for(name, users) when is_list(users) do
    case feature(name) do
      :on -> true
      :canary -> Enum.any?(users, &dev_user?/1)
      :off -> false
    end
  end

  def feature_for(name, user), do: feature_for(name, [user])

  defp app_feature(key) do
    case Application.get_env(:risime, key, false) do
      v when v in [true, :on, "on"] -> :on
      v when v in [:canary, "canary"] -> :canary
      _ -> :off
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
      ] ++ Enum.sort(Map.keys(@features))

    t = table()

    for n <- names do
      {value, from} =
        cond do
          Map.has_key?(t, n) ->
            {t[n], "table"}

          is_map_key(@features, n) ->
            {Atom.to_string(feature(n)), if(env(n), do: "env", else: "default")}

          env(n) ->
            {env(n), "env"}

          true ->
            {Map.get(@defaults, n, "off"), "default"}
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

  defp validate(name, v) when is_map_key(@features, name) and v in @feature_modes, do: :ok

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
