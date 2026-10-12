defmodule RisiMe.AgentsTest do
  @moduledoc """
  Decision 084 (deep agents, steps 0–2): flags (table > env > default, `RISI_AGENTS=legacy`),
  the orchestrator (off / shadow / canary / on, the hard budget and auto-fallback, incidents),
  agent-activity-v1 (no text), the MCP-style tool registry wrapping today's tools, the Fast
  router in shadow beside a real turn (the turn unchanged, its route and timing logged).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import Ecto.Query
  import RisiMe.RisiHelpers

  alias RisiMe.Agents.{Activity, Flags, Orchestrator, Router, Runs, Shadow, ToolRegistry}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  defmodule Slow do
    @moduledoc false
    @behaviour RisiMe.Agents.Agent
    def spec,
      do: %{
        name: "router",
        type: :specialist,
        goal: "test",
        tools: [],
        memory: :none,
        eval_set: nil,
        budget: %{p50_ms: 10, p95_ms: 50, hard_ms: 100},
        learned_version: "test-v1",
        legacy: [],
        status: :planned
      }

    def run(:slow, _), do: Process.sleep(1_000) && {:ok, :late}
    def run(:boom, _), do: raise("boom")
    def run(:fail, _), do: {:error, :nope}
    def run(x, _), do: {:ok, x}
    def label(x), do: inspect(x)
  end

  setup do
    restore_on_exit([:risi_agent_flags, :risi_dev_users])
    Application.delete_env(:risime, :risi_agent_flags)
    :ok
  end

  defp force!(map), do: Application.put_env(:risime, :risi_agent_flags, map)

  defp runs(agent),
    do:
      Repo.all(
        from r in "risi_agent_runs",
          where: r.agent == ^agent,
          order_by: r.at,
          select: %{
            mode: r.mode,
            status: r.status,
            decision: r.decision,
            legacy_route: r.legacy_route,
            agreed: r.agreed,
            fell_back: r.fell_back,
            reason: r.reason,
            latency_ms: r.latency_ms,
            first_ms: r.first_ms,
            learned_version: r.learned_version,
            user_ref: r.user_ref,
            lang: r.lang
          }
      )

  describe "flags" do
    test "defaults: master on, router shadow, every other agent off" do
      assert Flags.master() == :on
      assert Flags.mode("router") == :shadow

      for n <- RisiMe.Agents.names() -- ["router"], do: assert(Flags.mode(n) == :off)
    end

    test "the table wins over the default, is validated, and RISI_AGENTS=legacy turns all off" do
      assert :ok = Flags.put("RISI_AGENT_ROUTER", "on")
      assert Flags.mode("router") == :on
      assert {:error, :invalid_flag} = Flags.put("RISI_AGENT_ROUTER", "maybe")
      assert {:error, :invalid_flag} = Flags.put("RISI_AGENTS", "off")
      assert :ok = Flags.put("RISI_AGENTS", "legacy")
      assert Flags.legacy?() and Flags.mode("router") == :off
      assert :ok = Flags.clear("RISI_AGENTS")
      assert Flags.mode("router") == :on

      assert %{value: "on", from: "table"} =
               Enum.find(Flags.all(), &(&1.name == "RISI_AGENT_ROUTER"))

      Flags.clear("RISI_AGENT_ROUTER")
      assert Flags.mode("router") == :shadow
    end

    test "canary: on for RISI_DEV_USERS, shadow for everyone else" do
      force!(%{"RISI_AGENT_ROUTER" => "canary"})
      dev = Ecto.UUID.generate()
      Application.put_env(:risime, :risi_dev_users, [dev])
      assert Flags.mode_for("router", dev) == :on
      assert Flags.mode_for("router", Ecto.UUID.generate()) == :shadow
    end
  end

  describe "orchestrator" do
    test "off: only the legacy path, nothing logged" do
      force!(%{"RISI_AGENT_ROUTER" => "off"})
      assert {:legacy, 1} = Orchestrator.call(Slow, :x, legacy: fn -> 1 end)
      assert runs("router") == []
    end

    test "shadow: the legacy result answers; the agent's decision is logged with agreement" do
      force!(%{"RISI_AGENT_ROUTER" => "shadow"})
      Activity.subscribe()
      user = Ecto.UUID.generate()

      assert {:legacy, :today} =
               Orchestrator.call(Slow, :x,
                 user: user,
                 lang: "si",
                 legacy: {:value, :today},
                 legacy_label: "loop",
                 compare: fn "loop", :x -> true end
               )

      [r] = runs("router")
      assert r.mode == "shadow" and r.status == "ok" and r.decision == ":x" and r.agreed
      assert r.legacy_route == "loop" and r.learned_version == "test-v1" and r.lang == "si"
      assert r.user_ref == Activity.user_ref(user) and r.user_ref != user

      assert_receive {:agent_activity, %{"v" => "agent-activity-v1", "kind" => "decision"} = e}
      assert e["agent"] == "router" and e["mode"] == "shadow" and e["user_ref"] == r.user_ref
      refute Map.has_key?(e, "text")
    end

    test "on: the agent answers; over the hard budget, an error or a raise falls back" do
      force!(%{"RISI_AGENT_ROUTER" => "on"})
      assert {:agent, :x} = Orchestrator.call(Slow, :x, legacy: fn -> :legacy end)

      for input <- [:slow, :boom, :fail] do
        assert {:legacy, :legacy} = Orchestrator.call(Slow, input, legacy: fn -> :legacy end)
      end

      [ok | incidents] = runs("router")
      assert ok.status == "ok" and not ok.fell_back

      assert Enum.map(incidents, &{&1.status, &1.fell_back}) == [
               {"timeout", true},
               {"error", true},
               {"error", true}
             ]

      assert hd(incidents).latency_ms < 1_000
      assert length(Runs.incidents()) == 3
    end

    test "a declared but unbuilt agent never answers, even when on" do
      force!(%{"RISI_AGENT_PLANNER" => "on"})
      assert {:legacy, :turn} = Orchestrator.call("planner", "hi", legacy: fn -> :turn end)
      assert [%{status: "skipped", fell_back: true}] = runs("planner")
    end
  end

  describe "tool registry" do
    test "today's tools as MCP-style descriptors, unchanged" do
      names = Enum.map(ToolRegistry.list(), & &1["name"])

      for t <- ~w(capabilities set_reminder calendar_check calendar_add memory_search
                  summarise_range risi_items),
          do: assert(t in names)

      d = ToolRegistry.get("calendar_add")
      assert d["annotations"]["readOnlyHint"] == false
      assert d["annotations"]["where"] == "client"
      assert d["inputSchema"] == RisiMe.Agent.Tools.get("calendar_add").args
      assert ToolRegistry.get("capabilities")["annotations"]["readOnlyHint"]
      assert {:error, :unknown_tool} = ToolRegistry.call("nope", %{}, %{})
    end
  end

  describe "the Fast router" do
    test "rules: today's deterministic routes unchanged, finer routes for the loop" do
      d = ~D[2026-10-11]
      assert %{route: "calendar_read", agent: "calendar"} = Router.rules("am I free tomorrow?", d)
      assert %{route: "ledger_items"} = Router.rules("show my reminders", d)
      assert %{route: "memory"} = Router.rules("what did I agree with Upali last week", d)
      assert %{route: "reminder"} = Router.rules("remind me at 9 to call Kamal", d)
      assert %{route: "chat", agent: "direct"} = Router.rules("thanks!", d)
      assert %{route: "planner", confidence: c} = Router.rules("what's the capital of Peru?", d)
      assert c < 0.6
    end

    test "the model decides a low-confidence ask when RISI_ROUTER_MODEL names one" do
      force!(%{"RISI_ROUTER_MODEL" => "risi-l1"})

      fake_llm!(fn "risi_route", body ->
        assert body["max_tokens"] == 40
        assert body["chat_template_kwargs"] == %{"enable_thinking" => false}
        %{"route" => "chat", "confidence" => 0.8}
      end)

      assert {:ok, %{route: "chat", by: "model"}} = Router.run("hmm, ok then", %{})
      # A confident rules decision never asks the model.
      assert {:ok, %{route: "reminder", by: "rules"}} = Router.run("remind me at 9", %{})
      assert length(llm_requests()) == 1
      assert Router.learned_version() =~ "risi-l1"
    end

    test "a model failure keeps the rules decision" do
      force!(%{"RISI_ROUTER_MODEL" => "risi-l1"})
      fake_llm!(fn _, _ -> {:status, 500} end)
      assert {:ok, %{route: "planner", by: "rules"}} = Router.run("hmm, ok then", %{})
    end

    test "legacy route labels and agreement" do
      assert Shadow.legacy_route({:schedule_read, %{}}, nil) == "calendar_read"
      assert Shadow.legacy_route(:risi_items, nil) == "ledger_items"
      assert Shadow.legacy_route({:memory, %{}}, nil) == "memory"
      assert Shadow.legacy_route(:none, nil) == "loop"
      assert Shadow.legacy_route(:none, {%{}, "x"}) == "clarify"
      assert Shadow.compare("loop", %{route: "reminder"})
      refute Shadow.compare("loop", %{route: "memory"})
      assert Shadow.compare("memory", %{route: "memory"})
      assert Shadow.compare("clarify", %{route: "chat"}) == nil
    end
  end

  describe "shadow beside a real turn" do
    setup do
      harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
      kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
      og = risi_chat!([harsha, kamal])
      restore_on_exit([:risi_tools])
      Application.put_env(:risime, :risi_tools, true)
      RisiMe.Agents.TurnTiming.attach()
      on_exit(fn -> :telemetry.detach("risi-agents-turn-timing") end)
      %{harsha: harsha, og: og}
    end

    defp ask!(ctx, text) do
      rid = Ecto.UUID.generate()

      envelope!(ctx.og, ctx.harsha, %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      })

      [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
      perform_job(Job, job.args)
    end

    defp answer do
      receive do
        {:risi_post, _, body, %{"kind" => "answer"}} -> body
      after
        500 -> nil
      end
    end

    test "the turn answers exactly as before; the router's decision and the turn's row are logged",
         ctx do
      fake_llm!(fn "risi_next_action", _ ->
        %{"tool" => "final", "answer" => "Hello!", "sources" => [], "next_steps" => []}
      end)

      assert :ok = ask!(ctx, "thanks Risi")
      assert answer() == "Hello!"
      assert length(llm_requests()) == 1

      [r] = runs("router")
      assert r.mode == "shadow" and r.legacy_route == "loop" and r.decision == "chat/rules"
      assert r.agreed == true and r.lang == "en"

      [t] = runs("legacy_turn")
      assert t.mode == "legacy" and t.status == "ok" and t.legacy_route == "loop"
      assert is_integer(t.latency_ms)
    end

    test "RISI_AGENTS=legacy: the same answer, nothing logged", ctx do
      force!(%{"RISI_AGENTS" => "legacy"})

      fake_llm!(fn "risi_next_action", _ ->
        %{"tool" => "final", "answer" => "Hello!", "sources" => [], "next_steps" => []}
      end)

      assert :ok = ask!(ctx, "thanks Risi")
      assert answer() == "Hello!"
      assert runs("router") == [] and runs("legacy_turn") == []
    end

    test "the hook never fails a turn (a broken ctx is ignored)" do
      assert :ok = Shadow.turn("x", :none, nil, %{now: :bad, tz: nil})
    end
  end
end
