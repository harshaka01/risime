defmodule RisiMe.Agent.Ledger do
  @moduledoc """
  The Commitment Ledger follow-ups (contract v1.27 §27.2–§27.5, decision 070), while
  `RISI_LEDGER=on`.

  **The quiet rule** (§27.2, per Official conversation, never a Risi chat). Risi keeps a
  **cutoff** (`risi_chat_state.summary_cutoff_ts`: the server_ts of the last message covered by
  a discussion summary; initially the start of the 24-h buffer). **Counted messages** are the
  buffered `text` messages of active human members after the cutoff (deleted ones are gone from
  the buffer; Risi's own, requests, actions and other envelopes are never counted). Each counted
  message (re)schedules one `discussion_quiet` job for the conversation (`on_counted/2`), at
  `newest + RISI_QUIET_S` (600 s) or, once the oldest counted message is 4 h old, at the next
  3-min gap. The job (`quiet/1`) triggers when all hold:

    1. quiet: the newest counted message is ≥ `RISI_QUIET_S` old (or the 4-h rule's gap);
    2. enough: ≥ `RISI_QUIET_MIN_MSGS` (6) counted messages from ≥ 2 distinct humans;
    3. spacing: no summary in this conversation in the last 30 min (else it re-checks then), and
       fewer than 8 today (the chat's zone);
    4. Official on (the worker's `may_act?/1`) and `RISI_LEDGER=on`.

  **On a trigger** one `discussion_summarise` model call over the window gives key points, a
  one-line summary and items, checked here: an item's owner must be an active human member,
  counterparts are active human members other than the owner, confidence ≥
  `RISI_MIN_CONFIDENCE`, at most 10 items. **No item: nothing is posted**; the cutoff moves to
  the newest message of the window either way. With items, the discussion (key points and
  summary sealed with `RISI_DATA_KEY`) and its items (proposed, sealed) are stored and published
  (`publish/1`, §27.3–§27.4).

  Participants are the humans who sent a counted message in the window; recipients are the
  participants plus the items' owners, all active members.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, Commitment, Discussion, ItemDedupe, LLM, Offers, Prompts, Secretary}
  alias RisiMe.{Repo, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @quiet_s 600
  @min_msgs 6
  @min_people 2
  @spacing_s 30 * 60
  @per_day 8
  @long_s 4 * 3600
  @gap_s 180
  @max_items 10
  @expire_s 48 * 3600

  @doc "RISI_QUIET_S (default 600)."
  def quiet_s, do: Application.get_env(:risime, :risi_quiet_s, @quiet_s)

  @doc "RISI_QUIET_MIN_MSGS (default 6)."
  def min_msgs, do: Application.get_env(:risime, :risi_quiet_min_msgs, @min_msgs)

  @doc "RISI_QUIET_MIN_PEOPLE: distinct humans needed (default 2)."
  def min_people, do: Application.get_env(:risime, :risi_quiet_min_people, @min_people)

  @doc "RISI_QUIET_SPACING_S: seconds between two summaries of a chat (default 1800)."
  def spacing_s, do: Application.get_env(:risime, :risi_quiet_spacing_s, @spacing_s)

  @doc "RISI_QUIET_PER_DAY: summaries per chat per local day, fewer than this (default 8)."
  def per_day, do: Application.get_env(:risime, :risi_quiet_per_day, @per_day)

  @doc "A proposed item's life (48 h)."
  def expire_s, do: @expire_s

  defp min_confidence, do: Application.get_env(:risime, :risi_min_confidence, 0.6)

  ## Counting (from RisiMe.Agent.Secretary.on_message/2)

  @doc """
  A counted message (a `text` of an active human member in an Official, non-Risi chat) was
  buffered: notes the first counted message after the cutoff and (re)schedules the quiet check.
  """
  def on_counted(conv, message_id) do
    ts = TimeUUID.to_datetime(message_id)
    since = mark_since(conv, ts)
    schedule_quiet(conv, check_at(ts, since))
  end

  # When to check: `newest + quiet`, or earlier at the next 3-min gap once the discussion is 4 h
  # long.
  defp check_at(newest, since) do
    quiet = DateTime.add(newest, quiet_s(), :second)

    long =
      since &&
        Enum.max(
          [DateTime.add(newest, @gap_s, :second), DateTime.add(since, @long_s, :second)],
          DateTime
        )

    if long && DateTime.compare(long, quiet) == :lt, do: long, else: quiet
  end

  @doc "Schedules (or moves) the conversation's one `discussion_quiet` job."
  def schedule_quiet(conv, at) do
    %{"kind" => "discussion_quiet", "conv" => conv}
    |> Job.new(
      queue: :risi,
      scheduled_at: at,
      unique: [
        period: :infinity,
        keys: [:kind, :conv],
        states: [:available, :scheduled, :retryable]
      ],
      replace: [scheduled: [:scheduled_at], retryable: [:scheduled_at]]
    )
    |> Oban.insert()

    :ok
  end

  defp mark_since(conv, ts) do
    now = DateTime.utc_now()

    Repo.insert_all(
      "risi_chat_state",
      [%{conversation_id: conv, inserted_at: now, updated_at: now}],
      on_conflict: :nothing,
      conflict_target: [:conversation_id]
    )

    Repo.update_all(
      from(s in "risi_chat_state",
        where:
          s.conversation_id == ^conv and is_nil(s.discussion_since) and
            (is_nil(s.summary_cutoff_ts) or s.summary_cutoff_ts < ^ts)
      ),
      set: [discussion_since: ts]
    )

    Repo.one(
      from s in "risi_chat_state",
        where: s.conversation_id == ^conv,
        select: type(s.discussion_since, :utc_datetime_usec)
    )
  end

  ## The quiet check (the `discussion_quiet` job)

  @doc "The quiet check of `conv` (see the module doc). Oban result."
  def quiet(conv) do
    if RisiMe.Risi.ledger_on?() and not RisiMe.Groups.Tabs.risi_chat?(conv),
      do: check(conv),
      else: :ok
  end

  defp check(conv) do
    st = state(conv)
    now = Clock.now()

    case counted(conv, st.cutoff) do
      [] ->
        :ok

      msgs ->
        newest = msg_ts(List.last(msgs))
        oldest = msg_ts(hd(msgs))
        quiet? = DateTime.diff(now, newest) >= quiet_s()
        long? = DateTime.diff(now, oldest) >= @long_s and DateTime.diff(now, newest) >= @gap_s
        senders = msgs |> Enum.map(& &1.sender_id) |> Enum.uniq()

        cond do
          not (quiet? or long?) ->
            schedule_quiet(conv, check_at(newest, oldest))

          length(msgs) < min_msgs() or length(senders) < min_people() ->
            :ok

          st.last_summary_at && DateTime.diff(now, st.last_summary_at) < spacing_s() ->
            schedule_quiet(conv, DateTime.add(st.last_summary_at, spacing_s(), :second))

          today_count(st, Secretary.chat_tz(conv), now) >= per_day() ->
            :ok

          true ->
            summarise(conv, msgs, senders, :chat)
        end
    end
  end

  defp state(conv) do
    case Repo.one(
           from s in "risi_chat_state",
             where: s.conversation_id == ^conv,
             select: %{
               cutoff: type(s.summary_cutoff_ts, :utc_datetime_usec),
               last_summary_at: type(s.last_summary_at, :utc_datetime_usec),
               summaries_today: s.summaries_today,
               summaries_on: s.summaries_on
             }
         ) do
      nil -> %{cutoff: nil, last_summary_at: nil, summaries_today: 0, summaries_on: nil}
      st -> st
    end
  end

  defp today_count(st, tz, now) do
    today = now |> Clock.local(tz) |> NaiveDateTime.to_date()
    if st.summaries_on == today, do: st.summaries_today, else: 0
  end

  @doc """
  The counted messages of `conv` after `cutoff` (oldest first): buffered `text` messages of
  active human members.
  """
  def counted(conv, cutoff) do
    humans = conv |> Secretary.members() |> MapSet.new(& &1.user_id)

    conv
    |> Secretary.text_messages(cutoff)
    |> Enum.filter(fn m ->
      MapSet.member?(humans, m.sender_id) and
        (cutoff == nil or DateTime.compare(msg_ts(m), cutoff) == :gt)
    end)
  end

  defp msg_ts(%{message_id: id}), do: TimeUUID.to_datetime(id)

  ## The extraction (`discussion_summarise`)

  defp summarise(conv, msgs, participants, source) do
    members = Secretary.members(conv)
    tzs = Clock.user_tzs(Enum.map(members, & &1.user_id))
    tz = Secretary.chat_tz(conv)
    {text, refs} = Prompts.render(members, msgs, tz, tzs: tzs, now: Clock.now())

    req = %{
      task: "discussion_summarise",
      conversation_id: conv,
      chat_id: Secretary.chat_id(conv),
      system: Prompts.discussion_system(),
      user: text,
      schema_name: "discussion_summary",
      schema: Prompts.discussion_schema(),
      source_message_ids: Enum.map(msgs, & &1.message_id),
      max_tokens: 1_500,
      confidence: &confidence/1
    }

    case LLM.complete(req) do
      {:ok, %{call_ref: call_ref, output: out}} ->
        found = validate(out, refs, members, tzs)
        # Item 10: an item that repeats one already kept is merged into it, not stored again.
        found = %{found | items: ItemDedupe.drop_merged(found.items)}
        newest = msg_ts(List.last(msgs))

        window = %{
          conversation_id: conv,
          source: Atom.to_string(source),
          started_at: msg_ts(hd(msgs)),
          ended_at: newest,
          participants: participants,
          call_ref: call_ref
        }

        if found.items == [] do
          # No item: nothing is posted; the cutoff moves on.
          move_cutoff(conv, newest, false)
          :ok
        else
          store_and_publish(window, found, members)
        end

      {:error, :rate_limited} ->
        {:snooze, 30}

      {:error, reason} ->
        {:error, reason}
    end
  end

  defp confidence(%{"items" => []}), do: 1.0

  defp confidence(%{"items" => is}),
    do: is |> Enum.map(&(&1["confidence"] || 0)) |> Enum.min() |> Kernel./(1)

  defp confidence(_), do: 0.0

  # The model's output checked against the server's own view of the chat.
  defp validate(out, refs, members, tzs) do
    member_ids = MapSet.new(members, & &1.user_id)

    items =
      (out["items"] || [])
      |> Enum.map(&item(&1, refs, member_ids, tzs))
      |> Enum.reject(&is_nil/1)
      |> Enum.uniq_by(&{&1.owner, String.downcase(&1.text)})
      |> Enum.take(@max_items)

    key_points =
      (out["key_points"] || [])
      |> Enum.map(&(&1 |> to_string() |> String.trim() |> String.slice(0, 200)))
      |> Enum.reject(&(&1 == ""))
      |> Enum.take(8)

    summary = (out["summary"] || "") |> to_string() |> String.trim() |> String.slice(0, 280)

    # 1–8 key points and a one-line summary are always sent: each falls back on the other.
    key_points =
      cond do
        key_points != [] -> key_points
        summary != "" -> [String.slice(summary, 0, 200)]
        true -> ["Follow-ups were agreed."]
      end

    summary = if summary == "", do: String.slice(hd(key_points), 0, 280), else: summary

    %{items: items, key_points: key_points, summary: summary}
  end

  defp item(i, refs, member_ids, tzs) do
    owner = refs.users[i["owner"]]
    text = i["text"] |> to_string() |> String.trim()

    cond do
      owner == nil or not MapSet.member?(member_ids, owner) ->
        nil

      text == "" ->
        nil

      (i["confidence"] || 0) < min_confidence() ->
        nil

      true ->
        tz = Map.get(tzs, owner, Clock.default_tz())
        {due, all_day} = due(i["due_local"], tz)

        %{
          item_id: Ecto.UUID.generate(),
          owner: owner,
          counterpart:
            (i["counterparts"] || [])
            |> Enum.map(&refs.users[&1])
            |> Enum.reject(&(is_nil(&1) or &1 == owner))
            |> Enum.filter(&MapSet.member?(member_ids, &1))
            |> Enum.uniq(),
          text: String.slice(text, 0, 200),
          due: due,
          all_day: all_day,
          due_text: i["due_text"] && String.slice(i["due_text"], 0, 100),
          source_message_ids:
            (i["source"] || []) |> Enum.map(&refs.messages[&1]) |> Enum.reject(&is_nil/1),
          confidence: i["confidence"] / 1
        }
    end
  end

  @doc """
  A model's `due_local` in the owner's zone `tz` as `{due_utc, all_day}`: a date-only due is the
  end of that local day (23:59:59.999) with `all_day: true` (§27.3).
  """
  def due(s, tz) when is_binary(s) do
    if Regex.match?(~r/^\d{4}-\d{2}-\d{2}$/, s) do
      case Date.from_iso8601(s) do
        {:ok, d} -> {Clock.to_utc(NaiveDateTime.new!(d, ~T[23:59:59.999]), tz), true}
        _ -> {nil, false}
      end
    else
      {due, _kind} = Clock.parse_due(s, tz)
      {due, false}
    end
  end

  def due(_s, _tz), do: {nil, false}

  ## Storing

  defp store_and_publish(window, found, members) do
    conv = window.conversation_id
    now = Clock.now()
    summary_id = Ecto.UUID.generate()
    owners = found.items |> Enum.map(& &1.owner) |> Enum.uniq()
    active = MapSet.new(members, & &1.user_id)

    recipients =
      (window.participants ++ owners) |> Enum.uniq() |> Enum.filter(&MapSet.member?(active, &1))

    discussion = %Discussion{
      summary_id: summary_id,
      conversation_id: conv,
      chat_id: Secretary.chat_id(conv),
      source: window.source,
      call_id: window[:call_id],
      media: window[:media],
      duration_s: window[:duration_s],
      started_at: Clock.usec(window.started_at),
      ended_at: Clock.usec(window.ended_at),
      participants: window.participants,
      recipients: recipients,
      key_points: found.key_points,
      summary: found.summary,
      items_count: length(found.items),
      call_ref: window.call_ref,
      made_by: RisiMe.Agent.MadeBy.build(window.call_ref, [], window[:also] || []),
      created_at: Clock.usec(now)
    }

    with {:ok, discussion} <- Discussion.seal(discussion),
         {:ok, rows} <- item_rows(discussion, found.items, now) do
      Repo.transaction(fn ->
        Repo.insert!(discussion)
        Enum.each(rows, &Repo.insert!/1)
        move_cutoff(conv, window.ended_at, true)
      end)

      publish(discussion, rows)
    else
      :error ->
        # No data key: nothing derived can be kept sealed, so nothing is kept or posted.
        Logger.warning("Risi discussion not summarised in #{conv}: missing_key RISI_DATA_KEY")
        move_cutoff(conv, window.ended_at, false)
        :ok
    end
  end

  defp item_rows(d, items, now) do
    Enum.reduce_while(items, {:ok, []}, fn i, {:ok, acc} ->
      row = %Commitment{
        id: i.item_id,
        conversation_id: d.conversation_id,
        chat_id: d.chat_id,
        state: "proposed",
        item_state: "proposed",
        summary_id: d.summary_id,
        source: d.source,
        all_day: i.all_day,
        text: i.text,
        owner_id: i.owner,
        counterpart_ids: i.counterpart,
        due: i.due && Clock.usec(i.due),
        due_kind: if(i.due, do: if(i.all_day, do: "date", else: "datetime")),
        due_text: i.due_text,
        source_message_ids: i.source_message_ids,
        confidence: i.confidence,
        call_ref: d.call_ref,
        proposed_at: Clock.usec(now),
        needs_clarification: Offers.vague?(i.due, i.due_text)
      }

      case Commitment.seal(row) do
        {:ok, row} -> {:cont, {:ok, acc ++ [row]}}
        :error -> {:halt, :error}
      end
    end)
  end

  defp move_cutoff(conv, ts, summarised?) do
    now = Clock.now()
    tz = Secretary.chat_tz(conv)
    today = now |> Clock.local(tz) |> NaiveDateTime.to_date()
    st = state(conv)

    set =
      [summary_cutoff_ts: Clock.usec(ts), discussion_since: nil, updated_at: DateTime.utc_now()] ++
        if summarised?,
          do: [
            last_summary_at: Clock.usec(now),
            summaries_on: today,
            summaries_today: today_count(st, tz, now) + 1
          ],
          else: []

    Repo.update_all(from(s in "risi_chat_state", where: s.conversation_id == ^conv), set: set)
    :ok
  end

  ## Publishing (§27.3–§27.4)

  @doc """
  Publishes a stored discussion and its items (`RisiMe.Agent.LedgerOut.publish/2`: the
  per-person copies, the owner fallback, the short card). Each proposed ledger item expires 48 h
  after the summary (`item_expire`). Oban result.
  """
  def publish(%Discussion{} = d, rows) do
    kept = RisiMe.Agent.LedgerOut.publish(d, rows)
    for r <- kept, Commitment.ledger?(r), do: expire_timer(d.conversation_id, r.id)
    # Items 8/10: "Add to calendar?" for a timed item, one question for a vague one.
    Offers.consider_all(kept)
    :ok
  end

  defp expire_timer(conv, id) do
    %{"kind" => "item_expire", "conv" => conv, "item_id" => id}
    |> Job.new(queue: :risi_timers, schedule_in: @expire_s)
    |> Oban.insert!()
  end

  @doc "A proposed item nobody confirmed within 48 h: `expired`, then deleted."
  def expire(id) do
    Repo.delete_all(from c in Commitment, where: c.id == ^id and c.item_state == "proposed")

    :ok
  end

  ## Deletion

  @doc "Deletes the conversation's discussions (§24.4 Official off, with its other Risi data)."
  def forget(conv) do
    Repo.delete_all(from d in Discussion, where: d.conversation_id == ^conv)
    RisiMe.Agent.LedgerOut.forget(conv)
    :ok
  end
end
