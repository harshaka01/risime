defmodule RisiMe.Agents.Supervisor do
  @moduledoc """
  The deep agents' tree (decision 084), the last child of `RisiMe.Agent.Supervisor` (so it runs
  only with `RISI=on`): the flag cache, a `Task.Supervisor` for runs (`RisiMe.Agents.TaskSup`),
  the legacy-turn timing handler, and one `RisiMe.Agents.Server` per registered agent
  (`one_for_one`: an agent's crash restarts only that agent).

  Nothing depends on this tree running: without it, the orchestrator runs agents inline and the
  flags read the table directly.
  """
  use Supervisor

  def start_link(opts \\ []), do: Supervisor.start_link(__MODULE__, opts, name: __MODULE__)

  @impl true
  def init(_opts) do
    RisiMe.Agents.Flags.init_cache()
    RisiMe.Agents.TurnTiming.attach()

    agents =
      for mod <- RisiMe.Agents.modules() do
        Supervisor.child_spec({RisiMe.Agents.Server, mod}, id: {RisiMe.Agents.Server, mod})
      end

    # Decision 090: the Voice agent's Call Risi sessions (idle unless RISI_CALL_RISI applies).
    children = [
      {Task.Supervisor, name: RisiMe.Agents.TaskSup},
      RisiMe.Agent.CallRisi.Supervisor | agents
    ]

    Supervisor.init(children, strategy: :one_for_one, max_restarts: 10, max_seconds: 60)
  end
end

defmodule RisiMe.Agents.Server do
  @moduledoc """
  One supervised process per agent (decision 084): holds the agent's loaded learned-state
  version and its run counters. Runs themselves do not queue behind it (they run in
  `RisiMe.Agents.TaskSup`), so a slow agent never blocks another.
  """
  use GenServer

  def start_link(mod), do: GenServer.start_link(__MODULE__, mod, name: via(mod.spec().name))

  @doc "The agent's state: `%{name, learned_version, started_at}`, or nil when not running."
  def info(name) do
    GenServer.call(via(name), :info)
  catch
    :exit, _ -> nil
  end

  # A fixed, small set of agents: one local name each.
  defp via(name), do: String.to_atom("risi_agent_" <> name)

  @impl true
  def init(mod) do
    spec = mod.spec()

    {:ok,
     %{
       name: spec.name,
       mod: mod,
       learned_version: spec.learned_version,
       status: spec.status,
       started_at: DateTime.utc_now()
     }}
  end

  @impl true
  def handle_call(:info, _from, st), do: {:reply, Map.delete(st, :mod), st}
end
