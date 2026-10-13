defmodule RisiMe.Admin.Settings do
  @moduledoc """
  Runtime settings changed from the admin console (decision 089). **A change needs no deploy.**

  A setting resolves as: the row in Postgres `admin_settings` **>** the OS environment
  (`env_name/1`: `speech_route_si` → `RISI_SPEECH_ROUTE_SI`) **>** the caller's default. The table is cached per node for 5 s (ETS, owned by this process; without it
  every read goes to the table), like `RisiMe.Agents.Flags`; `put/3` and `clear/2` refresh the
  calling node at once. A database error reads as env/default (never a crash).

  Writing goes through `put/3` / `clear/2`, which also append to the admin audit log.

  Tests may set `config :risime, :admin_settings, %{"speech_route_si" => "..."}`, which wins over
  everything (and skips the table).
  """
  use GenServer

  import Ecto.Query

  alias RisiMe.Repo

  @ttl_ms 5_000
  @ets :risime_admin_settings

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @impl true
  def init(:ok) do
    if :ets.whereis(@ets) == :undefined,
      do: :ets.new(@ets, [:named_table, :public, :set, read_concurrency: true])

    {:ok, nil}
  end

  @doc "The OS environment variable of a setting: `RISI_` + the key upper-cased."
  def env_name(key), do: "RISI_" <> String.upcase(key)

  @doc "The value of `key` (table > env > `default`)."
  def get(key, default \\ nil) when is_binary(key) do
    case Application.get_env(:risime, :admin_settings) do
      %{} = forced when is_map_key(forced, key) ->
        forced[key]

      _ ->
        Map.get(table(), key) || env(key) || default
    end
  end

  @doc "Where `key`'s value comes from: `:table`, `:env` or `:default`."
  def source(key) when is_binary(key) do
    cond do
      Map.has_key?(table(), key) -> :table
      env(key) -> :env
      true -> :default
    end
  end

  @doc "Sets `key` (audit-logged by `by`, the admin's user id). Live at once on this node."
  def put(key, value, by) when is_binary(key) and is_binary(value) do
    before = get(key)
    now = DateTime.utc_now()

    Repo.transaction(fn ->
      Repo.insert_all(
        "admin_settings",
        [%{key: key, value: value, updated_by: dump_uuid(by), updated_at: now}],
        on_conflict: [set: [value: value, updated_by: dump_uuid(by), updated_at: now]],
        conflict_target: [:key]
      )

      RisiMe.Admin.audit(by, "setting_set", key, before || "(default)", value)
    end)

    refresh()
    :ok
  end

  @doc "Removes `key`'s row (back to env/default; audit-logged)."
  def clear(key, by) when is_binary(key) do
    before = get(key)

    Repo.transaction(fn ->
      Repo.delete_all(from s in "admin_settings", where: s.key == ^key)
      RisiMe.Admin.audit(by, "setting_clear", key, before || "(default)", "(default)")
    end)

    refresh()
    :ok
  end

  @doc "Re-reads the table now (this node)."
  def refresh do
    if cache?(), do: :ets.delete(@ets, :table)
    :ok
  rescue
    ArgumentError -> :ok
  end

  defp dump_uuid(nil), do: nil
  defp dump_uuid(id), do: Ecto.UUID.dump!(id)

  defp cache?, do: :ets.whereis(@ets) != :undefined

  # Tests turn the OS environment off (`config :risime, :admin_settings_env, false`): the test
  # VM loads the repo's .env, which must never change a test's routing.
  defp env(key) do
    case Application.get_env(:risime, :admin_settings_env, true) &&
           System.get_env(env_name(key)) do
      false -> nil
      nil -> nil
      "" -> nil
      v -> String.trim(v)
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
    Repo.all(from s in "admin_settings", select: {s.key, s.value}) |> Map.new()
  rescue
    _ -> %{}
  catch
    :exit, _ -> %{}
  end
end
