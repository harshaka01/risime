defmodule RisiRegistry.Sandbox.Client do
  @moduledoc """
  How the sandbox reaches an agent. `ask/3` sends one probe request and returns the raw body.

  Phase 0 ships only `RisiRegistry.Sandbox.DisabledClient`: the registry makes no calls to any
  agent until both architects agree the transport and Risi's brain gate has passed (architect
  22:05, "not in phase 0"). Tests use a stub client.
  """
  @callback ask(agent :: map(), request :: map(), opts :: keyword()) ::
              {:ok, %{status: non_neg_integer(), body: binary()}} | {:error, term()}
end

defmodule RisiRegistry.Sandbox.DisabledClient do
  @moduledoc "Refuses every probe: no network calls to agents in phase 0."
  @behaviour RisiRegistry.Sandbox.Client

  @impl true
  def ask(_agent, _request, _opts), do: {:error, :calls_disabled}
end
