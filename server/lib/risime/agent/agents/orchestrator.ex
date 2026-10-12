defmodule RisiMe.Agents.Orchestrator do
  @moduledoc """
  The only way to run an agent (decision 084): agents never call each other directly.

  `call(name, input, opts)` reads the agent's flag for the asker (`RisiMe.Agents.Flags`):

    * `off` (or `RISI_AGENTS=legacy`): runs the legacy path only; no row, no event.
    * `shadow`: runs the legacy path (its answer is the result), then the agent under its hard
      budget; logs the agent's decision against the legacy one (run row + activity). With
      `async: true` the agent part runs in the background and never delays the caller.
    * `canary`: `on` for `RISI_DEV_USERS`, `shadow` for everyone else.
    * `on`: runs the agent under its hard budget; an error, a timeout (the hard budget) or a
      refusal **falls back to the legacy path** and is logged as an incident.

  Options:
    * `:user` — the asker's id (canary group, hashed `user_ref`);
    * `:legacy` — a 0-arity function computing today's result, or `{:value, v}` when the caller
      already has it;
    * `:legacy_label` — a short code of the legacy result (e.g. the route), for the run row;
    * `:compare` — `(legacy_label, agent_output) -> boolean | nil` (agreement, shadow only);
    * `:async` — run a shadow agent in the background;
    * `:lang` — the request's language code, for the run row.

  Returns `{:legacy, value}` or `{:agent, output}`. Never raises because of the agent.
  """
  require Logger

  alias RisiMe.Agents.{Activity, Budget, Flags, Runs}

  def call(agent, input, opts \\ [])

  def call(name, input, opts) when is_binary(name) do
    mod = RisiMe.Agents.module(name) || raise ArgumentError, "unknown agent #{inspect(name)}"
    call(mod, input, opts)
  end

  # An agent module (tests, and agents not in the registry yet).
  def call(mod, input, opts) when is_atom(mod) do
    mode = Flags.mode_for(mod.spec().name, opts[:user])
    do_call(mode, mod, input, opts)
  end

  defp do_call(:off, _mod, _input, opts), do: {:legacy, legacy(opts)}

  defp do_call(:shadow, mod, input, opts) do
    value = legacy(opts)

    if opts[:async] do
      start_background(fn -> shadow(mod, input, opts) end)
    else
      shadow(mod, input, opts)
    end

    {:legacy, value}
  end

  defp do_call(:on, mod, input, opts) do
    spec = mod.spec()
    run_id = Ecto.UUID.generate()
    base = base(spec, run_id, :on, opts)
    Activity.emit("start", base)
    {result, ms} = Budget.run(fn -> mod.run(input, ctx(opts, :on)) end, spec.budget.hard_ms)

    case result do
      {:ok, out} ->
        label = label(mod, out)
        status = if ms > spec.budget.p95_ms, do: "over_budget", else: "ok"
        Activity.emit("decision", Map.merge(base, %{decision: label, latency_ms: ms}))
        Activity.emit("done", Map.merge(base, %{status: status, latency_ms: ms}))

        Runs.record(
          row(spec, :on, opts, %{
            decision: label,
            status: status,
            latency_ms: ms,
            agreed: agreed(opts, out)
          })
        )

        {:agent, out}

      {:error, reason} ->
        status = status_of(reason)

        Logger.warning(
          "risi agents: incident agent=#{spec.name} reason=#{status} latency_ms=#{ms} fallback=legacy"
        )

        Activity.emit("fallback", Map.merge(base, %{status: status, latency_ms: ms}))

        Runs.record(
          row(spec, :on, opts, %{
            status: status,
            fell_back: true,
            reason: reason_code(reason),
            latency_ms: ms
          })
        )

        {:legacy, legacy(opts)}
    end
  end

  defp shadow(mod, input, opts) do
    spec = mod.spec()
    run_id = Ecto.UUID.generate()
    base = base(spec, run_id, :shadow, opts)
    {result, ms} = Budget.run(fn -> mod.run(input, ctx(opts, :shadow)) end, spec.budget.hard_ms)

    {fields, label} =
      case result do
        {:ok, out} ->
          label = label(mod, out)
          status = if ms > spec.budget.p95_ms, do: "over_budget", else: "ok"
          {%{decision: label, status: status, latency_ms: ms, agreed: agreed(opts, out)}, label}

        {:error, reason} ->
          {%{status: status_of(reason), reason: reason_code(reason), latency_ms: ms}, nil}
      end

    Activity.emit(
      "decision",
      Map.merge(base, %{decision: label, status: fields.status, latency_ms: ms})
    )

    Runs.record(row(spec, :shadow, opts, fields))
    :ok
  rescue
    e ->
      Logger.warning("risi agents: shadow run failed (#{inspect(e.__struct__)})")
      :error
  end

  defp legacy(opts) do
    case opts[:legacy] do
      {:value, v} -> v
      f when is_function(f, 0) -> f.()
      nil -> nil
    end
  end

  defp ctx(opts, mode) do
    opts |> Keyword.take([:user, :lang, :today, :tz]) |> Map.new() |> Map.put(:mode, mode)
  end

  defp base(spec, run_id, mode, opts) do
    %{
      run_id: run_id,
      agent: spec.name,
      mode: mode,
      learned_version: spec.learned_version,
      user: opts[:user]
    }
  end

  defp row(spec, mode, opts, fields) do
    Map.merge(
      %{
        agent: spec.name,
        mode: mode,
        legacy_route: opts[:legacy_label],
        budget_ms: spec.budget.hard_ms,
        learned_version: spec.learned_version,
        lang: opts[:lang],
        user: opts[:user]
      },
      fields
    )
  end

  defp label(mod, out) do
    if function_exported?(mod, :label, 1), do: mod.label(out), else: nil
  end

  defp agreed(opts, out) do
    case opts[:compare] do
      f when is_function(f, 2) -> f.(opts[:legacy_label], out)
      _ -> nil
    end
  end

  defp status_of(:timeout), do: "timeout"
  defp status_of(:not_built), do: "skipped"
  defp status_of(_), do: "error"

  defp reason_code(r) when is_atom(r), do: Atom.to_string(r)
  defp reason_code(_), do: "error"

  defp start_background(fun) do
    case Process.whereis(RisiMe.Agents.TaskSup) do
      nil -> Task.start(fun)
      _ -> Task.Supervisor.start_child(RisiMe.Agents.TaskSup, fun)
    end
  end
end
