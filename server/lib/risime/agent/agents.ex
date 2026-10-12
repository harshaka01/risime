defmodule RisiMe.Agents do
  @moduledoc """
  The registered Risi agents (decision 084). Step 2 ships the Fast router (`RisiMe.Agents.Router`,
  shadow by default); the others are declared specs (`status: :planned`) so flags, the
  scoreboard and the eval sets exist before their code does. Today's modules stay the live path.
  """

  @agents [
    RisiMe.Agents.Router,
    RisiMe.Agents.Planned.Planner,
    RisiMe.Agents.Planned.Ledger,
    RisiMe.Agents.Planned.Voice,
    RisiMe.Agents.Planned.Calendar,
    RisiMe.Agents.Planned.Memory,
    RisiMe.Agents.Planned.Summary,
    RisiMe.Agents.Planned.Marketplace,
    RisiMe.Agents.Planned.Twin
  ]

  @doc "The agent modules."
  def modules, do: @agents

  @doc "Every agent's spec."
  def specs, do: Enum.map(@agents, & &1.spec())

  @doc "Every agent's name."
  def names, do: Enum.map(specs(), & &1.name)

  @doc "The module of agent `name`, or nil."
  def module(name), do: Enum.find(@agents, &(&1.spec().name == name))

  @doc "The spec of agent `name`, or nil."
  def spec(name) do
    case module(name) do
      nil -> nil
      m -> m.spec()
    end
  end
end
