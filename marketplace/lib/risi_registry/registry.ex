defmodule RisiRegistry.Registry do
  @moduledoc """
  The registry: records, status, delisting and the per-call check.

  * `check/1` is what a caller runs **before every call** (agent-connector.md section 4 and
    review change 9): status is read from the database each time; the card's signature and hash
    are re-checked through a 5-minute cache. A card whose content changed drops the agent to
    `unverified` (with an audit row) and the check fails, so no call is made.
  * `set_status/4` is the one switch (section 12). Every change writes an audit row with who and
    why, and takes effect on the next check.
  """
  import Ecto.Query
  alias Ecto.Multi
  alias RisiRegistry.{Audit, Repo}
  alias RisiRegistry.Cards.{Cache, Verifier}
  alias RisiRegistry.Registry.Agent

  # Allowed status moves. Leaving `delisted` goes back to `proposed`: a person must approve the
  # card and activate the agent again before anyone is asked about it.
  @transitions %{
    "proposed" => ~w(active delisted),
    "active" => ~w(suspended delisted),
    "suspended" => ~w(active delisted),
    "delisted" => ~w(proposed)
  }

  ## Records

  def register(attrs) do
    attrs |> Agent.register_changeset() |> Repo.insert()
  end

  def get(agent_id), do: Repo.get_by(Agent, agent_id: agent_id)

  def list_all do
    Agent |> order_by([a], asc: a.tier, asc: a.agent_id) |> Repo.all()
  end

  @doc """
  Discovery (section 6): only active, verified agents. Filters: `skill` (an id in `skills[]`),
  `tier`, and `q` (a case-insensitive match on name or description).
  """
  def discover(filters \\ %{}) do
    filters = Map.new(filters, fn {k, v} -> {to_string(k), v} end)

    Agent
    |> where([a], a.status == "active" and a.verification == "verified")
    |> filter_tier(filters["tier"])
    |> filter_text(filters["q"])
    |> order_by([a], asc: a.tier, asc: a.agent_id)
    |> limit(50)
    |> Repo.all()
    |> filter_skill(filters["skill"])
  end

  defp filter_tier(q, tier) when tier in ["official", "verified", "listed"],
    do: where(q, [a], a.tier == ^tier)

  defp filter_tier(q, _), do: q

  defp filter_text(q, text) when is_binary(text) and text != "" do
    pattern = "%" <> String.replace(String.slice(text, 0, 100), ~r/[\\%_]/, "\\\\\\0") <> "%"
    where(q, [a], ilike(a.name, ^pattern) or ilike(a.description, ^pattern))
  end

  defp filter_text(q, _), do: q

  defp filter_skill(agents, skill) when is_binary(skill) and skill != "" do
    Enum.filter(agents, fn a -> Enum.any?(a.skills, &(&1["id"] == skill)) end)
  end

  defp filter_skill(agents, _), do: agents

  ## Status

  @doc "Moves `agent_id` to `status`, writing an audit row with `actor` and `reason`."
  def set_status(agent_id, status, actor, reason)
      when is_binary(actor) and actor != "" and is_binary(reason) and reason != "" do
    with %Agent{} = agent <- get(agent_id) || {:error, :not_found},
         :ok <- check_transition(agent, status) do
      Multi.new()
      |> Multi.update(:agent, Ecto.Changeset.change(agent, status: status))
      |> Multi.run(:audit, fn _repo, _ ->
        {:ok,
         Audit.record("status_change", %{
           actor: actor,
           agent_id: agent_id,
           result_code: "#{agent.status}->#{status}",
           reason: reason
         })}
      end)
      |> Repo.transaction()
      |> case do
        {:ok, %{agent: updated}} ->
          Cache.invalidate(agent_id)
          {:ok, updated}

        {:error, _step, reason, _} ->
          {:error, reason}
      end
    end
  end

  def set_status(_agent_id, _status, _actor, _reason), do: {:error, :actor_and_reason_required}

  @doc "Delisting (section 12): one switch, an audit row with who and why."
  def delist(agent_id, actor, reason), do: set_status(agent_id, "delisted", actor, reason)
  def suspend(agent_id, actor, reason), do: set_status(agent_id, "suspended", actor, reason)

  defp check_transition(%Agent{status: from} = agent, to) do
    cond do
      to not in Map.get(@transitions, from, []) ->
        {:error, {:bad_transition, from, to}}

      to == "active" and (agent.verification != "verified" or is_nil(agent.card_hash)) ->
        {:error, :card_not_approved}

      true ->
        :ok
    end
  end

  ## Card approval

  @doc """
  A person approves the agent's current card: the signature must verify against one of the
  owner's keys; its hash becomes `card_hash` and the agent becomes `verified`. Skills come from
  the card, scopes and data classes from its `x-risi` block.
  """
  def approve_card(agent_id, actor, reason) when is_binary(actor) and actor != "" do
    with %Agent{} = agent <- get(agent_id) || {:error, :not_found},
         {:ok, %{card: card, card_hash: hash, kid: kid}} <-
           Verifier.fetch_and_verify(agent, expected_hash: :any) do
      changes =
        %{card_hash: hash, key_id: kid, verification: "verified"}
        |> Map.merge(from_card(card))

      Multi.new()
      |> Multi.update(:agent, agent |> Ecto.Changeset.change(changes) |> Agent.validate_common())
      |> Multi.run(:audit, fn _repo, _ ->
        {:ok,
         Audit.record("card_approved", %{
           actor: actor,
           agent_id: agent_id,
           result_code: "ok",
           reason: reason
         })}
      end)
      |> Repo.transaction()
      |> case do
        {:ok, %{agent: updated}} ->
          Cache.invalidate(agent_id)
          {:ok, updated}

        {:error, _step, reason, _} ->
          {:error, reason}
      end
    end
  end

  defp from_card(card) do
    xrisi = if is_map(card["x-risi"]), do: card["x-risi"], else: %{}

    skills =
      for %{"id" => id} = s <- List.wrap(card["skills"]), is_binary(id) do
        %{"id" => id, "description" => s["description"] |> to_string() |> String.slice(0, 200)}
      end

    scopes =
      for %{"id" => id, "label" => label} <- List.wrap(xrisi["scopes"]),
          is_binary(id) and is_binary(label) do
        %{"id" => id, "label" => String.slice(label, 0, 200)}
      end

    %{
      skills: skills,
      scopes: scopes,
      data_classes: Enum.filter(List.wrap(xrisi["data_classes"]), &is_binary/1),
      consequential: xrisi["consequential"] == true
    }
  end

  ## The per-call check

  @doc """
  Runs before every call. Returns `{:ok, %{...}}` only for an active agent whose card still
  verifies; otherwise `{:error, reason}`. Never returns card content.
  """
  def check(agent_id) do
    case get(agent_id) do
      nil ->
        {:error, :not_found}

      %Agent{status: status} when status != "active" ->
        {:error, {:not_active, status}}

      %Agent{verification: "unverified"} ->
        {:error, :unverified}

      %Agent{} = agent ->
        case Cache.fetch(agent.agent_id, agent.card_hash, fn ->
               Verifier.fetch_and_verify(agent)
             end) do
          {:ok, verified, source} ->
            maybe_note_rotation(agent, verified.kid)

            {:ok,
             %{agent_id: agent.agent_id, status: "active", verification: "verified", card: source}}

          {:error, reason} ->
            mark_unverified(agent, reason)
            {:error, :unverified}
        end
    end
  end

  # A card re-signed by another listed key with the same content stays verified; record the
  # new key id and say so in the audit.
  defp maybe_note_rotation(%Agent{key_id: kid}, kid), do: :ok

  defp maybe_note_rotation(%Agent{} = agent, new_kid) do
    {1, _} =
      Agent
      |> where([a], a.id == ^agent.id)
      |> Repo.update_all(set: [key_id: new_kid, updated_at: DateTime.utc_now()])

    Audit.record("key_rotated", %{actor: "system", agent_id: agent.agent_id, result_code: "ok"})
    :ok
  end

  # Transient fetch failures do not change the record; a card that no longer matches does.
  @transient [:fetch_failed]
  defp mark_unverified(agent, reason) do
    code = reason_code(reason)

    unless reason in @transient or match?({:http_status, s} when s >= 500, reason) do
      Agent
      |> where([a], a.id == ^agent.id)
      |> Repo.update_all(set: [verification: "unverified", updated_at: DateTime.utc_now()])
    end

    Audit.record("card_unverified", %{
      actor: "system",
      agent_id: agent.agent_id,
      result_code: code
    })
  end

  defp reason_code({tag, value}), do: "#{tag}:#{value}"
  defp reason_code(reason), do: to_string(reason)
end
