defmodule RisiMe.Agent.CalendarCards do
  @moduledoc """
  The Risi-chat and Official cards of Risi Calendar (contract v1.29 §29.8–§29.10): native cards,
  never text chips. Every card goes through `RisiMe.Agent.Out` (MLS-encrypted; `made_by` rule,
  `model: null`); its `body` is the English fallback for old apps.

  * `calendar_invite` — in each participant's own Risi chat, `notify: [participant]`. A
    participant who isn't a calendar user (or has no active Risi chat) is still a participant:
    the invite is **held** (`risi_calendar_invites_pending`) until they become one or the event
    starts (`deliver_pending/1`).
  * `event_update` — to every participant's Risi chat after any accepted change (`notify: []`;
    phones apply it to every card of that event and show no bubble).
  * `calendar_suggestion` — to the owner; `calendar_reminder` — `RisiMe.Agent.CalendarReminders`.
  * `event_card` — `mode: "added"` after [Add] in the asker's Risi chat; `mode: "official"` in an
    Official conversation for a Risi-made event whose participants are all calendar users.

  Only calendar users get these kinds (§29.1: an app without `risi_events` sees v1.28 exactly).
  Titles are only in the encrypted cards, never in logs.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, ClientTools, Clock, Out}
  alias RisiMe.Agent.Calendar.{Event, Participant}
  alias RisiMe.{Repo, RisiChat}

  ## Invites

  @doc """
  Sends `user` the `calendar_invite` of event `e` (`reason`: `new` | `time_changed` | `added`;
  `from`: the inviting user, nil for Risi), or holds it. `:sent` | `:held`.
  """
  def invite(%Event{} = e, parts, user, reason, from) do
    rc = Calendar.calendar_user?(user) && RisiChat.active_id(user)

    if is_binary(rc) and e.state == "active" do
      case post_invite(e, parts, user, rc, reason, from) do
        :ok ->
          unhold(e.event_id, user)
          :sent

        :error ->
          hold(e.event_id, user, reason)
          retry_pending(user)
          :held
      end
    else
      if e.state == "active", do: hold(e.event_id, user, reason)
      :held
    end
  end

  defp post_invite(e, parts, user, rc, reason, from) do
    with {:ok, title} <- Calendar.open_title(e) do
      tz = Clock.user_tz(user)
      when_ = ClientTools.span12(e.start_at, e.end_at, e.all_day, tz)
      with = with_names(parts, [user])
      lead = if reason == "time_changed", do: "New time for an invitation", else: "Invitation"

      body =
        "#{lead}: #{title} · #{when_}#{with_part(with)}. Accept, decline or suggest another time " <>
          "in RisiMe."

      risi =
        e
        |> base(parts, title)
        |> Map.merge(%{
          "kind" => "calendar_invite",
          "from" => from,
          "reason" => reason,
          "source_conversation_id" => e.source_conversation_id,
          "item_id" => e.source_item_id,
          "note_id" => e.source_note_id,
          "buttons" => ["accept", "decline", "suggest"],
          "expires_at" => Clock.ts(e.start_at),
          "notify" => [user]
        })

      case Out.post(rc, body, risi) do
        {:ok, _} ->
          :ok

        {:error, reason} ->
          Logger.warning("Risi calendar invite not sent: #{inspect(reason)}")
          :error
      end
    else
      _ -> :error
    end
  end

  defp hold(event_id, user, reason) do
    Repo.insert_all(
      "risi_calendar_invites_pending",
      [
        %{
          event_id: Ecto.UUID.dump!(event_id),
          user_id: Ecto.UUID.dump!(user),
          reason: reason,
          inserted_at: DateTime.utc_now()
        }
      ],
      on_conflict: {:replace, [:reason, :inserted_at]},
      conflict_target: [:event_id, :user_id]
    )

    :ok
  end

  defp unhold(event_id, user) do
    Repo.delete_all(
      from i in "risi_calendar_invites_pending",
        where: i.event_id == type(^event_id, :binary_id) and i.user_id == type(^user, :binary_id)
    )

    :ok
  end

  @doc "The invites held for `user` (event ids and reasons; tests, support)."
  def pending(user) do
    Repo.all(
      from i in "risi_calendar_invites_pending",
        where: i.user_id == type(^user, :binary_id),
        select: {type(i.event_id, :binary_id), i.reason}
    )
  end

  @doc "A device started advertising `risi_events`: the user's held invites go out (a job)."
  def became_calendar_user(user) do
    if Calendar.on?() do
      retry_pending(user, 0)
      schedule_backfill(user)
    end

    :ok
  end

  # The §29.12 backfill ran at deploy before any device had `risi_events`: run it for this user.
  defp schedule_backfill(user) do
    %{"kind" => "calendar_backfill", "user_id" => user}
    |> RisiMe.Workers.Risi.new(
      queue: :risi_timers,
      schedule_in: 30,
      unique: [period: 120, keys: [:kind, :user_id], states: [:available, :scheduled]]
    )
    |> Oban.insert()

    :ok
  end

  defp retry_pending(user, delay_s \\ 60) do
    %{"kind" => "calendar_pending", "user_id" => user}
    |> RisiMe.Workers.Risi.new(
      queue: :risi_timers,
      schedule_in: delay_s,
      unique: [period: 60, keys: [:kind, :user_id], states: [:available, :scheduled]]
    )
    |> Oban.insert()

    :ok
  end

  @doc """
  Posts the invites held for `user` once they are a calendar user with an active Risi chat
  (events that already started are dropped). Returns the number sent.
  """
  def deliver_pending(user) do
    if Calendar.calendar_user?(user) and RisiChat.active_id(user) do
      now = DateTime.utc_now()

      Enum.count(pending(user), fn {id, reason} ->
        case Calendar.load(id) do
          {%Event{state: "active"} = e, parts} ->
            cond do
              DateTime.compare(e.start_at, now) != :gt ->
                unhold(id, user)
                false

              not Enum.any?(parts, &(&1.user_id == user and not &1.removed)) ->
                unhold(id, user)
                false

              true ->
                invite(e, parts, user, reason, nil) == :sent
            end

          _ ->
            unhold(id, user)
            false
        end
      end)
    else
      0
    end
  end

  ## Updates

  @doc """
  The `event_update` (`change`: `status` | `time` | `title` | `participants` | `cancelled`) to
  every participant's Risi chat (calendar users with an active Risi chat). `by`: who changed it
  (nil = Risi).
  """
  def update(%Event{} = e, parts, change, by, _opts \\ []) do
    with {:ok, title} <- Calendar.open_title(e) do
      names = names(Enum.map(parts, & &1.user_id) ++ List.wrap(by))

      risi =
        e
        |> base(parts, title)
        |> Map.merge(%{
          "kind" => "event_update",
          "by" => by,
          "change" => change,
          "state" => e.state,
          "notify" => []
        })
        |> Map.drop(["owner", "tz"])

      for p <- parts, rc = recipient_chat(p.user_id), rc != nil do
        body = update_body(e, parts, change, by, title, names, Clock.user_tz(p.user_id))
        post(rc, body, risi)
      end
    end

    :ok
  rescue
    err ->
      Logger.warning("Risi event_update not sent: #{inspect(err.__struct__)}")
      :ok
  end

  defp update_body(e, parts, change, by, title, names, tz) do
    who = if by, do: names[by] || "Someone", else: "Risi"

    case change do
      "status" ->
        status = Enum.find_value(parts, "updated", &(&1.user_id == by && &1.status))

        case status do
          "accepted" -> "#{who} accepted '#{title}'."
          "declined" -> "#{who} declined '#{title}'."
          _ -> "#{who} hasn't answered '#{title}' yet."
        end

      "time" ->
        "'#{title}' moved to #{ClientTools.span12(e.start_at, e.end_at, e.all_day, tz)}."

      "participants" ->
        "The people in '#{title}' changed."

      "cancelled" ->
        "'#{title}' was cancelled."

      _ ->
        "'#{title}' was updated."
    end
  end

  @doc "A suggestion of another time, to the owner (`calendar_suggestion`)."
  def suggestion(%Event{} = e, _parts, s) do
    with rc when is_binary(rc) <- recipient_chat(e.owner),
         {:ok, title} <- Calendar.open_title(e) do
      who = names([s.user_id])[s.user_id] || "Someone"
      when_ = ClientTools.span12(s.start_at, s.end_at, s.all_day, Clock.user_tz(e.owner))

      post(rc, "#{who} suggests #{when_} for '#{title}'.", %{
        "kind" => "calendar_suggestion",
        "suggestion_id" => s.suggestion_id,
        "event_id" => e.event_id,
        "by" => s.user_id,
        "start" => Clock.ts(s.start_at),
        "end" => Clock.ts(s.end_at),
        "all_day" => s.all_day,
        "buttons" => ["use", "keep"],
        "notify" => [e.owner]
      })
    end

    :ok
  end

  @doc "The owner kept the original time: the suggester is told (an `event_update` `status`)."
  def kept(%Event{} = e, parts, s) do
    with rc when is_binary(rc) <- recipient_chat(s.user_id),
         {:ok, title} <- Calendar.open_title(e) do
      owner = names([e.owner])[e.owner] || "The organiser"

      risi =
        e
        |> base(parts, title)
        |> Map.merge(%{
          "kind" => "event_update",
          "by" => e.owner,
          "change" => "status",
          "state" => e.state,
          "notify" => [s.user_id]
        })
        |> Map.drop(["owner", "tz"])

      post(rc, "#{owner} kept the original time for '#{title}'.", risi)
    end

    :ok
  end

  ## Reminders

  @doc "The `calendar_reminder` of `e` for participant `p` (accepted) in their Risi chat."
  def reminder(%Event{} = e, parts, %Participant{} = p) do
    with rc when is_binary(rc) <- recipient_chat(p.user_id),
         {:ok, title} <- Calendar.open_title(e) do
      tz = Clock.user_tz(p.user_id)
      with = with_names(parts, [p.user_id])

      lead =
        cond do
          e.all_day ->
            today = NaiveDateTime.to_date(Clock.local(DateTime.utc_now(), tz))
            day = NaiveDateTime.to_date(Clock.local(e.start_at, tz))
            if Date.compare(day, today) == :gt, do: "Tomorrow", else: "Today"

          true ->
            "In " <> minutes(p.reminder_min || 0)
        end

      at = if e.all_day, do: "all day", else: clock12(Clock.local(e.start_at, tz))
      people = if with == [], do: "", else: " with " <> join(with)
      body = "#{lead}: #{title} (#{at})#{people}."

      risi = %{
        "kind" => "calendar_reminder",
        "event_id" => e.event_id,
        "title" => title,
        "start" => Clock.ts(e.start_at),
        "end" => Clock.ts(e.end_at),
        "all_day" => e.all_day,
        "reminder_min" => p.reminder_min,
        "buttons" => ["open"],
        "notify" => [p.user_id]
      }

      case Out.post(rc, body, risi) do
        {:ok, _} -> :ok
        {:error, reason} -> {:error, reason}
      end
    else
      _ -> {:error, :no_risi_chat}
    end
  end

  # 14:00 → "2:00 PM".
  defp clock12(t) do
    h = rem(t.hour + 11, 12) + 1
    m = String.pad_leading("#{t.minute}", 2, "0")
    "#{h}:#{m} #{if t.hour < 12, do: "AM", else: "PM"}"
  end

  defp minutes(0), do: "a moment"
  defp minutes(n) when n < 60, do: "#{n} min"

  defp minutes(n) do
    h = div(n, 60)
    m = rem(n, 60)
    hs = if h >= 24 and rem(h, 24) == 0, do: "#{div(h, 24)} d", else: "#{h} h"
    if m == 0, do: hs, else: "#{hs} #{m} min"
  end

  ## Event cards

  @doc """
  `event_card` `mode: "added"` in `conv` (the asker's Risi chat) after [Add]; `dropped` are the
  people who couldn't be invited ("Couldn't invite <name>").
  """
  def added(%Event{} = e, parts, conv, viewer, dropped \\ []) do
    with {:ok, title} <- Calendar.open_title(e) do
      tz = Clock.user_tz(viewer)
      with = with_names(parts, [viewer])
      when_ = ClientTools.span12(e.start_at, e.end_at, e.all_day, tz)

      couldnt =
        case dropped do
          [] -> ""
          ids -> " Couldn't invite #{join(Enum.map(ids, &(names(ids)[&1] || "someone")))}."
        end

      body = "Added to your Risi Calendar: #{title} · #{when_}#{with_part(with)}.#{couldnt}"

      risi =
        e
        |> base(parts, title)
        |> Map.merge(%{
          "kind" => "event_card",
          "mode" => "added",
          "source_conversation_id" => e.source_conversation_id,
          "item_id" => e.source_item_id,
          "buttons" => ["open", "edit", "delete"],
          "notify" => []
        })

      case Out.post(conv, body, risi) do
        {:ok, _} -> :ok
        {:error, reason} -> {:error, reason}
      end
    else
      _ -> {:error, :agent_unavailable}
    end
  end

  @doc """
  `event_card` `mode: "official"` in the event's Official conversation (§29.9): accept /
  decline / suggest, shown by the phone only to participants whose status is `proposed`.
  """
  def official(%Event{} = e, parts, conv) do
    with {:ok, title} <- Calendar.open_title(e) do
      tz = RisiMe.Agent.Secretary.chat_tz(conv)
      when_ = ClientTools.span12(e.start_at, e.end_at, e.all_day, tz)
      with = with_names(parts, [])

      body =
        "Meeting: #{title} · #{when_}#{with_part(with)}. Accept, decline or suggest another " <>
          "time in RisiMe."

      risi =
        e
        |> base(parts, title)
        |> Map.merge(%{
          "kind" => "event_card",
          "mode" => "official",
          "source_conversation_id" => conv,
          "item_id" => e.source_item_id,
          "buttons" => ["accept", "decline", "suggest"],
          "notify" => []
        })

      case Out.post(conv, body, risi) do
        {:ok, _} -> :ok
        {:error, reason} -> {:error, reason}
      end
    else
      _ -> {:error, :agent_unavailable}
    end
  end

  ## Helpers

  defp base(e, parts, title) do
    %{
      "event_id" => e.event_id,
      "version" => e.version,
      "title" => title,
      "start" => Clock.ts(e.start_at),
      "end" => Clock.ts(e.end_at),
      "all_day" => e.all_day,
      "tz" => e.tz,
      "owner" => e.owner,
      "participants" => for(p <- parts, do: %{"user_id" => p.user_id, "status" => p.status})
    }
  end

  defp recipient_chat(user) do
    if Calendar.calendar_user?(user), do: RisiChat.active_id(user)
  end

  defp post(rc, body, risi) do
    case Out.post(rc, body, risi) do
      {:ok, _} ->
        :ok

      {:error, reason} ->
        Logger.warning("Risi calendar card not sent: #{inspect(reason)}")
        :error
    end
  end

  defp with_names(parts, except) do
    ids = for p <- parts, not p.removed, p.user_id not in except, do: p.user_id
    ns = names(ids)
    Enum.map(ids, &(ns[&1] || "someone"))
  end

  defp with_part([]), do: ""
  defp with_part(names), do: " · with " <> join(names)

  defp join([a]), do: a
  defp join(list), do: Enum.join(Enum.drop(list, -1), ", ") <> " and " <> List.last(list)

  @doc false
  def names([]), do: %{}

  def names(ids) do
    Repo.all(
      from u in RisiMe.Accounts.User,
        where: u.id in ^Enum.uniq(ids),
        select: {u.id, u.display_name}
    )
    |> Map.new(fn {id, n} -> {id, n || "Someone"} end)
  end
end
