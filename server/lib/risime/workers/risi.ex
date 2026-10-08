defmodule RisiMe.Workers.Risi do
  @moduledoc """
  Risi's jobs (§24.11–§24.13, decision 066). **Args hold only ids, codes and counters, never
  text**: a job reads what it needs back from the sealed buffer by message id.

  | kind | queue | what |
  |---|---|---|
  | `extract` | `risi` (2) | commitment extraction of a conversation (debounced, one pending) |
  | `request` | `risi_requests` (2) | an `ask`/`summarise`/`report` (or its `error` reply) |
  | `action` | `risi_timers` (2) | a `risi_action` on a commitment |
  | `expire` | `risi_timers` | a proposal unconfirmed after 48 h is deleted |
  | `reminder`, `escalation` | `risi_timers` | §24.11 timing (no-op when `v` is stale) |
  | `digest_sweep` | `risi_timers` | cron every 15 min: 09:00-local digests |
  | `forget` | `risi_timers` | the safety re-run of `RisiMe.Agent.forget/1` (§24.4, within 1 h) |

  Every job re-checks the §24.5 rule (`RisiMe.Agent.may_act?/1`) before touching anything, and
  is discarded while `RISI` is off.
  """
  use Oban.Worker, queue: :risi, max_attempts: 3

  alias RisiMe.Agent
  alias RisiMe.Agent.{Commitments, Out, Requests, Secretary}

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"kind" => "forget", "conv" => conv}}) do
    Secretary.forget(conv)
  end

  def perform(%Oban.Job{args: %{"kind" => "expire", "commitment_id" => id}}),
    do: Commitments.expire(id)

  def perform(%Oban.Job{args: %{"kind" => "digest_sweep"}}) do
    if Out.ready?(), do: Commitments.digest_sweep(), else: :ok
  end

  def perform(%Oban.Job{args: %{"conv" => conv} = args}) do
    cond do
      not RisiMe.Risi.enabled?() -> {:cancel, :risi_off}
      not Out.ready?() -> {:snooze, 60}
      not Agent.may_act?(conv) -> :ok
      true -> run(args)
    end
  end

  def perform(_job), do: :ok

  defp run(%{"kind" => "extract", "conv" => conv}), do: Commitments.extract(conv)

  defp run(%{"kind" => "request", "conv" => conv, "user_id" => user} = a) do
    if Secretary.active_human?(conv, user),
      do: Requests.handle(conv, a["message_id"], a["request_id"], user, a["error"]),
      else: :ok
  end

  defp run(%{"kind" => "action", "conv" => conv, "message_id" => id, "user_id" => user}) do
    with true <- Secretary.active_human?(conv, user),
         %{"type" => "risi_action", "__sender" => ^user} = env <- Secretary.envelope(conv, id) do
      Commitments.act(conv, user, env)
    else
      _ -> :ok
    end
  end

  defp run(%{"kind" => "reminder", "commitment_id" => id, "v" => v}),
    do: Commitments.remind(id, v)

  defp run(%{"kind" => "escalation", "commitment_id" => id, "v" => v, "n" => n}),
    do: Commitments.escalate(id, v, n)

  defp run(_args), do: :ok
end
