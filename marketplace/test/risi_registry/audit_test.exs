defmodule RisiRegistry.AuditTest do
  use RisiRegistry.DataCase, async: false
  alias RisiRegistry.Audit

  test "a call line keeps only who, agent, skill, company, result, time and request id" do
    Audit.call_line(%{
      actor: "person:42",
      agent_id: "risicloud.ai/risiwork-ask",
      skill: "ask.bills",
      company_id: "c1",
      result_code: "ok",
      latency_ms: 812.4,
      request_id: "req-1",
      question: "How much did we spend on fuel?",
      answer: "LKR 123,456",
      figures: [123_456],
      reason: "not for call lines"
    })

    [e] = Audit.recent(action: "call")
    assert e.actor == "person:42"
    assert e.latency_ms == 812
    assert e.reason == nil

    text = inspect(Map.from_struct(e))
    refute text =~ "fuel"
    refute text =~ "123,456"
    refute text =~ "123456"
    refute text =~ "LKR"
  end

  test "the audit table has no column that could hold content" do
    cols = RisiRegistry.Audit.Event.__schema__(:fields)
    refute Enum.any?(cols, &(&1 in [:question, :answer, :body, :content, :card, :figures]))
  end

  test "reason is kept only for actions a person takes, and is capped" do
    Audit.record("status_change", %{actor: "admin", reason: String.duplicate("x", 500)})
    Audit.record("call", %{actor: "p", reason: "dropped"})
    [call, change] = Audit.recent(limit: 2)
    assert call.reason == nil
    assert String.length(change.reason) == 200
  end
end
