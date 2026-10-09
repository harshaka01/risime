defmodule RisiMe.Agent.ClientToolsS16Test do
  @moduledoc """
  v1.26 §26.6 (server S16): the client tools `set_alarm`, `schedule_message`,
  `cancel_scheduled` and `calendar_remove` — their schemas and cards (exact `args`), routing to
  the asking/confirming `risi_skills` device, the 2-minute tool-call row deleted on the result,
  activity entries (never the scheduled text) and undo routing; plus the canary: a scheduled
  message's text is in no table, job, step row or log once the tool call is answered.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]
  import ExUnit.CaptureLog

  alias RisiMe.Agent.{Skills, TurnSteps, Writes}
  alias RisiMe.Workers.Risi, as: Job

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal Perera")
    RisiMe.Fixtures.befriend!(harsha, kamal)
    og = risi_chat!([harsha.user, kamal.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = skills_device!(harsha.user)
    rc = own_risi_chat!(harsha.user)
    %{harsha: harsha, kamal: kamal, og: og, dev: dev, rc: rc}
  end

  defp set!(ctx, id, state) do
    {200, _} =
      api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{"changes" => [%{"id" => id, "state" => state, "client_permission" => "granted"}]},
        ctx.dev
      )
  end

  defp ask!(conv, user, text, device) do
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
    {rid, perform_job(Job, job.args)}
  end

  defp action_args(conv, user, action, target, device) do
    id =
      envelope!(
        conv,
        user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => nil
        },
        device
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    job.args
  end

  defp wait_call(user_id, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      [call | _] ->
        call

      [] when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, tries - 1)

      [] ->
        flunk("no risi_tool_call event")
    end
  end

  defp result!(ctx, id, body, device \\ nil),
    do:
      api(
        :post,
        "/api/v1/risi/tool_calls/#{id}/result",
        ctx.harsha.token,
        body,
        device || ctx.dev
      )

  defp script(tool, args),
    do: [
      %{
        "task" => "risi_next_action",
        "actions" => [
          %{"tool" => tool, "args" => args},
          %{"tool" => "final", "answer" => "Tap Add.", "sources" => [], "next_steps" => []}
        ]
      }
    ]

  defp confirm_and_answer(ctx, card, result, device \\ nil) do
    device = device || ctx.dev
    args = action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], device)
    task = Task.async(fn -> perform_job(Job, args) end)
    d = wait_call(ctx.harsha.user.id)["data"]
    {204, _} = result!(ctx, d["tool_call_id"], result, device)
    assert :ok = Task.await(task)
    d
  end

  @tag capture_log: true
  test "set_alarm (ask): the card's exact args, the call to the confirming device, skill_done",
       ctx do
    set!(ctx, "alarm", "ask")
    scripted_llm!(script("set_alarm", %{"time" => "5:30am", "label" => "Wake up", "days" => nil}))
    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "wake me up at 5:30", ctx.dev)

    assert_receive {:risi_post, rc, _, %{"kind" => "confirm"} = card}
    assert rc == ctx.rc and card["tool"] == "set_alarm" and card["skill_id"] == "alarm"
    assert card["args"] == %{"time" => "05:30", "label" => "Wake up", "days" => nil}
    assert card["summary"] =~ ~r/^Set an alarm on this phone for 05:30 (today|tomorrow) \(/
    assert card["text"] == "Wake up"

    # Confirmed from another risi_skills phone of Harsha: the alarm goes to that phone.
    other = skills_device!(ctx.harsha.user)

    d =
      confirm_and_answer(
        ctx,
        card,
        %{"status" => "ok", "result" => %{"alarm_set" => true}},
        other
      )

    assert d["to_devices"] == [other] and d["undo_entry_id"] == nil
    assert d["args"] == Map.put(card["args"], "write_id", card["write_id"])

    assert_receive {:risi_post, ^rc, body, %{"kind" => "skill_done"} = done}
    assert body == "Set an alarm for 05:30, 'Wake up'. To remove it, open Clock."
    assert done["kind"] == "skill_done" and done["action"] == "alarm_set"
    assert done["via"] == "confirm" and done["undo_token"] == nil

    assert done["undo"] == %{
             "kind" => "manual",
             "state" => nil,
             "until" => nil,
             "hint" => "Open Clock to remove it"
           }

    assert events(ctx.harsha.user.id, "risi_tool_call") == []
  end

  @tag capture_log: true
  test "set_alarm (allowed): no card; a confirm from a v1.25 phone routes to the turn's", ctx do
    set!(ctx, "alarm", "allowed")
    h = ctx.harsha

    task =
      Task.async(fn ->
        scripted_llm!(script("set_alarm", %{"time" => "06:15", "label" => "", "days" => [1, 3]}))
        ask!(ctx.og, h.user, "set an alarm", ctx.dev)
      end)

    d = wait_call(h.user.id)["data"]
    assert d["tool"] == "set_alarm" and d["to_devices"] == [ctx.dev]
    assert d["args"]["days"] == [1, 3] and d["args"]["label"] == ""

    {204, _} =
      result!(ctx, d["tool_call_id"], %{"status" => "ok", "result" => %{"alarm_set" => true}})

    assert {_, :ok} = Task.await(task)
    refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
    assert_receive {:risi_post, _, "Set an alarm for 06:15. To remove it, open Clock.", done}
    assert done["via"] == "allowed"

    # Ask mode, confirmed from a phone without risi_skills: the turn's device gets the call.
    set!(ctx, "alarm", "ask")
    old = RisiMe.TabsHelpers.risi_tools_device!(h.user)
    scripted_llm!(script("set_alarm", %{"time" => "07:00", "label" => "Gym", "days" => nil}))
    {_, :ok} = ask!(ctx.og, h.user, "alarm", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}

    args = action_args(ctx.rc, h.user, "confirm_write", card["write_id"], old)
    task = Task.async(fn -> perform_job(Job, args) end)
    d = wait_call(h.user.id)["data"]
    assert d["to_devices"] == [ctx.dev] and old != ctx.dev

    {204, _} =
      result!(ctx, d["tool_call_id"], %{"status" => "ok", "result" => %{"alarm_set" => true}})

    assert :ok = Task.await(task)
  end

  test "schedule_message: always a card with the full text; the text leaves no trace", ctx do
    set!(ctx, "scheduled_messages", "ask")
    canary = "CANARY-" <> Ecto.UUID.generate()
    text = canary <> " good morning"

    log =
      capture_log(fn ->
        scripted_llm!(
          script("schedule_message", %{
            "to" => "kamal",
            "text" => text,
            "at" => "tomorrow 6am",
            "repeat" => "daily"
          })
        )

        {_, :ok} =
          ask!(ctx.og, ctx.harsha.user, "send Kamal good morning at 6am every day", ctx.dev)

        assert_receive {:risi_post, rc, body, card}
        assert rc == ctx.rc and card["kind"] == "confirm" and card["tool"] == "schedule_message"
        dm = RisiMe.Messaging.conversation_id(ctx.harsha.user.id, ctx.kamal.user.id)
        assert card["skill_id"] == "scheduled_messages" and card["text"] == text

        assert card["args"] == %{
                 "conversation_id" => dm,
                 "text" => text,
                 "at" => card["when"]["start"],
                 "repeat" => "daily"
               }

        assert card["summary"] =~ ~s(Send "#{text}" to Kamal Perera every day at 06:00, starting)
        assert body =~ "Update RisiMe to answer"
        sid = Ecto.UUID.generate()

        d =
          confirm_and_answer(ctx, card, %{"status" => "ok", "result" => %{"schedule_id" => sid}})

        assert d["args"]["text"] == text and d["args"]["write_id"] == card["write_id"]

        assert_receive {:risi_post, ^rc, done_body, %{"kind" => "skill_done"} = done}
        assert done["kind"] == "skill_done" and done["action"] == "message_scheduled"
        assert done["summary"] == "Scheduled a message to Kamal Perera, every day at 06:00"
        assert done_body == "Scheduled a message to Kamal Perera, every day at 06:00."
        assert done["undo"]["kind"] == "client" and done["undo"]["until"] == nil

        # Nothing holds the text any more: no event, no write args, no entry, no job, no step.
        assert events(ctx.harsha.user.id, "risi_tool_call") == []
        assert Writes.get(card["write_id"]).args == nil

        {200, %{"entries" => es}} =
          api(
            :get,
            "/api/v1/risi/skills/scheduled_messages/activity",
            ctx.harsha.token,
            nil,
            ctx.dev
          )

        refute Jason.encode!(es) =~ canary
        [e | _] = es
        assert e["target_conversation_id"] == dm and e["device_id"] == ctx.dev

        refute inspect(Repo.all(Oban.Job)) =~ canary
        refute inspect(Repo.all(RisiMe.Agent.ToolCalls)) =~ canary
        refute inspect(Repo.all(Writes.Write)) =~ canary
        refute inspect(Repo.all(RisiMe.Agent.Reminders)) =~ canary
        refute inspect(TurnSteps.list(card["turn_ref"])) =~ canary
        refute inspect(RisiMe.Agent.LearningLog.get(card["call_ref"])) =~ canary

        for t <- ~w(risi_pending_writes risi_tool_calls risi_skill_activity risi_skills),
            row <- Repo.query!("SELECT * FROM #{t}").rows,
            do: refute(inspect(row, limit: :infinity, printable_limit: :infinity) =~ canary)

        send(self(), {:done_entry, e})
      end)

    refute log =~ canary

    # The undo goes to the entry's device: cancel_scheduled with undo_entry_id, no write_id.
    assert_received {:done_entry, e}
    path = "/api/v1/risi/skills/scheduled_messages/activity/#{e["entry_id"]}/undo"
    {202, _} = api(:post, path, ctx.harsha.token, %{"undo_token" => e["undo_token"]}, ctx.dev)
    u = wait_call(ctx.harsha.user.id)["data"]
    assert u["tool"] == "cancel_scheduled" and u["undo_entry_id"] == e["entry_id"]
    assert u["args"]["write_id"] == nil and is_binary(u["args"]["schedule_id"])
    assert u["to_devices"] == [ctx.dev]

    {204, _} =
      result!(ctx, u["tool_call_id"], %{
        "status" => "ok",
        "result" => %{"cancelled" => true, "reason" => nil}
      })

    {200, %{"entries" => [c, s | _]}} =
      api(:get, "/api/v1/risi/skills/scheduled_messages/activity", ctx.harsha.token, nil, ctx.dev)

    assert c["action"] == "scheduled_cancelled" and c["via"] == "undo"
    assert s["undo"]["state"] == "done"
  end

  @tag capture_log: true
  test "cancel_scheduled by request: always a card; the phone cancels; the entry settles", ctx do
    set!(ctx, "scheduled_messages", "ask")

    e =
      Skills.log!(
        ctx.harsha.user.id,
        "scheduled_messages",
        "message_scheduled",
        "Scheduled a message to Kamal Perera, every day at 06:00",
        via: "confirm",
        device_id: ctx.dev,
        undo_kind: "client",
        target_conversation_id: "dm:x",
        data: %{"schedule_id" => Ecto.UUID.generate(), "to_name" => "Kamal Perera"}
      )

    scripted_llm!(script("cancel_scheduled", %{"to" => "Kamal Perera"}))
    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "stop the good morning message", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}
    assert card["tool"] == "cancel_scheduled"
    assert card["summary"] == "Cancel the scheduled message to Kamal Perera (every day at 06:00)"
    assert Map.keys(card["args"]) == ["schedule_id"]

    d =
      confirm_and_answer(ctx, card, %{
        "status" => "ok",
        "result" => %{"cancelled" => true, "reason" => nil}
      })

    assert d["args"]["write_id"] == card["write_id"]

    assert_receive {:risi_post, _, _,
                    %{"kind" => "skill_done", "action" => "scheduled_cancelled"}}

    {200, %{"entries" => es}} =
      api(:get, "/api/v1/risi/skills/scheduled_messages/activity", ctx.harsha.token, nil, ctx.dev)

    assert Enum.find(es, &(&1["entry_id"] == e.entry_id))["undo"]["state"] == "done"
  end

  @tag capture_log: true
  test "recipients: a friend's 1:1 or this Official chat; never a stranger or the Risi chat",
       ctx do
    set!(ctx, "scheduled_messages", "ask")

    for {to, conv, reason} <- [
          {"Somebody", ctx.og, "unknown_recipient"},
          {"this chat", ctx.rc, "unknown_recipient"}
        ] do
      scripted_llm!(
        script("schedule_message", %{"to" => to, "text" => "hi", "at" => "tomorrow 6am"})
      )

      {_, :ok} = ask!(conv, ctx.harsha.user, "send hi", ctx.dev)
      [_, second] = llm_requests()
      res = Jason.decode!(List.last(second["messages"])["content"])
      assert res["status"] == "failed" and res["reason"] =~ reason
    end

    scripted_llm!(
      script("schedule_message", %{"to" => "this chat", "text" => "hi", "at" => "tomorrow 6am"})
    )

    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "send hi here", ctx.dev)

    assert_receive {:risi_post, _, _,
                    %{"kind" => "confirm", "args" => %{"conversation_id" => og}}}

    assert og == ctx.og
  end

  @tag capture_log: true
  test "the v1.26 tools are offered only to risi_skills devices of gated askers", ctx do
    set!(ctx, "alarm", "ask")
    set!(ctx, "scheduled_messages", "ask")

    scripted_llm!([
      %{
        "actions" => [%{"tool" => "final", "answer" => "ok", "sources" => [], "next_steps" => []}]
      }
    ])

    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "hi", ctx.dev)
    [req] = llm_requests()

    tools =
      for alt <- req["response_format"]["json_schema"]["schema"]["anyOf"],
          do: hd(alt["properties"]["tool"]["enum"])

    assert "set_alarm" in tools and "schedule_message" in tools and "cancel_scheduled" in tools
    refute "calendar_remove" in tools

    # From a risi_tools-only phone of the same (gated) user: no phone skill tool at all.
    old = RisiMe.TabsHelpers.risi_tools_device!(ctx.harsha.user)

    scripted_llm!([
      %{
        "actions" => [%{"tool" => "final", "answer" => "ok", "sources" => [], "next_steps" => []}]
      }
    ])

    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "hi", old)
    [req] = llm_requests()

    tools =
      for alt <- req["response_format"]["json_schema"]["schema"]["anyOf"],
          do: hd(alt["properties"]["tool"]["enum"])

    refute "set_alarm" in tools or "schedule_message" in tools
  end
end
