defmodule RisiMe.Agent.Parity.TurnTest do
  @moduledoc """
  Deep agents step 0: parity pins for `RisiMe.Agent.Turn` as it is today (fake `risi-l1`).
  An `ask` in an Official group answered by a `final`, a real tool step (`capabilities`) then
  `final`, the deterministic schedule route (`ScheduleIntent` → `risi_calendar_check`) and the
  `risi_items` route (no model). Inputs → posted body, the answer envelope's key set and values,
  the model task/schema names and call counts, the stored turn steps, and a timing bound.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers

  alias RisiMe.Agent.{ScheduleIntent, TurnSteps}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :risi_parity
  @moduletag capture_log: true

  @bound_ms 3_000

  @answer_keys ~w(answer call_ref confidence kind made_by next_actions next_steps notify refs
                  request_id sources steps turn_ref v)

  defp timed(fun) do
    t = System.monotonic_time(:millisecond)
    r = fun.()
    {System.monotonic_time(:millisecond) - t, r}
  end

  defp keys(m), do: m |> Map.keys() |> Enum.sort()

  defp schema_names,
    do: Enum.map(llm_requests(), &get_in(&1, ~w(response_format json_schema name)))

  defp enums(req) do
    get_in(req, ["response_format", "json_schema", "schema", "anyOf"])
    |> Enum.flat_map(&get_in(&1, ["properties", "tool", "enum"]))
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      80 -> Enum.reverse(acc)
    end
  end

  defp answer_of(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)

  defp final(answer, sources \\ [], next \\ []),
    do: %{"tool" => "final", "answer" => answer, "sources" => sources, "next_steps" => next}

  defp request!(conv, user, text, device \\ nil) do
    rid = Ecto.UUID.generate()

    envelope!(
      conv,
      user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => text
      },
      device
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    {ms, res} = timed(fn -> perform_job(Job, job.args) end)
    assert res == :ok
    {rid, ms}
  end

  describe "@Risi ask in an Official group" do
    setup do
      harsha = RisiMe.GroupHelpers.fast_user!("Harsha")
      kamal = RisiMe.GroupHelpers.fast_user!("Kamal")
      og = risi_chat!([harsha, kamal])
      restore_on_exit([:risi_tools, :risi_tool_registry, :risi_turn])
      Application.put_env(:risime, :risi_tools, true)
      %{harsha: harsha, kamal: kamal, og: og}
    end

    test "a direct final: answer body, message source, one risi_next_action call", ctx do
      m1 = say!(ctx.og, ctx.kamal, "Budget review moved to Tuesday")

      fake_llm!(fn "risi_next_action", body ->
        final("It moved to Tuesday.", [ref_of(body, "Budget review moved to Tuesday")], [
          "Remind us Tuesday"
        ])
      end)

      {rid, ms} = request!(ctx.og, ctx.harsha, "When is the budget review?")
      assert ms < @bound_ms

      [{conv, body, a}] = posts()
      assert conv == ctx.og
      assert body == "It moved to Tuesday."
      assert keys(a) == Enum.sort(@answer_keys)
      assert a["kind"] == "answer" and a["request_id"] == rid and a["v"] == 1
      assert a["answer"] == body and a["confidence"] == 1.0
      assert is_binary(a["call_ref"])

      assert a["next_actions"] == [
               %{"action" => "ask", "label" => "Remind us Tuesday", "text" => "Remind us Tuesday"}
             ]

      assert a["steps"] == []
      assert a["refs"] == [m1]

      assert a["sources"] == [
               %{"type" => "message", "conversation_id" => ctx.og, "message_id" => m1}
             ]

      assert a["next_steps"] == ["Remind us Tuesday"]
      assert a["notify"] == [ctx.harsha.id]
      assert a["turn_ref"] =~ ~r/^[0-9a-f-]{36}$/
      assert a["made_by"]["model"] == "risi-l1" and a["made_by"]["provider"] == "risime"

      assert schema_names() == ["risi_next_action"]
      [req] = llm_requests()
      assert "final" in enums(req) and "capabilities" in enums(req)
      assert List.last(req["messages"])["content"] =~ "Refs you may cite in sources: m1."

      assert Enum.map(TurnSteps.list(a["turn_ref"]), &{&1.n, &1.tool, &1.status}) ==
               [{1, "final", "ok"}]
    end

    test "a tool step then final: capabilities runs, two model calls, the step is reported",
         ctx do
      fake_llm!(fn "risi_next_action", body ->
        if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
          do: final("I can set reminders and track promises.", [], ["Try: remind us Tuesday"]),
          else: %{"tool" => "capabilities", "args" => %{}}
      end)

      {_rid, ms} = request!(ctx.og, ctx.harsha, "What can you do?")
      assert ms < @bound_ms

      [{_, body, a}] = posts()
      assert body == "I can set reminders and track promises."
      # A "Try: …" next step is not turned into a chip: no next_actions key at all.
      assert keys(a) == Enum.sort(@answer_keys -- ["next_actions"])
      assert is_binary(a["call_ref"]) and a["made_by"]["model"] == "risi-l1"
      assert a["steps"] == [%{"tool" => "capabilities", "status" => "ok"}]
      assert a["sources"] == [] and a["refs"] == []
      assert a["next_steps"] == ["Try: remind us Tuesday"]

      assert schema_names() == ["risi_next_action", "risi_next_action"]
      [_, second] = llm_requests()
      assert Enum.map(second["messages"], & &1["role"]) == ~w(system user assistant user)
      result = Jason.decode!(List.last(second["messages"])["content"])
      assert result["ok"] == true and is_list(result["result"]["now"])

      assert Enum.map(TurnSteps.list(a["turn_ref"]), &{&1.n, &1.tool, &1.status}) ==
               [{1, "capabilities", "ok"}, {2, "final", "ok"}]
    end
  end

  describe "deterministic routes in the asker's Risi chat" do
    setup do
      RisiMe.CalendarHelpers.calendar_world!()
    end

    test "ScheduleIntent: a schedule question forces risi_calendar_check before any answer",
         ctx do
      fake_llm!(fn _name, _body -> final("You're free all day tomorrow!") end)

      {_rid, ms} =
        request!(ctx.hrc, ctx.harsha.user, "what appointment do I have tomorrow", ctx.hd)

      assert ms < @bound_ms

      {conv, body, a} = answer_of(posts())
      assert conv == ctx.hrc

      assert body ==
               "Sat 10 Oct: nothing on your calendars.\n\nChecked: Risi Calendar · Risi's items."

      assert keys(a) == Enum.sort(@answer_keys)
      assert a["steps"] == [%{"tool" => "risi_calendar_check", "status" => "ok"}]
      assert a["made_by"]["model"] == "risi-l1" and is_binary(a["call_ref"])
      assert a["next_actions"] == [] and a["refs"] == []

      assert a["sources"] ==
               for(
                 {src, name} <- [
                   {"risi_calendar", "Risi Calendar"},
                   {"risi_items", "Risi's items"}
                 ],
                 do: %{
                   "type" => "calendar_source",
                   "source" => src,
                   "names" => [name],
                   "read_ok" => true,
                   "reason" => nil
                 }
               )

      # One model call, offered no write tool after the forced read.
      assert schema_names() == ["risi_next_action"]
      [req] = llm_requests()
      refute "schedule_message" in enums(req)
      refute "risi_calendar_add" in enums(req)
      tool_msg = Enum.find(req["messages"], &(&1["role"] == "assistant"))

      assert Jason.decode!(tool_msg["content"])["args"] == %{
               "from" => "2026-10-09T18:30:00.000Z",
               "to" => "2026-10-10T18:30:00.000Z"
             }
    end

    test "risi_items: 'what have you set up for me?' is answered by the server, no model call",
         ctx do
      fake_llm!(fn _, _ -> flunk("no model call") end)
      {_rid, ms} = request!(ctx.hrc, ctx.harsha.user, "what have you set up for me?", ctx.hd)
      assert ms < @bound_ms

      {_, body, a} = answer_of(posts())
      assert body == "I haven't set anything up for you from Fri 9 Oct to Sat 9 Jan."
      assert keys(a) == Enum.sort(@answer_keys)
      assert a["answer"] == body and a["confidence"] == 1.0 and a["call_ref"] == nil
      assert a["sources"] == [] and a["refs"] == [] and a["next_actions"] == []

      assert a["made_by"] == %{
               "also" => [],
               "at" => "2026-10-09T05:00:00.000Z",
               "model" => nil,
               "provider" => "risime"
             }

      assert a["steps"] == [%{"tool" => "risi_items", "status" => "ok"}]
      assert a["made_by"]["model"] == nil
      assert llm_requests() == []
    end
  end

  test "ScheduleIntent.classify routes today's fixture phrases" do
    today = ~D[2026-10-11]
    assert {:schedule_read, _} = ScheduleIntent.classify("what's on this week", today)
    assert :risi_items = ScheduleIntent.classify("what have you set up for me?", today)

    assert {:memory, %{mode: :agreed}} =
             ScheduleIntent.classify("what did I agree with Upali last week", today)

    assert :none = ScheduleIntent.classify("summarise this chat", today)
    assert :none = ScheduleIntent.classify("translate my last message into Tamil", today)
  end
end
