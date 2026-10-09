defmodule RisiMe.Workers.Risi do
  @moduledoc """
  Risi's jobs (§24.11–§24.13, decision 066). **Args hold only ids, codes and counters, never
  text**: a job reads what it needs back from the sealed buffer by message id.

  | kind | queue | what |
  |---|---|---|
  | `extract` | `risi` (2) | commitment extraction of a conversation (debounced, one pending) |
  | `discussion_quiet` | `risi` | v1.27 §27.2 quiet rule of a conversation (one pending, moved by each counted message) |
  | `item_expire` | `risi_timers` | v1.27 §27.5: a proposed ledger item unconfirmed after 48 h |
  | `request` | `risi_requests` (2) | an `ask`/`summarise`/`report` (or its `error` reply) |
  | `action` | `risi_timers` (4) | a `risi_action`: a commitment, or a confirm card (§25.4; a client write waits ≤ 15 s for the phone here) |
  | `expire` | `risi_timers` | a proposal unconfirmed after 48 h is deleted |
  | `reminder`, `escalation` | `risi_timers` | §24.11 timing (no-op when `v` is stale) |
  | `reminder_fire` | `risi_timers` | a `set_reminder` reminder at its time (§25.4) |
  | `digest_sweep` | `risi_timers` | cron every 15 min: 09:00-local digests |
  | `prune` | `risi_timers` | cron hourly: expired confirm cards, old tool-call rows, activity over 90 days |
  | `undo_timeout` | `risi_timers` | a client undo unanswered after 15 s → `failed` (§26.4) |
  | `forget` | `risi_timers` | the safety re-run of `RisiMe.Agent.forget/1` (§24.4, within 1 h) |
  | `offers_backfill` | `risi_timers` | one-shot: offers for existing future-dated items (item 8, idempotent) |
  | `calendar_reminder` | `risi_timers` | v1.29 §29.10: a Risi Calendar reminder (event, user, `v`) |
  | `calendar_changed` | `risi_timers` | v1.29 §29.6: the coalesced `risi_calendar_changed` of a user |
  | `calendar_pending` | `risi_timers` | v1.29 §29.1: a user's held invites (a new `risi_events` device) |
  | `calendar_backfill` | `risi_timers` | v1.29 §29.12: one-shot proposed events + invites (idempotent) |

  Every job re-checks the §24.5 rule (`RisiMe.Agent.may_act?/1`) before touching anything, and
  is discarded while `RISI` is off.
  """
  use Oban.Worker, queue: :risi, max_attempts: 3

  alias RisiMe.Agent
  alias RisiMe.Agent.{Commitments, LedgerActions, Out, Requests, Secretary, Writes}

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"kind" => "forget", "conv" => conv}}) do
    Secretary.forget(conv)
  end

  def perform(%Oban.Job{args: %{"kind" => "expire", "commitment_id" => id}}),
    do: Commitments.expire(id)

  # v1.25/v1.26 (S13+): expired confirm cards (their sealed args), old tool-call rows.
  def perform(%Oban.Job{args: %{"kind" => "prune"}}) do
    RisiMe.Agent.Writes.prune()
    RisiMe.Agent.ActionDraft.prune()
    RisiMe.Agent.ToolCalls.prune()
    RisiMe.Agent.Skills.prune()
    RisiMe.Agent.Reminders.prune()
    # v1.27 §27.3: held copies (expired ones go; ready ones are posted).
    if Out.ready?(), do: RisiMe.Agent.LedgerOut.prune()
    # 30-day summaries are kept 35 days.
    RisiMe.Agent.DailySummaries.prune()
    # v1.29 §29.4–§29.6: purged cancelled events, the 30-day change log, held invites.
    RisiMe.Agent.Calendar.prune()
    # v1.29 §30.7: notes after 365 days, and notes nobody keeps.
    RisiMe.Agent.Notes.prune()
    :ok
  end

  # v1.27 §27.3: a Risi chat became active; its owner's held copies are posted (v1.29: and
  # their held calendar invites).
  def perform(%Oban.Job{args: %{"kind" => "pending_copies", "user_id" => user}}) do
    cond do
      not RisiMe.Risi.enabled?() ->
        {:cancel, :risi_off}

      not Out.ready?() ->
        {:snooze, 60}

      true ->
        result = RisiMe.Agent.LedgerOut.deliver_pending(user)
        RisiMe.Agent.CalendarCards.deliver_pending(user)
        result
    end
  end

  # v1.29 §29.10: a Risi Calendar reminder (ids and `v` only).
  def perform(%Oban.Job{args: %{"kind" => "calendar_reminder"} = args}) do
    cond do
      not RisiMe.Risi.enabled?() -> {:cancel, :risi_off}
      not RisiMe.Agent.Calendar.on?() -> :ok
      not Out.ready?() -> {:snooze, 60}
      true -> RisiMe.Agent.CalendarReminders.fire(args)
    end
  end

  # v1.29 §29.6: the coalesced `risi_calendar_changed` (the latest cursor wins).
  def perform(%Oban.Job{args: %{"kind" => "calendar_changed", "user_id" => user}}),
    do: RisiMe.Agent.Calendar.publish_changed(user)

  # v1.29 §29.1: held invites of a user who became a calendar user.
  def perform(%Oban.Job{args: %{"kind" => "calendar_pending", "user_id" => user}}) do
    cond do
      not RisiMe.Risi.enabled?() ->
        {:cancel, :risi_off}

      not Out.ready?() ->
        {:snooze, 60}

      true ->
        RisiMe.Agent.CalendarCards.deliver_pending(user)
        :ok
    end
  end

  # v1.29 §29.12: the one-shot calendar backfill (`RisiMe.Release.risi_calendar_backfill/1`).
  def perform(%Oban.Job{args: %{"kind" => "calendar_backfill"} = args}) do
    cond do
      not RisiMe.Risi.enabled?() ->
        {:cancel, :risi_off}

      not Out.ready?() ->
        {:snooze, 60}

      true ->
        require Logger
        opts = if u = args["user_id"], do: [user_id: u], else: []
        counts = RisiMe.Agent.CalendarOffers.backfill(opts)
        Logger.info("risi calendar backfill: #{inspect(counts)}")
        :ok
    end
  end

  # v1.26 §26.4: a client undo whose phone didn't answer in 15 s.
  def perform(%Oban.Job{args: %{"kind" => "undo_timeout", "entry_id" => e, "tool_call_id" => c}}),
    do: RisiMe.Agent.Skills.undo_timeout(e, c)

  # Item 8 (2026-10-09): the one-shot offers backfill (`RisiMe.Release.risi_offers_backfill/1`).
  def perform(%Oban.Job{args: %{"kind" => "offers_backfill"}}) do
    cond do
      not RisiMe.Risi.enabled?() ->
        {:cancel, :risi_off}

      not Out.ready?() ->
        {:snooze, 60}

      true ->
        require Logger
        # Run first: a filtered-out Logger call never evaluates its message.
        counts = RisiMe.Agent.Offers.backfill()
        Logger.info("risi offers backfill: #{inspect(counts)}")
        :ok
    end
  end

  def perform(%Oban.Job{args: %{"kind" => "digest_sweep"}}) do
    if Out.ready?() do
      Commitments.digest_sweep()
      # v1.27 §27.6: the personal 09:00 digests of ledger items.
      RisiMe.Agent.LedgerReminders.digest_sweep()
      # 30-day summaries: each Official chat's summary of its day (23:30 local).
      RisiMe.Agent.DailySummaries.sweep()
    else
      :ok
    end
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

  # v1.27 §27.2: the quiet rule's check of a conversation.
  defp run(%{"kind" => "discussion_quiet", "conv" => conv}), do: RisiMe.Agent.Ledger.quiet(conv)

  # v1.27 §27.6: a ledger item's reminder, follow-up or nudge.
  defp run(%{"kind" => "item_reminder"} = args), do: RisiMe.Agent.LedgerReminders.fire(args)

  # v1.27 §27.5: a proposed item unconfirmed 48 h after its summary.
  defp run(%{"kind" => "item_expire", "item_id" => id}), do: RisiMe.Agent.Ledger.expire(id)

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
        a when a in ~w(confirm_write cancel_write) ->
          Writes.act(conv, user, env["__device"], env)

        a when a in ~w(me_too not_me) ->
          RisiMe.Agent.Reminders.act(conv, user, env)

        # v1.29 §29.11: Risi Calendar invites and suggestions.
        a when a in ~w(event_accept event_decline event_suggest suggestion_use suggestion_keep) ->
          RisiMe.Agent.CalendarActions.act(conv, user, env)

        # v1.27 §27.5: item actions (and done) on ledger items, in the actor's Risi chat.
        a when a in ~w(item_confirm item_decline item_edit item_reopen) ->
          LedgerActions.act(conv, user, env)

        "done" ->
          if risi_chat?(conv),
            do: LedgerActions.act(conv, user, env),
            else: Commitments.act(conv, user, env)

        _ ->
          Commitments.act(conv, user, env)
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

  defp risi_chat?(conv), do: RisiMe.Groups.Tabs.risi_chat?(conv)
end
