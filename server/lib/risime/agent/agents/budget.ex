defmodule RisiMe.Agents.Budget do
  @moduledoc """
  Speed budgets (decision 084) and the bounded run of one agent.

  | Class | p50 / p95 |
  |---|---|
  | typing indicator (`risi_progress` `working` after the request reached the server) | < 1 s |
  | simple answer (one tool or none) | 3 s / 6 s |
  | multi-step: first useful line | 4 s, then progress |

  `run/2` runs a function under an agent's hard limit in a task (under
  `RisiMe.Agents.TaskSup` when it runs, else a linked-free `Task.async`); past the limit the task
  is killed and the result is `{:error, :timeout}`. A raise or exit is `{:error, :error}`.
  """

  @classes %{
    typing: %{p50_ms: 1_000, p95_ms: 1_000},
    simple: %{p50_ms: 3_000, p95_ms: 6_000},
    multi_first_line: %{p50_ms: 4_000, p95_ms: 4_000}
  }

  @doc "The budget classes (`:typing`, `:simple`, `:multi_first_line`)."
  def classes, do: @classes

  @doc "The budget of a class."
  def class(name), do: Map.fetch!(@classes, name)

  @doc "True when `ms` is within the class's p95 budget."
  def within?(class, ms) when is_integer(ms), do: ms <= class(class).p95_ms

  @doc """
  Runs `fun` (0-arity, returning `{:ok, v} | {:error, r}`) for at most `hard_ms`. Returns
  `{result, latency_ms}` where result is `{:ok, v}`, `{:error, r}`, `{:error, :timeout}` or
  `{:error, :error}` (raised/exited).
  """
  def run(fun, hard_ms) when is_function(fun, 0) and is_integer(hard_ms) do
    started = System.monotonic_time(:millisecond)

    task =
      case Process.whereis(RisiMe.Agents.TaskSup) do
        nil -> Task.async(fn -> safe(fun) end)
        _ -> Task.Supervisor.async_nolink(RisiMe.Agents.TaskSup, fn -> safe(fun) end)
      end

    result =
      case Task.yield(task, hard_ms) || Task.shutdown(task, :brutal_kill) do
        {:ok, {:ok, _} = ok} -> ok
        {:ok, {:error, _} = err} -> err
        {:ok, _other} -> {:error, :error}
        {:exit, _} -> {:error, :error}
        nil -> {:error, :timeout}
      end

    {result, System.monotonic_time(:millisecond) - started}
  end

  defp safe(fun) do
    fun.()
  rescue
    _ -> {:error, :error}
  catch
    _, _ -> {:error, :error}
  end
end
