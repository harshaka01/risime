defmodule RisiMe.Agent.LedgerOut do
  @moduledoc """
  What the Commitment Ledger posts (contract v1.27 §27.3–§27.5), with `RISI_LEDGER=on`.

  * **Per person** (`publish/2`): every recipient R of a summary who has a `risi_ledger` device
    gets one `discussion_summary` **in R's own Risi chat** (§25.2), the same `summary_id` for
    all, `body` rendered in R's zone. R without an active Risi chat: the copy is **held**
    (`risi_followups_pending`, sealed with `RISI_DATA_KEY`) for up to 24 h and posted when the
    Risi chat becomes active (`deliver_pending/1`; also retried by the hourly prune); a copy
    that can't be posted now (Risi's send limit) is held the same way.
  * **Owner fallback:** an owner with no `risi_ledger` device gets the v1.24 in-group
    `commitment` card for their items (§24.11 rules, the 10-a-day card limit; a card over the
    limit is not kept); the other copies list those items read-only.
  * **The short card** (`discussion_card`) in the Official conversation, silent, with no items,
    owners or dues: posted whenever at least one copy went out. It replaces the in-group
    proposed card for ledger owners.
  * **`item_update`** (`item_update/3`) to the Risi chat of every `risi_ledger` recipient of the
    summary, after every accepted item action.

  Older apps never receive a copy as the addressee (§27.10).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, Commitment, Commitments, Discussion, Out, Seal, Secretary}
  alias RisiMe.{Devices, Repo, RisiChat}

  @hold_s 24 * 3600
  @table "risi_followups_pending"

  ## Publishing a summary

  @doc """
  Fans a stored summary out (see the module doc). `rows` are its proposed items (ledger items
  as stored). Returns the items that are kept (an owner-fallback card over the daily limit is
  dropped).
  """
  def publish(%Discussion{} = d, rows) do
    ledger = MapSet.new(Devices.risi_ledger_users(d.recipients))

    kept =
      Enum.filter(rows, fn r ->
        MapSet.member?(ledger, r.owner_id) or legacy_card(d, r)
      end)

    kept =
      Enum.map(kept, fn r ->
        if MapSet.member?(ledger, r.owner_id), do: r, else: %{r | item_state: nil}
      end)

    names = names(d.conversation_id)
    items = Enum.map(kept, &item_json/1)
    targets = Enum.filter(d.recipients, &MapSet.member?(ledger, &1))

    for r <- targets do
      {body, risi} = copy(d, items, r, names)
      deliver_or_hold(d, r, body, risi)
    end

    if targets != [] and items != [], do: card(d, length(items))
    kept
  end

  # §27.3 owner fallback: the v1.24 card in Official. False when it isn't kept.
  defp legacy_card(d, row) do
    row
    |> Ecto.Changeset.change(item_state: nil)
    |> Repo.update!()

    Commitments.legacy_card(d.conversation_id, %{row | item_state: nil}, d.call_ref)
  end

  @doc "An item of a summary as the wire `Item` (§27.3)."
  def item_json(%Commitment{} = c) do
    %{
      "item_id" => c.id,
      "owner" => c.owner_id,
      "counterpart" => c.counterpart_ids,
      "text" => c.text,
      "due" => Clock.ts(c.due),
      "all_day" => c.all_day == true,
      "due_text" => c.due_text,
      "state" => c.item_state || c.state
    }
  end

  defp names(conv), do: Map.new(Secretary.members(conv), &{&1.user_id, &1.name})

  @doc "The `discussion_summary` copy for recipient `r`: `{body, risi}`."
  def copy(%Discussion{} = d, items, r, names) do
    tz = Clock.user_tz(r)
    with_ids = Enum.reject(d.participants, &(&1 == r))

    risi = %{
      "kind" => "discussion_summary",
      "summary_id" => d.summary_id,
      "chat_id" => d.chat_id,
      "conversation_id" => d.conversation_id,
      "for" => r,
      "with" => with_ids,
      "started_at" => Clock.ts(d.started_at),
      "ended_at" => Clock.ts(d.ended_at),
      "source" => d.source,
      "call_id" => d.call_id,
      "media" => d.media,
      "duration_s" => d.duration_s,
      "key_points" => d.key_points,
      "items" => items,
      "expires_at" => Clock.ts(DateTime.add(d.created_at, RisiMe.Agent.Ledger.expire_s())),
      "call_ref" => d.call_ref,
      "made_by" => d.made_by,
      "notify" => [r]
    }

    {copy_body(d, items, r, with_ids, names, tz), risi}
  end

  defp copy_body(d, items, r, with_ids, names, tz) do
    header =
      case with_ids do
        [] -> "Summary of your discussion (#{when_part(d, tz)})"
        ids -> "Summary of your discussion with #{name_list(ids, names)} (#{when_part(d, tz)})"
      end

    {mine, others} = Enum.split_with(items, &(&1["owner"] == r))

    by_owner =
      others
      |> Enum.chunk_by(& &1["owner"])
      |> Enum.flat_map(fn [first | _] = chunk ->
        ["#{names[first["owner"]] || "A member"} agreed:" | Enum.map(chunk, &line(&1, tz))]
      end)

    ([header] ++
       Enum.map(d.key_points, &("• " <> &1)) ++
       if(mine == [], do: [], else: ["You agreed:" | Enum.map(mine, &line(&1, tz))]) ++
       by_owner ++
       if(mine == [], do: [], else: ["Confirm your items in RisiMe."]))
    |> Enum.join("\n")
  end

  defp line(i, tz), do: "• #{i["text"]}#{due_part(i, tz)}"

  defp due_part(%{"due_text" => t}, _tz) when is_binary(t) and t != "", do: " (#{t})"
  defp due_part(%{"due" => nil}, _tz), do: ""

  defp due_part(%{"due" => due, "all_day" => all_day}, tz) do
    {:ok, dt, _} = DateTime.from_iso8601(due)
    " (#{Clock.human(dt, if(all_day, do: "date", else: "datetime"), tz)})"
  end

  defp when_part(%Discussion{source: "call"} = d, tz) do
    kind = if d.media == "video", do: "video call", else: "voice call"
    min = div((d.duration_s || 0) + 30, 60)
    "#{hm(d.started_at, tz)}, #{kind} #{min} min"
  end

  defp when_part(d, tz) do
    {a, b} = {hm(d.started_at, tz), hm(d.ended_at, tz)}
    if a == b, do: "#{a}, chat", else: "#{a}–#{b}, chat"
  end

  defp hm(dt, tz), do: dt |> Clock.local(tz) |> Calendar.strftime("%H:%M")

  @doc "`A`, `A and B`, `A, B and C`."
  def name_list(ids, names) do
    case Enum.map(ids, &(names[&1] || "a member")) do
      [one] -> one
      list -> Enum.join(Enum.drop(list, -1), ", ") <> " and " <> List.last(list)
    end
  end

  defp card(d, n) do
    risi = %{
      "kind" => "discussion_card",
      "summary_id" => d.summary_id,
      "source" => d.source,
      "call_id" => d.call_id,
      "media" => d.media,
      "duration_s" => d.duration_s,
      "started_at" => Clock.ts(d.started_at),
      "ended_at" => Clock.ts(d.ended_at),
      "with" => d.participants,
      "summary" => d.summary,
      "items_count" => n,
      "call_ref" => d.call_ref,
      "made_by" => d.made_by,
      "notify" => []
    }

    items = if n == 1, do: "1 item", else: "#{n} items"
    body = "Risi summarised this discussion (#{items}). Details in your Risi chat."

    case Out.post(d.conversation_id, body, risi) do
      {:ok, _} -> :ok
      {:error, reason} -> Logger.warning("Risi discussion card not sent: #{inspect(reason)}")
    end
  end

  ## item_update (§27.5)

  @doc """
  After an accepted action on an item of summary `d`: `item_update` (silent, no model) in the
  Risi chat of every `risi_ledger` recipient of the summary, the actor's included. Oban result.
  """
  def item_update(%Commitment{} = c, %Discussion{} = d, state, by) do
    names = names(d.conversation_id)
    who = names[by] || "A member"
    text = c.text

    body =
      case state do
        "confirmed" -> "#{who} confirmed '#{text}'"
        "edited" -> "#{who} changed it to '#{text}'"
        "declined" -> "#{who} declined '#{text}'"
        "cancelled" -> "#{who} cancelled '#{text}'"
        "done" -> "#{who} marked '#{text}' done"
      end

    risi = %{
      "kind" => "item_update",
      "summary_id" => d.summary_id,
      "item_id" => c.id,
      "state" => state,
      "by" => by,
      "text" => text,
      "due" => Clock.ts(c.due),
      "all_day" => c.all_day == true,
      "call_ref" => nil,
      "notify" => []
    }

    result =
      for u <- Devices.risi_ledger_users(d.recipients), rc = RisiChat.active_id(u) do
        tz = Clock.user_tz(u)
        item = item_json(c)
        due = if state in ~w(confirmed edited), do: due_part(item, tz), else: ""

        case Out.post(rc, body <> due <> ".", risi) do
          {:ok, _} -> :ok
          {:error, :rate_limited} -> {:snooze, 10}
          {:error, _} -> :ok
        end
      end

    Enum.find(result, :ok, &match?({:snooze, _}, &1))
  end

  @doc "v1.27 §27.3: an owner-fallback (v1.24) card changed state: the copies hear about it."
  def legacy_update(%Commitment{summary_id: sid} = c, state, by) when is_binary(sid) do
    case Repo.get(Discussion, sid) do
      %Discussion{} = d when state in ~w(confirmed edited declined cancelled done) ->
        item_update(c, d, state, by)

      _ ->
        :ok
    end
  end

  def legacy_update(_c, _state, _by), do: :ok

  ## Copies: posted, or held for the recipient's Risi chat (24 h)

  defp deliver_or_hold(d, user, body, risi) do
    case post_personal(user, body, risi) do
      :ok -> :ok
      :hold -> hold(d, user, body, risi)
    end
  end

  # Into the user's own active Risi chat, or :hold.
  defp post_personal(user, body, risi) do
    case RisiChat.active_id(user) do
      nil ->
        :hold

      rc ->
        case Out.post(rc, body, risi) do
          {:ok, _} -> :ok
          {:error, _} -> :hold
        end
    end
  end

  defp hold(d, user, body, risi) do
    with {:ok, key} <- Seal.data() do
      sealed = Seal.seal(key, aad(d.summary_id, user), %{"body" => body, "risi" => risi})

      Repo.insert_all(
        @table,
        [
          %{
            summary_id: Ecto.UUID.dump!(d.summary_id),
            user_id: Ecto.UUID.dump!(user),
            conversation_id: d.conversation_id,
            envelope_sealed: sealed,
            expires_at: DateTime.add(Clock.usec(Clock.now()), @hold_s)
          }
        ],
        on_conflict: :nothing,
        conflict_target: [:summary_id, :user_id]
      )
    end

    :ok
  end

  defp aad(summary_id, user), do: "#{@table}:#{summary_id}:#{user}:envelope"

  @doc """
  Posts the user's held copies (not expired) into their now active Risi chat; each posted copy
  is deleted. Oban result.
  """
  def deliver_pending(user) do
    now = Clock.now()

    rows =
      Repo.all(
        from p in @table,
          where: p.user_id == type(^user, :binary_id) and p.expires_at > ^now,
          select: %{
            summary_id: type(p.summary_id, :binary_id),
            sealed: p.envelope_sealed
          }
      )

    with [_ | _] <- rows,
         rc when is_binary(rc) <- RisiChat.active_id(user),
         {:ok, key} <- Seal.data() do
      Enum.reduce_while(rows, :ok, fn r, :ok ->
        with {:ok, %{"body" => body, "risi" => risi}} <-
               Seal.open(key, aad(r.summary_id, user), r.sealed),
             {:ok, _} <- Out.post(rc, body, risi) do
          drop(r.summary_id, user)
          {:cont, :ok}
        else
          {:error, :rate_limited} -> {:halt, {:snooze, 10}}
          # Unreadable (another key): dropped, never plaintext.
          :error -> drop(r.summary_id, user) && {:cont, :ok}
          _ -> {:cont, :ok}
        end
      end)
    else
      _ -> :ok
    end
  end

  defp drop(summary_id, user) do
    Repo.delete_all(
      from p in @table,
        where:
          p.summary_id == type(^summary_id, :binary_id) and p.user_id == type(^user, :binary_id)
    )

    true
  end

  @doc "Hourly: expired held copies go; held copies of users whose Risi chat is active now are posted."
  def prune do
    now = Clock.now()
    Repo.delete_all(from p in @table, where: p.expires_at <= ^now)

    users =
      Repo.all(from p in @table, distinct: true, select: type(p.user_id, :binary_id))

    for u <- users, RisiChat.active?(u), do: deliver_pending(u)
    :ok
  end

  @doc "Deletes the held copies of a conversation's summaries (§24.4)."
  def forget(conv) do
    Repo.delete_all(from p in @table, where: p.conversation_id == ^conv)
    :ok
  end
end
