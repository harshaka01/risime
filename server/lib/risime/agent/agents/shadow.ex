defmodule RisiMe.Agents.Shadow do
  @moduledoc """
  The deep agents' hook in today's turn (decision 084). `Agent.Turn` calls `turn/4` once per
  turn, right after it chose its route; the hook **never changes the turn** and never raises:

    * remembers the turn's legacy route, language and typing time (request at the server →
      `risi_progress` `working`) for `RisiMe.Agents.TurnTiming`, which writes the turn's row;
    * runs the Fast router through the orchestrator (`RISI_AGENT_ROUTER`, default `shadow`):
      rules inline (microseconds), or in the background when a router model is on, and logs
      its decision against the legacy route.

  Nothing runs while `RISI_AGENTS=legacy`.
  """
  require Logger

  alias RisiMe.Agents.{Flags, Orchestrator, Router}

  @key :risi_agents_turn

  @doc "See the module doc. `ctx` is the turn's context (`asker`, `now`, `tz`, `lang`)."
  def turn(question, intent, clarified, ctx) do
    unless Flags.legacy?() do
      legacy = legacy_route(intent, clarified)

      Process.put(@key, %{
        legacy_route: legacy,
        first_ms: first_ms(ctx[:now]),
        lang: ctx[:lang],
        user: ctx[:asker]
      })

      if Flags.mode("router") != :off do
        today =
          try do
            ctx.now |> RisiMe.Agent.Clock.local(ctx.tz) |> NaiveDateTime.to_date()
          rescue
            _ -> Date.utc_today()
          end

        Orchestrator.call("router", question,
          user: ctx[:asker],
          lang: ctx[:lang],
          today: today,
          legacy: {:value, legacy},
          legacy_label: legacy,
          compare: &compare/2,
          async: Router.model() != nil
        )
      end
    end

    :ok
  rescue
    e ->
      Logger.warning("risi agents: shadow hook failed (#{inspect(e.__struct__)})")
      :ok
  end

  @doc "Pops what `turn/4` remembered for this process (nil when nothing)."
  def pop, do: Process.delete(@key)

  @doc """
  Today's route of a turn, in the router's labels: `clarify` (a name chip), `calendar_read`,
  `ledger_items`, `memory`, or `loop` (the `risi_next_action` loop).
  """
  def legacy_route(_intent, clarified) when not is_nil(clarified) and clarified != false,
    do: "clarify"

  def legacy_route({:schedule_read, _}, _), do: "calendar_read"
  def legacy_route(:risi_items, _), do: "ledger_items"
  def legacy_route({:memory, _}, _), do: "memory"
  def legacy_route(_, _), do: "loop"

  @doc """
  Does the router agree with today's route? The deterministic routes must match exactly; on a
  `loop` turn any route the loop serves agrees (it may be finer: calendar_write, chat, …). A
  name-chip turn is not judged.
  """
  def compare("clarify", _), do: nil

  def compare("loop", %{route: r}), do: r not in ~w(calendar_read ledger_items memory)

  def compare(legacy, %{route: r}) when is_binary(legacy), do: r == legacy

  def compare(_, _), do: nil

  # The typing budget: the request's server time → now (the turn has just sent `working`).
  defp first_ms(%DateTime{} = at) do
    if Application.get_env(:risime, :risi_now) do
      nil
    else
      ms = DateTime.diff(DateTime.utc_now(), at, :millisecond)
      if ms >= 0 and ms < 86_400_000, do: ms, else: nil
    end
  end

  defp first_ms(_), do: nil
end

defmodule RisiMe.Agents.TurnTiming do
  @moduledoc """
  Writes one `risi_agent_runs` row per finished legacy turn (agent `legacy_turn`, mode
  `legacy`): the turn's duration, its typing time, its route and language (decision 084's
  baseline and budgets). A telemetry handler on today's `[:risime, :risi, :turn, :stop]` event
  (it runs in the turn's process, after the turn). Snoozed turns are not counted (they run
  again). Nothing while `RISI_AGENTS=legacy`.
  """
  alias RisiMe.Agents.{Budget, Flags, Runs, Shadow}

  @id "risi-agents-turn-timing"

  @doc "Attaches the handler (idempotent)."
  def attach do
    :telemetry.attach(@id, [:risime, :risi, :turn, :stop], &__MODULE__.handle/4, nil)
    :ok
  rescue
    _ -> :ok
  end

  @doc false
  def handle(_event, %{duration: d}, %{outcome: outcome}, _cfg) do
    seen = Shadow.pop()

    if outcome != :snooze and not Flags.legacy?() do
      ms = System.convert_time_unit(d, :native, :millisecond)
      seen = seen || %{}

      Runs.record(%{
        agent: "legacy_turn",
        mode: "legacy",
        status: if(outcome == :ok, do: "ok", else: "error"),
        legacy_route: seen[:legacy_route],
        latency_ms: ms,
        first_ms: seen[:first_ms],
        budget_ms: Budget.class(:simple).p95_ms,
        learned_version: "legacy",
        lang: seen[:lang],
        user: seen[:user]
      })
    end

    :ok
  rescue
    _ -> :ok
  end

  def handle(_, _, _, _), do: :ok
end
