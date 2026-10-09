defmodule RisiMe.Agent.LedgerReminders do
  @moduledoc """
  Reminders, follow-ups and the personal digest of ledger items (contract v1.27 §27.6). All go
  to **the addressee's own Risi chat**, `notify` = that person, `made_by.model: null`, times in
  the addressee's zone; only for items `confirmed` or `edited` (a job finding any other state,
  or an older `schedule_v`, does nothing); items without a due get none.

  | moment | to | timed `due` | `all_day` |
  |---|---|---|---|
  | `item_due` `before` | owner | `due − 1 h` (skipped if confirmed later) | 09:00 the day before |
  | `item_due` `at` | owner | `due` | 09:00 on the due date |
  | `item_due` `today` | each counterpart | 09:00 on the due date, or `due − 1 h` if earlier | 09:00 on the due date |
  | `item_overdue` | owner | `due + 2 h` | 10:00 the next day |
  | `item_nudge` | each counterpart | 24 h after `item_overdue` | the same |

  `item_overdue` and `item_nudge` happen at most once per item in its life, whatever the edits
  (`overdue_sent_at`, `nudged_at`). Jobs (`item_reminder`) carry ids, the moment and
  `schedule_v` only, never text.

  **Personal digest** (`digest_sweep/1`, every 15 min): at 09:xx in the user's zone, at most once
  a day, in the user's Risi chat, only when the user has open promises (own or owed): exactly the
  items of "My promises" (`GET /risi/commitments`, P0 2026-10-09) (`scope: "personal"`).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, Commitment, Commitments, Out, Secretary}
  alias RisiMe.{Devices, Repo, RisiChat}
  alias RisiMe.Workers.Risi, as: Job

  @open ~w(confirmed edited)

  ## Scheduling

  @doc "Schedules the §27.6 reminders of a confirmed or edited item (old ones are cancelled by the caller)."
  def schedule(%Commitment{due: nil}), do: :ok

  def schedule(%Commitment{item_state: state} = c) when state in @open do
    now = Clock.now()
    owner_tz = Clock.user_tz(c.owner_id)

    base = %{
      "kind" => "item_reminder",
      "conv" => c.conversation_id,
      "commitment_id" => c.id,
      "v" => c.schedule_v
    }

    {before, at, overdue} = owner_moments(c, owner_tz)
    confirmed = c.confirmed_at || now

    # `before` is skipped when the item was confirmed later than it.
    if DateTime.compare(before, confirmed) == :gt and DateTime.compare(before, now) == :gt,
      do: job(base, "before", c.owner_id, before)

    if DateTime.compare(at, now) == :gt, do: job(base, "at", c.owner_id, at)

    for cp <- c.counterpart_ids do
      today = today_moment(c, owner_tz, Clock.user_tz(cp))
      if DateTime.compare(today, now) == :gt, do: job(base, "today", cp, today)
    end

    # Once per item: a confirmation after the overdue time still gets its follow-up (now).
    if is_nil(c.overdue_sent_at),
      do: job(base, "overdue", c.owner_id, latest(overdue, now))

    :ok
  end

  def schedule(_c), do: :ok

  defp owner_moments(%Commitment{all_day: true} = c, tz) do
    date = due_date(c, tz)

    {at_local(Date.add(date, -1), ~T[09:00:00], tz), at_local(date, ~T[09:00:00], tz),
     at_local(Date.add(date, 1), ~T[10:00:00], tz)}
  end

  defp owner_moments(c, _tz),
    do: {DateTime.add(c.due, -3600), c.due, DateTime.add(c.due, 2 * 3600)}

  # The counterpart's "due today": 09:00 (their zone) on the due date, or due − 1 h if earlier.
  defp today_moment(%Commitment{all_day: true} = c, owner_tz, tz),
    do: at_local(due_date(c, owner_tz), ~T[09:00:00], tz)

  defp today_moment(c, _owner_tz, tz) do
    nine = at_local(due_date(c, tz), ~T[09:00:00], tz)
    Enum.min([nine, DateTime.add(c.due, -3600)], DateTime)
  end

  defp due_date(c, tz), do: c.due |> Clock.local(tz) |> NaiveDateTime.to_date()
  defp at_local(date, time, tz), do: Clock.to_utc(NaiveDateTime.new!(date, time), tz)
  defp latest(a, b), do: if(DateTime.compare(a, b) == :gt, do: a, else: b)

  defp job(base, moment, to, at) do
    base
    |> Map.merge(%{"moment" => moment, "to" => to})
    |> Job.new(queue: :risi_timers, scheduled_at: at)
    |> Oban.insert!()
  end

  ## Firing

  @doc "One `item_reminder` job (see the module doc). Oban result."
  def fire(%{"commitment_id" => id, "v" => v, "moment" => moment, "to" => to}) do
    with %Commitment{schedule_v: ^v, item_state: state} = c when state in @open <-
           Repo.get(Commitment, id),
         {:ok, c} <- Commitment.open(c) do
      send_moment(moment, c, to)
    else
      _ -> :ok
    end
  end

  def fire(_args), do: :ok

  defp send_moment("overdue", %Commitment{overdue_sent_at: nil} = c, to) do
    now = Clock.now()
    tz = Clock.user_tz(to)

    body =
      if c.all_day,
        do: "'#{c.text}' was due yesterday. Done, or a new date?",
        else: "'#{c.text}' was due at #{hm(c.due, tz)}. Done, or a new date?"

    risi =
      common(c, "item_overdue", to)
      |> Map.merge(%{
        "overdue_by" => max(DateTime.diff(now, c.due), 0),
        "buttons" => ~w(done new_date)
      })

    with :ok <- post(to, body, risi) do
      c |> Ecto.Changeset.change(overdue_sent_at: Clock.usec(now)) |> Repo.update!()

      # The nudge to the counterparts, 24 h after the follow-up.
      if c.counterpart_ids != [] do
        %{
          "kind" => "item_reminder",
          "conv" => c.conversation_id,
          "commitment_id" => c.id,
          "v" => c.schedule_v,
          "moment" => "nudge",
          "to" => nil
        }
        |> Job.new(queue: :risi_timers, scheduled_at: DateTime.add(now, 24 * 3600))
        |> Oban.insert!()
      end

      :ok
    end
  end

  defp send_moment("overdue", _c, _to), do: :ok

  # The nudge goes once, to every counterpart; the job's `v` may be older than an edit after
  # the follow-up (the nudge belongs to that follow-up), so it is checked against `nudged_at`.
  defp send_moment("nudge", %Commitment{nudged_at: nil} = c, _to) do
    now = Clock.now()
    owner = name(c.conversation_id, c.owner_id)

    for cp <- c.counterpart_ids do
      risi =
        common(c, "item_nudge", cp)
        |> Map.merge(%{"owner" => c.owner_id, "overdue_by" => max(DateTime.diff(now, c.due), 0)})

      post(cp, "#{owner}'s item '#{c.text}' was due yesterday and isn't marked done.", risi)
    end

    c |> Ecto.Changeset.change(nudged_at: Clock.usec(now)) |> Repo.update!()
    :ok
  end

  defp send_moment("nudge", _c, _to), do: :ok

  defp send_moment(moment, c, to) when moment in ~w(before at today) do
    tz = Clock.user_tz(to)
    owner? = moment != "today"

    body =
      case moment do
        "before" -> "Reminder: #{lower_first(c.text)}#{to_whom(c, tz)} (due #{due_words(c, tz)})."
        "at" when c.all_day -> "Due today: #{lower_first(c.text)}#{to_whom(c, tz)}."
        "at" -> "Due now: #{lower_first(c.text)}#{to_whom(c, tz)}."
        "today" -> "#{name(c.conversation_id, c.owner_id)}'s item '#{c.text}' is due today."
      end

    risi =
      common(c, "item_due", to)
      |> Map.merge(%{
        "owner" => c.owner_id,
        "moment" => moment,
        "role" => if(owner?, do: "owner", else: "counterpart"),
        "buttons" => if(owner?, do: ~w(done new_date), else: [])
      })

    post(to, body, risi)
  end

  defp send_moment(_moment, _c, _to), do: :ok

  defp common(c, kind, to) do
    %{
      "kind" => kind,
      "item_id" => c.id,
      "summary_id" => c.summary_id,
      "text" => c.text,
      "due" => Clock.ts(c.due),
      "all_day" => c.all_day == true,
      "call_ref" => nil,
      "notify" => [to]
    }
  end

  # Into the addressee's own active Risi chat (none: nothing is sent).
  defp post(to, body, risi) do
    case RisiChat.active_id(to) do
      nil ->
        :ok

      rc ->
        case Out.post(rc, body, risi) do
          {:ok, _} -> :ok
          {:error, :rate_limited} -> {:snooze, 10}
          {:error, reason} when reason in [:not_member, :private_tab] -> :ok
          {:error, reason} -> {:error, reason}
        end
    end
  end

  defp name(conv, user) do
    Map.new(Secretary.members(conv), &{&1.user_id, &1.name})[user] ||
      Repo.one(from u in RisiMe.Accounts.User, where: u.id == ^user, select: u.display_name) ||
      "A member"
  end

  defp to_whom(%Commitment{counterpart_ids: []}, _tz), do: ""

  defp to_whom(c, _tz) do
    names = Map.new(Secretary.members(c.conversation_id), &{&1.user_id, &1.name})
    " to " <> RisiMe.Agent.LedgerOut.name_list(c.counterpart_ids, names)
  end

  defp due_words(%Commitment{all_day: true}, _tz), do: "tomorrow"

  defp due_words(c, tz) do
    today = Clock.now() |> Clock.local(tz) |> NaiveDateTime.to_date()
    local = Clock.local(c.due, tz)

    if NaiveDateTime.to_date(local) == today,
      do: Calendar.strftime(local, "%H:%M"),
      else: Clock.human(c.due, "datetime", tz)
  end

  defp hm(dt, tz), do: dt |> Clock.local(tz) |> Calendar.strftime("%H:%M")

  defp lower_first(<<c::utf8, rest::binary>>), do: String.downcase(<<c::utf8>>) <> rest
  defp lower_first(s), do: s

  ## The personal digest

  @doc """
  The personal digest sweep (every 15 min): each user with open ledger items whose clock reads
  09:xx and who had no personal digest today gets one in their Risi chat. `now` for tests.
  """
  def digest_sweep(now \\ Clock.now()) do
    if RisiMe.Risi.ledger_on?() do
      # Everyone with an open promise (the rows `GET /risi/commitments` lists as open).
      users =
        Repo.all(
          from c in Commitment,
            where:
              c.state in ^Commitment.open_states() and
                (is_nil(c.item_state) or c.item_state != "proposed"),
            select: {c.owner_id, c.counterpart_ids}
        )
        |> Enum.flat_map(fn {o, cps} -> [o | cps] end)
        |> Enum.uniq()

      for u <- Devices.risi_ledger_users(users), do: digest(u, now)
    end

    :ok
  end

  @doc """
  Sends `user`'s personal digest if their clock reads 09:xx and none was sent today. P0
  2026-10-09: its items are exactly "My promises" (`RisiMe.Agent.Rest.open_commitments/1`, the
  query of `GET /risi/commitments`), so the digest never says less than the app lists.
  """
  def digest(user, now \\ Clock.now()) do
    tz = Clock.user_tz(user)
    local = Clock.local(now, tz)
    today = NaiveDateTime.to_date(local)
    rc = RisiChat.active_id(user)

    items =
      if (rc && local.hour == 9) and last_digest(rc) != today, do: digest_items(user), else: []

    if items == [] do
      :skipped
    else
      post_digest(user, rc, tz, today, items)
    end
  end

  @doc "The digest's items for `user`: \"My promises\" (open), as the app lists them."
  def digest_items(user) do
    case RisiMe.Agent.Rest.open_commitments(user) do
      {:ok, items} -> items
      {:error, _} -> []
    end
  end

  defp post_digest(user, rc, tz, today, items) do
    {own, owed} = Enum.split_with(items, &(&1.owner_id == user))

    line = fn c -> "#{lower_first(c.text)} (due #{digest_due(c, tz, today)})" end

    owed_line = fn c ->
      "#{name(c.conversation_id, c.owner_id)}, #{lower_first(c.text)} (#{digest_due(c, tz, today)})"
    end

    parts =
      if(own == [], do: [], else: ["Your open items: " <> Enum.map_join(own, "; ", line) <> "."]) ++
        if owed == [],
          do: [],
          else: ["Owed to you: " <> Enum.map_join(owed, "; ", owed_line) <> "."]

    risi = %{
      "kind" => "digest",
      "scope" => "personal",
      "date" => Date.to_iso8601(today),
      "items" =>
        for c <- items do
          %{
            "commitment_id" => c.id,
            "text" => c.text,
            "owner" => c.owner_id,
            "due" => Clock.ts(c.due),
            "state" => c.item_state || c.state,
            # Item 9 (2026-10-09): the My promises split.
            "direction" => RisiMe.Agent.Rest.direction(c, user)
          }
        end,
      # Item 9: equal to `GET /risi/commitments` `totals` (the same query).
      "totals" => RisiMe.Agent.Rest.totals(items, user),
      "notify" => [user]
    }

    case Out.post(rc, Enum.join(parts, " "), risi) do
      {:ok, _} ->
        ts = DateTime.utc_now()

        Repo.insert_all(
          "risi_chat_state",
          [%{conversation_id: rc, last_digest_on: today, inserted_at: ts, updated_at: ts}],
          on_conflict: [set: [last_digest_on: today, updated_at: ts]],
          conflict_target: [:conversation_id]
        )

        :sent

      {:error, reason} ->
        Logger.warning("Risi personal digest not sent: #{inspect(reason)}")
        :error
    end
  end

  defp digest_due(%{due: nil}, _tz, _today), do: "no date"

  defp digest_due(c, tz, today) do
    local = Clock.local(c.due, tz)
    date = NaiveDateTime.to_date(local)

    cond do
      c.all_day and date == today -> "today"
      c.all_day -> Calendar.strftime(local, "%A")
      date == today -> "today " <> Calendar.strftime(local, "%H:%M")
      Date.compare(date, today) == :lt -> "overdue since " <> Clock.human(c.due, "datetime", tz)
      true -> Clock.human(c.due, "datetime", tz)
    end
  end

  defp last_digest(rc),
    do:
      Repo.one(
        from s in "risi_chat_state", where: s.conversation_id == ^rc, select: s.last_digest_on
      )

  @doc false
  def cancel(id), do: Commitments.cancel_timers(id)
end
