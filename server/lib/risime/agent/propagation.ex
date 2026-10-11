defmodule RisiMe.Agent.Propagation do
  @moduledoc """
  Cancellation and change propagation (contract v1.36 §35.4, Risi Brain v2 step 4), from the
  memory ingest (`RisiMe.Agent.MemoryIngest`, independent of `RISI_LEDGER`/`RISI_NOTES`).

  When the chat cancels or moves a meeting (an extracted `meeting` item with `status`
  `cancelled` or `moved`), the matching **Risi Calendar event made from this chat** (active, in
  the future, `source_conversation_id` = the chat) is found:

    * by its time when the item names the old time (within 1 h), otherwise among the chat's
      upcoming events by title similarity (the only one, or the clear best);
    * nothing is changed when no single event matches.

  Then:

    * **cancelled** → the event is cancelled for everyone (`Calendar.cancel/3`, `by` = Risi; the
      participants get the `event_update` "cancelled"), **read back** (`state: "cancelled"`), and
      each participant with a Risi chat gets **"Reschedule?"** with up to 3 slots free for every
      participant in their Risi Calendars (the next 5 days, 09:00–18:00 in the owner's zone, not
      Sundays, the original length), as `ask` chips;
    * **moved** to a new future time → the event is moved (`Calendar.patch/3` as its owner; the
      others get the update and are asked again), **read back** (the new start).

  No claim without the read-back. Phone calendars can't be read by the server: the slots say
  "free in Risi Calendar".
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, Clock, Embeddings, Out}
  alias RisiMe.Agent.Calendar.Event
  alias RisiMe.Repo

  @slot_days 5
  @max_slots 3

  @doc """
  A meeting of `conv` was cancelled or moved: `%{status, text, at (old time) | nil, new_at |
  nil}`. Returns `{:cancelled, event_id}`, `{:moved, event_id}` or `:none`.
  """
  def meeting_changed(conv, %{status: status} = m) when status in ~w(cancelled moved) do
    if Calendar.on?() do
      case match(conv, m) do
        %Event{} = e -> apply_change(status, e, m)
        nil -> :none
      end
    else
      :none
    end
  rescue
    err ->
      Logger.warning("Risi propagation failed: #{inspect(err.__struct__)}")
      :none
  end

  def meeting_changed(_conv, _m), do: :none

  ## Matching

  defp match(conv, m) do
    now = Clock.now()

    events =
      Repo.all(
        from e in Event,
          where: e.source_conversation_id == ^conv and e.state == "active" and e.start_at > ^now,
          order_by: [asc: e.start_at]
      )

    by_time =
      case {m[:at], m[:on]} do
        {%DateTime{} = at, _} ->
          Enum.filter(events, &(abs(DateTime.diff(&1.start_at, at)) <= 3600))

        {_, %Date{} = on} ->
          Enum.filter(events, fn e ->
            e.start_at |> Clock.local(e.tz || Clock.default_tz()) |> NaiveDateTime.to_date() == on
          end)

        _ ->
          events
      end

    case by_time do
      [one] -> one
      [] -> nil
      many -> best(many, m.text)
    end
  end

  defp best(events, text) do
    v = Embeddings.embed(text || "")

    scored =
      events
      |> Enum.flat_map(fn e ->
        case Calendar.open_title(e) do
          {:ok, t} -> [{Embeddings.cosine(v, Embeddings.embed(t)), e}]
          _ -> []
        end
      end)
      |> Enum.sort_by(&elem(&1, 0), :desc)

    case scored do
      [{s, e}] when s >= 0.25 -> e
      [{s1, e}, {s2, _} | _] when s1 >= 0.25 and s1 - s2 >= 0.15 -> e
      _ -> nil
    end
  end

  ## Applying

  defp apply_change("cancelled", e, _m) do
    {e, parts} = Calendar.load(e.event_id)
    :ok = Calendar.cancel(e, parts, nil)

    case Calendar.load(e.event_id) do
      {%Event{state: "cancelled"} = e2, ps} ->
        offer_reschedule(e2, ps)
        {:cancelled, e.event_id}

      _ ->
        :none
    end
  end

  defp apply_change("moved", e, %{new_at: %DateTime{} = new_at}) do
    if DateTime.compare(new_at, Clock.now()) == :gt do
      len = DateTime.diff(e.end_at, e.start_at)
      stop = DateTime.add(new_at, len, :second)

      body = %{
        "version" => e.version,
        "start" => Clock.ts(new_at),
        "end" => Clock.ts(stop)
      }

      with {:ok, _view} <- Calendar.patch(e.owner, e.event_id, body),
           {%Event{} = e2, _} <- Calendar.load(e.event_id),
           true <- DateTime.compare(e2.start_at, Clock.usec(new_at)) == :eq do
        {:moved, e.event_id}
      else
        _ -> :none
      end
    else
      :none
    end
  end

  defp apply_change(_status, _e, _m), do: :none

  ## Reschedule with free slots

  @doc """
  Up to 3 slots of the event's length free for every active participant in their Risi
  Calendars: the next 5 days from 2 h from now, 09:00–18:00 in the owner's zone, no Sundays,
  at most one per day.
  """
  def free_slots(%Event{} = e, parts, now \\ Clock.now()) do
    users = for p <- parts, not p.removed, p.status != "declined", do: p.user_id
    len = max(DateTime.diff(e.end_at, e.start_at), 1800)
    tz = e.tz || Clock.user_tz(e.owner)
    from = DateTime.add(now, 2 * 3600, :second)
    to = DateTime.add(now, (@slot_days + 1) * 86_400, :second)

    busy =
      Enum.flat_map(users, fn u ->
        case Calendar.own_events(u, from, to) do
          {:ok, rows} -> Enum.map(rows, &{&1.event.start_at, &1.event.end_at})
          _ -> []
        end
      end)

    today = now |> Clock.local(tz) |> NaiveDateTime.to_date()

    1..@slot_days
    |> Enum.map(&Date.add(today, &1 - 1))
    |> Enum.reject(&(Date.day_of_week(&1) == 7))
    |> Enum.flat_map(fn d ->
      d
      |> day_slots(tz, len)
      |> Enum.filter(fn {s, t} ->
        DateTime.compare(s, from) != :lt and
          not Enum.any?(busy, fn {bs, be} ->
            DateTime.compare(bs, t) == :lt and DateTime.compare(be, s) == :gt
          end)
      end)
      |> Enum.take(1)
    end)
    |> Enum.take(@max_slots)
  end

  defp day_slots(date, tz, len) do
    for m <- 0..(9 * 60 - div(len, 60))//30 do
      s = Clock.to_utc(NaiveDateTime.new!(date, Time.add(~T[09:00:00], m * 60)), tz)
      {s, DateTime.add(s, len, :second)}
    end
  end

  defp offer_reschedule(e, parts) do
    with {:ok, title} <- Calendar.open_title(e) do
      slots = free_slots(e, parts)
      users = for p <- parts, not p.removed, do: p.user_id
      names = RisiMe.Agent.CalendarCards.names(users)

      for u <- users, rc = RisiMe.RisiChat.active_id(u), rc != nil do
        tz = Clock.user_tz(u)
        others = for(x <- users, x != u, do: names[x] || "someone") |> Enum.join(", ")
        with_part = if others == "", do: "", else: " with #{others}"

        labels =
          Enum.map(slots, fn {s, _} ->
            Elixir.Calendar.strftime(Clock.local(s, tz), "%a %-d %b %H:%M")
          end)

        body =
          case labels do
            [] ->
              "\"#{title}\" was cancelled in the chat. I found no time free for everyone in " <>
                "Risi Calendar in the next #{@slot_days} days."

            _ ->
              "\"#{title}\" was cancelled in the chat. Reschedule? Free for everyone in Risi " <>
                "Calendar: #{Enum.join(labels, ", ")}."
          end

        chips =
          for l <- labels,
              do: %{
                "label" => l,
                "action" => "ask",
                "text" => "Add \"#{title}\"#{with_part} on #{l}"
              }

        risi = %{
          "kind" => "answer",
          "request_id" => Ecto.UUID.generate(),
          "answer" => body,
          "refs" => [],
          "confidence" => 1.0,
          "steps" => [],
          "sources" => [],
          "next_steps" => [],
          "next_actions" => chips,
          "call_ref" => nil,
          "made_by" => RisiMe.Agent.MadeBy.rule(),
          "notify" => [u]
        }

        Out.post(rc, body, risi)
      end
    end

    :ok
  end
end
