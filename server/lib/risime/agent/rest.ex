defmodule RisiMe.Agent.Rest do
  @moduledoc """
  The data behind `/api/v1/risi/*` (contract §24.11, decision 066): feedback on a model call,
  "what Risi knows about me" (facts) and "my promises" (commitments). Everything is scoped to the
  caller: facts whose subject is the caller, commitments where the caller is owner or
  counterpart, and only in conversations where the caller is still an active member. With RISI
  off the tables are simply empty, so the lists are `[]`. Fact and commitment texts are sealed
  at rest (`RISI_DATA_KEY`); when they can't be opened the lists answer `503 agent_unavailable`.
  """
  import Ecto.Query

  alias RisiMe.Agent.{Commitment, Fact, LearningLog, Secretary}
  alias RisiMe.{Messaging, Repo}

  @doc """
  Feedback from `user` on `call_ref`: only active human members of the conversation that holds
  the call may send it; anyone else (or an unknown call) gets `{:error, :not_found}`.
  """
  def feedback(user, call_ref, rating, reason) do
    with {:ok, call_ref} <- or_not_found(Ecto.UUID.cast(call_ref)),
         {:ok, %{conversation_id: conv}} <- or_not_found(LearningLog.get(call_ref)),
         true <- Secretary.active_human?(conv, user) || {:error, :not_found} do
      LearningLog.put_feedback(call_ref, user, rating, reason)
    end
  end

  defp or_not_found({:ok, _} = ok), do: ok
  defp or_not_found(_), do: {:error, :not_found}

  # Conversations (Official groups) the user is still an active member of.
  defp mine(user),
    do:
      from(m in RisiMe.Groups.Member,
        where: m.user_id == ^user and m.state == "active",
        select: m.group_id
      )

  @doc """
  The caller's facts, newest first, as wire maps: `{:ok, list}`, or `{:error,
  :agent_unavailable}` when they can't be opened (texts are sealed under `RISI_DATA_KEY`; no or
  a wrong key). No rows: `{:ok, []}` with or without a key.
  """
  def facts(user, device_id \\ nil) do
    with {:ok, facts} <- user_facts(user) do
      # 30-day summaries: the "Summaries" group, for apps that know the kind (risi_tools).
      if RisiMe.Devices.risi_tools_device?(user, device_id),
        do: {:ok, facts ++ RisiMe.Agent.DailySummaries.facts(user)},
        else: {:ok, facts}
    end
  end

  defp user_facts(user) do
    Repo.all(
      from f in Fact,
        where: f.subject_user_id == ^user and f.conversation_id in subquery(mine(user)),
        order_by: [desc: f.inserted_at, asc: f.id]
    )
    |> opened(&Fact.open_all/1)
    |> wire(fn f ->
      %{
        fact_id: f.id,
        kind: f.kind,
        text: f.text,
        chat_id: f.chat_id,
        created_at: Messaging.iso(f.inserted_at)
      }
    end)
  end

  @doc "Hard-deletes one of the caller's facts (the embedding goes with it, ON DELETE CASCADE)."
  def delete_fact(user, fact_id) do
    with {:ok, id} <- or_not_found(Ecto.UUID.cast(fact_id)),
         {1, _} <-
           Repo.delete_all(from f in Fact, where: f.id == ^id and f.subject_user_id == ^user) do
      :ok
    else
      # 30-day summaries: a summary of a chat the caller is a member of (for the chat).
      {0, _} ->
        {:ok, id} = Ecto.UUID.cast(fact_id)

        case RisiMe.Agent.DailySummaries.delete(user, id) do
          :ok -> :ok
          :not_found -> {:error, :not_found}
        end

      error ->
        error
    end
  end

  @doc "Hard-deletes every fact about the caller (in any chat, including ones they left)."
  def delete_facts(user) do
    Repo.delete_all(from f in Fact, where: f.subject_user_id == ^user)
    :ok
  end

  defp opened([], _open), do: {:ok, []}

  defp opened(rows, open) do
    case open.(rows) do
      {:ok, rows} ->
        {:ok, rows}

      :error ->
        require Logger
        Logger.warning("Risi data unavailable: missing_key or wrong RISI_DATA_KEY")
        {:error, :agent_unavailable}
    end
  end

  defp wire({:ok, rows}, fun), do: {:ok, Enum.map(rows, fun)}
  defp wire(error, _fun), do: error

  @doc """
  The caller's commitments (`:open` = confirmed or edited, or `:all`) as wire maps: `{:ok,
  list}` or `{:error, :agent_unavailable}` (like `facts/1`).
  """
  def commitments(user, state) do
    user
    |> query(state)
    |> Repo.all()
    |> opened(&Commitment.open_all/1)
    |> wire(fn c -> commitment_json(c, user) end)
  end

  @doc """
  The caller's open promises exactly as `GET /risi/commitments` lists them (the same query),
  opened: `{:ok, [Commitment]}` or `{:error, :agent_unavailable}`. The personal digest and the
  Risi chat's context use this (P0 2026-10-09: the digest must never disagree with My
  promises).
  """
  def open_commitments(user),
    do: user |> query(:open) |> Repo.all() |> opened(&Commitment.open_all/1)

  defp query(user, state) do
    q =
      from c in Commitment,
        where:
          (c.owner_id == ^user or ^user in c.counterpart_ids) and
            c.conversation_id in subquery(mine(user)),
        order_by: [asc_nulls_last: c.due, asc: c.inserted_at, asc: c.id]

    q = if state == :open, do: where(q, [c], c.state in ^Commitment.open_states()), else: q

    # v1.27 §27.5: a ledger item is a promise only once its owner confirmed it; until then it
    # is nobody's (counterparts hear about it only after the owner's ✓).
    where(q, [c], is_nil(c.item_state) or c.item_state != "proposed")
  end

  # v1.27 §27.9: ledger items gain summary_id, source, all_day and the caller's role.
  defp commitment_json(c, user) do
    base = commitment_json(c)

    if Commitment.ledger?(c),
      do:
        Map.merge(base, %{
          summary_id: c.summary_id,
          source: c.source,
          all_day: c.all_day == true,
          role: if(c.owner_id == user, do: "owner", else: "counterpart")
        }),
      else: base
  end

  defp commitment_json(c) do
    %{
      commitment_id: c.id,
      chat_id: c.chat_id,
      official_conversation_id: c.conversation_id,
      state: c.state,
      text: c.text,
      owner: c.owner_id,
      counterpart: c.counterpart_ids,
      due: c.due && Messaging.iso(c.due),
      due_text: c.due_text,
      created_at: Messaging.iso(c.inserted_at),
      updated_at: Messaging.iso(c.updated_at)
    }
  end
end
