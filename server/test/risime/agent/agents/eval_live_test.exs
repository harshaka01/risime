defmodule RisiMe.Agents.EvalLiveTest do
  @moduledoc """
  Decision 084 step 1: the golden sets against the live risi-l1 (read-only API calls, one at a
  time). Excluded by default (`:llm_live`); `scripts/risi-eval --live` runs it:

      LLM_API_KEY=… RISI_EVAL_OUT=dir mix test --only llm_live test/risime/agent/agents/eval_live_test.exs

  Writes `<set>.live.json` per set to `RISI_EVAL_OUT`. Asserts only that the harness ran (the
  numbers are the result, not a gate).
  """
  use RisiMe.DataCase, async: false

  alias RisiMe.Agents.Eval

  @moduletag :llm_live
  @moduletag timeout: 900_000

  @dir Path.expand("../golden", __DIR__)

  setup do
    old = Application.get_env(:risime, :risi_llm)

    Application.put_env(:risime, :risi_llm,
      url: System.get_env("RISI_LLM_URL", "http://127.0.0.1:8100/v1"),
      api_key: System.get_env("LLM_API_KEY"),
      req_options: [],
      fallback: false,
      retries: 0
    )

    on_exit(fn -> Application.put_env(:risime, :risi_llm, old) end)
  end

  test "every golden set against risi-l1" do
    for set <- Eval.sets() do
      r = Eval.run(set, Eval.load(@dir, set), live: true)
      assert r.n > 0

      if dir = System.get_env("RISI_EVAL_OUT") do
        File.mkdir_p!(dir)
        File.write!(Path.join(dir, set <> ".live.json"), Jason.encode!(r))
      end

      IO.puts(
        "#{set}: #{r.ok}/#{r.n} ok, halluc #{r.halluc}, p50 #{r.p50_ms} ms, p95 #{r.p95_ms} ms" <>
          if(r[:model],
            do: " | model #{Float.round(r.model.accuracy * 100, 1)}% p50 #{r.model.p50_ms} ms",
            else: ""
          )
      )
    end
  end
end
