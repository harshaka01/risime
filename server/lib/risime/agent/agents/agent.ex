defmodule RisiMe.Agents.Agent do
  @moduledoc """
  One Risi agent (decision 084, PART 6 "deep agents"): a goal, tools from the one tool registry
  (`RisiMe.Agents.ToolRegistry`), a memory, a golden eval set, a latency budget and the version
  of what it has learned.

  An agent never calls another agent directly: every run goes through
  `RisiMe.Agents.Orchestrator.call/3`, which reads the agent's flag, enforces its budget, falls
  back to the legacy path on an error/timeout/budget breach, writes the run row
  (`RisiMe.Agents.Runs`) and emits the `agent-activity-v1` events (`RisiMe.Agents.Activity`).
  """

  @typedoc "`%{p50_ms, p95_ms, hard_ms}`: the hard limit is enforced (fallback), the others scored."
  @type budget :: %{p50_ms: pos_integer, p95_ms: pos_integer, hard_ms: pos_integer}

  @type spec :: %{
          name: String.t(),
          type: :deep | :specialist | :on_device,
          goal: String.t(),
          tools: [String.t()],
          memory: :none | :preferences | :pgvector | :ledger | :on_device,
          eval_set: String.t() | nil,
          budget: budget,
          learned_version: String.t(),
          legacy: [module],
          status: :live | :shadow_ready | :planned
        }

  @doc "The agent's declaration (see the module doc and decision 084)."
  @callback spec() :: spec

  @doc """
  Runs the agent on `input`. `ctx` carries at least `:user` (the asker's id, or nil) and may carry
  `:lang`, `:today`, `:tz`. Must not post, write or notify on its own while in shadow: the
  orchestrator passes `mode` in `ctx` and a shadow run's output is only logged.
  """
  @callback run(input :: term, ctx :: map) :: {:ok, term} | {:error, term}

  @doc """
  Labels a run's output for the run row and the activity event (a short code such as a route,
  never content). Optional; the default is `nil`.
  """
  @callback label(output :: term) :: String.t() | nil

  @optional_callbacks label: 1

  @doc "The flag name of an agent spec (`router` → `RISI_AGENT_ROUTER`)."
  def flag_name(%{name: name}), do: flag_name(name)
  def flag_name(name) when is_binary(name), do: "RISI_AGENT_" <> String.upcase(name)
end
