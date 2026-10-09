defmodule RisiMe.Agent.ClientTools do
  @moduledoc """
  The client tools as registry entries (contract v1.25 §25.3/§25.5, v1.26 §26.6): what the model
  may pass (`args`, the server's schema: times may be local phrases, `RisiMe.Agent.TimePhrase`),
  how a step runs (`run`), and how a confirmed write runs on the phone (`exec`, through
  `RisiMe.Agent.ToolCalls`).

  * `calendar_check` (read, personal): free/busy blocks; each block becomes a `c<n>` ref
    (a `calendar` Source). Never titles.
  * `calendar_add` (write, personal): a confirm card, then the phone adds the event.

  **Device routing** (§25.4, §26.8): a confirmed write goes to the confirming device when it
  advertises the tool's capability (`risi_tools`; `risi_skills` for the v1.26 tools), else to
  the turn's device; an allowed write to the turn's device.
  """
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
