defmodule RisiMe.Agent.ActionGuardTest do
  @moduledoc """
  Principle 4 (audit G9): the consequential-action guard. Every category (money, pay, HR,
  deletion, legal, unknown) by tool kind and by args text; a consequential tool is never run
  (an approval request is recorded, without raw text); a reviewed write flagged by its text is
  always asked and runs only after its human's confirm; today's tools behave exactly as before
  (parity); the flag; and the architecture scan: no code in `lib/` calls a tool's `run` or a
  write's `exec` except through the guard.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers

  alias RisiMe.Agent.{ActionGuard, ClientTools, Tools}
  alias RisiMe.Agents.ToolRegistry
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @secret "SECRET-PAYLOAD-7731"

  # A stub registry: one tool per consequential category, plus an ordinary read and write.
  defmodule GuardTools do
    @moduledoc false

    defp tool(name, extra) do
      Map.merge(
        %{
          name: name,
          description: name,
          where: :server,
          personal: false,
          write: false,
          finds: false,
          args: %{
            "type" => "object",
            "properties" => %{"q" => %{"type" => "string"}},
            "additionalProperties" => false
          },
          run: fn args, _ctx ->
            send(Application.get_env(:risime, :guard_test_pid), {:ran, name, args})
            {:ok, %{"done" => true}, %{}}
          end
        },
        extra
      )
    end

    def tools,
      do: [
        RisiMe.Agent.Tools.capabilities(),
        tool("look", %{}),
        tool("pay_invoice", %{}),
        tool("run_payroll", %{}),
        tool("approve_leave", %{write: true}),
        tool("delete_messages", %{write: true}),
        tool("sign_contract", %{write: true}),
        tool("do_thing", %{write: true}),
        tool("note_it", %{write: true, consequential: false}),
        tool("quiet_helper", %{consequential: :legal})
      ]
  end

  setup do
    restore_on_exit([:risi_tools, :risi_tool_registry, :risi_turn, :risi_agent_flags])
    Application.put_env(:risime, :guard_test_pid, self())
    on_exit(fn -> Application.delete_env(:risime, :guard_test_pid) end)
    :ok
  end

  defp guard_off!,
    do: Application.put_env(:risime, :risi_agent_flags, %{"RISI_ACTION_GUARD" => "off"})

  defp stub(name), do: Enum.find(GuardTools.tools(), &(&1.name == name))

  defp ctx(user \\ Ecto.UUID.generate()),
    do: %{asker: user, device_id: nil, conv: "grp:test", member?: true, risi_chat?: true}

  defp no_secret_anywhere!(rows) do
    for r <- rows, v <- Map.values(Map.from_struct(r)), is_binary(v), do: refute(v =~ @secret)
  end

  describe "classify/2 by tool kind" do
    test "each category by name, a declared category, and an undeclared write" do
      assert {:consequential, :money, :kind} = ActionGuard.classify(stub("pay_invoice"), %{})
      assert {:consequential, :pay, :kind} = ActionGuard.classify(stub("run_payroll"), %{})
      assert {:consequential, :hr, :kind} = ActionGuard.classify(stub("approve_leave"), %{})

      assert {:consequential, :deletion, :kind} =
               ActionGuard.classify(stub("delete_messages"), %{})

      assert {:consequential, :legal, :kind} = ActionGuard.classify(stub("sign_contract"), %{})
      assert {:consequential, :legal, :declared} = ActionGuard.classify(stub("quiet_helper"), %{})
      # Unsure is consequential: a write nobody reviewed.
      assert {:consequential, :unknown, :undeclared} = ActionGuard.classify(stub("do_thing"), %{})
      assert {:consequential, :unknown, :undeclared} = ActionGuard.classify(%{}, %{})
    end

    test "reads and declared ordinary writes are ordinary; an undeclared write's text counts" do
      assert ActionGuard.classify(stub("look"), %{"q" => "what did we say about salaries"}) ==
               :ordinary

      assert ActionGuard.classify(stub("note_it"), %{"q" => "buy milk"}) == :ordinary

      assert {:consequential, :money, :args} =
               ActionGuard.classify(stub("note_it"), %{"q" => "transfer Rs 5000 to Kamal"})
    end

    test "every tool of today's registry is classified explicitly (read or reviewed write)" do
      for t <- Tools.Registry.tools() do
        assert Map.get(t, :write) == false or t.name in ActionGuard.ordinary_writes() or
                 Map.get(t, :consequential) in ActionGuard.categories(),
               unreviewed(t.name)

        if Map.get(t, :consequential) not in ActionGuard.categories(),
          do: assert(ActionGuard.classify(t, %{}) == :ordinary, unreviewed(t.name))
      end
    end

    test "every write tool in lib/ (any map with `exec:`) is reviewed, registered or not" do
      lib = Path.expand("../../../lib", __DIR__)

      names =
        for path <- Path.wildcard(Path.join(lib, "**/*.ex")),
            src = File.read!(path),
            [_, name] <-
              Regex.scan(~r/name:\s*"([a-z0-9_]+)"(?:(?!name:\s*")[\s\S])*?exec:\s*&/, src),
            do: name

      assert "send_message" in names and "schedule_message" in names

      for name <- Enum.uniq(names) do
        assert name in ActionGuard.ordinary_writes(), unreviewed(name)
      end
    end

    test "a write that speaks for the asker (`send_message`) is text-checked" do
      assert "send_message" in ActionGuard.args_checked()
      tool = RisiMe.Agent.SendForMe.tool()

      assert ActionGuard.classify(tool, %{"to" => "Upali", "text" => "See you at 6"}) ==
               :ordinary

      assert {:consequential, :hr, :args} =
               ActionGuard.classify(tool, %{"to" => "Upali", "text" => "You're fired"})
    end
  end

  # The exact instruction a failing scan gives (a new tool must be reviewed, never missed).
  defp unreviewed(name),
    do:
      "write tool #{inspect(name)} is not reviewed by the action guard. Review it, then either " <>
        "add #{inspect(name)} to @ordinary_writes in lib/risime/agent/action_guard.ex (and to " <>
        "@args_checked if its args are words sent to other people as the asker), or declare " <>
        "`consequential: :money | :pay | :hr | :deletion | :legal` on the tool. " <>
        "`consequential: false` alone is not a review."

  describe "classify/2, today's writes" do
    test "each reviewed write is ordinary with ordinary args" do
      for name <- ActionGuard.ordinary_writes(),
          t = Tools.known(name),
          t != nil,
          do: assert(ActionGuard.classify(t, %{}) == :ordinary, name)
    end
  end

  describe "classify/2 by args text (schedule_message speaks for the asker)" do
    setup do: %{sm: ClientTools.schedule_message()}

    test "each category in English, Sinhala and Tamil", %{sm: sm} do
      cases = [
        {"Your salary for October is credited", :pay},
        {"Please transfer Rs 5000 today", :money},
        {"I paid the invoice", :money},
        {"You're fired", :hr},
        {"I approve your leave for Friday", :hr},
        {"Delete all the files from the drive", :deletion},
        {"I accept the contract as it is", :legal},
        {"Let's sign the agreement tomorrow", :legal},
        {"ඔයාගේ වැටුප හෙට", :pay},
        {"பணம் அனுப்பு", :money},
        {"ஒப்பந்தம் ok", :legal}
      ]

      for {text, cat} <- cases do
        assert {:consequential, ^cat, :args} =
                 ActionGuard.classify(sm, %{"to" => "Kamal", "text" => text, "at" => "6pm"}),
               text
      end
    end

    test "an ordinary message stays ordinary (parity)", %{sm: sm} do
      for text <- ["See you at 6", "Happy birthday!", "Call me when you land", "ok"] do
        assert ActionGuard.classify(sm, %{"to" => "Kamal", "text" => text, "at" => "6pm"}) ==
                 :ordinary
      end
    end

    test "a reminder is never blocked by its words (parity: \"remind me to pay rent\")" do
      assert ActionGuard.classify(RisiMe.Agent.Reminders.tool(), %{"text" => "pay the rent"}) ==
               :ordinary

      assert ActionGuard.classify(ClientTools.calendar_add(), %{"title" => "Sign the contract"}) ==
               :ordinary
    end
  end

  describe "run/3, the choke point" do
    test "a consequential tool is never run: denied, approval requested, no raw text kept" do
      user = Ecto.UUID.generate()

      for name <- ~w(pay_invoice run_payroll approve_leave delete_messages sign_contract
                     do_thing quiet_helper) do
        assert {:error, "denied", "needs_approval: " <> _} =
                 ActionGuard.run(stub(name), %{"q" => @secret}, ctx(user))

        refute_received {:ran, ^name, _}
      end

      rows = ActionGuard.decisions(user)
      assert length(rows) == 7
      assert Enum.all?(rows, &(&1.decision == "approval_requested" and &1.approver_id == user))

      assert rows |> Enum.map(& &1.category) |> Enum.sort() ==
               Enum.sort(~w(money pay hr deletion legal unknown legal))

      no_secret_anywhere!(rows)
    end

    test "an ordinary tool runs exactly as before and records nothing" do
      user = Ecto.UUID.generate()

      assert {:ok, %{"done" => true}, %{}} =
               ActionGuard.run(stub("look"), %{"q" => "x"}, ctx(user))

      assert_received {:ran, "look", %{"q" => "x"}}
      assert ActionGuard.decisions(user) == []
    end

    test "a reviewed write flagged by its text keeps its proposal, marked to be asked" do
      user = Ecto.UUID.generate()

      tool = %{
        ClientTools.schedule_message()
        | run: fn _a, _c -> {:propose, %{args: %{"wire" => %{"text" => @secret}}}} end
      }

      args = %{"to" => "Kamal", "text" => "I accept the contract " <> @secret, "at" => "6pm"}
      assert {:propose, %{guard: :legal} = card} = ActionGuard.run(tool, args, ctx(user))
      assert ActionGuard.must_ask?(tool, card)

      assert [%{decision: "confirm_required", category: "legal"} = row] =
               ActionGuard.decisions(user)

      no_secret_anywhere!([row])
    end

    test "RISI_ACTION_GUARD=off: everything runs as before (only `off` turns it off)" do
      guard_off!()
      refute ActionGuard.on?()
      assert {:ok, _, _} = ActionGuard.run(stub("pay_invoice"), %{}, ctx())
      assert_received {:ran, "pay_invoice", _}
      refute ActionGuard.must_ask?(stub("do_thing"), %{args: %{}})

      Application.put_env(:risime, :risi_agent_flags, %{"RISI_ACTION_GUARD" => "maybe"})
      assert ActionGuard.on?()
      Application.delete_env(:risime, :risi_agent_flags)
      assert ActionGuard.on?()
    end

    test "the deep agents' ToolRegistry.call goes through the guard too" do
      Application.put_env(:risime, :risi_tool_registry, GuardTools)

      assert {:error, "denied", "needs_approval: " <> _} =
               ToolRegistry.call("pay_invoice", %{"q" => "x"}, ctx())

      refute_received {:ran, "pay_invoice", _}
      assert {:ok, _, _} = ToolRegistry.call("look", %{"q" => "x"}, ctx())
      assert_received {:ran, "look", _}
    end
  end

  describe "must_ask?/2 and exec_allowed?/3 (the write path)" do
    test "an ordinary card may be Allowed; a consequential one is always asked" do
      refute ActionGuard.must_ask?(ClientTools.calendar_add(), %{args: %{"title" => "Lunch"}})
      assert ActionGuard.must_ask?(stub("do_thing"), %{args: %{}})

      assert ActionGuard.must_ask?(ClientTools.schedule_message(), %{
               args: %{"wire" => %{"text" => "Your bonus is approved"}}
             })
    end

    test "a consequential write runs only after its human's confirm" do
      user = Ecto.UUID.generate()
      wid = Ecto.UUID.generate()
      sm = ClientTools.schedule_message()
      args = %{"wire" => %{"text" => "You're hired " <> @secret}}
      w = %{user_id: user, card_conversation_id: "grp:x", write_id: wid, via: "allowed"}

      refute ActionGuard.exec_allowed?(w, sm, args)
      assert ActionGuard.exec_allowed?(%{w | via: "confirm"}, sm, args)
      # Ordinary writes and an unknown tool are untouched (nothing recorded).
      assert ActionGuard.exec_allowed?(w, sm, %{"wire" => %{"text" => "see you"}})
      assert ActionGuard.exec_allowed?(w, nil, args)

      rows = ActionGuard.decisions(user)
      assert rows |> Enum.map(& &1.decision) |> Enum.sort() == ~w(approved blocked)
      assert Enum.all?(rows, &(&1.write_id == wid and &1.category == "hr"))
      no_secret_anywhere!(rows)
    end
  end

  describe "the turn (§25 tool loop)" do
    setup do
      harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
      kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
      og = risi_chat!([harsha, kamal])
      Application.put_env(:risime, :risi_tools, true)
      Application.put_env(:risime, :risi_tool_registry, GuardTools)
      %{harsha: harsha, og: og}
    end

    test "a model that calls a payment tool gets `denied` + needs_approval; nothing runs", c do
      scripted_llm!([
        %{
          "actions" => [
            %{"tool" => "pay_invoice", "args" => %{"q" => @secret}},
            %{
              "tool" => "final",
              "answer" => "That needs approval.",
              "sources" => [],
              "next_steps" => []
            }
          ]
        }
      ])

      rid = Ecto.UUID.generate()

      envelope!(c.og, c.harsha, %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => "pay the invoice"
      })

      [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
      assert :ok = perform_job(Job, job.args)

      assert_receive {:risi_post, _, _body, a}
      assert a["steps"] == [%{"tool" => "pay_invoice", "status" => "denied"}]
      refute_received {:ran, "pay_invoice", _}

      [_, second] = llm_requests()
      reply = Jason.decode!(List.last(second["messages"])["content"])
      assert reply["ok"] == false and reply["status"] == "denied"
      assert reply["reason"] =~ "needs_approval"

      assert [%{decision: "approval_requested", category: "money"} = row] =
               ActionGuard.decisions(c.harsha.id)

      no_secret_anywhere!([row])
    end
  end

  describe "architecture: no agent code path bypasses the guard" do
    @lib Path.expand("../../../lib", __DIR__)

    defp sources,
      do:
        Path.wildcard(Path.join(@lib, "**/*.ex"))
        |> Enum.map(&{Path.relative_to(&1, @lib), File.read!(&1)})

    defp hits(regex),
      do: for({path, src} <- sources(), Regex.match?(regex, src), do: path) |> Enum.sort()

    test "a tool's `run` is called only by ActionGuard.run/3" do
      assert hits(~r/\.run\s*\.\(/) == ["risime/agent/action_guard.ex"]
      # No indirect call either (`apply`, `Map.get(tool, :run)`, `tool[:run]`).
      assert hits(~r/(\[:run\]|get!?\([^)]*,\s*:run\)|fetch!?\([^)]*,\s*:run\)|apply\([^)]*:run)/) ==
               []
    end

    test "a write's `exec` is called once, in Writes.execute, behind exec_allowed?/3" do
      assert hits(~r/\.exec\s*\.\(/) == ["risime/agent/writes.ex"]

      assert hits(
               ~r/(\[:exec\]|get!?\([^)]*,\s*:exec\)|fetch!?\([^)]*,\s*:exec\)|apply\([^)]*:exec)/
             ) ==
               []

      src = File.read!(Path.join(@lib, "risime/agent/writes.ex"))
      assert length(Regex.scan(~r/\.exec\s*\.\(/, src)) == 1
      [_, execute] = String.split(src, "defp execute(%Write{} = w, args, device) do", parts: 2)
      [body | _] = String.split(execute, "\n  defp ", parts: 2)
      assert body =~ "ActionGuard.exec_allowed?(w, tool, args)"
      assert src =~ "ActionGuard.must_ask?(tool, card)"
    end

    test "every executor is listed: the turn, the registry, client tool calls, human actions" do
      # The turn and the deep agents' registry run tools only through the guard.
      assert File.read!(Path.join(@lib, "risime/agent/turn.ex")) =~
               "ActionGuard.run(tool, args, ctx)"

      assert File.read!(Path.join(@lib, "risime/agent/agents/tool_registry.ex")) =~
               "ActionGuard.run(tool, args, ctx)"

      # Phone tool calls start only inside a tool's run/exec (client_tools; send_for_me's exec,
      # §42.3, after the human's [Send]) or a human's Undo tap (skills, §26.4); nowhere else.
      assert hits(~r/ToolCalls\.(call|start)\(/) ==
               [
                 "risime/agent/client_tools.ex",
                 "risime/agent/send_for_me.ex",
                 "risime/agent/skills.ex"
               ],
             "a new ToolCalls.call/start site: it must run only inside a tool's run/exec " <>
               "(behind ActionGuard) or on a human's tap; review it, then add its file here"

      # `risi_action` handlers act only on a human's tap (the attested sender): the confirm card
      # (Writes.act), Ledger items, Risi Calendar, action items. Adding one is reviewed here.
      worker = File.read!(Path.join(@lib, "risime/workers/risi.ex"))

      # (`Voice.transcript_delete`: the note's own sender deletes Risi's transcript, §37.6;
      # `SpeechCompare.label_reply`: a dev user's own reply labels a compare clip, reviewed
      # 2026-10-14: not consequential; `PermissionFixit.act`: the asker's [Allow] resumes the
      # same turn, whose tools run through the guard, §43.3; `Voice.transcript_language`: a
      # member's "Wrong language?" re-transcribes Risi's own post, §37.13.1.)
      reviewed =
        ~w(CalendarActions.act CalendarV2.act_titles Commitments.act LedgerActions.act PermissionFixit.act
           Reminders.act SpeechCompare.label_reply Voice.transcript_delete
           Voice.transcript_language Writes.act)

      handlers =
        Regex.scan(~r/(\w+)\.(\w+)\(conv, user\b/, worker)
        |> Enum.map(fn [_, mod, fun] -> mod <> "." <> fun end)
        |> Enum.uniq()
        |> Enum.sort()

      assert handlers -- reviewed == [],
             "new risi_action handler(s) #{inspect(handlers -- reviewed)} in " <>
               "lib/risime/workers/risi.ex: check each acts only on a human's tap and runs no " <>
               "tool outside ActionGuard, then add it to `reviewed` in this test"
    end
  end
end
