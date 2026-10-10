defmodule RisiMe.Agent.CalendarHonesty do
  @moduledoc """
  P0 2026-10-09 (Harsha's phone: "Your calendar is clear" while his Google Calendar was full):
  a deterministic post-check on the final answer of every turn that checked the calendar (or
  claims the asker is free without checking).

  * A read is **trustworthy** only when the phone said what it read: a source with `read_ok`
    and at least one calendar (`calendar_check` result `sources`, see
    docs/status/android.md "Contract asks"). An old phone (no `sources`), a failed, declined
    or timed-out check, and `read_ok: false` are never trustworthy.
  * The answer may say "clear" / "free" / "nothing on" only after a trustworthy read whose
    busy blocks don't contradict it. Otherwise it is rewritten from the tool result:
    "I couldn't read your calendar on this phone (<reason>). Connect it in Settings →
    Risi skills → Calendar."
  * Every answer after a check names what was checked: "I checked: Phone calendar — Work
    (Google) 0 events (Mon 12 Oct, 14:00–15:00)."

  Source-agnostic: a check is a list of sources (`phone_provider` today, `google_api` and a
  server-side `risi_calendar` later); any source with `read_ok` and a calendar is a read.

  The prompt says the same, but this module is what enforces it.
  """

  alias RisiMe.Agent.ClientTools

  @connect "Connect it in Settings → Risi skills → Calendar."
  @max_named 6
  @sync_off_why "sync is off, so it may be out of date"

  @doc """
  A check as the turn keeps it: `%{status, result, reason}` (`result` the `calendar_check` step
  result: `from`, `to`, `blocks`, and from a new phone `sources` / `connected_sources`).
  """
  def check(status, result, reason), do: %{status: status, result: result, reason: reason}

  @doc "The final answer after the post-check (`checks` in the order they ran)."
  def enforce(answer, checks, tz), do: elem(enforce_made(answer, checks, tz), 0)

  @doc """
  `{answer, rule_made?, next_steps}`: as `enforce_made/3`, and `next_steps` is `["Retry"]` when
  the answer was rewritten because a connected source (Google) could not be read (v1.31 §31.5),
  else `nil` (the model's own).
  """
  def enforce_full(answer, checks, tz) when is_binary(answer) do
    {out, rule?} = enforce_made(answer, checks, tz)
    {out, rule?, if(unread_connected?(checks), do: retry_if_rewritten(answer, out), else: nil)}
  end

  def enforce_full(answer, _checks, _tz), do: {answer, false, nil}

  # The free-claim rewrite of `unread_text/2` (the only answer built with this sentence).
  defp retry_if_rewritten(_answer, out),
    do: if(out =~ ~r/^Your .* free then, but I couldn't check /, do: ["Retry"])

  defp unread_connected?(checks) do
    case List.last(checks) do
      %{status: "ok", result: %{"sources" => ss}} when is_list(ss) ->
        Enum.any?(ss, &unread_connected_source?/1)

      _ ->
        false
    end
  end

  @doc """
  `{answer, rule_made?}`: `rule_made?` when the model's text was discarded (the answer is then
  built by the server, `made_by` rule, v1.29 §29.3).
  """
  def enforce_made(answer, checks, tz) when is_binary(answer) do
    cond do
      checks == [] and claims_calendar_free?(answer) ->
        {cant_read("I didn't check it for this answer"), true}

      checks == [] ->
        {answer, false}

      risi_check?(List.last(checks)) ->
        enforce_v129(answer, List.last(checks), tz)

      true ->
        last = List.last(checks)

        case trusted(last) do
          {:ok, sources} ->
            busy = busy_blocks(last.result)
            rewrite? = claims_free?(answer) and busy != []
            body = if rewrite?, do: busy_text(busy, tz), else: answer
            line = checked_line(sources, last.result, tz) <> not_checked(last.result)
            {add_line(body, line), rewrite?}

          # A v1 phone with blocks: something was read, but not which calendars.
          {:v1, _} ->
            if claims_free?(answer),
              do: {cant_read(elem(trusted_v1_reason(), 1)), true},
              else:
                {add_line(
                   answer,
                   "Checked: your phone's calendar (update RisiMe to see which calendars)."
                 ), false}

          # v1.29 §29.3 rule 1: no read, the model's text is discarded.
          {:error, reason} ->
            {cant_read(reason), true}
        end
    end
  end

  def enforce_made(answer, _checks, _tz), do: {answer, false}

  ## v1.29 §29.7: a check that includes Risi Calendar (`risi_calendar_check`)

  defp risi_check?(%{status: "ok", result: %{"sources" => sources}}) when is_list(sources),
    do: Enum.any?(sources, &(&1["source"] == "risi_calendar"))

  defp risi_check?(_), do: false

  @cant_check "I couldn't check your calendar, so I can't tell whether you're free."

  # v1.31 §31.5: a connected Google Calendar that was not read for one of these reasons.
  @unread_reasons ~w(reauth_needed no_answer network timeout api_error)

  defp unread_connected_source?(%{"source" => "google_api", "read_ok" => false, "reason" => r}),
    do: r in @unread_reasons

  defp unread_connected_source?(_), do: false

  # A real link is in play (v1.31): the source carries the link's `count`.
  defp linked?(sources), do: Enum.any?(sources, &(&1["source"] == "google_api" and &1["count"]))

  # Sources in the §29.7 order, Risi Calendar · Google Calendar · Phone calendar as of v1.31
  # (with no link in play: Risi Calendar · Phone calendar, and Google `not_connected`).
  defp v129_sources(%{"sources" => sources}) do
    # P0 2026-10-10: a Google API link that is not in play is not a source at all: the phone
    # provider already covers Google accounts synced to the phone.
    sources = Enum.reject(sources, &ClientTools.never_connected_google?/1)

    order =
      if linked?(sources),
        do: %{"risi_calendar" => 0, "google_api" => 1, "phone_provider" => 2},
        else: %{"risi_calendar" => 0, "phone_provider" => 1, "google_api" => 2}

    Enum.sort_by(sources, &Map.get(order, &1["source"], 3))
  end

  defp read?(%{"source" => "risi_calendar", "read_ok" => true}), do: true

  defp read?(%{"read_ok" => true, "calendars" => [_ | _]}), do: true
  defp read?(_), do: false

  defp enforce_v129(answer, %{result: result}, tz) do
    sources = v129_sources(result)
    {read, missed} = Enum.split_with(sources, &read?/1)

    if read == [] do
      # No read: the model's text is discarded; one line per source.
      lines =
        Enum.map_join(
          sources,
          "\n",
          &"#{source_label(&1["source"])}: #{v129_reason(&1)}."
        )

      {@cant_check <> "\n" <> lines, true}
    else
      busy = busy_blocks(result)
      claims? = claims_free?(answer)
      unread = Enum.filter(missed, &unread_connected_source?/1)
      google_read? = Enum.any?(read, &(&1["source"] == "google_api"))

      cond do
        claims? and busy != [] ->
          {add_line(busy_text(busy, tz, google_read?), v129_line(read, missed)), true}

        # v1.31 §31.5: never "free" about a connected source that was not read.
        claims? and unread != [] ->
          {add_line(unread_text(read, unread), v129_line(read, missed)), true}

        true ->
          {add_line(answer, v129_line(read, missed)), false}
      end
    end
  end

  defp unread_text(read, [g | _]) do
    names = Enum.map(read, &source_label(&1["source"]))

    who =
      case names do
        ["Risi Calendar"] -> "Your Risi Calendar is"
        ns -> "Your #{Enum.join(ns, " and ")} #{if length(ns) > 1, do: "are", else: "is"}"
      end

    "#{who} free then, but I couldn't check #{source_label(g["source"])} (#{v129_reason(g)})."
  end

  # A source's reason in words. Google uses the §31.5 table; the phone keeps its own words.
  defp v129_reason(%{"source" => "google_api", "reason" => r}), do: google_reason(r)
  defp v129_reason(%{"reason" => "not_connected"}), do: "not connected"
  defp v129_reason(%{"reason" => nil}), do: reason_text("query_failed")
  defp v129_reason(%{"reason" => "paused"}), do: google_reason("paused")
  defp v129_reason(%{"reason" => r}), do: reason_text(r)

  @doc "The words for a Google Calendar `reason` (§31.5 table)."
  def google_reason("not_connected"), do: "not connected"
  def google_reason("reauth_needed"), do: "needs reconnecting"
  def google_reason("no_answer"), do: "phone didn't answer"
  def google_reason("paused"), do: "Calendar skill is off"
  def google_reason("network"), do: "no network on the phone"
  def google_reason("timeout"), do: "Google didn't answer in time"
  def google_reason("api_error"), do: "Google didn't answer"
  def google_reason("no_calendars"), do: "no calendars picked"
  def google_reason(nil), do: "Google didn't answer"
  def google_reason(r), do: reason_text(r)

  @doc """
  "Checked: Risi Calendar · Phone calendar (Work). Not checked: Google Calendar (not
  connected)." (§29.7).
  """
  def v129_line(read, missed) do
    checked =
      Enum.map_join(read, " · ", fn s ->
        case {s["source"], s["calendars"] || []} do
          {"risi_calendar", _} ->
            "Risi Calendar"

          {"google_api", cals} ->
            # The server never knows the names: a count ("2 calendars"), §31.5.
            n = s["count"] || length(cals)
            "Google Calendar (#{n} #{if n == 1, do: "calendar", else: "calendars"})"

          {src, cals} ->
            names = for c <- Enum.take(cals, @max_named), is_binary(c["name"]), do: c["name"]
            more = length(cals) - length(names)
            names = if more > 0, do: names ++ ["#{more} more"], else: names

            case names do
              [] -> source_label(src)
              ns -> "#{source_label(src)} (#{Enum.join(ns, ", ")})"
            end
        end
      end)

    # v1.31 example: " · " between the unread sources once a Google link is in play.
    sep = if linked?(read ++ missed), do: " · ", else: ", "

    not_checked =
      case missed do
        [] ->
          ""

        ms ->
          " Not checked: " <>
            Enum.map_join(
              ms,
              sep,
              &"#{source_label(&1["source"])} (#{v129_reason(&1)})"
            ) <>
            "."
      end

    "Checked: #{checked}.#{not_checked}"
  end

  defp trusted_v1_reason,
    do: {:error, "this version of the app doesn't say which calendars it read; update RisiMe"}

  defp not_checked(%{"sources" => sources}) when is_list(sources) do
    # An optional source that was never connected isn't news ("not_connected").
    case for(s <- sources, s["read_ok"] != true, s["reason"] != "not_connected", do: s) do
      [] ->
        ""

      miss ->
        " Not checked: " <>
          Enum.map_join(
            miss,
            ", ",
            &"#{source_label(&1["source"])} (#{reason_text(&1["reason"])})"
          ) <>
          "."
    end
  end

  defp not_checked(_), do: ""

  @doc "`{:ok, read sources}` for a trustworthy read, else `{:error, why}` (in words)."
  def trusted(%{status: "ok", result: %{"sources" => sources}}) when is_list(sources) do
    read = Enum.filter(sources, &(&1["read_ok"] == true and (&1["calendars"] || []) != []))

    # The phone's own reason first ("google_api" is `not_connected` until it is built).
    failed =
      sources
      |> Enum.reject(&(&1["read_ok"] == true or ClientTools.never_connected_google?(&1)))
      |> Enum.sort_by(&(&1["reason"] == "not_connected"))

    cond do
      read != [] -> {:ok, read}
      s = List.first(failed) -> {:error, trusted_reason(s)}
      true -> {:error, reason_text("no_calendars")}
    end
  end

  def trusted(%{status: "ok", result: %{"blocks" => [_ | _]}}), do: {:v1, :blocks}
  def trusted(%{status: "ok"}), do: trusted_v1_reason()

  def trusted(%{status: "no_permission"}), do: {:error, "calendar permission is off"}

  def trusted(%{status: "declined"}),
    do: {:error, "the Calendar skill is off on this phone"}

  def trusted(%{status: "timeout"}), do: {:error, "your phone didn't answer in time"}

  def trusted(%{reason: r}) when is_binary(r), do: {:error, reason_text(r)}
  def trusted(_), do: {:error, "the phone couldn't read its calendar"}

  # v1.32 §29.7: sync off is told apart from no access and from an empty read.
  defp trusted_reason(%{"reason" => "sync_off"}), do: @sync_off_why
  defp trusted_reason(s), do: reason_text(s["reason"] || "no_calendars")

  @doc "The words for a phone's `reason` code."
  def reason_text("sync_off"), do: "sync is off"
  def reason_text("no_permission"), do: "calendar permission is off"
  def reason_text("no_calendars"), do: "no calendars on this phone"
  def reason_text("no_google_calendar"), do: "no Google account calendar on this phone"

  def reason_text("google_sync_off"),
    do: "calendar sync is off for your Google account on this phone"

  def reason_text("google_calendars_hidden"),
    do: "your Google calendars are hidden on this phone"

  def reason_text("query_failed"), do: "the phone's calendar couldn't be read"
  def reason_text("not_connected"), do: "not connected yet"
  def reason_text("reauth_needed"), do: "you need to sign in to Google again"
  def reason_text("no_play_services"), do: "Google Play services aren't available"
  def reason_text("network"), do: "there was no network"
  def reason_text("timeout"), do: "the calendar didn't answer in time"
  def reason_text("api_error"), do: "the calendar couldn't be read"
  def reason_text("calendar_unavailable"), do: "the phone's calendar isn't available"
  def reason_text("unavailable"), do: "it couldn't be opened"
  def reason_text("bad_args"), do: "the time to check couldn't be read"
  def reason_text("end_before_start"), do: "the time to check couldn't be read"

  def reason_text(r) when is_binary(r) do
    cond do
      String.starts_with?(r, "ambiguous_time") -> "the time to check was ambiguous"
      String.starts_with?(r, "unreadable_time") -> "the time to check couldn't be read"
      true -> r |> String.replace(~r/[^\w .,'-]/u, " ") |> String.slice(0, 80)
    end
  end

  def reason_text(_), do: "the phone couldn't read its calendar"

  @doc "The Risi Calendar (§29.7) sentence when nothing could be checked."
  def cant_check, do: @cant_check

  @doc "The sentence when a calendar write couldn't be offered (fix 2026-10-09)."
  def cant_use(reason),
    do: "I couldn't use your calendar on this phone (#{reason}). #{@connect}"

  @doc "The sentence for an untrustworthy read."
  def cant_read(@sync_off_why),
    do:
      "I couldn't read your calendar on this phone (#{@sync_off_why}). " <>
        "Turn on sync in Settings → Risi skills → Calendar → Details."

  def cant_read(reason),
    do: "I couldn't read your calendar on this phone (#{reason}). #{@connect}"

  @doc "\"I checked: Phone calendar — Work (Google) 0 events (Mon 12 Oct, 14:00–15:00).\""
  def checked_line(sources, result, tz) do
    parts =
      for s <- sources do
        named =
          for c <- s["calendars"] || [] do
            n = c["events"]
            count = if n == 1, do: "1 event", else: "#{n} events"
            "#{c["name"]} (#{type_label(c["account_type"])}) #{count}"
          end

        shown = Enum.take(named, @max_named)
        more = length(named) - length(shown)
        more = if more > 0, do: ", and #{more} more", else: ""
        "#{source_label(s["source"])} — #{Enum.join(shown, ", ")}#{more}"
      end

    "I checked: #{Enum.join(parts, "; ")}#{window(result, tz)}."
  end

  def source_label("google_api"), do: "Google Calendar"
  def source_label("risi_calendar"), do: "Risi Calendar"
  def source_label(_), do: "Phone calendar"

  @doc """
  The `answer.sources` items for the checks (`{"type": "calendar_source", "source", "names",
  "read_ok", "reason"}`): what was read, by name; only the last check of the turn.
  """
  def answer_sources([]), do: []

  def answer_sources(checks) do
    case List.last(checks) do
      %{status: "ok", result: %{"sources" => sources}} when is_list(sources) ->
        for s <- sources, not ClientTools.never_connected_google?(s) do
          base = %{
            "type" => "calendar_source",
            "source" => s["source"],
            "names" => for(c <- s["calendars"] || [], do: c["name"]),
            "read_ok" => s["read_ok"] == true,
            "reason" => s["reason"]
          }

          # v1.31 §31.5: a linked Google has no names (always []), its `count` and the `refs` read.
          if s["source"] == "google_api" and s["count"],
            do:
              Map.merge(base, %{
                "names" => [],
                "count" => s["count"] || length(s["calendars"] || []),
                "refs" => for(c <- s["calendars"] || [], do: c["ref"])
              }),
            else: base
        end

      _ ->
        []
    end
  end

  @doc """
  v1.32 §29.7 `answer.local_events`: `%{"from", "to"}` (the checked range) when the last check
  holds a successful `phone_provider` read, else nil. The caller adds it in the asker's own
  Risi chat only.
  """
  def local_events(checks) when is_list(checks) do
    case List.last(checks) do
      %{status: "ok", result: %{"sources" => ss, "from" => f, "to" => t}}
      when is_list(ss) and is_binary(f) and is_binary(t) ->
        if Enum.any?(ss, &(&1["source"] == "phone_provider" and &1["read_ok"] == true)),
          do: %{"from" => f, "to" => t}

      _ ->
        nil
    end
  end

  defp window(%{"from" => f, "to" => t}, tz) when is_binary(f) and is_binary(t) do
    with {:ok, a, _} <- DateTime.from_iso8601(f),
         {:ok, b, _} <- DateTime.from_iso8601(t) do
      " (" <> ClientTools.span(a, b, false, tz) <> ")"
    else
      _ -> ""
    end
  rescue
    _ -> ""
  end

  defp window(_, _), do: ""

  def type_label("com.google"), do: "Google"
  def type_label("LOCAL"), do: "Phone only"

  def type_label(t) when t in ~w(com.google.android.gm.exchange com.android.exchange),
    do: "Exchange"

  def type_label(t) when is_binary(t) and t != "",
    do: t |> String.split(".") |> List.last() |> String.capitalize()

  def type_label(_), do: "Phone"

  defp busy_blocks(%{"blocks" => blocks}) when is_list(blocks),
    do: Enum.filter(blocks, &(&1["busy"] == true))

  defp busy_blocks(_), do: []

  defp busy_text(busy, tz, plural? \\ false) do
    spans =
      busy
      |> Enum.take(3)
      |> Enum.map(fn b ->
        with {:ok, a, _} <- DateTime.from_iso8601(b["start"]),
             {:ok, e, _} <- DateTime.from_iso8601(b["end"]) do
          ClientTools.span(a, e, b["all_day"] == true, tz)
        else
          _ -> nil
        end
      end)
      |> Enum.reject(&is_nil/1)

    n = length(busy)
    what = if n == 1, do: "1 busy time", else: "#{n} busy times"
    have = if plural?, do: "your calendars have", else: "your calendar has"
    "You're not free then: #{have} #{what} (#{Enum.join(spans, "; ")})."
  end

  defp add_line(answer, line) do
    a = String.trim(answer)

    cond do
      String.contains?(a, line) -> a
      a == "" -> line
      true -> a <> "\n\n" <> line
    end
  end

  # "Feel free…", "free to…", "for free" and "toll-free" are not claims about the calendar.
  @not_claims ~r/\bfeel free\b|\bfree to\b|\bfor free\b|\b\w+-free\b|\bclear(?:ly|ed)? (?:up|out|the)\b|\bis that clear\b/i
  @free ~r/\b(?:clear|free|nothing (?:on|scheduled|planned|booked|else)|no (?:events?|meetings?|appointments?|plans|conflicts?)|not busy|available|open)\b/i
  @calendar ~r/\b(?:calendar|schedule|diary|agenda)\b|\byou(?:'re| are) (?:free|available|clear)\b/i

  @doc "Does the answer say the asker is free / their calendar is clear?"
  def claims_free?(answer) when is_binary(answer) do
    Regex.match?(@free, Regex.replace(@not_claims, answer, " "))
  end

  def claims_free?(_), do: false

  @doc "A free claim about the calendar (used when no check ran at all)."
  def claims_calendar_free?(answer),
    do: claims_free?(answer) and Regex.match?(@calendar, answer)
end
