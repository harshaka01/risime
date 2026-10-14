defmodule RisiMe.Agent.CalendarV2 do
  @moduledoc """
  Calendar v2 (proposal 2026-10-14-calendar-v2, §44; Harsha's P1 14 Oct 10:55–11:47 SLT), behind
  `RISI_CALENDAR_V2` (`off | canary | on`, default `canary` = `RISI_DEV_USERS`).

  * **What counts as a read (§44.2):** a `phone_provider` source with calendars is a read even when
    the phone said `sync_off` (Android's account auto-sync: it says nothing about whether the read
    happened). `normalize/1` turns such a source into `read_ok: true` before anything else sees
    the check, so the answer gets `local_events` (the phone lists its events with their titles)
    and the footer names the phone.
  * **The footer (§44.3):** "From: your phone calendars · Risi", built only from the sources THIS
    request read (`footer_line/1`, `footer/2`).
  * **First read (§44.4):** "✓ I can see your calendars now." once per grant.
  * **One row per thing (§44.6):** `merge/2` groups items by their ledger item, else by title and
    day, with tags; `conflict_line/3` flags a blocked day that holds meetings.
  * **Preferences (§44.10, `risi_calendar_prefs`):** the titles consent (`times` until Yes), the
    all-day reminder time (18:00) and the learned reminder lead (L1: the median of the user's last
    5 chosen leads once there are 3; default 15 min).

  Owning agent: the Risi Brain's calendar tools (Secretary); this module holds the deterministic
  rules (decision 085: exactness — what was read, times, merging), not decisions.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.Clock
  alias RisiMe.Repo

  @flag "RISI_CALENDAR_V2"
  @default_lead_min 15
  @min_samples 3
  @keep_samples 5

  defmodule Prefs do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:user_id, :binary_id, autogenerate: false}
    schema "risi_calendar_prefs" do
      field :titles, :string, default: "times"
      field :titles_asked_at, :utc_datetime_usec
      field :first_read_at, :utc_datetime_usec
      field :allday_remind_at, :time, default: ~T[18:00:00]
      field :lead_samples, {:array, :integer}, default: []
      field :updated_at, :utc_datetime_usec
    end
  end

  ## The switch

  @doc "`:off | :canary | :on`."
  def mode, do: RisiMe.Agents.Flags.feature(@flag)

  @doc "True while the switch applies to `user` (§44.1; also the `/me` field `calendar_v2`)."
  def on_for?(user) when is_binary(user), do: RisiMe.Agents.Flags.feature_for(@flag, user)
  def on_for?(_), do: false

  ## §44.2 what counts as a read

  @doc """
  A check (or a model-facing result) with every `phone_provider` source that read calendars made a
  read: `sync_off` with calendars → `read_ok: true, reason: nil`. Walks `sources` and a nested
  `phone` map (`risi_calendar_check`'s model view). Anything else is unchanged.
  """
  def normalize(%{} = m) do
    m
    |> Map.update("sources", nil, &normalize_sources/1)
    |> then(fn x -> if is_map(x["phone"]), do: Map.update!(x, "phone", &normalize/1), else: x end)
    |> then(fn x -> if Map.has_key?(m, "sources"), do: x, else: Map.delete(x, "sources") end)
  end

  def normalize(other), do: other

  defp normalize_sources(ss) when is_list(ss), do: Enum.map(ss, &normalize_source/1)
  defp normalize_sources(other), do: other

  defp normalize_source(
         %{"source" => "phone_provider", "reason" => "sync_off", "calendars" => cals} = s
       ) do
    if (is_list(cals) and cals != []) or (is_integer(cals) and cals > 0),
      do: %{s | "read_ok" => true, "reason" => nil},
      else: s
  end

  defp normalize_source(s), do: s

  ## §44.9 the guide

  @doc "True when the last check's phone source read no calendars at all (nothing on the phone)."
  def no_calendars?(%{status: "ok", result: %{"sources" => ss}}) when is_list(ss) do
    phone = Enum.filter(ss, &(&1["source"] == "phone_provider"))

    phone != [] and
      Enum.all?(phone, &(&1["read_ok"] != true and &1["reason"] == "no_calendars")) and
      not Enum.any?(ss, &(&1["source"] == "google_api" and &1["read_ok"] == true))
  end

  def no_calendars?(_), do: false

  @doc """
  The guide's card can be sent: the switch, §43's fix-it for the calendar, and an asking device
  that advertises `risi_calendar_v2` (it runs the steps itself).
  """
  def guide?(ctx) do
    on_for?(ctx.asker) and RisiMe.Agent.PermissionFixit.applies?(ctx, "calendar") and
      v2_device?(ctx.asker, Map.get(ctx, :device_id))
  rescue
    _ -> false
  end

  @doc "True when `device` of `user` advertises `risi_calendar_v2`."
  def v2_device?(user, device) when is_binary(device) do
    case Ecto.UUID.cast(device) do
      {:ok, d} ->
        Repo.exists?(
          from x in RisiMe.Devices.Device,
            where:
              x.user_id == ^user and x.device_id == ^d and "risi_calendar_v2" in x.capabilities
        )

      :error ->
        false
    end
  end

  def v2_device?(_user, _device), do: false

  ## §44.3 the footer

  @order ~w(phone_provider google_api risi_calendar risi_items)

  @doc "The sources a check read (in §44.3 order), from its `sources`."
  def read_sources(%{result: %{"sources" => ss}}) when is_list(ss), do: read_sources(ss)

  def read_sources(ss) when is_list(ss) do
    ss
    |> Enum.filter(&read?/1)
    |> Enum.map(& &1["source"])
    |> Enum.uniq()
    |> Enum.sort_by(&Enum.find_index(@order, fn o -> o == &1 end))
  end

  def read_sources(_), do: []

  defp read?(%{"source" => s, "read_ok" => true}) when s in ~w(risi_calendar risi_items),
    do: true

  defp read?(%{"read_ok" => true, "calendars" => [_ | _]}), do: true
  defp read?(_), do: false

  @doc "\"From: your phone calendars · Risi\" (nil when nothing was read)."
  def footer_line(read) do
    parts =
      [
        "phone_provider" in read && "your phone calendars",
        "google_api" in read && "Google Calendar",
        Enum.any?(read, &(&1 in ~w(risi_calendar risi_items))) && "Risi"
      ]
      |> Enum.filter(& &1)

    if parts != [], do: "From: " <> Enum.join(parts, " · ")
  end

  @doc "`answer.footer` (§44.3) for the turn's last check."
  def footer(check, first_read?) do
    read = read_sources(check)

    phone =
      case check do
        %{result: %{"sources" => ss}} when is_list(ss) ->
          Enum.find_value(ss, fn
            %{"source" => "phone_provider", "read_ok" => true, "calendars" => cals}
            when is_list(cals) ->
              length(cals)

            _ ->
              nil
          end)

        _ ->
          nil
      end

    %{"v" => 1, "read" => read, "phone_calendars" => phone, "first_read" => first_read?}
  end

  @doc """
  Connected sources that could not be read, as one plain sentence each (never "Not checked"):
  "I couldn't reach Google Calendar (needs reconnecting)." An optional source never connected,
  and the phone's sync state, are not news.
  """
  def unread_lines(%{result: %{"sources" => ss}}) when is_list(ss) do
    for s <- ss,
        s["read_ok"] != true,
        s["reason"] not in [nil, "not_connected", "paused"],
        s["source"] in ~w(google_api phone_provider) do
      what = if s["source"] == "google_api", do: "Google Calendar", else: "your phone calendar"

      why =
        if s["source"] == "google_api",
          do: RisiMe.Agent.CalendarHonesty.google_reason(s["reason"]),
          else: RisiMe.Agent.CalendarHonesty.reason_text(s["reason"])

      "I couldn't reach #{what} (#{why})."
    end
  end

  def unread_lines(_), do: []

  @first_read "✓ I can see your calendars now."

  @doc "§44.4 the first-read line."
  def first_read_text, do: @first_read

  ## Preferences

  @doc "The user's prefs (defaults when no row)."
  def prefs(user) do
    Repo.get(Prefs, user) || %Prefs{user_id: user}
  rescue
    _ -> %Prefs{user_id: user}
  end

  defp put(user, changes) do
    now = DateTime.utc_now()
    row = Map.merge(%{user_id: user, updated_at: now}, changes)

    Repo.insert_all(Prefs, [row],
      on_conflict: {:replace, Map.keys(changes) ++ [:updated_at]},
      conflict_target: :user_id
    )

    :ok
  rescue
    e ->
      Logger.warning("Risi calendar prefs write failed: #{inspect(e.__struct__)}")
      :error
  end

  @doc """
  §44.4: true (and recorded) the first time a phone read succeeds for `user` since the last grant.
  Atomic: of two concurrent turns only one gets true.
  """
  def claim_first_read(user) do
    now = DateTime.utc_now()

    Repo.insert_all(Prefs, [%{user_id: user, first_read_at: now, updated_at: now}],
      on_conflict:
        from(p in Prefs,
          where: is_nil(p.first_read_at),
          update: [set: [first_read_at: ^now, updated_at: ^now]]
        ),
      conflict_target: :user_id
    )
    |> elem(0) == 1
  rescue
    _ -> false
  end

  @doc "§44.4: the next successful read says the first-read line again (a new grant)."
  def reset_first_read(user) do
    Repo.update_all(from(p in Prefs, where: p.user_id == ^user),
      set: [first_read_at: nil, updated_at: DateTime.utc_now()]
    )

    :ok
  rescue
    _ -> :ok
  end

  @doc "§44.5 `\"times\"` (default) or `\"titles\"`."
  def titles(user), do: prefs(user).titles || "times"

  @doc "§44.5 the user's answer to the titles question."
  def put_titles(user, v) when v in ~w(times titles),
    do: put(user, %{titles: v, titles_asked_at: DateTime.utc_now()})

  def put_titles(_user, _v), do: :error

  @doc "§44.5 true once the titles question was asked (or answered)."
  def titles_asked?(user), do: prefs(user).titles_asked_at != nil

  @doc "Records that the titles question was asked (once)."
  def mark_titles_asked(user), do: put(user, %{titles_asked_at: DateTime.utc_now()})

  @titles_question "Let Risi read meeting titles to help plan your day?"
  @prep ~r/\b(?:prep(?:are)?|preparation|get\s+ready|brief\s+me|what\s+(?:are|is)\s+(?:my|the)\s+meetings?\s+about)\b/iu

  @doc "§44.5 the question's sentence."
  def titles_question, do: @titles_question

  @doc "§44.5 the two chips."
  def titles_chips,
    do: [
      %{"label" => "Yes", "action" => "calendar_titles", "target" => "titles"},
      %{"label" => "Only times", "action" => "calendar_titles", "target" => "times"}
    ]

  @doc """
  §44.5: ask the titles question now? Only when the request needs titles (meeting prep), the
  user's choice is still `times` and it was never asked. Records the ask (once).
  """
  def ask_titles?(user, question) do
    with true <- is_binary(question) and Regex.match?(@prep, question),
         %Prefs{titles: t, titles_asked_at: nil} when t in [nil, "times"] <- prefs(user) do
      mark_titles_asked(user) == :ok
    else
      _ -> false
    end
  end

  @doc "§44.5 the answer (`risi_action` `calendar_titles`, the asker's own leaf). Always `:ok`."
  def act_titles(conv, user, %{"target" => v}) when v in ~w(titles times) do
    if RisiMe.RisiChat.active_id(user) == conv do
      put_titles(user, v)

      text =
        if v == "titles",
          do: "OK, I'll use your meeting titles to help plan your day.",
          else: "OK, I'll only use the times of your meetings."

      RisiMe.Agent.Out.post(conv, text, %{
        "kind" => "answer",
        "request_id" => Ecto.UUID.generate(),
        "answer" => text,
        "refs" => [],
        "confidence" => 1.0,
        "sources" => [],
        "next_steps" => [],
        "next_actions" => [],
        "call_ref" => nil,
        "made_by" => RisiMe.Agent.MadeBy.rule(),
        "notify" => [user]
      })
    end

    :ok
  rescue
    _ -> :ok
  end

  def act_titles(_conv, _user, _env), do: :ok

  @doc "§44.8 the all-day reminder's local time (default 18:00)."
  def allday_remind_at(user), do: prefs(user).allday_remind_at || ~T[18:00:00]

  @doc "§44.8 the user's reminder lead in minutes: learned (median of ≥ 3) or 15."
  def lead_min(user) do
    case prefs(user).lead_samples || [] do
      s when length(s) >= @min_samples -> median(s)
      _ -> @default_lead_min
    end
  end

  @doc "§44.8 L1: the user chose a reminder `min` minutes before an event (0–1440)."
  def learn_lead(user, min) when is_integer(min) and min in 0..1440 do
    samples = [min | prefs(user).lead_samples || []] |> Enum.take(@keep_samples)
    put(user, %{lead_samples: samples})
  end

  def learn_lead(_user, _min), do: :error

  defp median(s) do
    sorted = Enum.sort(s)
    Enum.at(sorted, div(length(sorted), 2))
  end

  ## §44.6 one row per thing

  @tag_order ~w(risi_calendar_event phone_event_added promise follow_up reminder scheduled_message)

  @doc """
  Merges agenda items (`%{"kind", "title", "start", "end", "all_day", "group"?}`) into rows: one per
  `group`, else per normalized title and local day. Each row keeps the most specific time (all day
  beats a due; a timed event beats a due) and gets `"tags"` (the kinds, in §44.6 order) and
  `"reminder_at"` (a reminder's start) when one was merged in.
  """
  def merge(items, tz) do
    {reminders, things} =
      Enum.split_with(items, &(&1["kind"] == "reminder" and !is_binary(&1["group"])))

    groups = Enum.group_by(things, &key(&1, tz))

    # A reminder fires before its thing (often the day before): it joins the group with the same
    # title whose thing starts after it, within 2 days; else it is its own row.
    groups =
      Enum.reduce(reminders, groups, fn r, acc ->
        case Enum.find(Map.keys(acc), &reminder_of?(r, &1, acc[&1])) do
          nil -> Map.put(acc, {:reminder, r["id"] || make_ref()}, [r])
          k -> Map.update!(acc, k, &(&1 ++ [r]))
        end
      end)

    Enum.map(groups, fn {_k, group} -> row(group) end)
  end

  defp key(%{"group" => g}, _tz) when is_binary(g), do: {:group, g}

  defp key(i, tz) do
    day =
      with s when is_binary(s) <- i["start"],
           {:ok, t, _} <- DateTime.from_iso8601(s) do
        t |> Clock.local(tz) |> NaiveDateTime.to_date()
      else
        _ -> nil
      end

    {:title, norm(i["title"]), day}
  end

  defp reminder_of?(r, _key, group) do
    t = norm(r["title"])

    t != "" and
      Enum.any?(group, fn i ->
        norm(i["title"]) == t and within?(r["start"], i["start"], 2 * 86_400)
      end)
  end

  defp within?(a, b, max_s) when is_binary(a) and is_binary(b) do
    with {:ok, x, _} <- DateTime.from_iso8601(a),
         {:ok, y, _} <- DateTime.from_iso8601(b) do
      d = DateTime.diff(y, x)
      d >= -3600 and d <= max_s
    else
      _ -> false
    end
  end

  defp within?(_, _, _), do: false

  @doc "A title for comparing: lower case, letters and digits only."
  def norm(t) when is_binary(t) do
    t
    |> String.downcase()
    |> String.replace(~r/^(?:reminder|your promise|following up)\s*:\s*/u, "")
    |> String.replace(~r/[^\p{L}\p{N}]+/u, " ")
    |> String.trim()
  end

  def norm(_), do: ""

  defp row([one]), do: Map.put(one, "tags", [one["kind"]])

  defp row(group) do
    reminders = Enum.filter(group, &(&1["kind"] == "reminder"))
    things = group -- reminders
    things = if things == [], do: reminders, else: things

    best =
      Enum.min_by(things, fn i ->
        cond do
          i["kind"] in ~w(risi_calendar_event phone_event_added) -> 0
          i["all_day"] == true -> 1
          true -> 2
        end
      end)

    tags =
      group
      |> Enum.map(& &1["kind"])
      |> Enum.uniq()
      |> Enum.sort_by(&(Enum.find_index(@tag_order, fn k -> k == &1 end) || 99))

    best
    |> Map.put("tags", tags)
    |> Map.put("reminder_at", reminders |> Enum.map(& &1["start"]) |> Enum.min(fn -> nil end))
  end

  @doc "The tags in words: \"promise · reminder Sat 18:00\" (the row's own kind first)."
  def tags_text(%{"tags" => tags} = row, tz) when length(tags) > 1 do
    Enum.map_join(tags, " · ", fn
      "reminder" -> "reminder" <> remind_when(row["reminder_at"], tz)
      k -> tag_word(k)
    end)
  end

  def tags_text(_row, _tz), do: nil

  defp remind_when(s, tz) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, t, _} -> " " <> Elixir.Calendar.strftime(Clock.local(t, tz), "%a %H:%M")
      _ -> ""
    end
  end

  defp remind_when(_, _), do: ""

  defp tag_word("risi_calendar_event"), do: "Risi Calendar"
  defp tag_word("phone_event_added"), do: "phone calendar"
  defp tag_word("promise"), do: "promise"
  defp tag_word("follow_up"), do: "follow-up"
  defp tag_word("scheduled_message"), do: "message"
  defp tag_word(k), do: to_string(k)

  @doc """
  §44.6 conflict: for each all-day block row, the timed busy things on the same local day
  (`others`: `%{"start", "all_day"}` maps). "18 Oct already has 2 meetings. Move them, or keep
  both?" — nil when no conflict.
  """
  def conflict_line(rows, others, tz) do
    Enum.find_value(rows, fn r ->
      with true <- r["all_day"] == true and blocking?(r),
           %Date{} = d <- local_day(r["start"], tz),
           n when n > 0 <-
             Enum.count(others, &(&1["all_day"] != true and local_day(&1["start"], tz) == d)) do
        what = if n == 1, do: "1 meeting", else: "#{n} meetings"
        them = if n == 1, do: "it", else: "them"

        "#{Elixir.Calendar.strftime(d, "%-d %b")} already has #{what}. Move #{them}, or keep both?"
      else
        _ -> nil
      end
    end)
  end

  defp blocking?(r), do: RisiMe.Agent.DayPart.block?(r["title"] || "")

  defp local_day(s, tz) when is_binary(s) do
    case DateTime.from_iso8601(s) do
      {:ok, t, _} -> t |> Clock.local(tz) |> NaiveDateTime.to_date()
      _ -> nil
    end
  end

  defp local_day(_, _), do: nil
end
