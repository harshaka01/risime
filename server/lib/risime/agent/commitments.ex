defmodule RisiMe.Agent.Commitments do
  @moduledoc """
  Commitments (§24.11, decision 066): extraction → a `proposed` card → ✓ (or ✗, edit, done) →
  reminder, escalations and the daily digest.

  **Nothing is tracked without ✓.** A proposal is a row plus a card; it gets no timer but its
  48-h expiry, and an expired or declined proposal is deleted. Only `confirm` (or `edit`) by the
  owner or a counterpart (an active human member) starts the reminder and escalations and writes
  facts.

  * **Extraction** (`extract/1`, Oban queue `risi`): the new buffered `text` messages since the
    conversation's cursor (`risi_chat_state.extracted_upto`), with up to 15 earlier lines as
    context, through `RisiMe.Agent.LLM` (task `commitment_extract`). Each candidate is checked:
    the owner and counterparts must be current active human members, the owner must have written
    one of its source messages, at least one source must be new, it must not repeat a source of
    an existing commitment, and its confidence must reach `:risi_min_confidence` (0.6). At most
    10 cards per chat per day (§24.13).
  * **Timing** in the owner's zone (`RisiMe.Agent.Clock`): reminder at `due − 1 h` (09:00 local
    on the due date when only a date is known), escalations 24 h and 48 h after due (at most 2,
    notifying the counterparts), the digest at 09:00 in the chat's zone when open items exist,
    at most once per chat per day. Timer jobs carry `schedule_v`; an edit or `done` bumps it, so
    older jobs are no-ops (they are cancelled as well).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, Commitment, Embeddings, Fact, LLM, Out, Prompts, Secretary}
  alias RisiMe.{RateLimiter, Repo, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @context 15
  @chunk 60
  @lease_s 300
  @expire_s 48 * 3600
  @cards_per_day 10

  ## Extraction

  @doc "Runs one extraction pass over `conv` (see the module doc). Oban result."
  def extract(conv) do
    case lease(conv) do
      :busy ->
        {:snooze, 15}

      {:ok, upto} ->
        try do
          do_extract(conv, upto)
        after
          release(conv)
        end
    end
  end

  defp do_extract(conv, upto) do
    all = Secretary.text_messages(conv)
    {old, new} = split_at(all, upto)

    case new do
      [] ->
        :ok

      _ ->
        {chunk, rest} = Enum.split(new, @chunk)
        context = Enum.take(old, -@context)

        case run_extraction(conv, context, chunk) do
          :ok ->
            set_upto(conv, List.last(chunk).message_id)
            if rest != [], do: Secretary.schedule_extract(conv, 1)
            :ok

          {:error, :rate_limited} ->
            {:snooze, 30}

          {:error, reason} ->
            {:error, reason}
        end
    end
  end

  # Messages after `upto` (TimeUUID order) are new.
  defp split_at(all, nil), do: {[], all}

  defp split_at(all, upto) do
    ts = TimeUUID.timestamp(upto)

    Enum.split_with(all, fn m ->
      t = TimeUUID.timestamp(m.message_id)
      t < ts or (t == ts and m.message_id <= upto)
    end)
  end

  defp run_extraction(conv, context, chunk) do
    members = Secretary.members(conv)
    tzs = Clock.user_tzs(Enum.map(members, & &1.user_id))
    tz = Secretary.chat_tz(conv)

    messages =
      Enum.map(context, &Map.put(&1, :new?, false)) ++ Enum.map(chunk, &Map.put(&1, :new?, true))

    {text, refs} = Prompts.render(members, messages, tz, tzs: tzs)

    req = %{
      task: "commitment_extract",
      conversation_id: conv,
      chat_id: Secretary.chat_id(conv),
      system: Prompts.extract_system(),
      user: text,
      schema_name: "commitments",
      schema: Prompts.extract_schema(),
      source_message_ids: Enum.map(chunk, & &1.message_id),
      max_tokens: 1_000,
      confidence: &extract_confidence/1
    }

    with {:ok, %{call_ref: call_ref, output: %{"commitments" => found}}} <- LLM.complete(req) do
      new_ids = MapSet.new(chunk, & &1.message_id)
      senders = Map.new(messages, &{&1.message_id, &1.sender_id})
      member_ids = MapSet.new(members, & &1.user_id)

      found
      |> Enum.map(&candidate(&1, refs, new_ids, senders, member_ids, tzs))
      |> Enum.reject(&is_nil/1)
      |> Enum.uniq_by(& &1.source_message_ids)
      |> Enum.each(&propose(conv, &1, call_ref, members))

      :ok
    end
  end

  defp extract_confidence(%{"commitments" => []}), do: 1.0

  defp extract_confidence(%{"commitments" => cs}),
    do: cs |> Enum.map(&(&1["confidence"] || 0)) |> Enum.min() |> Kernel./(1)

  defp extract_confidence(_), do: 0.0

  # A model candidate checked against the server's own view, or nil.
  defp candidate(c, refs, new_ids, senders, member_ids, tzs) do
    owner = refs.users[c["owner"]]
    counterparts = c["counterparts"] |> Enum.map(&refs.users[&1]) |> Enum.reject(&is_nil/1)
    sources = c["source"] |> Enum.map(&refs.messages[&1]) |> Enum.reject(&is_nil/1) |> Enum.uniq()
    text = c["text"] |> to_string() |> String.trim()

    cond do
      owner == nil or not MapSet.member?(member_ids, owner) ->
        nil

      text == "" ->
        nil

      sources == [] or not Enum.any?(sources, &MapSet.member?(new_ids, &1)) ->
        nil

      not Enum.any?(sources, &(senders[&1] == owner)) ->
        nil

      (c["confidence"] || 0) < min_confidence() ->
        nil

      true ->
        tz = Map.get(tzs, owner, Clock.default_tz())
        {due, kind} = Clock.parse_due(c["due_local"], tz)

        %{
          text: String.slice(text, 0, 200),
          owner: owner,
          counterparts:
            counterparts
            |> Enum.uniq()
            |> Enum.reject(&(&1 == owner))
            |> Enum.filter(&MapSet.member?(member_ids, &1)),
          due: due,
          due_kind: kind,
          due_text: c["due_text"] && String.slice(c["due_text"], 0, 100),
          source_message_ids: sources,
          confidence: c["confidence"] / 1
        }
    end
  end

  defp min_confidence, do: Application.get_env(:risime, :risi_min_confidence, 0.6)

  defp propose(conv, c, call_ref, members) do
    seen? =
      Repo.exists?(
        from x in Commitment,
          where:
            x.conversation_id == ^conv and
              fragment("? && ?::varchar[]", x.source_message_ids, ^c.source_message_ids)
      )

    cond do
      seen? ->
        :ok

      RateLimiter.hit_if_allowed(:risi_cards, conv, @cards_per_day, :timer.hours(24)) != :ok ->
        Logger.info("Risi card limit reached in #{conv}")

      true ->
        new_proposal(conv, c, call_ref, members)
    end
  end

  defp new_proposal(conv, c, call_ref, members) do
    now = DateTime.utc_now()

    sealed =
      Commitment.seal(%Commitment{
        id: Ecto.UUID.generate(),
        conversation_id: conv,
        chat_id: Secretary.chat_id(conv),
        state: "proposed",
        text: c.text,
        owner_id: c.owner,
        counterpart_ids: c.counterparts,
        due: c.due,
        due_kind: c.due_kind,
        due_text: c.due_text,
        source_message_ids: c.source_message_ids,
        confidence: c.confidence,
        call_ref: call_ref,
        proposed_at: now
      })

    case sealed do
      # No data key: nothing can be kept sealed, so nothing is proposed.
      :error ->
        Logger.warning("Risi commitment not proposed in #{conv}: missing_key RISI_DATA_KEY")

      {:ok, row} ->
        row = Repo.insert!(row)
        post_proposal(conv, row, call_ref, members)
    end
  end

  @doc """
  v1.27 §27.3 owner fallback: a summary item of an owner without a `risi_ledger` device (its row
  already stored, `item_state` nil) gets the v1.24 card in Official and the v1.24 rules. Over
  the 10-a-day card limit the row is deleted. True when the card went out and the row is kept.
  """
  def legacy_card(conv, %Commitment{} = row, call_ref) do
    if RateLimiter.hit_if_allowed(:risi_cards, conv, @cards_per_day, :timer.hours(24)) == :ok do
      post_proposal(conv, row, call_ref, Secretary.members(conv))
      Repo.get(Commitment, row.id) != nil
    else
      Logger.info("Risi card limit reached in #{conv}")
      Repo.delete!(row)
      false
    end
  end

  defp post_proposal(conv, row, call_ref, members) do
    names = Map.new(members, &{&1.user_id, &1.name})

    body =
      "#{names[row.owner_id]} will: #{row.text}" <>
        if(row.due_text, do: " (#{row.due_text})", else: "") <> ". Track it?"

    risi = %{
      "kind" => "commitment",
      "commitment_id" => row.id,
      "state" => "proposed",
      "text" => row.text,
      "owner" => row.owner_id,
      "counterpart" => row.counterpart_ids,
      "due" => Clock.ts(row.due),
      "due_text" => row.due_text,
      "source_message_ids" => row.source_message_ids,
      "confidence" => row.confidence,
      "call_ref" => call_ref,
      "notify" => [row.owner_id | row.counterpart_ids]
    }

    case Out.post(conv, body, risi) do
      {:ok, %{message_id: mid}} ->
        row |> Ecto.Changeset.change(card_message_id: mid) |> Repo.update!()
        timer(%{"kind" => "expire", "conv" => conv, "commitment_id" => row.id}, @expire_s)

      {:error, reason} ->
        # No card, no proposal: nothing is kept.
        Logger.warning("Risi card not sent in #{conv}: #{inspect(reason)}")
        Repo.delete!(row)
    end
  end

  ## The extraction lease and cursor (risi_chat_state)

  defp lease(conv) do
    now = DateTime.utc_now()
    until = DateTime.add(now, @lease_s, :second)

    Repo.insert_all(
      "risi_chat_state",
      [%{conversation_id: conv, inserted_at: now, updated_at: now}],
      on_conflict: :nothing,
      conflict_target: [:conversation_id]
    )

    {n, rows} =
      Repo.update_all(
        from(s in "risi_chat_state",
          where:
            s.conversation_id == ^conv and
              (is_nil(s.extracting_until) or s.extracting_until < ^now),
          select: s.extracted_upto
        ),
        set: [extracting_until: until, updated_at: now]
      )

    if n == 1, do: {:ok, hd(rows)}, else: :busy
  end

  defp release(conv),
    do:
      Repo.update_all(from(s in "risi_chat_state", where: s.conversation_id == ^conv),
        set: [extracting_until: nil]
      )

  defp set_upto(conv, id),
    do:
      Repo.update_all(from(s in "risi_chat_state", where: s.conversation_id == ^conv),
        set: [extracted_upto: id, updated_at: DateTime.utc_now()]
      )

  ## Actions (risi_action, §24.11)

  @doc """
  Handles one `risi_action` envelope from `user` (already checked: an active human member of
  `conv`). Only the owner or a counterpart may act; others are ignored. Oban result.
  """
  def act(conv, user, %{"target" => target, "action" => action} = env) do
    with {:ok, _} <- Ecto.UUID.cast(target),
         %Commitment{} = c <- Repo.get(Commitment, target),
         true <- c.conversation_id == conv,
         # v1.27 §27.5: a v1.24 action on a ledger item is ignored (those go to the Ledger).
         false <- Commitment.ledger?(c),
         true <- user == c.owner_id or user in c.counterpart_ids,
         {:ok, c} <- open(c) do
      do_act(action, c, user, env["edit"])
    else
      _ -> :ok
    end
  end

  def act(_conv, _user, _env), do: :ok

  # A sealed row opened; without the data key (or with a wrong one) the commitment is
  # unavailable: the action, reminder or digest is skipped (never a crash, never plaintext).
  defp open(c) do
    case Commitment.open(c) do
      {:ok, c} ->
        {:ok, c}

      :error ->
        Logger.warning("Risi commitment unavailable: missing_key or wrong RISI_DATA_KEY")
        :unavailable
    end
  end

  defp do_act("confirm", %Commitment{state: "proposed"} = c, user, _edit) do
    c =
      c
      |> Ecto.Changeset.change(
        state: "confirmed",
        by: user,
        confirmed_at: DateTime.utc_now(),
        schedule_v: c.schedule_v + 1
      )
      |> Repo.update!()

    cancel_timers(c.id)
    schedule(c)
    learn(c)
    update_card(c, "confirmed", user)
  end

  defp do_act("decline", %Commitment{state: state} = c, user, _edit)
       when state in ~w(proposed confirmed edited) do
    # ✗ on a proposal: declined; on a tracked one: cancelled. Either way nothing is kept.
    cancel_timers(c.id)
    Repo.delete!(c)
    update_card(c, if(state == "proposed", do: "declined", else: "cancelled"), user)
  end

  defp do_act("edit", %Commitment{state: state} = c, user, %{} = edit)
       when state in ~w(proposed confirmed edited) do
    text = edit["text"]
    text = if is_binary(text), do: String.trim(text), else: c.text

    {due, kind} =
      case Map.fetch(edit, "due") do
        {:ok, nil} ->
          {nil, nil}

        {:ok, s} when is_binary(s) ->
          case DateTime.from_iso8601(s) do
            {:ok, dt, _} -> {%{dt | microsecond: {elem(dt.microsecond, 0), 6}}, "datetime"}
            _ -> {c.due, c.due_kind}
          end

        :error ->
          {c.due, c.due_kind}
      end

    due_text = if(due == c.due, do: c.due_text, else: nil)

    with false <- text == "" or String.length(text) > 200,
         {:ok, sealed} <- Commitment.sealed_changes(c.id, text, due_text) do
      c =
        c
        |> Ecto.Changeset.change(
          [
            state: "edited",
            due: due,
            due_kind: kind,
            by: user,
            confirmed_at: c.confirmed_at || DateTime.utc_now(),
            schedule_v: c.schedule_v + 1
          ] ++ sealed
        )
        |> Repo.update!()

      cancel_timers(c.id)
      schedule(c)
      Repo.delete_all(from f in Fact, where: f.commitment_id == ^c.id)
      learn(c)
      update_card(c, "edited", user)
    else
      _ -> :ok
    end
  end

  defp do_act("done", %Commitment{state: state} = c, user, _edit)
       when state in ~w(confirmed edited) do
    c =
      c
      |> Ecto.Changeset.change(state: "done", by: user, schedule_v: c.schedule_v + 1)
      |> Repo.update!()

    cancel_timers(c.id)
    update_card(c, "done", user)
  end

  # offer_yes / offer_not_now: stage 1 sends no offers. Anything else: ignored.
  defp do_act(_action, _c, _user, _edit), do: :ok

  defp update_card(c, state, by) do
    names = names(c.conversation_id)
    tz = Clock.user_tz(c.owner_id)
    due = Clock.human(c.due, c.due_kind, tz)
    who = names[by] || "A member"

    body =
      case state do
        "confirmed" -> "#{who} confirmed: #{c.text}#{due_suffix(due)}."
        "edited" -> "#{who} changed it to: #{c.text}#{due_suffix(due)}."
        "declined" -> "#{who} declined: #{c.text}. I won't track it."
        "cancelled" -> "#{who} cancelled: #{c.text}. I've stopped tracking it."
        "done" -> "#{who} marked done: #{c.text}."
      end

    risi = %{
      "kind" => "commitment_update",
      "commitment_id" => c.id,
      "state" => state,
      "by" => by,
      "text" => c.text,
      "due" => Clock.ts(c.due)
    }

    case Out.post(c.conversation_id, body, risi) do
      {:ok, _} -> RisiMe.Agent.LedgerOut.legacy_update(c, state, by)
      {:error, :rate_limited} -> {:snooze, 10}
      {:error, reason} -> {:error, reason}
    end
  end

  defp due_suffix(nil), do: ""
  defp due_suffix(due), do: " by #{due}"

  defp names(conv), do: Map.new(Secretary.members(conv), &{&1.user_id, &1.name})

  # Facts (§24.12) are written only for a tracked commitment: one for the owner and one for
  # each counterpart. Derived lines only; embeddings when a model is served.
  @doc false
  def learn(c) do
    names = names(c.conversation_id)
    owner = names[c.owner_id] || "A member"
    now = DateTime.utc_now()

    rows =
      [{c.owner_id, c.text}] ++
        for(u <- c.counterpart_ids, do: {u, "#{owner} will: #{c.text}"})

    for {subject, text} <- rows do
      fact = %Fact{
        id: Ecto.UUID.generate(),
        subject_user_id: subject,
        chat_id: c.chat_id,
        conversation_id: c.conversation_id,
        kind: "commitment",
        text: String.slice(text, 0, 400),
        source_message_ids: c.source_message_ids,
        commitment_id: c.id,
        inserted_at: now
      }

      # Sealed at rest; without the data key nothing is learned (never plaintext).
      with {:ok, fact} <- Fact.seal(fact), do: fact |> Repo.insert!() |> Embeddings.index()
    end

    :ok
  end

  ## Timers

  defp schedule(%Commitment{due: nil}), do: :ok

  defp schedule(%Commitment{} = c) do
    now = DateTime.utc_now()
    tz = Clock.user_tz(c.owner_id)
    base = %{"conv" => c.conversation_id, "commitment_id" => c.id, "v" => c.schedule_v}

    case Clock.remind_at(c.due, c.due_kind, tz) do
      nil ->
        :ok

      at ->
        if DateTime.compare(c.due, now) == :gt,
          do: timer_at(Map.put(base, "kind", "reminder"), latest(at, now))
    end

    for n <- 1..2 do
      at = DateTime.add(c.due, n * 86_400, :second)

      if DateTime.compare(at, now) == :gt,
        do: timer_at(Map.merge(base, %{"kind" => "escalation", "n" => n}), at)
    end

    :ok
  end

  defp latest(a, b), do: if(DateTime.compare(a, b) == :gt, do: a, else: b)

  defp timer(args, in_s),
    do: args |> Job.new(queue: :risi_timers, schedule_in: in_s) |> Oban.insert!()

  defp timer_at(args, at),
    do: args |> Job.new(queue: :risi_timers, scheduled_at: at) |> Oban.insert!()

  @doc "Cancels the pending timer jobs of one commitment."
  def cancel_timers(id) do
    Oban.cancel_all_jobs(
      from j in Oban.Job,
        where:
          j.worker == "RisiMe.Workers.Risi" and
            j.state in ["available", "scheduled", "retryable"] and
            fragment("?->>'commitment_id' = ?", j.args, ^id) and
            fragment("?->>'kind' <> 'expire'", j.args)
    )

    :ok
  end

  @doc "A proposal nobody confirmed within 48 h is deleted (nothing tracked without ✓)."
  def expire(id) do
    Repo.delete_all(from c in Commitment, where: c.id == ^id and c.state == "proposed")
    :ok
  end

  @doc "The reminder (§24.11 `reminder`, notifying the owner)."
  def remind(id, v) do
    with %Commitment{} = c <- current(id, v) do
      tz = Clock.user_tz(c.owner_id)
      due = Clock.human(c.due, c.due_kind, tz)

      body =
        if c.due_kind == "date",
          do: "Reminder: #{c.text} (due today, #{due}).",
          else: "Reminder: #{c.text} (due in 1 hour, #{due})."

      post(c, body, %{
        "kind" => "reminder",
        "commitment_id" => c.id,
        "due" => Clock.ts(c.due),
        "notify" => [c.owner_id]
      })
    end
  end

  @doc "Escalation `n` (1 or 2) to the counterparts, 24 h apart after due."
  def escalate(id, v, _n) do
    with %Commitment{counterpart_ids: [_ | _]} = c <- current(id, v) do
      overdue = max(DateTime.diff(DateTime.utc_now(), c.due, :second), 0)
      days = max(div(overdue + 3600, 86_400), 1)
      owner = names(c.conversation_id)[c.owner_id] || "the owner"

      post(
        c,
        "#{c.text} (#{owner}) is #{days} #{if days == 1, do: "day", else: "days"} overdue.",
        %{
          "kind" => "escalation",
          "commitment_id" => c.id,
          "overdue_by" => overdue,
          "notify" => c.counterpart_ids
        }
      )
    else
      _ -> :ok
    end
  end

  defp current(id, v) do
    case Repo.get(Commitment, id) do
      %Commitment{schedule_v: ^v} = c ->
        with true <- Commitment.open?(c), {:ok, c} <- open(c), do: c, else: (_ -> :ok)

      _ ->
        :ok
    end
  end

  defp post(c, body, risi) do
    case Out.post(c.conversation_id, body, risi) do
      {:ok, _} -> :ok
      {:error, :rate_limited} -> {:snooze, 10}
      {:error, :not_member} -> :ok
      {:error, :private_tab} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  ## The daily digest (§24.11)

  @doc """
  The digest sweep (every 15 min): every Official conversation with open items whose chat clock
  reads 09:xx and that had no digest today gets one. `now` for tests.
  """
  def digest_sweep(now \\ DateTime.utc_now()) do
    convs =
      Repo.all(
        from c in Commitment,
          where: c.state in ^Commitment.open_states() and is_nil(c.item_state),
          distinct: true,
          select: c.conversation_id
      )

    for conv <- convs, RisiMe.Agent.may_act?(conv), do: digest(conv, now)
    :ok
  end

  @doc """
  v1.27 §27.6: with the ledger on, the group digest is no longer posted in an Official
  conversation whose human members all have a `risi_ledger` device (they get personal digests);
  otherwise it continues for that chat's legacy cards only.
  """
  def group_digest_retired?(conv) do
    humans = Enum.map(Secretary.members(conv), & &1.user_id)

    RisiMe.Risi.ledger_on?() and humans != [] and
      length(RisiMe.Devices.risi_ledger_users(humans)) == length(humans)
  end

  @doc "Sends `conv`'s digest if its local time is 09:xx and none was sent today."
  def digest(conv, now \\ DateTime.utc_now()) do
    tz = Secretary.chat_tz(conv)
    local = Clock.local(now, tz)
    today = NaiveDateTime.to_date(local)

    # Sealed rows that can't be opened (no or a wrong data key): no digest.
    items =
      Repo.all(
        from c in Commitment,
          where:
            c.conversation_id == ^conv and c.state in ^Commitment.open_states() and
              is_nil(c.item_state),
          order_by: [asc_nulls_last: c.due, asc: c.inserted_at]
      )
      |> Commitment.open_all()
      |> case do
        {:ok, items} -> items
        :error -> []
      end

    last =
      Repo.one(
        from s in "risi_chat_state", where: s.conversation_id == ^conv, select: s.last_digest_on
      )

    if local.hour == 9 and items != [] and last != today and not group_digest_retired?(conv) do
      names = names(conv)

      lines =
        Enum.map_join(items, "; ", fn c ->
          due = Clock.human(c.due, c.due_kind, tz)
          "#{c.text} (#{names[c.owner_id] || "a member"}#{if due, do: ", due #{due}", else: ""})"
        end)

      risi = %{
        "kind" => "digest",
        "date" => Date.to_iso8601(today),
        "items" =>
          for c <- items do
            %{
              "commitment_id" => c.id,
              "text" => c.text,
              "owner" => c.owner_id,
              "due" => Clock.ts(c.due),
              "state" => c.state
            }
          end
      }

      case Out.post(conv, "Open items today: #{lines}.", risi) do
        {:ok, _} ->
          ts = DateTime.utc_now()

          Repo.insert_all(
            "risi_chat_state",
            [%{conversation_id: conv, last_digest_on: today, inserted_at: ts, updated_at: ts}],
            on_conflict: [set: [last_digest_on: today, updated_at: ts]],
            conflict_target: [:conversation_id]
          )

          :sent

        {:error, reason} ->
          Logger.warning("Risi digest not sent in #{conv}: #{inspect(reason)}")
          :error
      end
    else
      :skipped
    end
  end
end
