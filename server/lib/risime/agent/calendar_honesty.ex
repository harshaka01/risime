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

  @doc """
  A check as the turn keeps it: `%{status, result, reason}` (`result` the `calendar_check` step
  result: `from`, `to`, `blocks`, and from a new phone `sources` / `connected_sources`).
  """
  def check(status, result, reason), do: %{status: status, result: result, reason: reason}

  @doc "The final answer after the post-check (`checks` in the order they ran)."
  def enforce(answer, checks, tz), do: elem(enforce_made(answer, checks, tz), 0)

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
      |> Enum.reject(&(&1["read_ok"] == true))
      |> Enum.sort_by(&(&1["reason"] == "not_connected"))

    cond do
      read != [] -> {:ok, read}
      s = List.first(failed) -> {:error, reason_text(s["reason"] || "no_calendars")}
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

  @doc "The words for a phone's `reason` code."
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

  @doc "The sentence for an untrustworthy read."
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
        for s <- sources do
          %{
            "type" => "calendar_source",
            "source" => s["source"],
            "names" => for(c <- s["calendars"] || [], do: c["name"]),
            "read_ok" => s["read_ok"] == true,
            "reason" => s["reason"]
          }
        end

      _ ->
        []
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

  defp busy_text(busy, tz) do
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
    "You're not free then: your calendar has #{what} (#{Enum.join(spans, "; ")})."
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
