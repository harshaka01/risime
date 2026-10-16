defmodule RisiMe.Agent.CallRisi.Escalate do
  @moduledoc """
  The call brain's **low-confidence escalation path** (architect 19:15/19:20 SLT, D-001: Harsha's
  "C … but after, if we fail tonight"). Built, **off**:

    * `RISI_BRAIN_ESCALATE` (`off | canary | on`, default **off**);
    * `config :risime, :risi_brain, escalate: [model: nil, below: 0.5, daily_cap: 200]`: **no model
      is set** (no paid provider: that needs D-001 and Harsha's own key in `.env`). With no
      model the path never calls anything, whatever the flag says.

  When the flag is on for the caller, a model is set, the local decision's `confidence` is below
  `below` and today's count is under `daily_cap`, the decision is asked again of that model and
  its answer is used. **Every escalated decision and every correction** the caller makes on a
  call (a recipient corrected, a card cancelled or changed) is stored as a **training example**:
  a learning-log row, task `voice_call_training`, with labels only (the local and final tool and
  action, the confidence, the kind), never the words (the learning log's rule: input = the prompt's
  hash, output = what was decided).
  """

  alias RisiMe.Agent.CallRisi.Decider

  @flag "RISI_BRAIN_ESCALATE"
  @table :risi_brain_escalate

  defp cfg, do: Keyword.get(Decider.config(), :escalate, [])

  @doc "The escalation model alias (nil: none configured, the path is closed)."
  def model, do: Keyword.get(cfg(), :model)

  @doc "The confidence below which a decision escalates."
  def below, do: Keyword.get(cfg(), :below, 0.5)

  @doc "The most escalations a day (all callers)."
  def daily_cap, do: Keyword.get(cfg(), :daily_cap, 200)

  @doc "True when the flag is on for `user` (canary = RISI_DEV_USERS)."
  def on_for?(user) do
    case RisiMe.Agents.Flags.feature(@flag) do
      :on -> true
      :canary -> RisiMe.Agents.Flags.dev_user?(user)
      _ -> false
    end
  rescue
    _ -> false
  end

  @doc """
  Whether this decision goes to the stronger model: the flag, a model, a confidence below the
  line and today's cap. Counts the escalation when it says yes.
  """
  def escalate?(%{confidence: c}, ctx) when is_number(c) do
    is_binary(model()) and c < below() and on_for?(ctx[:user_id]) and take()
  end

  def escalate?(_dec, _ctx), do: false

  defp take do
    ensure()
    day = Date.utc_today()
    n = :ets.update_counter(@table, day, {2, 1}, {day, 0})
    n <= daily_cap()
  end

  @doc "Today's escalations so far."
  def count do
    ensure()

    case :ets.lookup(@table, Date.utc_today()) do
      [{_, n}] -> n
      _ -> 0
    end
  end

  defp ensure do
    if :ets.whereis(@table) == :undefined,
      do: :ets.new(@table, [:named_table, :public, :set, write_concurrency: true])

    :ok
  rescue
    ArgumentError -> :ok
  end

  @doc """
  One training example (labels only): `kind` "escalated" | "correction", `labels` a small map of
  tool/action/field names and numbers. Never the words.
  """
  def example(ctx, kind, labels) when kind in ["escalated", "correction"] do
    labels =
      labels
      |> Map.take(~w(local_tool local_action final_tool final_action confidence field)a)
      |> Map.new(fn {k, v} -> {Atom.to_string(k), v} end)

    RisiMe.Agent.LearningLog.record(%{
      call_id: RisiMe.TimeUUID.generate(),
      conversation_id: ctx[:conv] || "call",
      chat_id: ctx[:conv] || "call",
      task: "voice_call_training",
      model_alias: Decider.model(),
      provider: "risime",
      status: "ok",
      output: Jason.encode!(Map.put(labels, "kind", kind)),
      meta: %{"call_id" => ctx[:call_id]}
    })
  rescue
    _ -> :ok
  catch
    _, _ -> :ok
  end
end
