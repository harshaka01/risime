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
        "check when the asker is free or busy on their phone's calendar (from, to: an ISO time " <>
          "or a local phrase like \"Tuesday 00:00\"; at most 14 days)",
      where: :client,
      personal: true,
      write: false,
      finds: true,
      skill: "calendar",
      args: obj(%{"from" => @str, "to" => @str}, ["from", "to"]),
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

      case call(ctx, "calendar_check", args) do
        {:ok, "ok", %{"blocks" => blocks}} ->
          refs =
            blocks
            |> Enum.with_index(1)
            |> Map.new(fn {b, i} -> {"c#{i}", Map.put(b, "type", "calendar")} end)

          shown = for {b, i} <- Enum.with_index(blocks, 1), do: Map.put(b, "ref", "c#{i}")
          {:ok, %{"from" => args["from"], "to" => args["to"], "blocks" => shown}, %{refs: refs}}

        other ->
          status(other)
      end
    end
  end

  defp run_check(_args, _ctx), do: {:error, "failed", "bad_args"}

  ## calendar_add

  def calendar_add do
    %{
      name: "calendar_add",
      description:
        "propose adding an event to the asker's phone calendar (the asker confirms first; " <>
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
         skill_id: "calendar"
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

      {:ok, status, _} ->
        unless w.via == "allowed", do: failed_reply(w, status)
        {:error, status}
    end
  end

  # The write's device (see the module doc).
  defp route(%{via: "allowed"} = w, _device), do: w.device_id

  defp route(w, device) do
    cap? =
      if ToolCalls.v126?(w.tool),
        do: RisiMe.Agent.Skills.device?(w.user_id, device),
        else: Devices.risi_tools_device?(w.user_id, device)

    if device && cap?, do: device, else: w.device_id
  end

  # A cancel that found nothing to cancel (already sent, unknown): said so, nothing logged.
  defp ok(%{tool: "cancel_scheduled"} = w, _args, _dev, %{"cancelled" => false}) do
    answer(w, "ok", "That message was already sent or isn't scheduled any more.")
    :ok
  end

  defp ok(w, args, dev, result) do
    RisiMe.Agent.Skills.client_done(w, args, dev, result) || answer(w, "ok", done_text(w, args))
    :ok
  end

  defp done_text(%{tool: "calendar_add"}, args),
    do: "Added \"#{args["title"]}\" to your calendar."

  defp done_text(_w, _args), do: "Done."

  defp answer(w, status, text) do
    Writes.post(w, text, %{
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
  end

  defp timeout_reply(w) do
    body = "I couldn't reach your phone to add it. Tap Retry to try again."

    Writes.post(w, body, %{
      "kind" => "error",
      "request_id" => w.request_id,
      "code" => "tool_timeout",
      "notify" => [w.user_id]
    })
  end

  defp failed_reply(w, status) do
    text =
      case status do
        "no_permission" -> "I couldn't do it: the permission is off on your phone."
        "declined" -> "Your phone declined it, so nothing was changed."
        _ -> "Your phone couldn't do it, so nothing was changed."
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
