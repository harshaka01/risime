defmodule RisiMe.Agent.ActionDraft do
  @moduledoc """
  The **pending action draft** of the Risi action loop (P0 2026-10-09): an event or a reminder
  the asker is building with Risi, one per (user, conversation), so follow-ups ("Monday 2pm,
  job interview, 1 hour") patch it instead of starting over.

  * **Slots:** `kind` (`event` | `reminder`), `title`, `date` (`YYYY-MM-DD`), `time` and
    `end_time` (`HH:MM`, the asker's wall clock), `duration_min`, `all_day`, `item` (a `k<n>`
    ref of one of the asker's promises, `RisiMe.Agent.Turn`). Phrases from the model are
    resolved once, at the request's `server_ts` in the asker's zone (`TimePhrase`), so a later
    turn never re-reads "Monday" from another day.
  * **Where slots come from:** the model's structured output only (the `draft` of a `final`, or
    the args of a `calendar_add`/`set_reminder` step). Free text is never parsed for intent
    (decision 066).
  * **Defaults, never asked:** the asker's zone (PATCH /me tz, else `RISI_DEFAULT_TZ`), 1 hour,
    the title of a matching promise.
  * **Storage:** `risi_action_drafts`, the draft sealed with `RISI_DATA_KEY` (AAD
    `risi_action_drafts:<user>:<conversation>`), `asks`/`stalls` for the loop guard, the
    `write_id` of the card last proposed from it; 24 h (`expires_at`, hourly prune); deleted
    when its write is done or cancelled, and with the conversation's data.
  """
  import Ecto.Query

  alias RisiMe.Agent.{Clock, Seal, TimePhrase}
  alias RisiMe.Repo

  defmodule Row do
    @moduledoc false
    use Ecto.Schema

    @primary_key false
    schema "risi_action_drafts" do
      field :user_id, :binary_id, primary_key: true
      field :conversation_id, :string, primary_key: true
      field :draft, :binary
      field :asks, :integer, default: 0
      field :stalls, :integer, default: 0
      field :write_id, :binary_id
      field :updated_at, :utc_datetime_usec
      field :expires_at, :utc_datetime_usec
    end
  end

  @ttl_s 24 * 3600
  @default_duration_min 60

  @doc "How long a draft lives (24 h)."
  def ttl_s, do: @ttl_s

  ## The store

  @doc """
  The pending draft of `user` in `conv`: `%{draft, asks, stalls, write_id}`, or nil (none,
  expired, or it can't be opened without the data key).
  """
  def get(user, conv) do
    now = DateTime.utc_now()

    with %Row{} = r <- Repo.get_by(Row, user_id: user, conversation_id: conv),
         :lt <- DateTime.compare(now, r.expires_at),
         {:ok, key} <- Seal.data(),
         {:ok, %{} = d} <- Seal.open(key, aad(user, conv), r.draft) do
      %{draft: d, asks: r.asks, stalls: r.stalls, write_id: r.write_id}
    else
      _ -> nil
    end
  end

  @doc "Stores (replaces) the draft of `user` in `conv`; the 24 h start again."
  def put(user, conv, %{draft: d} = s) do
    case Seal.data() do
      {:ok, key} ->
        now = DateTime.utc_now()

        row = %{
          user_id: user,
          conversation_id: conv,
          draft: Seal.seal(key, aad(user, conv), d),
          asks: Map.get(s, :asks, 0),
          stalls: Map.get(s, :stalls, 0),
          write_id: Map.get(s, :write_id),
          updated_at: now,
          expires_at: DateTime.add(now, @ttl_s, :second)
        }

        Repo.insert_all(Row, [row],
          on_conflict: {:replace, [:draft, :asks, :stalls, :write_id, :updated_at, :expires_at]},
          conflict_target: [:user_id, :conversation_id]
        )

        :ok

      :error ->
        :error
    end
  end

  @doc "Deletes the draft of `user` in `conv`."
  def delete(user, conv) do
    Repo.delete_all(from r in Row, where: r.user_id == ^user and r.conversation_id == ^conv)
    :ok
  end

  @doc "The write proposed from the draft was done or cancelled: the draft is finished."
  def finished(user, conv, write_id) do
    Repo.delete_all(
      from r in Row,
        where: r.user_id == ^user and r.conversation_id == ^conv and r.write_id == ^write_id
    )

    :ok
  end

  @doc "Deletes every draft in `conv` (Official off, removal, a Risi chat left)."
  def forget(conv) do
    Repo.delete_all(from r in Row, where: r.conversation_id == ^conv)
    :ok
  end

  @doc "Deletes expired drafts."
  def prune(now \\ DateTime.utc_now()) do
    Repo.delete_all(from r in Row, where: r.expires_at < ^now)
    :ok
  end

  defp aad(user, conv), do: "risi_action_drafts:#{user}:#{conv}"

  ## The model's side: the `draft` of a `final` (risi_next_action)

  @phrase %{"type" => ["string", "null"], "maxLength" => 60}

  @doc "The JSON schema of the optional `draft` in a `final`."
  def schema,
    do: %{
      "type" => ["object", "null"],
      "properties" => %{
        "kind" => %{"enum" => ["event", "reminder"]},
        "new" => %{"type" => ["boolean", "null"]},
        "title" => %{"type" => ["string", "null"], "maxLength" => 200},
        "date" => @phrase,
        "time" => @phrase,
        "end_time" => @phrase,
        "duration_min" => %{"type" => ["integer", "null"], "minimum" => 5, "maximum" => 1440},
        "all_day" => %{"type" => ["boolean", "null"]},
        "item" => %{"type" => ["string", "null"], "pattern" => "^k[0-9]{1,3}$"},
        # v1.29 §29.8: the people to invite to a Risi Calendar event (names as said).
        "with" => %{
          "type" => ["array", "null"],
          "maxItems" => 10,
          "items" => %{"type" => "string", "maxLength" => 80}
        }
      },
      "required" => ["kind"],
      "additionalProperties" => false
    }

  ## Normalising (phrases → the asker's wall clock, once)

  @doc """
  The slots of a model draft (or tool args mapped to one) with every phrase resolved at `now`
  in `tz`: `date` → `YYYY-MM-DD`, `time`/`end_time` → `HH:MM`. Unreadable or ambiguous phrases
  are left out (the slot stays missing). Never raises.
  """
  def normalise(nil, _now, _tz), do: %{}

  def normalise(%{} = raw, now, tz) do
    base =
      %{}
      |> put_if("kind", raw["kind"], &(&1 in ~w(event reminder)))
      |> put_if("title", clean_title(raw["title"]), &is_binary/1)
      |> put_if("duration_min", raw["duration_min"], &(is_integer(&1) and &1 in 5..1440))
      |> put_if("all_day", raw["all_day"], &is_boolean/1)
      |> put_if("item", raw["item"], &(is_binary(&1) and &1 =~ ~r/^k\d{1,3}$/))
      |> put_if("new", raw["new"], &(&1 == true))
      |> put_if("with", names(raw["with"]), &(&1 != []))

    {date, date_time} = date_of(raw["date"], now, tz)
    {time, time_date} = time_of(raw["time"], now, tz)
    {end_time, _} = time_of(raw["end_time"], now, tz)

    base
    |> put_if("date", date || time_date, &is_binary/1)
    |> put_if("time", time || date_time, &is_binary/1)
    |> put_if("end_time", end_time, &is_binary/1)
  rescue
    _ -> %{}
  end

  defp put_if(map, _k, nil, _ok?), do: map
  defp put_if(map, k, v, ok?), do: if(ok?.(v), do: Map.put(map, k, v), else: map)

  defp clean_title(t) when is_binary(t) do
    case String.trim(t) do
      "" -> nil
      s -> String.slice(s, 0, 200)
    end
  end

  defp clean_title(_), do: nil

  defp names(list) when is_list(list) do
    list
    |> Enum.filter(&is_binary/1)
    |> Enum.map(&(&1 |> String.trim() |> String.slice(0, 80)))
    |> Enum.reject(&(&1 == ""))
    |> Enum.uniq()
    |> Enum.take(10)
  end

  defp names(_), do: nil

  # A date phrase: {date, a time it also named}.
  defp date_of(p, now, tz) when is_binary(p) and p != "" do
    case TimePhrase.resolve(p, now, tz) do
      {:ok, t, :date} -> {iso_date(t, tz), nil}
      {:ok, t, :datetime} -> {iso_date(t, tz), hhmm(t, tz)}
      _ -> {nil, nil}
    end
  end

  defp date_of(_p, _now, _tz), do: {nil, nil}

  # A time phrase: {time, the date it also named ("Monday 2pm"), else nil}.
  defp time_of(p, now, tz) when is_binary(p) and p != "" do
    case TimePhrase.resolve(p, now, tz) do
      {:ok, t, :datetime} ->
        day? =
          Regex.match?(~r/[a-z]{3,}|\d{4}-\d\d-\d\d/i, String.replace(p, ~r/noon|midnight/i, ""))

        {hhmm(t, tz), if(day?, do: iso_date(t, tz))}

      _ ->
        {nil, nil}
    end
  end

  defp time_of(_p, _now, _tz), do: {nil, nil}

  defp iso_date(t, tz), do: t |> Clock.local(tz) |> NaiveDateTime.to_date() |> Date.to_iso8601()
  defp hhmm(t, tz), do: t |> Clock.local(tz) |> Calendar.strftime("%H:%M")

  @doc """
  The slots of a `calendar_add` / `set_reminder` step's args, as a draft (the model chose the
  tool: its args are structured output too).
  """
  def from_tool("calendar_add", %{} = a, now, tz) do
    normalise(
      %{
        "kind" => "event",
        "title" => a["title"],
        "date" => a["start"],
        "end_time" => if(is_binary(a["end"]) and a["end"] != "", do: a["end"]),
        "all_day" => a["all_day"]
      },
      now,
      tz
    )
  end

  # v1.29: the Risi Calendar card's args are the same slots (plus the people named).
  def from_tool("risi_calendar_add", %{} = a, now, tz) do
    "calendar_add"
    |> from_tool(a, now, tz)
    |> Map.merge(normalise(%{"kind" => "event", "with" => a["with"]}, now, tz))
  end

  def from_tool("set_reminder", %{} = a, now, tz),
    do: normalise(%{"kind" => "reminder", "title" => a["text"], "date" => a["when"]}, now, tz)

  def from_tool(_tool, _args, _now, _tz), do: %{}

  @doc """
  Merges new slots into the pending draft: `{draft, progress?}`. A draft the model marks
  `new`, or a different title after a card was already proposed, starts a new draft.
  """
  def merge(old, new, proposed? \\ false)

  def merge(old, new, _proposed?) when new == %{}, do: {old, false}

  def merge(old, new, proposed?) do
    old = old || %{}

    fresh? =
      new["new"] == true or
        (proposed? and is_binary(new["title"]) and is_binary(old["title"]) and
           String.downcase(new["title"]) != String.downcase(old["title"]))

    base = if fresh?, do: %{}, else: old
    merged = base |> Map.merge(Map.delete(new, "new")) |> Map.put_new("kind", "event")
    {merged, fresh? or merged != old}
  end

  ## Resolving a draft to a card

  @doc """
  What the card needs, or what is missing: `{:ok, %{kind, title, args}}` with the write tool's
  args (`calendar_add`: title, start, end, all_day; `set_reminder`: when, text, audience), or
  `{:missing, slots}` (`"title"`, `"date"`, `"time"`). `items` maps `k<n>` refs to the asker's
  promises (`%{text, due, all_day}`): a matching one fills the title, date and time.
  """
  def resolve(draft, items, tz, opts \\ []) do
    d = with_item(draft || %{}, items, tz)
    guard? = Keyword.get(opts, :defaults, false)
    kind = d["kind"] || "event"

    d = if guard?, do: defaults(d, kind, opts[:now], tz), else: d

    missing =
      [
        if(blank?(d["title"]), do: "title"),
        if(blank?(d["date"]) and blank?(d["time"]), do: "date"),
        if(
          blank?(d["time"]) and not (kind == "event" and d["all_day"] == true) and
            not blank?(d["date"]),
          do: "time"
        )
      ]
      |> Enum.reject(&is_nil/1)

    cond do
      missing != [] -> {:missing, missing}
      kind == "reminder" -> {:ok, %{kind: kind, title: d["title"], args: reminder_args(d)}}
      true -> {:ok, %{kind: kind, title: d["title"], args: event_args(d, opts[:now], tz)}}
    end
  end

  defp blank?(v), do: v in [nil, ""]

  # A promise named by `item` fills what the draft lacks.
  defp with_item(%{"item" => ref} = d, items, tz) when is_map(items) do
    case items[ref] do
      %{} = it ->
        {date, time} =
          case it[:due] do
            nil ->
              {nil, nil}

            due ->
              local = Clock.local(due, tz)
              date = local |> NaiveDateTime.to_date() |> Date.to_iso8601()
              if it[:all_day], do: {date, nil}, else: {date, Calendar.strftime(local, "%H:%M")}
          end

        d
        |> Map.put_new_lazy("title", fn -> it[:text] end)
        |> fill("date", date)
        |> fill("time", if(blank?(d["date"]) or d["date"] == date, do: time))
        |> fill("all_day", if(it[:all_day] and blank?(d["time"]), do: true))

      _ ->
        d
    end
  end

  defp with_item(d, _items, _tz), do: d

  defp fill(d, _k, nil), do: d
  defp fill(d, k, v), do: if(blank?(d[k]), do: Map.put(d, k, v), else: d)

  # The loop guard's card: everything known, sensible values for the rest (the asker edits or
  # cancels it; nothing runs without [Add]).
  defp defaults(d, kind, now, tz) do
    local_now = Clock.local(now || Clock.now(), tz)
    tomorrow = local_now |> NaiveDateTime.to_date() |> Date.add(1) |> Date.to_iso8601()

    d
    |> fill("title", if(kind == "reminder", do: "Reminder", else: "Event"))
    # Neither a day nor a time: tomorrow 09:00 (shown on the card, never run without [Add]).
    |> then(fn d ->
      if blank?(d["date"]) and blank?(d["time"]),
        do: Map.merge(d, %{"date" => tomorrow, "time" => "09:00"}),
        else: d
    end)
    |> then(fn d ->
      cond do
        not blank?(d["time"]) -> d
        kind == "reminder" -> Map.put(d, "time", "09:00")
        true -> Map.put(d, "all_day", true)
      end
    end)
  end

  defp event_args(d, now, tz) do
    if blank?(d["time"]) do
      %{"title" => d["title"], "start" => d["date"], "end" => nil, "all_day" => true}
    else
      date = d["date"] || next_date(d["time"], now, tz)
      {:ok, start} = NaiveDateTime.from_iso8601("#{date}T#{d["time"]}:00")
      stop = end_of(d, start)

      %{
        "title" => d["title"],
        "start" => fmt(start),
        "end" => fmt(stop),
        "all_day" => false
      }
    end
  end

  defp end_of(d, start) do
    by_duration = NaiveDateTime.add(start, (d["duration_min"] || @default_duration_min) * 60)

    with t when is_binary(t) <- d["end_time"],
         {:ok, e} <- NaiveDateTime.from_iso8601("#{NaiveDateTime.to_date(start)}T#{t}:00"),
         :gt <- NaiveDateTime.compare(e, start) do
      e
    else
      _ -> by_duration
    end
  end

  defp reminder_args(d) do
    date = d["date"]
    when_ = if date, do: "#{date} #{d["time"]}", else: d["time"]
    %{"when" => when_, "text" => d["title"], "audience" => "me"}
  end

  # A time alone: its next occurrence (as a phrase would resolve it).
  defp next_date(time, now, tz) do
    case TimePhrase.resolve(time, now || Clock.now(), tz) do
      {:ok, t, _} -> iso_date(t, tz)
      _ -> Date.to_iso8601(NaiveDateTime.to_date(Clock.local(now || Clock.now(), tz)))
    end
  end

  defp fmt(naive), do: Calendar.strftime(naive, "%Y-%m-%d %H:%M")

  ## Questions in a model's answer (the post-check)

  @slot_words [
    {"tz", ~r/\btime ?zones?\b/i},
    {"calendar", ~r/\bwhich calendar\b|\bwhat calendar\b|\bcalendar (?:account|app)\b/i},
    {"end", ~r/\b(?:end|ends|ending|finish|finishes|how long|duration|last|lasts)\b/i},
    {"title",
     ~r/\b(?:title|name|called|call it|what is it|what's it|what kind|what type|describe|description|about)\b/i},
    {"date", ~r/\b(?:date|day|which day|what day|when)\b/i},
    {"time", ~r/\b(?:time|what time|start|starts|o'clock|am or pm)\b/i},
    {"confirm",
     ~r/\b(?:confirm|shall i|should i|do you want me to|would you like me to|want me to|is that (?:right|correct)|is this (?:right|correct)|go ahead|proceed)\b/i}
  ]

  @doc "The question sentences of an answer (ending in \"?\")."
  def questions(answer) when is_binary(answer) do
    Regex.scan(~r/[^.!?\n]*\?/, answer) |> List.flatten() |> Enum.map(&String.trim/1)
  end

  def questions(_), do: []

  @doc """
  The slots a question asks about: `"title"`, `"date"`, `"time"`, `"end"`, `"tz"`,
  `"calendar"`, `"confirm"` (asking to confirm in text).
  """
  def asked_slots(question) do
    {slots, _} =
      Enum.reduce(@slot_words, {[], question}, fn {slot, re}, {acc, q} ->
        if Regex.match?(re, q), do: {[slot | acc], Regex.replace(re, q, " ")}, else: {acc, q}
      end)

    Enum.reverse(slots)
  end

  @doc "The one question Risi asks for the first missing slot (server-built)."
  def question_for(["title" | _], "reminder"), do: "What should I remind you about?"
  def question_for(["title" | _], _kind), do: "What should I call the event?"
  def question_for(["date" | _], _kind), do: "When is it?"
  def question_for(["time" | _], "reminder"), do: "What time should I remind you?"
  def question_for(["time" | _], _kind), do: "What time does it start?"
  def question_for(_, _kind), do: "When is it?"

  @doc "The text before the answer's questions (a restatement), or \"\"."
  def without_questions(answer) when is_binary(answer) do
    answer
    |> String.replace(~r/[^.!?\n]*\?/, "")
    |> String.replace(~r/\s{2,}/, " ")
    |> String.trim()
  end

  def without_questions(_), do: ""
end
