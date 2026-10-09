defmodule RisiMe.Agent.RemindersS15Test do
  @moduledoc """
  v1.25 §25.4/§25.5, v1.26 §26.3/§26.4 (server S15): `set_reminder` — times resolved from the
  request's `server_ts` in the asker's zone (asks on ambiguity, refuses the past and more than a
  year ahead), the confirm card → `risi_reminders` (sealed text) + the Oban fire → `reminder`
  to exactly the participants; Me too / Not me from human members only, each changing only the
  tapper; "Allowed" for a "remind me" only; undo before firing; `cancel_pending`.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5]

  alias RisiMe.Agent.{Reminders, Writes}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    nimal = RisiMe.Fixtures.logged_in_user(display_name: "Nimal")
    og = risi_chat!([harsha.user, kamal.user, nimal.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    dev = RisiMe.TabsHelpers.risi_tools_device!(harsha.user)
    %{harsha: harsha, kamal: kamal, nimal: nimal, og: og, dev: dev}
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

  defp action!(conv, user, action, target, device \\ nil) do
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
    perform_job(Job, job.args)
  end

  defp remind(args),
    do: [
      %{
        "task" => "risi_next_action",
        "actions" => [
          %{"tool" => "set_reminder", "args" => args},
          %{"tool" => "final", "answer" => "Done.", "sources" => [], "next_steps" => []}
        ]
      }
    ]

  defp fire_job(reminder_id) do
    [job] =
      for j <- all_enqueued(worker: Job),
          j.args["kind"] == "reminder_fire" and j.args["reminder_id"] == reminder_id,
          do: j

    job
  end

  test "q2 \"Remind us about Tuesday 2pm\": card, [Add], Me too, fires to exactly the participants",
       ctx do
    scripted_llm!()
    h = ctx.harsha.user
    {rid, :ok} = ask!(ctx.og, h, "Remind us about Tuesday 2pm", ctx.dev)

    # The card is the conversation's own (a group reminder): it stays in the group.
    assert_receive {:risi_post, og, body, card}
    assert og == ctx.og and card["kind"] == "confirm" and card["tool"] == "set_reminder"
    assert card["request_id"] == rid and card["text"] == "Budget review"
    assert card["summary"] =~ ~r/^Remind this chat on Tue \d+ \w+ at 14:00: Budget review/
    assert body =~ "Update RisiMe to answer"
    {:ok, start, _} = DateTime.from_iso8601(card["when"]["start"])
    assert RisiMe.Agent.Clock.local(start, "Asia/Colombo").hour == 14
    assert Date.day_of_week(RisiMe.Agent.Clock.local(start, "Asia/Colombo")) == 2
    assert_receive {:risi_post, ^og, _, %{"kind" => "answer"}}

    # Kamal's confirm is ignored; Harsha's sets it.
    :ok = action!(ctx.og, ctx.kamal.user, "confirm_write", card["write_id"])
    refute_receive {:risi_post, _, _, %{"kind" => "reminder_set"}}, 50
    :ok = action!(ctx.og, h, "confirm_write", card["write_id"], ctx.dev)
    assert_receive {:risi_post, ^og, set_body, set}
    assert set["kind"] == "reminder_set" and set["participants"] == [h.id] and set["me_too"]
    assert set["when"] == card["when"]["start"] and set["text"] == "Budget review"
    assert set_body =~ "I'll remind Harsha on Tue" and set_body =~ "Tap Me too"
    assert Writes.get(card["write_id"]).state == "done"

    r = Reminders.get(set["reminder_id"])
    assert r.state == "pending" and r.conversation_id == ctx.og
    refute r.text =~ "Budget"
    assert DateTime.compare(fire_job(r.reminder_id).scheduled_at, start) == :eq

    # A repeated confirm sets nothing more.
    :ok = action!(ctx.og, h, "confirm_write", card["write_id"], ctx.dev)
    refute_receive {:risi_post, _, _, %{"kind" => "reminder_set"}}, 50

    # Me too / Not me change only the tapper; the asker's Me too is a no-op.
    :ok = action!(ctx.og, ctx.kamal.user, "me_too", r.reminder_id)
    :ok = action!(ctx.og, h, "me_too", r.reminder_id)
    :ok = action!(ctx.og, ctx.nimal.user, "not_me", r.reminder_id)
    assert Reminders.get(r.reminder_id).participants == [h.id, ctx.kamal.user.id]
    :ok = action!(ctx.og, ctx.kamal.user, "not_me", r.reminder_id)
    :ok = action!(ctx.og, ctx.nimal.user, "me_too", r.reminder_id)
    assert Reminders.get(r.reminder_id).participants == [h.id, ctx.nimal.user.id]

    # Someone outside the group can't join.
    stranger = RisiMe.GroupHelpers.fast_user!("Stranger")
    :ok = action!(ctx.og, stranger, "me_too", r.reminder_id)
    assert Reminders.get(r.reminder_id).participants == [h.id, ctx.nimal.user.id]

    # It fires once, to exactly the participants.
    :ok = perform_job(Job, fire_job(r.reminder_id).args)
    assert_receive {:risi_post, ^og, "Reminder: Budget review", rem}
    assert rem["kind"] == "reminder" and rem["reminder_id"] == r.reminder_id
    assert rem["commitment_id"] == nil and rem["text"] == "Budget review"
    assert rem["notify"] == [h.id, ctx.nimal.user.id] and rem["due"] == set["when"]
    r = Reminders.get(r.reminder_id)
    assert r.state == "fired" and r.text == nil

    :ok =
      perform_job(Job, %{
        "kind" => "reminder_fire",
        "conv" => ctx.og,
        "reminder_id" => r.reminder_id
      })

    refute_receive {:risi_post, _, _, %{"kind" => "reminder"}}, 50
    :ok = action!(ctx.og, ctx.kamal.user, "me_too", r.reminder_id)
    assert Reminders.get(r.reminder_id).participants == [h.id, ctx.nimal.user.id]
  end

  test "the asker's Not me drops a reminder with no one left", ctx do
    scripted_llm!(
      remind(%{"when" => "tomorrow 9am", "text" => "Standup", "audience" => "conversation"})
    )

    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "remind us", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}
    :ok = action!(ctx.og, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "reminder_set", "reminder_id" => id}}
    :ok = action!(ctx.og, ctx.harsha.user, "not_me", id)
    assert Reminders.get(id).state == "cancelled"
    assert [] == for(j <- all_enqueued(worker: Job), j.args["reminder_id"] == id, do: j)
  end

  test "ambiguous, past and far times: the step fails with the reason and the final asks", ctx do
    for {phrase, reason} <- [
          {"Tuesday at 6", "ambiguous_time"},
          {"Tuesday", "ambiguous_time"},
          {"2020-01-01T09:00:00Z", "in_the_past"},
          {"2030-01-01T09:00:00Z", "more_than_a_year_ahead"},
          {"when the moon is full", "unreadable_time"}
        ] do
      scripted_llm!(remind(%{"when" => phrase, "text" => "x", "audience" => "me"}))
      {_, :ok} = ask!(ctx.og, ctx.harsha.user, "remind me", ctx.dev)
      [_, second] = llm_requests()
      res = Jason.decode!(List.last(second["messages"])["content"])
      assert res["ok"] == false and res["status"] == "failed"
      assert res["reason"] =~ reason
      assert_receive {:risi_post, _, _, %{"kind" => "answer", "steps" => steps}}
      assert steps == [%{"tool" => "set_reminder", "status" => "failed"}]
      refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
    end
  end

  test "remind me: a personal confirm goes to the Risi chat; Allowed skips it (me only)", ctx do
    h = ctx.harsha.user
    rc = own_risi_chat!(h)
    skills_on!()
    sdev = skills_device!(h)

    {200, _} =
      api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{
          "changes" => [
            %{"id" => "reminders", "state" => "ask", "client_permission" => "granted"}
          ]
        },
        sdev
      )

    scripted_llm!(
      remind(%{"when" => "in 2 hours", "text" => "Call the bank", "audience" => "me"})
    )

    {_, :ok} = ask!(ctx.og, h, "remind me to call the bank", sdev)
    assert_receive {:risi_post, ^rc, _, %{"kind" => "confirm"} = card}
    assert card["skill_id"] == "reminders" and card["summary"] =~ "Remind you on"
    refute Map.has_key?(card, "args")
    assert_receive {:risi_post, _, _, %{"kind" => "answer"}}
    assert_receive {:risi_post, og, "I've replied in your Risi chat.", _}
    assert og == ctx.og

    {200, _} =
      api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{"changes" => [%{"id" => "reminders", "state" => "allowed"}]},
        sdev
      )

    # Allowed: no card, reminder_set in the Risi chat, an entry with a server undo.
    scripted_llm!(remind(%{"when" => "in 3 hours", "text" => "Pick up Kumu", "audience" => "me"}))
    {_, :ok} = ask!(ctx.og, h, "remind me to pick up Kumu", sdev)
    assert_receive {:risi_post, ^rc, _, %{"kind" => "reminder_set"} = set}
    refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
    assert set["me_too"] == false and set["participants"] == [h.id]

    {200, %{"entries" => [e | _]}} =
      api(:get, "/api/v1/risi/skills/reminders/activity", ctx.harsha.token, nil, sdev)

    assert e["action"] == "reminder_set" and e["via"] == "allowed"
    assert e["undo"]["kind"] == "server" and e["undo"]["state"] == "available"
    assert e["undo"]["until"] == set["when"]

    # Allowed never skips the card for a conversation reminder.
    scripted_llm!(
      remind(%{"when" => "in 3 hours", "text" => "Team lunch", "audience" => "conversation"})
    )

    {_, :ok} = ask!(ctx.og, h, "remind us about lunch", sdev)
    assert_receive {:risi_post, ^og, _, %{"kind" => "confirm", "text" => "Team lunch"}}

    # The server undo: 200 done, the reminder cancelled, a reminder_cancelled entry.
    path = "/api/v1/risi/skills/reminders/activity/#{e["entry_id"]}/undo"

    {200, %{"entry" => u}} =
      api(:post, path, ctx.harsha.token, %{"undo_token" => e["undo_token"]}, sdev)

    assert u["undo"]["state"] == "done" and u["undo_token"] == nil
    assert Reminders.get(set["reminder_id"]).state == "cancelled"
    {409, _} = api(:post, path, ctx.harsha.token, %{"undo_token" => e["undo_token"]}, sdev)

    {200, %{"entries" => [c | _]}} =
      api(:get, "/api/v1/risi/skills/reminders/activity", ctx.harsha.token, nil, sdev)

    assert c["action"] == "reminder_cancelled" and c["via"] == "undo"
  end

  test "revoke with cancel_pending cancels the pending reminders", ctx do
    h = ctx.harsha.user
    own_risi_chat!(h)
    skills_on!()
    sdev = skills_device!(h)

    {200, _} =
      api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{"changes" => [%{"id" => "reminders", "state" => "allowed"}]},
        sdev
      )

    scripted_llm!(remind(%{"when" => "in 2 hours", "text" => "Gym", "audience" => "me"}))
    {_, :ok} = ask!(ctx.og, h, "remind me about the gym", sdev)
    assert_receive {:risi_post, _, _, %{"kind" => "reminder_set", "reminder_id" => id}}

    {200, _} =
      api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        %{"changes" => [%{"id" => "reminders", "state" => "off"}], "cancel_pending" => true},
        sdev
      )

    assert Reminders.get(id).state == "cancelled"

    {200, %{"entries" => es}} =
      api(:get, "/api/v1/risi/skills/reminders/activity", ctx.harsha.token, nil, sdev)

    assert Enum.map(es, & &1["action"]) |> Enum.take(2) == ~w(reminder_cancelled skill_off)
  end

  test "set_reminder needs an app that can answer a card (a risi_tools asking device)", ctx do
    scripted_llm!([
      %{
        "actions" => [%{"tool" => "final", "answer" => "ok", "sources" => [], "next_steps" => []}]
      }
    ])

    {_, :ok} = ask!(ctx.og, ctx.kamal.user, "remind us", nil)
    [req] = llm_requests()

    tools =
      for alt <- req["response_format"]["json_schema"]["schema"]["anyOf"],
          do: hd(alt["properties"]["tool"]["enum"])

    refute "set_reminder" in tools
  end
end
