defmodule RisiMe.Agent.SendForMeTest do
  @moduledoc """
  Proposal 2026-10-14-send-for-me (§42; Harsha's P1 2026-10-14, nightly.7):

    * the P1's false "Scheduled messages permission is off on this phone": a Risi-chat request
      without its device (a voice note) now runs for the asker's Risi device; a `denied` report
      blocks only while fresh; scheduled messages never need the exact-alarm permission;
    * `send_message`: ONE card (To · text · [Send] [Edit]), the phone sends it on [Send] (or with
      the edited text), "✓ Sent to <name>" (never the text), honest failures; "Which Upali?" for
      two matches; no 1:1 chat said so; Phase 1 (no `risi_send` device) is the `draft` card;
    * `schedule_message` only with a time, "✓ Scheduled for …";
    * the switch `RISI_SEND_FOR_ME` (off: no tool) and the consequential-action class.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Agent.{SendForMe, Skills}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @send_caps ~w(groups member_devices tabs risi_tools risi_skills risi_send)

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    restore_on_exit([:risi_send_for_me])
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    upali = RisiMe.Fixtures.logged_in_user(display_name: "Upali Perera")
    RisiMe.Fixtures.befriend!(harsha, upali)
    # Seeds Risi (and an Official group with Upali).
    _og = risi_chat!([harsha.user, upali.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = RisiMe.TabsHelpers.tabs_device!(harsha.user, caps: @send_caps)
    rc = own_risi_chat!(harsha.user)
    %{harsha: harsha, upali: upali, dev: dev, rc: rc}
  end

  ## Helpers

  defp send_on!, do: Application.put_env(:risime, :risi_send_for_me, :on)

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

  defp action_args(conv, user, action, target, device, edit \\ nil) do
    id =
      envelope!(
        conv,
        user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "target" => target,
          "action" => action,
          "edit" => edit
        },
        device
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    job.args
  end

  # The tool call of the write `wid` (or the first one).
  defp wait_call(user_id, wid \\ nil, tries \\ 150) do
    calls =
      for c <- events(user_id, "risi_tool_call"),
          wid == nil or c["data"]["args"]["write_id"] == wid,
          do: c

    case calls do
      [call | _] ->
        call

      [] when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, wid, tries - 1)

      [] ->
        flunk("no risi_tool_call event")
    end
  end

  defp result(ctx, id, body, device),
    do: api(:post, "/api/v1/risi/tool_calls/#{id}/result", ctx.harsha.token, body, device)

  defp send_script(args),
    do: [
      %{
        "task" => "risi_next_action",
        "actions" => [
          %{"tool" => "send_message", "args" => args},
          %{"tool" => "final", "answer" => "MODEL", "sources" => [], "next_steps" => []}
        ]
      }
    ]

  defp schema_tools(body) do
    for alt <- body["response_format"]["json_schema"]["schema"]["anyOf"],
        do: hd(alt["properties"]["tool"]["enum"])
  end

  defp need_enum(body) do
    Enum.find_value(body["response_format"]["json_schema"]["schema"]["anyOf"], fn alt ->
      if alt["properties"]["tool"]["enum"] == ["need_skill"],
        do: alt["properties"]["args"]["properties"]["skill_id"]["enum"]
    end)
  end

  defp final(answer \\ "ok"),
    do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}

  defp patch!(ctx, changes),
    do:
      {200, _} =
        api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => changes}, ctx.dev)

  defp age_report!(ctx, skill, seconds) do
    at = DateTime.add(DateTime.utc_now(), -seconds, :second)

    RisiMe.Repo.update_all(
      from(p in Skills.DevicePermission,
        where: p.device_id == ^ctx.dev and p.skill_id == ^skill
      ),
      set: [reported_at: at]
    )
  end

  defp dm(ctx), do: RisiMe.Messaging.conversation_id(ctx.harsha.user.id, ctx.upali.user.id)

  ## The P1: never a false "off"

  describe "P1 2026-10-14: skill and permission state" do
    test "a Risi-chat request without its device (voice) runs for the asker's Risi device", ctx do
      patch!(ctx, [
        %{"id" => "scheduled_messages", "state" => "ask", "client_permission" => "granted"}
      ])

      scripted_llm!([%{"actions" => [final()]}])
      # `nil` device: what a voice note's buffered request carried before the fix.
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "send Upali good morning at 6am", nil)
      [req] = llm_requests()

      assert "schedule_message" in schema_tools(req)
      refute "scheduled_messages" in (need_enum(req) || [])
      assert Skills.request_device(ctx.harsha.user.id, nil, ctx.rc) == ctx.dev
      # Outside the Risi chat the request keeps what it passed.
      assert Skills.request_device(ctx.harsha.user.id, nil, "grp:" <> Ecto.UUID.generate()) ==
               nil
    end

    test "a denied report blocks only while fresh; the exact alarm never blocks", ctx do
      patch!(ctx, [
        %{"id" => "calendar", "state" => "ask", "client_permission" => "denied"},
        %{"id" => "scheduled_messages", "state" => "ask", "client_permission" => "denied"}
      ])

      scripted_llm!([%{"actions" => [final()]}])
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "hi", ctx.dev)
      [req] = llm_requests()
      # Fresh `denied` for the calendar: not offered, need_skill says why.
      refute "calendar_check" in schema_tools(req)
      assert "calendar" in need_enum(req)
      # SCHEDULE_EXACT_ALARM is optional (§26.1): scheduled messages stay offered.
      assert "schedule_message" in schema_tools(req)
      refute "scheduled_messages" in need_enum(req)

      ctx_map = %{asker: ctx.harsha.user.id, device_id: ctx.dev}
      assert Skills.missing_reason(ctx_map, "calendar") == "no_permission"
      assert Skills.missing_reason(ctx_map, "scheduled_messages") == nil

      # Seven hours later the report is stale: offered, the phone decides at run time.
      age_report!(ctx, "calendar", 7 * 3600)
      assert Skills.missing_reason(ctx_map, "calendar") == nil
      scripted_llm!([%{"actions" => [final()]}])
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "hi", ctx.dev)
      [req] = llm_requests()
      assert "calendar_check" in schema_tools(req)

      # No device at all: never "no permission".
      assert Skills.missing_reason(%{asker: ctx.harsha.user.id, device_id: nil}, "calendar") ==
               nil
    end

    test "the user's own off is still said (server state)", ctx do
      ctx_map = %{asker: ctx.harsha.user.id, device_id: ctx.dev}
      assert Skills.missing_reason(ctx_map, "scheduled_messages") == "off"
    end
  end

  ## send_message

  describe "send_message (§42.2)" do
    test "off for non-dev users by default (canary); on: offered, non-consequential", ctx do
      scripted_llm!([%{"actions" => [final()]}])
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "hi", ctx.dev)
      [req] = llm_requests()
      refute "send_message" in schema_tools(req)
      assert SendForMe.mode() == :canary

      send_on!()
      scripted_llm!([%{"actions" => [final()]}])
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "hi", ctx.dev)
      [req] = llm_requests()
      assert "send_message" in schema_tools(req)
      assert SendForMe.tool().consequential == false and SendForMe.consequential?() == false
      assert SendForMe.tool().skill == nil
    end

    test "one card, [Send]: the phone sends it, then '✓ Sent to Upali Perera' (no text)", ctx do
      send_on!()
      text = "Hi Upali, please update RisiMe to the latest version."
      scripted_llm!(send_script(%{"to" => "Upali", "text" => text}))
      {rid, :ok} = ask!(ctx.rc, ctx.harsha.user, "send a message to Upali to update", ctx.dev)

      assert_receive {:risi_post, rc, body, %{"kind" => "confirm"} = card}
      assert rc == ctx.rc
      assert card["tool"] == "send_message" and card["request_id"] == rid
      assert card["buttons"] == ["send", "edit", "cancel"]
      assert card["args"] == %{"conversation_id" => dm(ctx), "text" => text}

      assert card["send"] == %{
               "to" => %{"name" => "Upali Perera", "user_id" => ctx.upali.user.id},
               "tab" => "private",
               "language" => "en"
             }

      assert card["for"] == [ctx.harsha.user.id] and card["when"] == nil
      assert card["skill_id"] == nil
      assert body =~ "Send to Upali Perera (Private): " <> text

      # The turn's own short line: the card is the action (no second model call).
      assert_receive {:risi_post, ^rc, line, %{"kind" => "answer"}}
      assert line == "Tap Send to send it to Upali, or Edit to change it."
      refute line =~ "MODEL"
      assert events(ctx.harsha.user.id, "risi_tool_call") == []

      args = action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
      task = Task.async(fn -> perform_job(Job, args) end)
      d = wait_call(ctx.harsha.user.id)["data"]
      assert d["tool"] == "send_message" and d["to_devices"] == [ctx.dev]

      assert d["args"] == %{
               "write_id" => card["write_id"],
               "conversation_id" => dm(ctx),
               "text" => text
             }

      assert Map.has_key?(d, "undo_entry_id")

      {204, _} =
        result(
          ctx,
          d["tool_call_id"],
          %{"status" => "ok", "result" => %{"state" => "sent"}},
          ctx.dev
        )

      assert :ok = Task.await(task)
      assert_receive {:risi_post, ^rc, sent_body, %{"kind" => "message_sent"} = sent}
      assert sent_body == "✓ Sent to Upali Perera"
      refute Jason.encode!(sent) =~ "update RisiMe"

      assert %{
               "request_id" => ^rid,
               "write_id" => _,
               "conversation_id" => _,
               "to" => %{"name" => "Upali Perera"},
               "tab" => "private",
               "state" => "sent",
               "call_ref" => nil,
               "notify" => _
             } = sent

      assert sent["conversation_id"] == dm(ctx)
      # The pending write's sealed text is wiped once done.
      assert %{state: "done", args: nil} = RisiMe.Agent.Writes.get(card["write_id"])
    end

    test "[Edit]: the edited text is what the phone sends; a bad edit runs nothing", ctx do
      send_on!()
      scripted_llm!(send_script(%{"to" => "Upali Perera", "text" => "Please update."}))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "message Upali to update", ctx.dev)
      assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}

      # Another key (or an empty text) is malformed: nothing runs.
      bad =
        action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev, %{
          "title" => "x"
        })

      assert :ok = perform_job(Job, bad)
      assert events(ctx.harsha.user.id, "risi_tool_call") == []

      args =
        action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev, %{
          "text" => "  Please update RisiMe today.  "
        })

      task = Task.async(fn -> perform_job(Job, args) end)
      d = wait_call(ctx.harsha.user.id)["data"]
      assert d["args"]["text"] == "Please update RisiMe today."

      {204, _} =
        result(
          ctx,
          d["tool_call_id"],
          %{"status" => "ok", "result" => %{"state" => "queued"}},
          ctx.dev
        )

      assert :ok = Task.await(task)
      assert_receive {:risi_post, _, body, %{"kind" => "message_sent", "state" => "queued"}}
      assert body == "Queued for Upali Perera: it goes as soon as your phone is online."
    end

    test "honest failures: not_member, declined, timeout", ctx do
      send_on!()

      for {reply, want} <- [
            {%{"status" => "error", "result" => %{"code" => "not_member"}},
             "I couldn't send it: you're not in a chat with Upali Perera on this phone."},
            {%{"status" => "declined", "result" => nil},
             "Your phone declined it, so nothing was sent."}
          ] do
        scripted_llm!(send_script(%{"to" => "Upali", "text" => "Hi"}))
        {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "message Upali hi", ctx.dev)
        assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}
        assert_receive {:risi_post, _, _, %{"kind" => "answer"}}
        args = action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
        task = Task.async(fn -> perform_job(Job, args) end)
        d = wait_call(ctx.harsha.user.id, card["write_id"])["data"]
        {204, _} = result(ctx, d["tool_call_id"], reply, ctx.dev)
        Task.await(task)
        assert_receive {:risi_post, _, ^want, %{"kind" => "answer"}}
        refute_received {:risi_post, _, _, %{"kind" => "message_sent"}}
      end

      # Timeout: the §25.4 tool_timeout error with Retry.
      Application.put_env(:risime, :risi_tool_deadline_ms, 200)
      scripted_llm!(send_script(%{"to" => "Upali", "text" => "Hi"}))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "message Upali hi", ctx.dev)
      assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}
      args = action_args(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
      perform_job(Job, args)

      assert_receive {:risi_post, _,
                      "I couldn't reach your phone to send it. Tap Retry to try again.",
                      %{"kind" => "error", "code" => "tool_timeout"}}
    end

    test "two Upalis: 'Which Upali?' with one chip each; a chip gives that person's card", ctx do
      send_on!()
      silva = RisiMe.Fixtures.logged_in_user(display_name: "Upali Silva")
      RisiMe.Fixtures.befriend!(ctx.harsha, silva)
      scripted_llm!(send_script(%{"to" => "Upali", "text" => "Please update."}))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "message Upali to update", ctx.dev)

      assert_receive {:risi_post, _, "Which Upali?", %{"kind" => "answer"} = q}
      refute_received {:risi_post, _, _, %{"kind" => "confirm"}}

      assert q["next_actions"] == [
               %{"label" => "Upali Perera", "action" => "ask", "text" => "Use Upali Perera"},
               %{"label" => "Upali Silva", "action" => "ask", "text" => "Use Upali Silva"}
             ]

      assert q["clarify"]["keep"] == false

      scripted_llm!([%{"actions" => [final("MODEL")]}])
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "Use Upali Silva", ctx.dev)
      assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}
      assert card["send"]["to"] == %{"name" => "Upali Silva", "user_id" => silva.user.id}
      # The message text is kept exactly (never rewritten with the full name).
      assert card["args"]["text"] == "Please update."

      assert card["args"]["conversation_id"] ==
               RisiMe.Messaging.conversation_id(ctx.harsha.user.id, silva.user.id)

      assert llm_requests() == []
    end

    test "no 1:1 chat, unknown name, Official off: said so, no card", ctx do
      send_on!()
      # A co-member who isn't a friend: no 1:1 chat.
      kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
      _og = risi_chat!([ctx.harsha.user, kamal.user])
      scripted_llm!(send_script(%{"to" => "Kamal", "text" => "Hi"}))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "message Kamal hi", ctx.dev)

      assert_receive {:risi_post, _, body, %{"kind" => "answer"}}

      assert body ==
               "You don't have a 1:1 chat with Kamal yet. Open Contacts → Kamal to start one, then ask me again."

      scripted_llm!(send_script(%{"to" => "Zebediah", "text" => "Hi"}))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "message Zebediah hi", ctx.dev)
      assert_receive {:risi_post, _, "I couldn't find Zebediah in your contacts.", _}

      scripted_llm!(send_script(%{"to" => "Upali", "text" => "Hi", "tab" => "official"}))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "message Upali in official hi", ctx.dev)
      assert_receive {:risi_post, _, "Official is off in your chat with Upali Perera.", _}

      refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
    end

    test "Phase 1 (no risi_send device): the draft card, nothing claimed sent", ctx do
      send_on!()
      old = skills_device!(ctx.harsha.user)
      text = "Hi Upali, please update RisiMe."
      scripted_llm!(send_script(%{"to" => "Upali", "text" => text}))
      {rid, :ok} = ask!(ctx.rc, ctx.harsha.user, "send Upali a message to update", old)

      assert_receive {:risi_post, rc, body, %{"kind" => "draft"} = draft}
      assert rc == ctx.rc and body == "Message for Upali Perera: " <> text

      assert %{
               "request_id" => ^rid,
               "text" => ^text,
               "language" => "en",
               "target_conversation_id" => target
             } = draft

      assert target == dm(ctx)

      assert_receive {:risi_post, ^rc, line, %{"kind" => "answer"}}

      assert line ==
               "Here's the message for Upali Perera. Tap Use to open your chat with it ready, then send it."

      refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
      assert events(ctx.harsha.user.id, "risi_tool_call") == []
    end

    test "Sinhala: the card line and the sent line in Sinhala", _ctx do
      assert SendForMe.line(:sent, "si", "උපාලි") == "✓ උපාලි ට යැව්වා"
      assert SendForMe.line(:sent, "fr", "Upali") == "✓ Sent to Upali"
    end
  end

  ## schedule_message only with a time (§42.4)

  describe "schedule_message (§42.4)" do
    setup ctx do
      patch!(ctx, [
        %{"id" => "scheduled_messages", "state" => "ask", "client_permission" => "granted"}
      ])

      :ok
    end

    defp schedule_script(at),
      do: [
        %{
          "task" => "risi_next_action",
          "actions" => [
            %{
              "tool" => "schedule_message",
              "args" => %{"to" => "Upali", "text" => "Good morning", "at" => at}
            },
            final("MODEL")
          ]
        }
      ]

    test "no time (now): refused with the reason, while the switch is on", ctx do
      send_on!()
      # What a model writes for "now": a time a few seconds ahead.
      soon = RisiMe.Agent.Clock.now() |> DateTime.add(30, :second) |> RisiMe.Agent.Clock.ts()
      scripted_llm!(schedule_script(soon))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "send Upali good morning", ctx.dev)
      refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
      [_, step2] = llm_requests()
      assert Jason.encode!(step2["messages"]) =~ "no_time"
    end

    test "with a time: the one-tap card [Schedule] [Edit] for a risi_send device", ctx do
      send_on!()
      scripted_llm!(schedule_script("tomorrow 9am"))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "send Upali good morning tomorrow 9am", ctx.dev)

      assert_receive {:risi_post, _, _,
                      %{"kind" => "confirm", "tool" => "schedule_message"} = card}

      assert card["buttons"] == ["schedule", "edit", "cancel"]
      assert card["send"]["to"]["name"] == "Upali Perera"
      assert card["send"]["tab"] == "private"

      # An older (non-risi_send) device keeps [Add] [Cancel].
      old = skills_device!(ctx.harsha.user)
      scripted_llm!(schedule_script("tomorrow 9am"))
      {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "send Upali good morning tomorrow 9am", old)
      assert_receive {:risi_post, _, _, %{"kind" => "confirm", "tool" => "schedule_message"} = c2}
      assert c2["buttons"] == ["add", "cancel"]
      refute Map.has_key?(c2, "send")
    end
  end
end
