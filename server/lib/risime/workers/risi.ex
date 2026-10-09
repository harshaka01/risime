defmodule RisiMe.Workers.Risi do
  @moduledoc """
  Risi's jobs (§24.11–§24.13, decision 066). **Args hold only ids, codes and counters, never
  text**: a job reads what it needs back from the sealed buffer by message id.

  | kind | queue | what |
  |---|---|---|
  | `extract` | `risi` (2) | commitment extraction of a conversation (debounced, one pending) |
  | `request` | `risi_requests` (2) | an `ask`/`summarise`/`report` (or its `error` reply) |
  | `action` | `risi_timers` (4) | a `risi_action`: a commitment, or a confirm card (§25.4; a client write waits ≤ 15 s for the phone here) |
  | `expire` | `risi_timers` | a proposal unconfirmed after 48 h is deleted |
  | `reminder`, `escalation` | `risi_timers` | §24.11 timing (no-op when `v` is stale) |
  | `reminder_fire` | `risi_timers` | a `set_reminder` reminder at its time (§25.4) |
  | `digest_sweep` | `risi_timers` | cron every 15 min: 09:00-local digests |
  | `prune` | `risi_timers` | cron hourly: expired confirm cards, old tool-call rows, activity over 90 days |
  | `undo_timeout` | `risi_timers` | a client undo unanswered after 15 s → `failed` (§26.4) |
  | `forget` | `risi_timers` | the safety re-run of `RisiMe.Agent.forget/1` (§24.4, within 1 h) |

  Every job re-checks the §24.5 rule (`RisiMe.Agent.may_act?/1`) before touching anything, and
  is discarded while `RISI` is off.
  """
  use Oban.Worker, queue: :risi, max_attempts: 3

  alias RisiMe.Agent
  alias RisiMe.Agent.{Commitments, Out, Requests, Secretary, Writes}

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"kind" => "forget", "conv" => conv}}) do
    Secretary.forget(conv)
  end

  def perform(%Oban.Job{args: %{"kind" => "expire", "commitment_id" => id}}),
    do: Commitments.expire(id)

  # v1.25/v1.26 (S13+): expired confirm cards (their sealed args), old tool-call rows.
  def perform(%Oban.Job{args: %{"kind" => "prune"}}) do
    RisiMe.Agent.Writes.prune()
    RisiMe.Agent.ToolCalls.prune()
    RisiMe.Agent.Skills.prune()
    RisiMe.Agent.Reminders.prune()
    :ok
  end

  # v1.26 §26.4: a client undo whose phone didn't answer in 15 s.
  def perform(%Oban.Job{args: %{"kind" => "undo_timeout", "entry_id" => e, "tool_call_id" => c}}),
    do: RisiMe.Agent.Skills.undo_timeout(e, c)

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
    cond do
      not Secretary.active_human?(conv, user) -> :ok
      a["error"] -> Requests.handle(conv, a["message_id"], a["request_id"], user, a["error"])
      true -> Requests.handle(conv, a["message_id"], a["request_id"], user, nil, a["t0"])
    end
  end

  defp run(%{"kind" => "action", "conv" => conv, "message_id" => id, "user_id" => user}) do
    with true <- Secretary.active_human?(conv, user),
         %{"type" => "risi_action", "__sender" => ^user} = env <- Secretary.envelope(conv, id) do
      case env["action"] do
        # v1.25 §25.4: confirm cards (the write runs here, without a model call).
        a when a in ~w(confirm_write cancel_write) -> Writes.act(conv, user, env["__device"], env)
        a when a in ~w(me_too not_me) -> RisiMe.Agent.Reminders.act(conv, user, env)
        _ -> Commitments.act(conv, user, env)
      end
    else
      _ -> :ok
    end
  end

  # v1.25 §25.4: a `set_reminder` reminder fires.
  defp run(%{"kind" => "reminder_fire", "reminder_id" => id}),
    do: RisiMe.Agent.Reminders.fire(id)

  defp run(%{"kind" => "reminder", "commitment_id" => id, "v" => v}),
    do: Commitments.remind(id, v)

  defp run(%{"kind" => "escalation", "commitment_id" => id, "v" => v, "n" => n}),
    do: Commitments.escalate(id, v, n)

  defp run(_args), do: :ok
end
