defmodule RisiMe.Agents.EvalTest do
  @moduledoc """
  Decision 084 step 1: the golden-set harness with the fake model. The oracle passes every case
  with no hallucination; every trap (an invented name, amount or date) is caught; the Fast
  router's rules pass the router set. `scripts/risi-eval` runs this file and writes the results
  (`RISI_EVAL_OUT`); `scripts/risi-eval --live` runs `eval_live_test.exs` against risi-l1.
  """
  use RisiMe.DataCase, async: false

  import RisiMe.RisiHelpers

  alias RisiMe.Agents.Eval

  @moduletag :risi_eval
  @moduletag capture_log: true

  @dir Path.expand("../golden", __DIR__)

  setup do
    fake_llm!(&Eval.fake_reply/2)
    :ok
  end

  test "router: the rules route every golden case" do
    r = Eval.run("router", Eval.load(@dir, "router"))
    assert r.n >= 60
    assert r.failed == [], "router misroutes: #{inspect(r.failed)}"
    assert r.accuracy == 1.0
    assert r.p95_ms < 50
    write!("router", r)
  end

  test "ledger and summary: the oracle passes, with no hallucination" do
    for set <- ~w(ledger summary) do
      r = Eval.run(set, Eval.load(@dir, set))
      assert r.failed == [], "#{set}: #{inspect(r.failed)}"
      assert r.halluc == 0
      assert Map.keys(r.by_lang) |> Enum.sort() |> Enum.all?(&(&1 in ~w(en si ta singlish)))
      write!(set, r)
    end
  end

  test "ledger and summary: every trap (invented name, amount or date) is caught" do
    for set <- ~w(ledger summary) do
      cases = Eval.load(@dir, set)
      traps = Enum.count(cases, & &1["trap"])
      assert traps > 0
      r = Eval.run(set, cases, trap: true)
      assert r.halluc_caught == traps, "#{set}: #{r.halluc_caught}/#{traps} traps caught"
    end
  end

  test "the checker: names, amounts and dates not in the source" do
    src = "Kamal: The venue costs 45,000 on Friday."
    assert Eval.invented(src, "The venue costs 45000 on Friday.") == []
    assert "54000" in Eval.invented(src, "It costs 54000.")
    assert "tuesday" in Eval.invented(src, "Book it on Tuesday.")
    assert "Nimal" in Eval.invented(src, "The venue will be booked by Nimal.")
    # Sentence starts are not names; members are known.
    assert Eval.invented(src, "Book the venue. Kamal agreed.") == []
  end

  defp write!(set, r) do
    if dir = System.get_env("RISI_EVAL_OUT") do
      File.mkdir_p!(dir)
      File.write!(Path.join(dir, set <> ".fake.json"), Jason.encode!(r))
    end
  end
end
