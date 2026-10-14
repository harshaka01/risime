defmodule RisiMe.Agents.ToolRegistry do
  @moduledoc """
  The one tool registry of the deep agents (decision 084), MCP-style. It **wraps** today's
  registry (`RisiMe.Agent.Tools.tools/0`: reminders, calendars, alarms, scheduled messages,
  PDF export, Risi's items, memory search and summaries): every tool is listed as

      %{"name", "description", "inputSchema",
        "annotations" => %{"readOnlyHint", "personal", "where", "skill", "finds"}}

  and `call/3` runs the existing tool exactly as a turn does: `RisiMe.Agent.Tools.authorize/3`
  first (membership, Risi chat for personal tools, the phone's capabilities, skills), then the
  tool's own `run/2`. Nothing is copied or rewritten, so a tool's behaviour can't drift between
  the turn and an agent. Client tools (`where: client`) still go through the phone (§25.3):
  their `run/2` is the same one the turn calls.
  """
  alias RisiMe.Agent.Tools

  @doc "Every tool as an MCP-style descriptor (sorted by name)."
  def list do
    Tools.tools()
    |> Enum.map(&descriptor/1)
    |> Enum.sort_by(& &1["name"])
  end

  @doc "The tools an agent may use (its spec's `tools`, intersected with the registry)."
  def list_for(%{tools: names}), do: Enum.filter(list(), &(&1["name"] in names))

  @doc "One tool's descriptor, or nil."
  def get(name) do
    case Tools.get(name) do
      nil -> nil
      t -> descriptor(t)
    end
  end

  @doc """
  Calls tool `name` with `args` for `ctx` (`%{asker, device_id, conv, …}`, as a turn's context).
  `{:ok, result, meta}` | `{:propose, card}` | `{:error, status}` exactly as the tool returns, or
  `{:error, :unknown_tool}` / `{:error, :denied}`.
  """
  def call(name, args, ctx) do
    case Tools.get(name) do
      nil ->
        {:error, :unknown_tool}

      tool ->
        case Tools.authorize(tool, ctx) do
          # Principle 4: through the consequential-action guard, as a turn's step.
          :ok -> RisiMe.Agent.ActionGuard.run(tool, args, ctx)
          {:error, :denied} -> {:error, :denied}
        end
    end
  end

  defp descriptor(t) do
    %{
      "name" => t.name,
      "description" => t.description,
      "inputSchema" => Map.get(t, :args, %{"type" => "object"}),
      "annotations" => %{
        "readOnlyHint" => not Map.get(t, :write, false),
        "personal" => Map.get(t, :personal, false),
        "where" => to_string(Map.get(t, :where, :server)),
        "skill" => Map.get(t, :skill) && to_string(t.skill),
        "finds" => Map.get(t, :finds, false)
      }
    }
  end
end
