defmodule RisiMe.Agent.CalendarReminders do
  @moduledoc """
  Risi Calendar reminders (contract v1.29 §29.10), on the §26 reminders path: one Oban job per
  (event, participant) — args ids and `schedule_v` only, never a title — at `start −
  reminder_min` for each participant whose status is **`accepted`**. Every time/reminder change
  bumps `schedule_v` and reschedules; decline, cancel and removal cancel. A job whose `v` is
  stale, or whose participant is no longer accepted, does nothing.

  All-day events: 09:00 local the day before when `reminder_min` ≥ 60, else 09:00 on the day.
  These reminders are the user's own calendar and are not gated by the Reminders skill (Q1); a
  gated user's Reminders activity log gets an entry (`action: "calendar_reminder"`, `via:
  "risi_calendar"`). The push is the usual content-free wake of the Risi-chat message.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, CalendarCards, Clock}
  alias RisiMe.Agent.Calendar.{Event, Participant}
  alias RisiMe.Repo
  alias RisiMe.Workers.Risi, as: Job

  @doc "Reschedules every participant's reminder of `e`."
  def schedule_all(%Event{} = e, parts), do: Enum.each(parts, &schedule(e, &1))

  @doc "Reschedules one participant's reminder (cancels the old one first)."
  def schedule(_e, nil), do: :ok

  def schedule(%Event{} = e, %Participant{} = p) do
    v = bump(e.event_id, p.user_id)
    cancel_jobs(e.event_id, p.user_id)

    with true <- e.state == "active" and p.status == "accepted" and not p.removed,
         n when is_integer(n) <- p.reminder_min,
         %DateTime{} = at <- at(e, n, Clock.user_tz(p.user_id)),
         :gt <- DateTime.compare(at, Clock.now()) do
      %{"kind" => "calendar_reminder", "event_id" => e.event_id, "user_id" => p.user_id, "v" => v}
      |> Job.new(queue: :risi_timers, scheduled_at: at)
      |> Oban.insert()
    end

    :ok
  end

  @doc "Cancels a participant's reminder (decline, cancel, removal)."
  def cancel(event_id, user_id) do
    bump(event_id, user_id)
    cancel_jobs(event_id, user_id)
    :ok
  end

  @doc "When the reminder of an event fires for a reminder of `n` minutes in zone `tz`."
  def at(%Event{all_day: true} = e, n, tz) do
    day = NaiveDateTime.to_date(Clock.local(e.start_at, tz))
    day = if n >= 60, do: Date.add(day, -1), else: day
    Clock.to_utc(NaiveDateTime.new!(day, ~T[09:00:00]), tz)
  end

  def at(%Event{} = e, n, _tz), do: DateTime.add(e.start_at, -n * 60, :second)

  defp bump(event_id, user_id) do
    case Repo.update_all(
           from(p in Participant,
             where: p.event_id == ^event_id and p.user_id == ^user_id,
             select: p.schedule_v
           ),
           inc: [schedule_v: 1]
         ) do
      {1, [v]} -> v
      _ -> 0
    end
  end

  defp cancel_jobs(event_id, user_id) do
    Oban.cancel_all_jobs(
      from j in Oban.Job,
        where:
          j.worker == "RisiMe.Workers.Risi" and
            j.state in ["available", "scheduled", "retryable"] and
            fragment("?->>'kind' = 'calendar_reminder'", j.args) and
            fragment("?->>'event_id' = ?", j.args, ^event_id) and
            fragment("?->>'user_id' = ?", j.args, ^user_id)
    )

    :ok
  end

  @doc "A reminder job fires (no-op when stale). Oban result."
  def fire(%{"event_id" => id, "user_id" => user, "v" => v}) do
    with {%Event{state: "active"} = e, parts} <- Calendar.load(id),
         %Participant{status: "accepted", removed: false, schedule_v: ^v} = p <-
           Enum.find(parts, &(&1.user_id == user)) do
      case CalendarCards.reminder(e, parts, p) do
        :ok ->
          log_activity(user, e)
          :ok

        {:error, :rate_limited} ->
          {:snooze, 30}

        {:error, reason} ->
          Logger.warning("Risi calendar reminder not sent: #{inspect(reason)}")
          :ok
      end
    else
      _ -> :ok
    end
  end

  def fire(_args), do: :ok

  # The Reminders activity log of a gated user (sealed with RISI_MEMORY_KEY; the user's own).
  defp log_activity(user, e) do
    if RisiMe.Agent.Skills.gated?(user) do
      {:ok, title} = Calendar.open_title(e)

      RisiMe.Agent.Skills.log!(user, "reminders", "calendar_reminder", "Reminded you: #{title}",
        via: "risi_calendar"
      )
    end

    :ok
  rescue
    err ->
      Logger.warning("Risi calendar reminder not logged: #{inspect(err.__struct__)}")
      :ok
  end
end
