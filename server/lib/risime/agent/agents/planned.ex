defmodule RisiMe.Agents.Planned do
  @moduledoc """
  The agents of decision 084 that are declared but not built yet (`status: :planned`). Their
  `run/2` refuses (`{:error, :not_built}`), so even `RISI_AGENT_<NAME>=on` falls back to today's
  module through the orchestrator. Each names the modules it will wrap (`legacy`).
  """

  @doc false
  defmacro __using__(spec) do
    quote do
      @behaviour RisiMe.Agents.Agent
      @spec_map Map.merge(
                  %{
                    tools: [],
                    memory: :none,
                    eval_set: nil,
                    learned_version: "v0-legacy",
                    status: :planned
                  },
                  Map.new(unquote(spec))
                )

      @impl true
      def spec, do: @spec_map

      @impl true
      def run(_input, _ctx), do: {:error, :not_built}
    end
  end
end

defmodule RisiMe.Agents.Planned.Planner do
  @moduledoc "Deep agent: multi-step asks (plan → specialists → one answer). Wraps `Agent.Turn`."
  use RisiMe.Agents.Planned,
    name: "planner",
    type: :deep,
    goal: "Answer multi-step asks correctly, first useful line within 4 s, then progress.",
    tools: ~w(capabilities calendar_check calendar_add set_reminder schedule_message memory_search
              summarise_range risi_items export_pdf),
    memory: :preferences,
    eval_set: "planner",
    budget: %{p50_ms: 4_000, p95_ms: 8_000, hard_ms: 60_000},
    legacy: [RisiMe.Agent.Turn]
end

defmodule RisiMe.Agents.Planned.Ledger do
  @moduledoc "Deep agent: promises in Official chats → correct cards. Wraps the Ledger pipeline."
  use RisiMe.Agents.Planned,
    name: "ledger",
    type: :deep,
    goal: "Every real promise becomes a correct card; never an invented owner, amount or date.",
    memory: :ledger,
    eval_set: "ledger",
    budget: %{p50_ms: 3_000, p95_ms: 8_000, hard_ms: 30_000},
    legacy: [RisiMe.Agent.Ledger, RisiMe.Agent.Commitments, RisiMe.Agent.RisiItems]
end

defmodule RisiMe.Agents.Planned.Voice do
  @moduledoc "Deep agent: calls and voice notes → transcript, summary, action items."
  use RisiMe.Agents.Planned,
    name: "voice",
    type: :deep,
    goal:
      "Calls and voice notes become a faithful summary and action items in the speaker's language.",
    memory: :pgvector,
    eval_set: "voice",
    budget: %{p50_ms: 6_000, p95_ms: 15_000, hard_ms: 120_000},
    legacy: [RisiMe.Agent.CallTranscribe, RisiMe.Agent.Voice, RisiMe.Agent.ActionItems]
end

defmodule RisiMe.Agents.Planned.Calendar do
  @moduledoc "Specialist: calendar read/add/remove with the honesty rules."
  use RisiMe.Agents.Planned,
    name: "calendar",
    type: :specialist,
    goal: "Correct calendar answers and proposals; never claim an event that was not added.",
    tools: ~w(calendar_check calendar_add calendar_remove risi_calendar_check risi_calendar_add),
    eval_set: "calendar",
    budget: %{p50_ms: 3_000, p95_ms: 6_000, hard_ms: 20_000},
    legacy: [RisiMe.Agent.Calendar, RisiMe.Agent.CalendarTools, RisiMe.Agent.CalendarHonesty]
end

defmodule RisiMe.Agents.Planned.Memory do
  @moduledoc "Specialist: what was said/agreed/pending, from Risi's memory only."
  use RisiMe.Agents.Planned,
    name: "memory",
    type: :specialist,
    goal: "Find what was said or agreed; never answer beyond what memory holds.",
    tools: ~w(memory_search summarise_range),
    memory: :pgvector,
    eval_set: "memory",
    budget: %{p50_ms: 3_000, p95_ms: 6_000, hard_ms: 30_000},
    legacy: [RisiMe.Agent.Memory, RisiMe.Agent.MemoryTools, RisiMe.Agent.MemoryIntent]
end

defmodule RisiMe.Agents.Planned.Summary do
  @moduledoc "Specialist: chat, daily and period summaries."
  use RisiMe.Agents.Planned,
    name: "summary",
    type: :specialist,
    goal: "Short, faithful summaries in the reader's language.",
    eval_set: "summary",
    budget: %{p50_ms: 3_000, p95_ms: 6_000, hard_ms: 30_000},
    legacy: [RisiMe.Agent.Secretary, RisiMe.Agent.DailySummaries, RisiMe.Agent.PeriodSummary]
end

defmodule RisiMe.Agents.Planned.Marketplace do
  @moduledoc "Specialist (later): RisiWork / marketplace asks."
  use RisiMe.Agents.Planned,
    name: "marketplace",
    type: :specialist,
    goal: "Later: RisiWork asks.",
    budget: %{p50_ms: 3_000, p95_ms: 6_000, hard_ms: 20_000},
    legacy: []
end

defmodule RisiMe.Agents.Planned.Twin do
  @moduledoc "On-device agent (later): the digital twin. Nothing of it runs on the server."
  use RisiMe.Agents.Planned,
    name: "twin",
    type: :on_device,
    goal:
      "Learn its user's behaviour on the phone; never leaves the device unless twin sync is on.",
    memory: :on_device,
    budget: %{p50_ms: 1_000, p95_ms: 2_000, hard_ms: 5_000},
    legacy: []
end
