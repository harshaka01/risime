defmodule RisiMe.Agent.MadeBy do
  @moduledoc """
  `risi.made_by` (contract v1.27 §27.1): what made a Risi message, from the learning-log row of
  its `call_ref` (§24.12).

      %{"model" => str | nil, "provider" => str, "at" => ts, "also" => [%{model, provider, task}]}

  * **`model`:** the alias for our own model (`"risi-l1"`), the provider's model id for a
    commercial one; `nil` for a message no model made (reminders, digests, updates, cards the
    server builds itself).
  * **`provider`:** `"risime"` for our own models and rules, else the commercial provider's
    display name (`RISI_COMMERCIAL_PROVIDER_NAME`, else the learning log's provider name).
  * **`at`:** the learning-log row's completion time (its `call_id` TimeUUID is minted when the
    call completes), or the moment Risi built a rule message (`RisiMe.Agent.Clock.now/0`).
  * **`also`:** other models the content rests on (earlier steps of the same turn, the speech
    model of a call), in the order they ran, without repeats and without the main model.

  `RisiMe.Agent.Out.post/4` adds it to every Risi envelope; a caller may pass its own.
  """
  require Logger

  alias RisiMe.Agent.{Clock, LearningLog}

  @own "risime"
  @local_providers ["risi_l1"]
  @local_alias "risi-l1"

  # §27.1: kinds no model makes, even when a `call_ref` (for feedback) rides along.
  @rule_kinds ~w(reminder escalation digest commitment_update error item_due item_overdue
                 item_nudge item_update call_listen reminder_set skill_done skill_needed)

  @doc "True for a `risi.kind` that is always rule-made (§27.1 `model: null`)."
  def rule_kind?(kind), do: kind in @rule_kinds

  @doc "`made_by` of a rule-made message (no model), made now."
  def rule(at \\ nil), do: %{"model" => nil, "provider" => @own, "at" => ts(at), "also" => []}

  @doc """
  `made_by` of a message whose content came from the call `call_ref` (nil: a rule message),
  resting also on the calls `also_refs` (call refs, in the order they ran) and the extra `also`
  entries given (e.g. the speech model of a call).
  """
  def build(call_ref, also_refs \\ [], extra \\ [])

  def build(nil, _also_refs, extra), do: Map.put(rule(), "also", dedupe(extra, nil))

  def build(call_ref, also_refs, extra) do
    main = entry(call_ref)

    others =
      also_refs
      |> Enum.reject(&(&1 == call_ref or is_nil(&1)))
      |> Enum.map(&entry/1)
      |> Enum.map(&Map.take(&1, ["model", "provider", "task"]))

    main
    |> Map.take(["model", "provider", "at"])
    |> Map.put("also", dedupe(others ++ extra, main))
  end

  # Without repeats, and without the main model itself.
  defp dedupe(list, main) do
    list
    |> Enum.reject(&same?(&1, main))
    |> Enum.reject(&is_nil(&1["model"]))
    |> Enum.uniq_by(&{&1["model"], &1["provider"]})
  end

  defp same?(_e, nil), do: false
  defp same?(e, main), do: e["model"] == main["model"] and e["provider"] == main["provider"]

  @doc "One learning-log row as `%{model, provider, at, task}`."
  def entry(call_ref) do
    case safe_get(call_ref) do
      {:ok, row} ->
        of_row(row, call_ref)

      _ ->
        # The row is missing (its write failed): a model still made it, never hidden. Every
        # call goes to our own model unless the fallback ran, which the row would say.
        Logger.warning("Risi made_by: no learning-log row for a call_ref")

        %{
          "model" => @local_alias,
          "provider" => @own,
          "at" => at_of(call_ref),
          "task" => nil
        }
    end
  end

  defp of_row(row, call_ref) do
    {model, provider} =
      if row[:provider] in @local_providers or row[:provider] == nil,
        do: {row[:model_alias] || @local_alias, @own},
        else:
          {row[:model] || row[:model_alias],
           Application.get_env(:risime, :risi_commercial_provider_name) || row[:provider]}

    %{"model" => model, "provider" => provider, "at" => at_of(call_ref), "task" => row[:task]}
  end

  defp safe_get(call_ref) do
    LearningLog.get(call_ref)
  rescue
    _ -> :not_found
  end

  defp at_of(call_ref) do
    call_ref |> RisiMe.TimeUUID.to_datetime() |> Clock.ts()
  rescue
    _ -> ts(nil)
  end

  defp ts(nil), do: Clock.ts(Clock.now())
  defp ts(%DateTime{} = at), do: Clock.ts(at)
end
