defmodule RisiMe.Agent.Brief do
  @moduledoc """
  The **07:00 daily brief** (contract v1.36 §35.4, Risi Brain v2 step 4): at 07:xx in the user's
  zone, at most once a day, in the user's own Risi chat, a `digest` (`scope: "brief"`) with:

    * **Today:** the user's Risi Calendar events (accepted or tentative) and Risi's items of the
      day (events Risi added to the phone, reminders, scheduled messages);
    * **Due today** and **Overdue:** the user's own open promises ("My promises");
    * **Waiting on others:** open promises owed to the user, due within 7 days or overdue;
    * **From your chats:** deadlines and meetings of today that Risi's memory found (§35.1 items
      naming the user) and that aren't already listed;
    * **Yesterday's decisions:** decisions Risi's memory found yesterday in the user's Official
      chats.

  It is posted only when one of these has something. **Independent of `RISI_LEDGER` and
  `RISI_NOTES`** (it reads the calendar, Risi's items, the promises as stored, and memory);
  `RISI_BRIEF=off` turns it off. The phone calendar can't be read by the server, so the
  "Checked:" line says so. Labels are in the user's language (`Lang.user_lang/1`); every line
  comes from a stored row, never from a model.

  The ledger's 09:00 personal digest is skipped on a day the brief was sent.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Calendar, Clock, Lang, Memory, Out, RisiItems}
  alias RisiMe.{Repo, RisiChat}

  @hour 7
  @max 6

  @doc "False only with `RISI_BRIEF=off`."
  def on? do
    case Application.get_env(:risime, :risi_brief) do
      nil -> System.get_env("RISI_BRIEF", "on") not in ~w(off false 0)
      v -> v not in [false, "off"]
    end
  end

  @doc "The sweep (every 15 min, the `digest_sweep` job): every user with an active Risi chat."
  def sweep(now \\ Clock.now()) do
    if on?() do
      Repo.all(
        from r in "risi_chats",
          where: r.state == "active",
          select: type(r.owner_id, Ecto.UUID)
      )
      |> Enum.uniq()
      |> Enum.each(&brief(&1, now))
    end

    :ok
  rescue
    e ->
      Logger.warning("Risi brief sweep failed: #{inspect(e.__struct__)}")
      :ok
  end

  @doc "True when `user` got today's brief (their zone)."
  def sent_today?(user, now \\ Clock.now()) do
    today = now |> Clock.local(Clock.user_tz(user)) |> NaiveDateTime.to_date()
    last_on(user) == today
  end

  defp last_on(user),
    do:
      Repo.one(
        from s in "risi_brief_state",
          where: s.user_id == type(^user, Ecto.UUID),
          select: s.last_on
      )

  @doc "Sends `user`'s brief if their clock reads 07:xx and none was sent today (`force: true` skips the hour)."
  def brief(user, now \\ Clock.now(), opts \\ []) do
    tz = Clock.user_tz(user)
    local = Clock.local(now, tz)
    today = NaiveDateTime.to_date(local)
    rc = RisiChat.active_id(user)

    cond do
      rc == nil -> :skipped
      not Keyword.get(opts, :force, false) and local.hour != @hour -> :skipped
      last_on(user) == today -> :skipped
      true -> build_and_post(user, rc, tz, today, now)
    end
  rescue
    e ->
      Logger.warning("Risi brief failed: #{inspect(e.__struct__)}")
      :error
  end

  defp build_and_post(user, rc, tz, today, now) do
    t0 = Clock.to_utc(NaiveDateTime.new!(today, ~T[00:00:00]), tz)
    t1 = Clock.to_utc(NaiveDateTime.new!(Date.add(today, 1), ~T[00:00:00]), tz)
    y0 = Clock.to_utc(NaiveDateTime.new!(Date.add(today, -1), ~T[00:00:00]), tz)

    events = events(user, t0, t1)
    items = items(user, t0, t1)
    promises = promises(user)
    {mine, owed} = Enum.split_with(promises, &(&1.owner_id == user))

    due_today =
      Enum.filter(
        mine,
        &((&1.due && in?(&1.due, t0, t1)) and DateTime.compare(&1.due, now) != :lt)
      )

    overdue = Enum.filter(mine, &(&1.due && DateTime.compare(&1.due, now) == :lt))

    waiting =
      Enum.filter(owed, fn c ->
        c.due != nil and DateTime.compare(c.due, DateTime.add(now, 7 * 86_400, :second)) == :lt
      end)

    convs = Memory.scope(user)

    known =
      MapSet.new(
        Enum.map(events, &norm(&1.title)) ++
          Enum.map(items, &norm(&1["title"])) ++ Enum.map(promises, &norm(&1.text))
      )

    from_chats =
      convs
      |> Memory.items(from: t0, to: t1, kinds: ~w(deadline meeting promise), people_ids: [user])
      |> Enum.reject(&MapSet.member?(known, norm(&1.text)))
      |> Enum.take(@max)

    decisions =
      convs
      |> Memory.items(kinds: ["decision"], limit: 40)
      |> Enum.filter(&in?(&1.inserted_at, y0, t0))
      |> Enum.take(@max)

    if events == [] and items == [] and due_today == [] and overdue == [] and waiting == [] and
         from_chats == [] and decisions == [] do
      :empty
    else
      lang = Lang.user_lang(user)

      sections = [
        {:today, today_lines(events, items, tz)},
        {:due_today, Enum.map(due_today, &promise_line(&1, tz, user))},
        {:overdue, Enum.map(overdue, &promise_line(&1, tz, user))},
        {:waiting, Enum.map(waiting, &promise_line(&1, tz, user))},
        {:from_chats, Enum.map(from_chats, & &1.text)},
        {:decisions, Enum.map(decisions, & &1.text)}
      ]

      body =
        ([label(:header, lang) <> " " <> Elixir.Calendar.strftime(today, "%a %-d %b")] ++
           for {k, lines} <- sections, lines != [] do
             label(k, lang) <> ":\n" <> Enum.map_join(Enum.take(lines, @max), "\n", &("• " <> &1))
           end ++ [label(:checked, lang)])
        |> Enum.join("\n\n")

      risi = %{
        "kind" => "digest",
        "scope" => "brief",
        "date" => Date.to_iso8601(today),
        "language" => lang,
        "events" => Enum.map(events, &event_ref/1),
        "risi_items" => Enum.map(items, &Map.take(&1, ~w(id kind start end all_day))),
        "items" =>
          for c <- due_today ++ overdue ++ waiting do
            %{
              "commitment_id" => c.id,
              "text" => c.text,
              "owner" => c.owner_id,
              "due" => Clock.ts(c.due),
              "state" => c.item_state || c.state,
              "direction" => RisiMe.Agent.Rest.direction(c, user)
            }
          end,
        "decisions" =>
          Enum.map(decisions, &%{"text" => &1.text, "conversation_id" => &1.conversation_id}),
        "notify" => [user]
      }

      case Out.post(rc, body, risi) do
        {:ok, _} ->
          ts = Clock.usec(DateTime.utc_now())

          Repo.insert_all(
            "risi_brief_state",
            [%{user_id: Ecto.UUID.dump!(user), last_on: today, updated_at: ts}],
            on_conflict: [set: [last_on: today, updated_at: ts]],
            conflict_target: [:user_id]
          )

          :sent

        {:error, reason} ->
          Logger.warning("Risi brief not sent: #{inspect(reason)}")
          :error
      end
    end
  end

  defp in?(dt, a, b), do: DateTime.compare(dt, a) != :lt and DateTime.compare(dt, b) == :lt

  defp norm(nil), do: ""
  defp norm(s), do: s |> String.downcase() |> String.trim()

  defp events(user, t0, t1) do
    with true <- Calendar.on?() and Calendar.calendar_user?(user),
         {:ok, rows} <- Calendar.own_events(user, t0, t1) do
      Enum.map(rows, fn r ->
        %{event: r.event, title: r.title, status: r.status}
      end)
    else
      _ -> []
    end
  end

  defp items(user, t0, t1) do
    case RisiItems.list(user,
           from: t0,
           to: t1,
           kinds: ~w(phone_event_added reminder scheduled_message),
           undated: false
         ) do
      {:ok, items} -> items
      _ -> []
    end
  rescue
    _ -> []
  end

  defp promises(user) do
    case RisiMe.Agent.Rest.open_commitments(user) do
      {:ok, cs} -> cs
      _ -> []
    end
  end

  defp today_lines(events, items, tz) do
    ev =
      for e <- events do
        tentative = if e.status == "proposed", do: ", tentative", else: ""

        {e.event.start_at,
         "#{span(e.event.start_at, e.event.end_at, e.event.all_day, tz)} · #{e.title}#{tentative}"}
      end

    it =
      for i <- items do
        {:ok, s, _} = DateTime.from_iso8601(i["start"])

        title =
          case i["kind"] do
            "scheduled_message" -> "Scheduled message"
            "reminder" -> "Reminder: #{i["title"]}"
            _ -> i["title"] || "Event"
          end

        stop =
          case i["end"] && DateTime.from_iso8601(i["end"]) do
            {:ok, t, _} -> t
            _ -> s
          end

        {s, "#{span(s, stop, i["all_day"] == true, tz)} · #{title}"}
      end

    (ev ++ it) |> Enum.sort_by(&elem(&1, 0), DateTime) |> Enum.map(&elem(&1, 1))
  end

  defp span(_s, _t, true, _tz), do: "All day"

  defp span(s, t, false, tz) do
    a = s |> Clock.local(tz) |> Elixir.Calendar.strftime("%H:%M")
    b = t |> Clock.local(tz) |> Elixir.Calendar.strftime("%H:%M")
    if a == b, do: a, else: "#{a}–#{b}"
  end

  defp promise_line(c, tz, user) do
    who = if c.owner_id == user, do: "", else: "#{owner_name(c)}: "
    due = if c.due, do: " (#{Clock.human(c.due, if(c.all_day, do: "date"), tz)})", else: ""
    who <> c.text <> due
  end

  defp owner_name(c) do
    RisiMe.Agent.Secretary.members(c.conversation_id)
    |> Enum.find_value("Someone", &(&1.user_id == c.owner_id && &1.name))
  end

  defp event_ref(e),
    do: %{
      "event_id" => e.event.event_id,
      "title" => e.title,
      "start" => Clock.ts(e.event.start_at),
      "end" => Clock.ts(e.event.end_at),
      "all_day" => e.event.all_day,
      "my_status" => e.status
    }

  @labels %{
    header: %{
      "en" => "Good morning. Your day,",
      "si" => "සුබ උදෑසනක්. ඔබේ දවස,",
      "ta" => "காலை வணக்கம். உங்கள் நாள்,"
    },
    today: %{"en" => "Today", "si" => "අද", "ta" => "இன்று"},
    due_today: %{"en" => "Due today", "si" => "අද කළ යුතු", "ta" => "இன்று செய்ய வேண்டியவை"},
    overdue: %{"en" => "Overdue", "si" => "කල් ඉකුත් වූ", "ta" => "தாமதமானவை"},
    waiting: %{"en" => "Waiting on others", "si" => "අනුන්ගෙන් බලාපොරොත්තු වන", "ta" => "மற்றவர்களிடமிருந்து காத்திருப்பவை"},
    from_chats: %{"en" => "From your chats", "si" => "ඔබේ චැට් වලින්", "ta" => "உங்கள் அரட்டைகளிலிருந்து"},
    decisions: %{"en" => "Yesterday's decisions", "si" => "ඊයේ ගත් තීරණ", "ta" => "நேற்றைய முடிவுகள்"},
    checked: %{
      "en" =>
        "Checked: Risi Calendar · Risi's items · your promises · my memory of your Official chats (not your phone calendar).",
      "si" =>
        "Checked: Risi Calendar · Risi's items · your promises · my memory of your Official chats (not your phone calendar).",
      "ta" =>
        "Checked: Risi Calendar · Risi's items · your promises · my memory of your Official chats (not your phone calendar)."
    }
  }

  defp label(k, lang), do: @labels[k][lang] || @labels[k]["en"]
end
