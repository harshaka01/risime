defmodule RisiMe.Agent.ClientTools do
  @moduledoc """
  The client tools as registry entries (contract v1.25 §25.3/§25.5, v1.26 §26.6): what the model
  may pass (`args`, the server's schema: times may be local phrases, `RisiMe.Agent.TimePhrase`),
  how a step runs (`run`), and how a confirmed write runs on the phone (`exec`, through
  `RisiMe.Agent.ToolCalls`).

  * `calendar_check` (read, personal): free/busy blocks; each block becomes a `c<n>` ref
    (a `calendar` Source). Never titles.
  * `calendar_add` (write, personal): a confirm card, then the phone adds the event.
  * `set_alarm` (v1.26, write, skill `alarm`): the Clock app on the asker's phone; undo is
    manual.
  * `schedule_message` (v1.26, write, skill `scheduled_messages`, **always** confirmed): the
    phone stores and sends it. The text passes the server once, in the tool call's args (a
    2-minute row deleted on the result); the pending write's sealed copy is wiped when it is
    done, and the activity entry names only the recipient and the time.
  * `cancel_scheduled` (v1.26, by request: always confirmed; or an undo with
    `undo_entry_id`, `RisiMe.Agent.Skills`).
  * `calendar_remove` (v1.26) is undo-only: never offered to the model.

  * `export_pdf` (v1.34 §33.15, personal, no skill): the model chooses only **references**; the
    phone renders the PDF from its own copy. Without `send_to` the turn ends with a server-built
    answer and a `pdf` chip (no write, no tool call). With `send_to` it is a write: a `confirm`
    card built from server-held metadata (never model text), then the tool call to the
    confirming device (`pdf_export`), strict result checks, and the honest sentences below
    ("Sent" only for the phone's `sent`).

  The v1.26 tools go only to `risi_skills` devices (`RisiMe.Agent.Skills.allows?/2`).

  **Device routing** (§25.4, §26.8): a confirmed write goes to the confirming device when it
  advertises the tool's capability (`risi_tools`; `risi_skills` for the v1.26 tools), else to
  the turn's device; an allowed write to the turn's device.
  """
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Agent.{Clock, TimePhrase, ToolCalls, Writes}
  alias RisiMe.Devices

  @max_range_s 14 * 86_400

  defp obj(props, required),
    do: %{
      "type" => "object",
      "properties" => props,
      "required" => required,
      "additionalProperties" => false
    }

  @str %{"type" => "string", "maxLength" => 100}

  ## calendar_check

  def calendar_check do
    %{
      name: "calendar_check",
      description:
        "check when the asker is free or busy on their phone's calendar, which includes Google " <>
          "accounts synced to the phone (busy times only, no event titles; from, to: an ISO " <>
          "time or a local phrase like \"Tuesday 00:00\"; at most 14 days)",
      where: :client,
      personal: true,
      write: false,
      finds: true,
      skill: "calendar",
      args: obj(%{"from" => @str, "to" => @str}, ["from", "to"]),
      # v1.29 §29.7: a calendar user's check is `risi_calendar_check` (it runs this one too).
      authorize: fn ctx ->
        if RisiMe.Agent.Calendar.calendar_user?(ctx.asker), do: {:error, :denied}, else: :ok
      end,
      run: &run_check/2
    }
  end

  defp run_check(%{"from" => f, "to" => t}, ctx) do
    with {:ok, from, _} <- time(f, ctx),
         {:ok, to, kind} <- time(t, ctx),
         to = if(kind == :date, do: DateTime.add(to, 86_400 - 1, :second), else: to),
         true <- DateTime.compare(to, from) == :gt || {:error, "failed", "end_before_start"} do
      to = Enum.min([to, DateTime.add(from, @max_range_s, :second)], DateTime)
      args = %{"from" => Clock.ts(from), "to" => Clock.ts(to)}
      # v1.31 §31.4: the routed Google read (`risi_calendar_check`) asks for named sources.
      call_args =
        if s = ctx[:check_sources], do: Map.put(args, "sources", s), else: args

      case call(ctx, "calendar_check", call_args) do
        {:ok, "ok", %{"blocks" => blocks} = r} ->
          refs =
            blocks
            |> Enum.with_index(1)
            |> Map.new(fn {b, i} -> {"c#{i}", Map.put(b, "type", "calendar")} end)

          shown = for {b, i} <- Enum.with_index(blocks, 1), do: Map.put(b, "ref", "c#{i}")
          result = %{"from" => args["from"], "to" => args["to"], "blocks" => shown}

          # P0 2026-10-09: the model sees per source only {source, read_ok, reason, calendars:
          # <count>}; the calendar names stay in `meta` for the server-built "I checked" line
          # (`RisiMe.Agent.CalendarHonesty`). An old phone says nothing: `sources: null`.
          result =
            Map.merge(result, %{
              "sources" => model_sources(r["sources"]),
              "connected_sources" => r["connected_sources"] || []
            })

          check = Map.merge(result, Map.take(r, ["sources", "connected_sources"]))
          {:ok, result, %{refs: refs, calendar_check: check}}

        {:ok, "error", %{"code" => code}} ->
          {:error, "failed", code}

        other ->
          status(other)
      end
    end
  end

  defp run_check(_args, _ctx), do: {:error, "failed", "bad_args"}

  defp model_sources(sources) when is_list(sources) do
    # P0 2026-10-10: a `google_api` that was never connected is not news to the model.
    for s <- sources, not never_connected_google?(s) do
      %{
        "source" => s["source"],
        "read_ok" => s["read_ok"],
        "reason" => s["reason"],
        "calendars" => length(s["calendars"] || [])
      }
    end
  end

  defp model_sources(_), do: nil

  @doc false
  def never_connected_google?(%{"source" => "google_api", "reason" => "not_connected"} = s),
    do: not Map.has_key?(s, "count")

  def never_connected_google?(_), do: false

  ## calendar_add

  def calendar_add do
    %{
      name: "calendar_add",
      description:
        "propose adding an event to the asker's phone calendar, Google accounts synced to the " <>
          "phone included (the asker confirms first; " <>
          "start/end: ISO or a local phrase like \"Friday 10:00\")",
      where: :client,
      personal: true,
      write: true,
      finds: false,
      skill: "calendar",
      args:
        obj(
          %{
            "title" => %{"type" => "string", "minLength" => 1, "maxLength" => 200},
            "start" => @str,
            "end" => %{"type" => ["string", "null"], "maxLength" => 100},
            "all_day" => %{"type" => ["boolean", "null"]}
          },
          ["title", "start"]
        ),
      run: &run_add/2,
      exec: &exec/3
    }
  end

  defp run_add(%{"title" => title, "start" => s} = a, ctx) do
    title = String.trim(title)

    with true <- (title != "" and String.length(title) <= 200) || {:error, "failed", "bad_title"},
         {:ok, start, kind} <- time(s, ctx),
         all_day = a["all_day"] == true or (kind == :date and a["end"] in [nil, ""]),
         {:ok, stop} <- stop_of(a["end"], start, all_day, ctx),
         true <- DateTime.compare(stop, start) == :gt || {:error, "failed", "end_before_start"},
         :ok <- future(start, ctx) do
      wire = %{
        "title" => title,
        "start" => Clock.ts(start),
        "end" => Clock.ts(stop),
        "all_day" => all_day
      }

      summary =
        "Add \"#{title}\" to your calendar on #{span(start, stop, all_day, ctx.tz)}"

      {:propose,
       %{
         args: %{"wire" => wire, "title" => title},
         card_args: wire,
         summary: summary,
         when: %{"start" => wire["start"], "end" => wire["end"], "all_day" => all_day},
         text: title,
         personal: true,
         skill_id: "calendar",
         # P0 2026-10-09: the calendar the phone last reported (null: it asks on first use).
         calendar: RisiMe.Agent.CalendarChoice.get(ctx.asker)
       }}
    end
  end

  defp run_add(_args, _ctx), do: {:error, "failed", "bad_args"}

  defp stop_of(nil, start, true, _ctx), do: {:ok, DateTime.add(start, 86_400, :second)}
  defp stop_of(nil, start, false, _ctx), do: {:ok, DateTime.add(start, 3600, :second)}
  defp stop_of("", start, all_day, ctx), do: stop_of(nil, start, all_day, ctx)

  defp stop_of(e, _start, all_day, ctx) do
    case time(e, ctx) do
      {:ok, t, :date} when all_day -> {:ok, DateTime.add(t, 86_400, :second)}
      {:ok, t, _} -> {:ok, t}
      error -> error
    end
  end

  @doc "`Fri 16 Oct, 10:00–11:00` (or `Fri 16 Oct` for an all-day event) in `tz`."
  def span(start, stop, all_day, tz) do
    if all_day do
      Calendar.strftime(Clock.local(start, tz), "%a %-d %b")
    else
      a = Clock.local(start, tz)
      b = Clock.local(stop, tz)

      if NaiveDateTime.to_date(a) == NaiveDateTime.to_date(b),
        do: Calendar.strftime(a, "%a %-d %b, %H:%M") <> "–" <> Calendar.strftime(b, "%H:%M"),
        else:
          Calendar.strftime(a, "%a %-d %b, %H:%M") <>
            " – " <> Calendar.strftime(b, "%a %-d %b, %H:%M")
    end
  end

  ## set_alarm (v1.26 §26.6)

  def set_alarm do
    %{
      name: "set_alarm",
      description:
        "set an alarm in the Clock app of the asker's phone (time: \"06:00\" or \"6am\"; " <>
          "label: a short name or \"\"; days: ISO weekdays 1=Monday..7 for a repeating " <>
          "alarm, or null for the next occurrence)",
      where: :client,
      personal: true,
      write: true,
      finds: false,
      skill: "alarm",
      args:
        obj(
          %{
            "time" => %{"type" => "string", "maxLength" => 20},
            "label" => %{"type" => ["string", "null"], "maxLength" => 60},
            "days" => %{
              "type" => ["array", "null"],
              "maxItems" => 7,
              "items" => %{"type" => "integer", "minimum" => 1, "maximum" => 7}
            }
          },
          ["time"]
        ),
      run: &run_alarm/2,
      exec: &exec/3
    }
  end

  @day_names ~w(Mon Tue Wed Thu Fri Sat Sun)

  defp run_alarm(%{"time" => t} = a, ctx) do
    label = String.trim(a["label"] || "")
    days = a["days"]

    with true <- String.length(label) <= 60 || {:error, "failed", "bad_label"},
         true <-
           (days == nil or (days != [] and Enum.uniq(days) == days)) ||
             {:error, "failed", "bad_days"},
         {:ok, next, :datetime} <- alarm_time(t, ctx, days) do
      local = Clock.local(next, ctx.tz)
      hhmm = Calendar.strftime(local, "%H:%M")
      days = days && Enum.sort(days)
      wire = %{"time" => hhmm, "label" => label, "days" => days}
      named = if label == "", do: "", else: ", '#{label}'"

      summary =
        case days do
          nil ->
            day =
              if NaiveDateTime.to_date(local) ==
                   NaiveDateTime.to_date(Clock.local(ctx.now, ctx.tz)),
                 do: "today",
                 else: "tomorrow"

            "Set an alarm on this phone for #{hhmm} #{day} (#{Calendar.strftime(local, "%a %-d %b")})#{named}"

          ds ->
            "Set a repeating alarm on this phone for #{hhmm} on " <>
              Enum.map_join(ds, ", ", &Enum.at(@day_names, &1 - 1)) <> named
        end

      {:propose,
       %{
         args: %{"wire" => wire},
         card_args: wire,
         summary: summary,
         when: %{"start" => Clock.ts(next), "end" => nil, "all_day" => false},
         text: label,
         personal: true,
         skill_id: "alarm"
       }}
    else
      {:ok, _, :date} -> {:error, "failed", "ambiguous_time: ask the person for the time"}
      error -> error
    end
  end

  defp run_alarm(_args, _ctx), do: {:error, "failed", "bad_args"}

  # A wall-clock time ("06:00", "6am", "tomorrow 6am"): its next occurrence. A one-off alarm
  # rings at the next occurrence of its time on the phone (§26.6), so it must be within 24 h.
  defp alarm_time(t, ctx, days) do
    case time(t, ctx) do
      # A repeating alarm only takes the time of day.
      {:ok, at, :datetime} when days != nil ->
        {:ok, at, :datetime}

      {:ok, at, :datetime} ->
        cond do
          DateTime.compare(at, ctx.now) != :gt ->
            {:error, "failed", "in_the_past"}

          DateTime.diff(at, ctx.now) > 86_400 ->
            {:error, "failed",
             "too_far_for_an_alarm: a one-off alarm rings within 24 hours; offer a reminder " <>
               "or a repeating alarm"}

          true ->
            {:ok, at, :datetime}
        end

      other ->
        other
    end
  end

  ## schedule_message (v1.26 §26.6)

  def schedule_message do
    %{
      name: "schedule_message",
      description:
        "schedule a message the asker wrote, sent later by their phone (always confirmed): to " <>
          "(a friend's name for a 1:1 chat, or \"this chat\" for this Official chat when the " <>
          "asker says so), text (exactly the asker's words), at (ISO or a phrase like " <>
          "\"tomorrow 6am\"), repeat: \"daily\" or null",
      where: :client,
      personal: true,
      write: true,
      finds: false,
      skill: "scheduled_messages",
      args:
        obj(
          %{
            "to" => %{"type" => "string", "minLength" => 1, "maxLength" => 64},
            "text" => %{"type" => "string", "minLength" => 1, "maxLength" => 4096},
            "at" => @str,
            "repeat" => %{"type" => ["string", "null"], "enum" => ["daily", nil]}
          },
          ["to", "text", "at"]
        ),
      run: &run_schedule/2,
      exec: &exec/3
    }
  end

  defp run_schedule(%{"to" => to, "text" => text, "at" => at} = a, ctx) do
    repeat = a["repeat"]

    with true <-
           (String.trim(text) != "" and String.length(text) <= 4096) ||
             {:error, "failed", "bad_text"},
         true <- repeat in [nil, "daily"] || {:error, "failed", "bad_repeat"},
         {:ok, conv, name} <- recipient(to, ctx),
         {:ok, t, kind} <- time(at, ctx),
         true <- kind == :datetime || {:error, "failed", "ambiguous_time: ask for the time"},
         :ok <- future(t, ctx) do
      local = Clock.local(t, ctx.tz)
      hhmm = Calendar.strftime(local, "%H:%M")
      day = Calendar.strftime(local, "%a %-d %b")
      wire = %{"conversation_id" => conv, "text" => text, "at" => Clock.ts(t), "repeat" => repeat}
      who = name || "this chat"

      when_text =
        if repeat == "daily", do: "every day at #{hhmm}", else: "on #{day} at #{hhmm}"

      summary =
        if repeat == "daily",
          do: "Send \"#{text}\" to #{who} every day at #{hhmm}, starting #{day}, from your phone",
          else: "Send \"#{text}\" to #{who} on #{day} at #{hhmm}, from your phone"

      {:propose,
       %{
         args: %{"wire" => wire, "to_name" => name, "when_text" => when_text},
         card_args: wire,
         summary: summary,
         when: %{"start" => wire["at"], "end" => nil, "all_day" => false},
         text: text,
         personal: true,
         skill_id: "scheduled_messages"
       }}
    end
  end

  defp run_schedule(_args, _ctx), do: {:error, "failed", "bad_args"}

  # §26.6: a conversation of a chat the asker is an active member of, never a Risi chat:
  # a friend's 1:1 (Private is the default), or this Official chat only when asked.
  defp recipient(to, ctx) do
    q = to |> String.trim() |> String.downcase()

    if q in ["this chat", "this group", "here"] do
      if ctx.in_risi_chat?,
        do: {:error, "failed", "unknown_recipient: ask who it is for"},
        else: {:ok, ctx.conv, nil}
    else
      ids = RisiMe.Social.friend_ids(ctx.asker)

      people =
        RisiMe.Repo.all(
          from u in RisiMe.Accounts.User,
            where: u.id in ^ids and u.kind == "user",
            select: {u.id, u.display_name}
        )

      exact = for {id, n} <- people, String.downcase(n || "") == q, do: {id, n}

      first =
        for {id, n} <- people,
            (n || "") |> String.downcase() |> String.split() |> List.first() == q,
            do: {id, n}

      case if(exact != [], do: exact, else: first) do
        [{id, n}] -> {:ok, RisiMe.Messaging.conversation_id(ctx.asker, id), n}
        [] -> {:error, "failed", "unknown_recipient: ask who it is for (a friend's name)"}
        _ -> {:error, "failed", "ambiguous_recipient: ask which person they mean"}
      end
    end
  end

  ## cancel_scheduled (v1.26 §26.6, by request)

  def cancel_scheduled do
    %{
      name: "cancel_scheduled",
      description:
        "cancel a message the asker scheduled earlier (always confirmed): to (the recipient's " <>
          "name as in the schedule, or \"\" when there is only one)",
      where: :client,
      personal: true,
      write: true,
      finds: false,
      skill: "scheduled_messages",
      args: obj(%{"to" => %{"type" => "string", "maxLength" => 64}}, ["to"]),
      run: &run_cancel/2,
      exec: &exec/3
    }
  end

  defp run_cancel(%{"to" => to}, ctx) do
    case RisiMe.Agent.Skills.scheduled(ctx.asker, to) do
      [s] ->
        who = s.to_name || "a group"

        {:propose,
         %{
           args: %{
             "wire" => %{"schedule_id" => s.schedule_id},
             "to_name" => s.to_name,
             "target_conversation_id" => s.target_conversation_id,
             "entry_id" => s.entry_id
           },
           card_args: %{"schedule_id" => s.schedule_id},
           summary: "Cancel the scheduled message to #{who} (#{s.summary_when})",
           when: %{"start" => Clock.ts(s.at), "end" => nil, "all_day" => false},
           text: "",
           personal: true,
           skill_id: "scheduled_messages"
         }}

      [] ->
        {:error, "failed", "not_found: no pending scheduled message matches"}

      _ ->
        {:error, "failed", "ambiguous: ask which scheduled message"}
    end
  end

  defp run_cancel(_args, _ctx), do: {:error, "failed", "bad_args"}

  ## export_pdf (v1.34 §33.15)

  @pdf_kinds %{
    "answer" => "Risi answer",
    "summary" => "Risi summary",
    "report" => "Risi report",
    "digest" => "Risi digest",
    "discussion_summary" => "Discussion summary",
    "note_card" => "Risi note"
  }
  @pdf_views ~w(day week agenda)
  @pdf_max_days 31

  @doc "The Risi message kinds a PDF can be made of (§33.14)."
  def pdf_kinds, do: Map.keys(@pdf_kinds)

  def export_pdf do
    %{
      name: "export_pdf",
      description:
        "make a PDF on the asker's phone of one of Risi's own earlier messages or a note " <>
          "(source: its ref, e.g. \"m3\" or \"n1\", only refs you were given) or of the " <>
          "calendar ({\"calendar\": {\"view\": \"day\"|\"week\"|\"agenda\", \"from\": " <>
          "ISO or phrase, \"to\": ISO or phrase, at most 31 days}}). send_to: null when they " <>
          "just want the PDF (they get a PDF button); or a friend's name, or \"this chat\" when " <>
          "they say to send it there (always confirmed). You choose only the reference; the " <>
          "phone writes the PDF, you never see or write its content",
      where: :client,
      personal: true,
      write: true,
      finds: false,
      skill: nil,
      args:
        obj(
          %{
            "source" => %{
              "anyOf" => [
                %{"type" => "string", "pattern" => "^[mn][0-9]{1,3}$"},
                obj(
                  %{
                    "calendar" =>
                      obj(
                        %{
                          "view" => %{"enum" => @pdf_views},
                          "from" => @str,
                          "to" => @str
                        },
                        ["view", "from", "to"]
                      )
                  },
                  ["calendar"]
                )
              ]
            },
            "send_to" => %{"type" => ["string", "null"], "maxLength" => 64}
          },
          ["source", "send_to"]
        ),
      # §33.1: only a device that advertises `pdf_export` (kept only with `risi_tools`).
      authorize: fn ctx ->
        if Devices.pdf_export_device?(ctx.asker, ctx.device_id), do: :ok, else: {:error, :denied}
      end,
      run: &run_pdf/2,
      exec: &exec/3
    }
  end

  defp run_pdf(%{"source" => src} = a, ctx) do
    with {:ok, source, title} <- pdf_source(src, ctx),
         {:ok, target} <- pdf_target(a["send_to"], ctx) do
      case target do
        nil ->
          # §33.15 path 1: no tool call, nothing sent; the turn ends with the `pdf` chip.
          {:ok, %{"status" => "button_ready"}, %{pdf_source: source}}

        {conv, name, label} ->
          who = name || "this chat"
          wire = %{"source" => source, "conversation_id" => conv}
          what = "Send \"#{title}\" as a PDF to #{who} (#{label})"

          {:propose,
           %{
             args: %{"wire" => wire, "to_name" => who},
             card_args: nil,
             export: wire,
             summary: what,
             body: String.replace(what, ~r/\((?:🔒|●) /, "(") <> "? Update RisiMe to answer.",
             when: nil,
             text: title,
             personal: true,
             skill_id: nil
           }}
      end
    end
  end

  defp run_pdf(_args, _ctx), do: {:error, "failed", "bad_args"}

  @unknown_source "unknown_source: I couldn't tell which item to export; ask which one"

  # A ref the server handed out this turn (never a model-written id), or a calendar range.
  defp pdf_source("m" <> _ = ref, ctx) do
    case (ctx[:export_refs] || %{})[ref] do
      %{conversation_id: conv, message_id: mid, kind: kind} when is_map_key(@pdf_kinds, kind) ->
        title = "#{@pdf_kinds[kind]} · #{pdf_day(RisiMe.TimeUUID.to_datetime(mid), ctx.tz)}"
        {:ok, %{"type" => "message", "conversation_id" => conv, "message_id" => mid}, title}

      _ ->
        {:error, "failed", @unknown_source}
    end
  end

  defp pdf_source("n" <> _ = ref, ctx) do
    with %{"type" => "note", "note_id" => id} <- (ctx[:refs] || %{})[ref],
         {:ok, note} <- RisiMe.Agent.Notes.kept(ctx.asker, id),
         {:ok, note} <- RisiMe.Agent.Notes.open(note) do
      at = note.started_at || note.created_at

      {:ok, %{"type" => "note", "note_id" => note.note_id},
       "#{note.topic} · #{pdf_day(at, ctx.tz)}"}
    else
      _ -> {:error, "failed", @unknown_source}
    end
  end

  defp pdf_source(%{"calendar" => %{"view" => view, "from" => f, "to" => t}}, ctx)
       when view in @pdf_views do
    with {:ok, from, _} <- time(f, ctx),
         {:ok, to, kind} <- time(t, ctx),
         to = if(kind == :date, do: DateTime.add(to, 86_400, :second), else: to),
         true <- DateTime.compare(to, from) == :gt || {:error, "failed", "end_before_start"},
         true <-
           DateTime.diff(to, from) <= @pdf_max_days * 86_400 ||
             {:error, "failed", "range_too_long: at most 31 days"} do
      last = DateTime.add(to, -1, :second)
      a = Clock.local(from, ctx.tz)
      b = Clock.local(last, ctx.tz)

      days =
        if NaiveDateTime.to_date(a) == NaiveDateTime.to_date(b),
          do: Calendar.strftime(a, "%a %-d %b"),
          else: Calendar.strftime(a, "%-d %b") <> " – " <> Calendar.strftime(b, "%-d %b")

      {:ok,
       %{"type" => "calendar", "view" => view, "from" => Clock.ts(from), "to" => Clock.ts(to)},
       "Calendar · " <> days}
    end
  end

  defp pdf_source(_other, _ctx), do: {:error, "failed", @unknown_source}

  defp pdf_day(%DateTime{} = t, tz), do: Calendar.strftime(Clock.local(t, tz), "%a %-d %b")

  # `send_to`, resolved like `schedule_message`'s recipient (§26.6): a friend's 1:1 is Private;
  # Official only for "this chat". Never a Risi chat (`recipient/2`).
  defp pdf_target(nil, _ctx), do: {:ok, nil}

  defp pdf_target(to, ctx) when is_binary(to) do
    if String.trim(to) == "" do
      {:ok, nil}
    else
      with {:ok, conv, name} <- recipient(to, ctx) do
        {:ok, {conv, name, if(name, do: "🔒 Private", else: "● Official")}}
      end
    end
  end

  defp pdf_target(_to, _ctx), do: {:error, "failed", "bad_args"}

  @doc """
  The words for an `export_pdf` error `code` (§33.15 honesty). `noun` is "note", "message" or
  "calendar view"; `who` the recipient's name.
  """
  def pdf_error_text(code, noun, who) do
    "I couldn't send it: " <>
      case code do
        "source_unavailable" -> "the #{noun} isn't on this phone"
        "not_member" -> "you're not in that chat any more"
        "files_not_ready" -> "#{who} needs to update RisiMe to receive files"
        "too_large" -> "it's too long for a PDF"
        "pdf_failed" -> "the phone couldn't make the PDF"
        _ -> "your phone couldn't do it"
      end <> "."
  end

  @doc "The `sent` / `queued` sentences (§33.15). Only the phone's `sent` says \"Sent\"."
  def pdf_done_text("sent", who), do: "Sent the PDF to #{who}."

  def pdf_done_text("queued", _who),
    do: "The PDF is in your phone's outbox. It goes as soon as your phone is online."

  defp source_noun(%{"wire" => %{"source" => %{"type" => "note"}}}), do: "note"
  defp source_noun(%{"wire" => %{"source" => %{"type" => "calendar"}}}), do: "calendar view"
  defp source_noun(_), do: "message"

  ## Running a confirmed (or allowed) client write on the phone

  @doc """
  `exec` of a client write: sends the tool call with the card's args and the `write_id` to the
  routed device, waits (15 s), and posts the result (§25.4: an `answer` with the step, or the
  `error` `tool_timeout` with Retry). `:ok` on the phone's `ok`, else `{:error, status}`.
  """
  def exec(%Writes.Write{} = w, args, device) do
    dev = route(w, device)
    wire = Map.put(args["wire"], "write_id", w.write_id)

    spec = %{
      user: w.user_id,
      device: dev,
      tool: w.tool,
      args: wire,
      turn_id: w.turn_id,
      request_id: w.request_id,
      conv: w.conversation_id,
      write_id: w.write_id
    }

    case ToolCalls.call(spec) do
      {:ok, "ok", result} ->
        ok(w, args, dev, result)

      {:error, :timeout} ->
        unless w.via == "allowed", do: timeout_reply(w)
        {:error, "timeout"}

      {:ok, status, result} ->
        unless w.via == "allowed", do: failed_reply(w, args, status, result)
        {:error, status}
    end
  end

  # The write's device (see the module doc).
  defp route(%{via: "allowed"} = w, _device), do: w.device_id

  defp route(w, device) do
    cap? =
      cond do
        # v1.34 §33.15: the confirming device when it advertises `pdf_export`, else the turn's.
        w.tool == "export_pdf" -> Devices.pdf_export_device?(w.user_id, device)
        ToolCalls.v126?(w.tool) -> RisiMe.Agent.Skills.device?(w.user_id, device)
        true -> Devices.risi_tools_device?(w.user_id, device)
      end

    if device && cap?, do: device, else: w.device_id
  end

  # A cancel that found nothing to cancel (already sent, unknown): said so, nothing logged.
  defp ok(%{tool: "cancel_scheduled"} = w, _args, _dev, %{"cancelled" => false}) do
    answer(w, "ok", "That message was already sent or isn't scheduled any more.")
    :ok
  end

  # v1.34 §33.15: "Sent" only for the phone's `sent`; `queued` says it is in the outbox. The
  # result was checked by `ToolCalls.parse/2` (state, pages); anything else is not believed.
  defp ok(%{tool: "export_pdf"} = w, args, _dev, result) do
    case result do
      %{"state" => state} when state in ~w(sent queued) ->
        answer(w, "ok", pdf_done_text(state, args["to_name"] || "the chat"))
        :ok

      _ ->
        answer(w, "failed", "Your phone didn't confirm that the PDF was sent. Please check.")
        {:error, "unconfirmed"}
    end
  end

  # v1.32 §25.3 write honesty (P0 2026-10-10): "added" only for an `event_id` the phone read
  # back (`verified: true`). An older phone's bare `event_id` is not proof.
  defp ok(%{tool: "calendar_add"} = w, args, dev, result) do
    if is_map(result) and result["verified"] == true and is_binary(result["event_id"]) do
      done(w, args, dev, result)
    else
      answer(w, "failed", unconfirmed_text())
      {:error, "unconfirmed"}
    end
  end

  defp ok(w, args, dev, result), do: done(w, args, dev, result)

  defp done(w, args, dev, result) do
    # P0 2026-10-09: the phone said which calendar it used: remembered for the next card.
    if w.tool == "calendar_add" and is_map(result["calendar"]),
      do: RisiMe.Agent.CalendarChoice.put(w.user_id, result["calendar"])

    RisiMe.Agent.Skills.client_done(w, args, dev, result) ||
      answer(
        w,
        "ok",
        done_text(w, Map.put(args, "result", result)),
        added_event(w, result)
      )

    :ok
  end

  @doc """
  The success line of a `calendar_add` (P0 2026-10-09): "Added to your Google Calendar:
  Interview with Shenika · Mon 12 Oct, 2–3 PM" (the calendar's name from the phone's result,
  else "calendar").
  """
  def added_text(args, result, tz) do
    result = result || args["result"] || %{}
    wire = args["wire"] || %{}

    where =
      case result["calendar"] do
        %{"name" => n} when is_binary(n) and n != "" -> n
        _ -> "calendar"
      end

    with {:ok, start, _} <- DateTime.from_iso8601(wire["start"] || ""),
         {:ok, stop, _} <- DateTime.from_iso8601(wire["end"] || "") do
      "Added to your #{where}: #{args["title"]} · " <>
        span12(start, stop, wire["all_day"] == true, tz)
    else
      _ -> "Added to your #{where}: #{args["title"]}"
    end
  end

  @doc "`Mon 12 Oct, 2–3 PM`, `Mon 12 Oct, 11 AM–12:30 PM`, `Mon 12 Oct` (all day), in `tz`."
  def span12(start, stop, all_day, tz) do
    a = Clock.local(start, tz)
    b = Clock.local(stop, tz)
    day = &Calendar.strftime(&1, "%a %-d %b")

    cond do
      all_day ->
        last = NaiveDateTime.add(b, -1)

        if NaiveDateTime.to_date(last) == NaiveDateTime.to_date(a),
          do: day.(a),
          else: day.(a) <> " – " <> day.(last)

      NaiveDateTime.to_date(a) == NaiveDateTime.to_date(b) ->
        if ampm(a) == ampm(b),
          do: "#{day.(a)}, #{h12(a)}–#{h12(b)} #{ampm(b)}",
          else: "#{day.(a)}, #{h12(a)} #{ampm(a)}–#{h12(b)} #{ampm(b)}"

      true ->
        "#{day.(a)}, #{h12(a)} #{ampm(a)} – #{day.(b)}, #{h12(b)} #{ampm(b)}"
    end
  end

  defp ampm(t), do: if(t.hour < 12, do: "AM", else: "PM")

  defp h12(t) do
    h = rem(t.hour + 11, 12) + 1
    if t.minute == 0, do: "#{h}", else: "#{h}:#{String.pad_leading("#{t.minute}", 2, "0")}"
  end

  @doc false
  def added_event(%{tool: "calendar_add"}, %{"event_id" => id}),
    do: %{"added_event" => %{"event_id" => id}}

  def added_event(_w, _result), do: %{}

  defp done_text(%{tool: "calendar_add"} = w, args),
    do: added_text(args, nil, Clock.user_tz(w.user_id))

  defp done_text(_w, _args), do: "Done."

  @doc "The words for a `calendar_add` that the phone reported but did not verify."
  def unconfirmed_text,
    do:
      "Your phone reported the event as added, but couldn't confirm it. " <>
        "Please check your calendar."

  @doc "The words for a `calendar_add` error `code` (§25.3 write honesty)."
  def add_error_text("no_permission"), do: "I couldn't add it: calendar access is off."
  def add_error_text("read_only_calendar"), do: "I couldn't add it: your calendars are read-only."

  def add_error_text("insert_failed"),
    do: "I couldn't add it: the phone's calendar refused it."

  def add_error_text("verify_failed"),
    do: "I couldn't add it: the event wasn't there when I checked."

  def add_error_text(_), do: "Your phone couldn't do it, so nothing was changed."

  defp answer(w, status, text, extra \\ %{}) do
    Writes.post(
      w,
      text,
      Map.merge(extra, %{
        "kind" => "answer",
        "request_id" => w.request_id,
        "answer" => text,
        "refs" => [],
        "confidence" => 1.0,
        "steps" => [%{"tool" => w.tool, "status" => status}],
        "sources" => [],
        "next_steps" => [],
        "turn_ref" => w.turn_id,
        "notify" => [w.user_id]
      })
    )
  end

  defp timeout_reply(w) do
    body =
      if w.tool == "export_pdf",
        do: "I couldn't reach your phone to send the PDF. Tap Retry to try again.",
        else: "I couldn't reach your phone to add it. Tap Retry to try again."

    Writes.post(w, body, %{
      "kind" => "error",
      "request_id" => w.request_id,
      "code" => "tool_timeout",
      "notify" => [w.user_id]
    })
  end

  defp failed_reply(w, args, status, result) do
    text =
      case status do
        "error" when w.tool == "export_pdf" ->
          pdf_error_text(
            is_map(result) && result["code"],
            source_noun(args),
            args["to_name"] || "They"
          )

        "error" when w.tool == "calendar_add" ->
          add_error_text(is_map(result) && result["code"])

        "no_permission" when w.tool == "calendar_add" ->
          add_error_text("no_permission")

        "no_permission" ->
          "I couldn't do it: the permission is off on your phone."

        "declined" ->
          "Your phone declined it, so nothing was changed."

        _ ->
          "Your phone couldn't do it, so nothing was changed."
      end

    answer(w, if(status == "error", do: "failed", else: status), text)
  end

  ## Helpers

  defp call(ctx, tool, args) do
    left = ctx.deadline - System.monotonic_time(:millisecond)

    ToolCalls.call(
      %{
        user: ctx.asker,
        device: ctx.device_id,
        to_device: ctx[:check_to],
        tool: tool,
        args: args,
        turn_id: ctx.turn_id,
        request_id: ctx.request_id,
        conv: ctx.conv
      },
      left
    )
  end

  defp status({:error, :timeout}), do: {:error, "timeout"}
  defp status({:ok, "error", _}), do: {:error, "failed"}
  defp status({:ok, s, _}) when s in ~w(no_permission declined), do: {:error, s}
  defp status(_), do: {:error, "failed"}

  @doc "A phrase or ISO time in the asker's zone, from the request's time (§25.5)."
  def time(phrase, ctx) do
    case TimePhrase.resolve(phrase, ctx.now, ctx.tz) do
      {:ok, t, kind} -> {:ok, t, kind}
      {:error, :ambiguous} -> {:error, "failed", "ambiguous_time: ask the person which time"}
      {:error, _} -> {:error, "failed", "unreadable_time: ask the person for the time"}
    end
  end

  @doc "`:ok` for a time ahead of the request and at most a year away (§26.6, S15)."
  def future(t, ctx) do
    cond do
      DateTime.compare(t, ctx.now) != :gt -> {:error, "failed", "in_the_past"}
      DateTime.diff(t, ctx.now) > 366 * 86_400 -> {:error, "failed", "more_than_a_year_ahead"}
      true -> :ok
    end
  end
end
