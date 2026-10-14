defmodule RisiMe.Agent.PermissionFixitTest do
  @moduledoc """
  Proposal 2026-10-14-permission-fixit (§43; Harsha 10:55/10:57): never just "can't access". One
  sentence, one button ([Allow calendar]); once allowed the original request runs again by itself;
  "no" gets one sentence and nothing more. One test per permission (calendar, notifications,
  alarms), the phone's own `no_permission`, and old phones keeping `skill_needed`.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.PermissionFixit
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @caps ~w(groups member_devices tabs risi_tools risi_skills risi_fixit)

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    restore_on_exit([:risi_permission_fixit])
    Application.put_env(:risime, :risi_permission_fixit, :on)
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    _og = risi_chat!([harsha.user, kamal.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = RisiMe.TabsHelpers.tabs_device!(harsha.user, caps: @caps)
    rc = own_risi_chat!(harsha.user)
    %{harsha: harsha, dev: dev, rc: rc}
  end

  defp ask!(ctx, text, device) do
    rid = Ecto.UUID.generate()

    envelope!(
      ctx.rc,
      ctx.harsha.user,
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

  defp answer!(ctx, action, resume_id) do
    id =
      envelope!(
        ctx.rc,
        ctx.harsha.user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "action" => action,
          "target" => resume_id,
          "edit" => nil
        },
        ctx.dev
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    perform_job(Job, job.args)
  end

  defp final(a), do: %{"tool" => "final", "answer" => a, "sources" => [], "next_steps" => []}

  # The model asks for the skill while it is missing; once it's there it answers "resumed".
  defp model!(skill) do
    fake_llm!(fn "risi_next_action", body ->
      enum =
        Enum.find_value(body["response_format"]["json_schema"]["schema"]["anyOf"], [], fn alt ->
          if alt["properties"]["tool"]["enum"] == ["need_skill"],
            do: alt["properties"]["args"]["properties"]["skill_id"]["enum"]
        end)

      if skill in enum,
        do: %{"tool" => "need_skill", "args" => %{"skill_id" => skill}},
        else: final("resumed #{skill}")
    end)
  end

  defp patch!(ctx, change),
    do:
      {200, _} =
        api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, ctx.dev)

  for {skill, permission, sentence} <- [
        {"calendar", "calendar", "I need your calendar to do that."},
        {"reminders", "notifications", "I need your notifications to do that."},
        {"alarm", "alarms", "I need your alarms to do that."}
      ] do
    test "#{permission}: card with the button → allowed → the original request runs again", ctx do
      model!(unquote(skill))
      {rid, :ok} = ask!(ctx, "help me with this please", ctx.dev)

      assert_receive {:risi_post, rc, body, %{"kind" => "needs_permission"} = card}
      assert rc == ctx.rc
      assert body =~ unquote(sentence)

      assert %{
               "request_id" => ^rid,
               "resume_id" => resume_id,
               "permission" => unquote(permission),
               "skill_id" => unquote(skill),
               "turn_on" => true,
               "text" => unquote(sentence),
               "buttons" => ["allow"],
               "notify" => [_]
             } = card

      refute_received {:risi_post, _, _, %{"kind" => "skill_needed"}}

      # The phone: the Android prompt said yes → the PATCH, then permission_granted.
      patch!(ctx, %{"id" => unquote(skill), "state" => "ask", "client_permission" => "granted"})
      :ok = answer!(ctx, "permission_granted", resume_id)

      [job] = all_enqueued(worker: Job, args: %{"kind" => "resume", "request_id" => rid})
      assert :ok = perform_job(Job, job.args)
      assert_receive {:risi_post, ^rc, answer, %{"kind" => "answer", "request_id" => ^rid}}
      assert answer == "resumed #{unquote(skill)}"

      # One use only: a second "granted" does nothing.
      :ok = answer!(ctx, "permission_granted", resume_id)

      assert length(all_enqueued(worker: Job, args: %{"kind" => "resume", "request_id" => rid})) ==
               1
    end
  end

  test "declined: one sentence and the Risi skills link, nothing more", ctx do
    model!("calendar")
    {rid, :ok} = ask!(ctx, "add dentist friday 10am", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "needs_permission", "resume_id" => resume_id}}
    :ok = answer!(ctx, "permission_declined", resume_id)

    assert_receive {:risi_post, _, text, %{"kind" => "answer", "request_id" => ^rid} = a}
    assert text == "OK, I won't use your calendar. You can change this in Risi skills."

    assert a["next_actions"] == [
             %{"label" => "Risi skills", "action" => "open", "target" => "settings.risi_skills"}
           ]

    assert all_enqueued(worker: Job, args: %{"kind" => "resume"}) == []
  end

  test "someone else's answer, or one in another chat, is ignored", ctx do
    model!("calendar")
    {_, :ok} = ask!(ctx, "add dentist friday 10am", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "needs_permission", "resume_id" => resume_id}}
    other = RisiMe.Fixtures.logged_in_user(display_name: "Other")

    assert :ok =
             PermissionFixit.act(ctx.rc, other.user.id, %{
               "action" => "permission_granted",
               "target" => resume_id
             })

    assert :ok =
             PermissionFixit.act("grp:" <> Ecto.UUID.generate(), ctx.harsha.user.id, %{
               "action" => "permission_granted",
               "target" => resume_id
             })

    assert all_enqueued(worker: Job, args: %{"kind" => "resume"}) == []
  end

  test "the phone's own no_permission in a turn gives the card", ctx do
    patch!(ctx, %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"})

    scripted_llm!([
      %{
        "task" => "risi_next_action",
        "actions" => [
          %{
            "tool" => "calendar_check",
            "args" => %{"from" => "2026-10-06T00:00:00Z", "to" => "2026-10-07T00:00:00Z"}
          },
          final("x")
        ]
      }
    ])

    task = Task.async(fn -> ask!(ctx, "am I free tuesday", ctx.dev) end)

    call =
      Enum.find_value(1..150, fn _ ->
        case events(ctx.harsha.user.id, "risi_tool_call") do
          [c | _] -> c
          [] -> Process.sleep(20) && nil
        end
      end)

    {204, _} =
      api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "no_permission", "result" => nil},
        ctx.dev
      )

    {_, :ok} = Task.await(task)

    assert_receive {:risi_post, _, _,
                    %{"kind" => "needs_permission", "permission" => "calendar"} = c}

    assert c["turn_on"] == false
  end

  test "a phone without risi_fixit (or the switch off) keeps skill_needed", ctx do
    old = skills_device!(ctx.harsha.user)
    model!("calendar")
    {_, :ok} = ask!(ctx, "add dentist friday 10am", old)
    assert_receive {:risi_post, _, _, %{"kind" => "skill_needed"}}
    refute_received {:risi_post, _, _, %{"kind" => "needs_permission"}}

    Application.put_env(:risime, :risi_permission_fixit, :off)
    model!("calendar")
    {_, :ok} = ask!(ctx, "add dentist friday 10am", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "skill_needed"}}
  end

  test "Sinhala and Tamil sentences" do
    assert PermissionFixit.line(:need, "calendar", "si") == "ඒක කරන්න මට ඔබේ calendar ඕන."
    assert PermissionFixit.line(:declined, "calendar", "xx") =~ "OK, I won't use your calendar."
  end
end
