defmodule RisiMe.Agent.CallRisi.EscalateTest do
  @moduledoc """
  D-001 (architect 19:15/19:20 SLT): the call brain's confidence, its config slot `:risi_brain`
  (the old `:risi_call_brain` read as an alias) and the low-confidence escalation path: OFF by
  default, no model configured, a daily cap; escalations and corrections are training examples
  (labels only).
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.CallRisi.{Decider, Escalate}

  setup do
    keys = ~w(risi_brain risi_call_brain risi_agent_flags risi_call_decider)a
    prev = for k <- keys, do: {k, Application.get_env(:risime, k)}

    on_exit(fn ->
      for {k, v} <- prev,
          do:
            if(v,
              do: Application.put_env(:risime, k, v),
              else: Application.delete_env(:risime, k)
            )
    end)

    :ok
  end

  test "the config slot is :risi_brain; the old key still works" do
    Application.delete_env(:risime, :risi_brain)
    Application.put_env(:risime, :risi_call_brain, model: "old-alias")
    assert Decider.model() == "old-alias"
    Application.put_env(:risime, :risi_brain, model: "risi-l1")
    assert Decider.model() == "risi-l1"
  end

  test "the decision carries a confidence (0..1); the schema requires it" do
    assert "confidence" in Decider.schema()["required"]
    assert %{confidence: 0.3} = Decider.normalize(%{"tool" => "none", "confidence" => 0.3})
    assert %{confidence: 1.0} = Decider.normalize(%{"tool" => "none", "confidence" => 7})
    assert %{confidence: nil} = Decider.normalize(%{"tool" => "none"})
  end

  test "escalation is off by default and closed without a model" do
    Application.put_env(:risime, :risi_brain, model: "risi-l1")
    ctx = %{user_id: Ecto.UUID.generate(), conv: "c", call_id: "x"}
    refute Escalate.escalate?(%{confidence: 0.1}, ctx)
    assert Escalate.model() == nil

    # The flag on, still no model: nothing is asked.
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_BRAIN_ESCALATE" => "on"})
    refute Escalate.escalate?(%{confidence: 0.1}, ctx)
  end

  test "with the flag on and a model set: only below the line, and only up to the daily cap" do
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_BRAIN_ESCALATE" => "on"})

    Application.put_env(:risime, :risi_brain,
      model: "risi-l1",
      escalate: [model: "stronger", below: 0.5, daily_cap: Escalate.count() + 1]
    )

    ctx = %{user_id: Ecto.UUID.generate(), conv: "c", call_id: "x"}
    refute Escalate.escalate?(%{confidence: 0.9}, ctx)
    assert Escalate.escalate?(%{confidence: 0.2}, ctx)
    refute Escalate.escalate?(%{confidence: 0.2}, ctx)
  end

  test "off: a low-confidence decision is the local one (no second model)" do
    defmodule Two do
      def decide(_input, _ctx),
        do: {:ok, %{"tool" => "message_draft", "action" => "tool", "confidence" => 0.2}}
    end

    Application.put_env(:risime, :risi_call_decider, Two)
    Application.put_env(:risime, :risi_agent_flags, %{"RISI_BRAIN_ESCALATE" => "off"})
    ctx = %{user_id: Ecto.UUID.generate(), conv: "c", call_id: "x"}
    # Off: the local decision stands.
    assert {:ok, %{tool: "message_draft", confidence: 0.2}} =
             Decider.run(%{text: "x", dialogue: %{}, history: [], now: "n", tz: "UTC"}, ctx)
  end

  test "a training example keeps labels only" do
    defmodule Log do
      def record(e), do: send(:persistent_term.get({__MODULE__, :t}), {:logged, e}) && :ok
    end

    prev = Application.get_env(:risime, :risi_learning_log)
    :persistent_term.put({Log, :t}, self())
    Application.put_env(:risime, :risi_learning_log, Log)

    on_exit(fn ->
      if prev,
        do: Application.put_env(:risime, :risi_learning_log, prev),
        else: Application.delete_env(:risime, :risi_learning_log)
    end)

    Escalate.example(%{conv: "c", call_id: "x"}, "correction", %{
      field: "to",
      text: "secret words"
    })

    assert_receive {:logged, %{task: "voice_call_training", output: out}}
    assert Jason.decode!(out) == %{"field" => "to", "kind" => "correction"}
    refute out =~ "secret"
  end
end
