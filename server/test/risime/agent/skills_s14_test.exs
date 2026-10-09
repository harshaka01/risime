defmodule RisiMe.Agent.SkillsS14Test do
  @moduledoc """
  v1.26 §26 (server S14): the skills registry and REST (`GET`/`PATCH /api/v1/risi/skills`, every
  error, all-or-none), `/auth/config` and `/health`, the `authorize/3` skill and device gates,
  `need_skill` → `skill_needed`, Allowed vs Ask, revoke voiding open cards and ignoring a late
  `ok`, the sealed activity log (GET/DELETE) and undo (client 202 → done/failed, timeout,
  tokens), and v1.25 behaviour for users without a `risi_skills` device.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]
  import Ecto.Query, only: [from: 2]

  alias RisiMe.Agent.{Skills, Tools, Writes}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    og = risi_chat!([harsha.user, kamal.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = skills_device!(harsha.user)
    rc = own_risi_chat!(harsha.user)
    %{harsha: harsha, kamal: kamal, og: og, dev: dev, rc: rc}
  end

  defp get!(ctx, path, device \\ :default),
    do: api(:get, path, ctx.harsha.token, nil, if(device == :default, do: ctx.dev, else: device))

  defp patch!(ctx, body, device \\ :default),
    do:
      api(
        :patch,
        "/api/v1/risi/skills",
        ctx.harsha.token,
        body,
        if(device == :default, do: ctx.dev, else: device)
      )

  defp set!(ctx, id, state, perm \\ nil) do
    change = %{"id" => id, "state" => state}
    change = if perm, do: Map.put(change, "client_permission", perm), else: change
    {200, _} = patch!(ctx, %{"changes" => [change]})
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

  defp action!(conv, user, action, target, device) do
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

  defp wait_call(user_id, n \\ 1, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      calls when length(calls) >= n ->
        Enum.at(calls, n - 1)

      _ when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, n, tries - 1)

      _ ->
        flunk("no risi_tool_call event")
    end
  end

  defp result!(ctx, id, body),
    do: api(:post, "/api/v1/risi/tool_calls/#{id}/result", ctx.harsha.token, body, ctx.dev)

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

  defp pub_get(path) do
    conn =
      Phoenix.ConnTest.dispatch(
        Phoenix.ConnTest.build_conn(),
        RisiMeWeb.Endpoint,
        :get,
        path,
        nil
      )

    {conn.status, Jason.decode!(conn.resp_body)}
  end

  defp final(answer \\ "ok"),
    do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}

  test "GET /risi/skills: the registry in order, every skill off, the device's client", ctx do
    {200, %{"skills" => skills}} = get!(ctx, "/api/v1/risi/skills")
    ex = RisiMe.TabsHelpers.example("risi_skills_reply.json")["skills"]
    assert Enum.map(skills, & &1["id"]) == ~w(alarm reminders calendar scheduled_messages email)

    for {ours, theirs} <- Enum.zip(skills, ex) do
      assert Map.keys(ours) |> Enum.sort() == Map.keys(theirs) |> Enum.sort()

      for k <-
            ~w(id kind title description can cannot permissions tools where modes undo available),
          do: assert(ours[k] == theirs[k], "#{ours["id"]} #{k}")

      assert ours["state"] == "off" and ours["state_changed_at"] == nil
    end

    assert Enum.at(skills, 0)["client"]["permission"] == "not_needed"
    assert Enum.at(skills, 2)["client"]["permission"] == "unknown"
    assert Enum.at(skills, 2)["client"]["device_id"] == ctx.dev
    assert Enum.at(skills, 4)["client"] == nil

    # Without X-Device-Id: no client.
    {200, %{"skills" => skills}} = get!(ctx, "/api/v1/risi/skills", nil)
    assert Enum.all?(skills, &(&1["client"] == nil))

    # Off: 503.
    Application.put_env(:risime, :risi_skills, false)
    {503, %{"error" => %{"code" => "agent_unavailable"}}} = get!(ctx, "/api/v1/risi/skills")
  end

  test "PATCH /risi/skills: errors, all or none, the reply and the entries", ctx do
    old = RisiMe.TabsHelpers.risi_tools_device!(ctx.harsha.user)
    body = RisiMe.TabsHelpers.example("risi_skills_patch.json")

    {403, %{"error" => %{"code" => "invalid_device"}}} = patch!(ctx, body, old)
    {403, _} = patch!(ctx, body, nil)

    {404, _} = patch!(ctx, %{"changes" => [%{"id" => "teleport", "state" => "ask"}]})

    {409, %{"error" => %{"code" => "skill_unavailable"}}} =
      patch!(ctx, %{"changes" => [%{"id" => "email", "state" => "ask"}]})

    {422, _} =
      patch!(ctx, %{"changes" => [%{"id" => "scheduled_messages", "state" => "allowed"}]})

    {422, _} = patch!(ctx, %{"changes" => []})
    {422, _} = patch!(ctx, %{"changes" => [%{"id" => "alarm"}]})
    {422, _} = patch!(ctx, %{"changes" => [%{"id" => "alarm", "state" => "on"}]})

    {422, _} =
      patch!(ctx, %{
        "changes" => [%{"id" => "alarm", "state" => "ask"}, %{"id" => "alarm", "state" => "off"}]
      })

    # All or none: a valid change next to an unavailable one applies nothing.
    {409, _} =
      patch!(ctx, %{
        "changes" => [
          %{"id" => "calendar", "state" => "ask"},
          %{"id" => "email", "state" => "ask"}
        ]
      })

    assert Skills.state(ctx.harsha.user.id, "calendar") == "off"

    {200, %{"skills" => [cal, sm]}} = patch!(ctx, body)
    ex = RisiMe.TabsHelpers.example("risi_skills_patch_reply.json")["skills"]
    assert Enum.map([cal, sm], &Map.keys/1) == Enum.map(ex, &Map.keys/1)
    assert cal["state"] == "ask" and cal["client"]["permission"] == "granted"
    assert cal["state_changed_at"] =~ ~r/Z$/
    assert sm["state"] == "off" and sm["client"]["permission"] == "denied"

    # The entry (sealed: the summary isn't in the row in plaintext).
    {200, %{"entries" => [e], "has_more" => false}} =
      get!(ctx, "/api/v1/risi/skills/calendar/activity")

    assert e["action"] == "skill_on" and e["via"] == "settings"
    assert e["summary"] == "Turned on Calendar (Ask me each time)"
    assert e["undo"] == %{"kind" => "none", "state" => nil, "until" => nil, "hint" => nil}
    assert e["undo_token"] == nil and e["device_id"] == ctx.dev

    [row] = Repo.all(from a in Skills.Entry, where: a.entry_id == ^e["entry_id"])
    refute row.sealed =~ "Turned on"

    set!(ctx, "calendar", "allowed")
    set!(ctx, "calendar", "ask")
    set!(ctx, "calendar", "off")

    {200, %{"entries" => es}} = get!(ctx, "/api/v1/risi/skills/calendar/activity?limit=2")
    assert Enum.map(es, & &1["action"]) == ~w(skill_off skill_ask)

    {200, %{"entries" => older, "has_more" => false}} =
      get!(
        ctx,
        "/api/v1/risi/skills/calendar/activity?limit=5&before=#{List.last(es)["entry_id"]}"
      )

    assert Enum.map(older, & &1["action"]) == ~w(skill_allowed skill_on)
    {404, _} = get!(ctx, "/api/v1/risi/skills/teleport/activity")
    {422, _} = get!(ctx, "/api/v1/risi/skills/calendar/activity?limit=0")

    {204, _} =
      api(:delete, "/api/v1/risi/skills/calendar/activity", ctx.harsha.token, nil, ctx.dev)

    {200, %{"entries" => []}} = get!(ctx, "/api/v1/risi/skills/calendar/activity")
  end

  test "auth config and health: risi_skills only with the switch and the memory key", _ctx do
    {200, cfg} = pub_get("/api/v1/auth/config")
    assert cfg["risi_skills"] == "on"
    assert Skills.health() == "ok"

    Application.delete_env(:risime, :risi_memory_key)
    {200, cfg} = pub_get("/api/v1/auth/config")
    refute Map.has_key?(cfg, "risi_skills")
    assert Skills.health() =~ "unavailable: missing_key RISI_MEMORY_KEY"
    {200, h} = pub_get("/health")
    assert h["checks"]["risi_skills"] =~ "RISI_MEMORY_KEY"

    Application.put_env(:risime, :risi_skills, false)
    assert Skills.health() == "off"
  end

  test "risi_skills is kept only with risi_tools", ctx do
    d = Ecto.UUID.generate()

    {:ok, _} =
      RisiMe.Devices.register(ctx.kamal.user.id, d, %{
        "platform" => "android",
        "mls" => %{
          "signature_key" => Base.encode64(:crypto.strong_rand_bytes(32)),
          "capabilities" => ~w(groups tabs risi_skills)
        }
      })

    refute "risi_skills" in RisiMe.Devices.get(ctx.kamal.user.id, d).capabilities
    refute Skills.gated?(ctx.kamal.user.id)
    assert Skills.gated?(ctx.harsha.user.id)
  end

  test "authorize/3: skill state and device permission; need_skill lists the rest", ctx do
    scripted_llm!([%{"actions" => [final()]}])
    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "hi", ctx.dev)
    [req] = llm_requests()
    # Every skill is off for a gated user: no calendar tool, and need_skill offers them.
    assert schema_tools(req) == ~w(capabilities need_skill final)
    assert need_enum(req) == ~w(alarm reminders calendar scheduled_messages email)

    set!(ctx, "calendar", "ask", "granted")
    scripted_llm!([%{"actions" => [final()]}])
    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "hi", ctx.dev)
    [req] = llm_requests()
    assert schema_tools(req) == ~w(capabilities calendar_check calendar_add need_skill final)
    refute "calendar" in need_enum(req)

    # The phone reports the permission denied: not offered there, need_skill says why.
    {200, _} =
      patch!(ctx, %{"changes" => [%{"id" => "calendar", "client_permission" => "denied"}]})

    scripted_llm!([%{"actions" => [final()]}])
    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "hi", ctx.dev)
    [req] = llm_requests()
    assert schema_tools(req) == ~w(capabilities need_skill final)
    assert "calendar" in need_enum(req)

    # A user without a risi_skills device keeps v1.25: calendar tools, no need_skill.
    k = ctx.kamal.user
    kdev = RisiMe.TabsHelpers.risi_tools_device!(k)
    own_risi_chat!(k)
    scripted_llm!([%{"actions" => [final()]}])
    {_, :ok} = ask!(ctx.og, k, "hi", kdev)
    [req] = llm_requests()
    assert schema_tools(req) == ~w(capabilities calendar_check calendar_add final)
  end

  test "need_skill: a skill_needed card in the Risi chat, the pointer in the group", ctx do
    rules = [
      %{
        "task" => "risi_next_action",
        "actions" => [%{"tool" => "need_skill", "args" => %{"skill_id" => "calendar"}}]
      }
    ]

    scripted_llm!(rules)
    {rid, :ok} = ask!(ctx.og, ctx.harsha.user, "Add dentist Friday 10am", ctx.dev)
    assert_receive {:risi_post, rc, body, n}
    assert rc == ctx.rc and n["kind"] == "skill_needed" and n["request_id"] == rid
    assert n["skill_id"] == "calendar" and n["reason"] == "off" and n["was_on"] == false
    assert n["buttons"] == ["open_skills"] and n["notify"] == [ctx.harsha.user.id]

    assert body ==
             "I don't have access to your calendar yet. Turn it on in Settings → Risi skills."

    assert_receive {:risi_post, og, "I've replied in your Risi chat.", _}
    assert og == ctx.og

    rows = RisiMe.Agent.TurnSteps.list(n["turn_ref"])
    assert [%{tool: "need_skill", status: "denied"}] = rows

    # Turned on, then off: "no longer".
    set!(ctx, "calendar", "ask")
    set!(ctx, "calendar", "off")
    scripted_llm!(rules)
    {_, :ok} = ask!(ctx.rc, ctx.harsha.user, "Add dentist Friday 10am", ctx.dev)
    assert_receive {:risi_post, _, body, %{"kind" => "skill_needed", "was_on" => true}}
    assert body =~ "I no longer have access to your calendar"
  end

  test "revoke voids open cards: a later confirm gets skill_needed, no write", ctx do
    set!(ctx, "calendar", "ask", "granted")
    scripted_llm!()
    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "Add dentist Friday 10am", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}
    assert card["skill_id"] == "calendar" and is_map(card["args"])

    set!(ctx, "calendar", "off")
    assert Writes.get(card["write_id"]).state == "void"

    args = action!(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
    assert :ok = perform_job(Job, args)
    assert_receive {:risi_post, rc, _, %{"kind" => "skill_needed", "was_on" => true}}
    assert rc == ctx.rc
    assert events(ctx.harsha.user.id, "risi_tool_call") == []
  end

  test "an ok arriving after the revoke is ignored and logs nothing", ctx do
    set!(ctx, "calendar", "ask", "granted")
    scripted_llm!()
    {_, :ok} = ask!(ctx.og, ctx.harsha.user, "Add dentist Friday 10am", ctx.dev)
    assert_receive {:risi_post, _, _, %{"kind" => "confirm"} = card}

    args = action!(ctx.rc, ctx.harsha.user, "confirm_write", card["write_id"], ctx.dev)
    task = Task.async(fn -> perform_job(Job, args) end)
    d = wait_call(ctx.harsha.user.id)["data"]
    set!(ctx, "calendar", "off")

    {204, _} =
      result!(ctx, d["tool_call_id"], %{"status" => "ok", "result" => %{"event_id" => "e"}})

    assert :ok = Task.await(task)
    refute_receive {:risi_post, _, _, %{"kind" => "skill_done"}}, 100

    {200, %{"entries" => es}} = get!(ctx, "/api/v1/risi/skills/calendar/activity")
    refute Enum.any?(es, &(&1["action"] == "calendar_added"))
  end

  test "Allowed: calendar_add runs without a card, skill_done with Undo; undo removes it", ctx do
    set!(ctx, "calendar", "allowed", "granted")
    h = ctx.harsha

    task =
      Task.async(fn ->
        scripted_llm!()
        ask!(ctx.og, h.user, "Add dentist Friday 10am", ctx.dev)
      end)

    d = wait_call(h.user.id)["data"]
    assert d["tool"] == "calendar_add" and d["to_devices"] == [ctx.dev]
    assert d["undo_entry_id"] == nil and d["args"]["title"] == "Dentist"

    {204, _} =
      result!(ctx, d["tool_call_id"], %{"status" => "ok", "result" => %{"event_id" => "e"}})

    assert {_, :ok} = Task.await(task)

    refute_received {:risi_post, _, _, %{"kind" => "confirm"}}
    assert_receive {:risi_post, rc, "Added 'Dentist' to your calendar.", done}
    assert rc == ctx.rc and done["kind"] == "skill_done" and done["via"] == "allowed"
    assert done["skill_id"] == "calendar" and done["action"] == "calendar_added"
    assert done["undo"]["kind"] == "client" and done["undo"]["state"] == "available"
    assert is_binary(done["undo_token"])
    assert Writes.get(d["args"]["write_id"]).state == "done"

    # The undo: wrong token 409, someone else's entry 404, then 202 → a calendar_remove call.
    path = "/api/v1/risi/skills/calendar/activity/#{done["entry_id"]}/undo"

    {409, %{"error" => %{"code" => "undo_unavailable"}}} =
      api(:post, path, h.token, %{"undo_token" => "u1.nope"}, ctx.dev)

    {404, _} = api(:post, path, ctx.kamal.token, %{"undo_token" => done["undo_token"]}, nil)
    {422, _} = api(:post, path, h.token, %{}, ctx.dev)

    {202, %{"entry" => e}} =
      api(:post, path, h.token, %{"undo_token" => done["undo_token"]}, ctx.dev)

    assert e["undo"]["state"] == "pending" and e["undo_token"] == nil

    u = wait_call(h.user.id)["data"]
    assert u["tool"] == "calendar_remove" and u["undo_entry_id"] == done["entry_id"]
    assert u["args"] == %{"target_write_id" => d["args"]["write_id"]}
    assert u["turn_id"] == nil and u["request_id"] == nil and u["conversation_id"] == nil

    # Used token: 409 again.
    {409, _} = api(:post, path, h.token, %{"undo_token" => done["undo_token"]}, ctx.dev)

    {204, _} =
      result!(ctx, u["tool_call_id"], %{
        "status" => "ok",
        "result" => %{"removed" => true, "reason" => nil}
      })

    {200, %{"entries" => [removed, added | _]}} =
      get!(ctx, "/api/v1/risi/skills/calendar/activity")

    assert removed["action"] == "calendar_removed" and removed["via"] == "undo"
    assert added["entry_id"] == done["entry_id"] and added["undo"]["state"] == "done"
  end

  test "a client undo the phone doesn't answer: failed, with a new token (retry)", ctx do
    e =
      Skills.log!(ctx.harsha.user.id, "calendar", "calendar_added", "Added 'X' to calendar",
        via: "confirm",
        device_id: ctx.dev,
        undo_kind: "client",
        undo_until: DateTime.add(DateTime.utc_now(), 3600, :second),
        data: %{"target_write_id" => Ecto.UUID.generate(), "title" => "X"}
      )

    tok = Skills.entry_json(e)["undo_token"]
    path = "/api/v1/risi/skills/calendar/activity/#{e.entry_id}/undo"
    {202, _} = api(:post, path, ctx.harsha.token, %{"undo_token" => tok}, ctx.dev)

    [job] = for j <- all_enqueued(worker: Job), j.args["kind"] == "undo_timeout", do: j
    assert :ok = perform_job(Job, job.args)

    {200, %{"entries" => [f]}} = get!(ctx, "/api/v1/risi/skills/calendar/activity")

    assert f["undo"]["state"] == "failed" and is_binary(f["undo_token"]) and
             f["undo_token"] != tok

    assert events(ctx.harsha.user.id, "risi_tool_call") == []

    # Manual and none can't be undone; nor a client entry whose device is gone.
    m =
      Skills.log!(ctx.harsha.user.id, "alarm", "alarm_set", "Set an alarm for 05:30",
        via: "allowed",
        undo_kind: "manual",
        hint: "Open Clock to remove it"
      )

    assert Skills.entry_json(m)["undo"]["hint"] == "Open Clock to remove it"

    {409, _} =
      api(
        :post,
        "/api/v1/risi/skills/alarm/activity/#{m.entry_id}/undo",
        ctx.harsha.token,
        %{"undo_token" => "u1.x"},
        ctx.dev
      )

    {204, _} = api(:delete, "/api/v1/me/devices/#{ctx.dev}", ctx.harsha.token, nil, ctx.dev)
    other = skills_device!(ctx.harsha.user)

    {409, _} =
      api(:post, path, ctx.harsha.token, %{"undo_token" => f["undo_token"]}, other)
  end

  test "Tools.authorize: v1.26-only tools never reach a v1.25 user", ctx do
    tool = %{name: "set_alarm", where: :client, personal: true, skill: "alarm"}
    k = ctx.kamal.user
    kdev = RisiMe.TabsHelpers.risi_tools_device!(k)
    own_risi_chat!(k)
    refute Skills.allows?(tool, %{asker: k.id, device_id: kdev})

    assert Tools.authorize(%{tool | name: "calendar_add"} |> Map.put(:skill, "calendar"), %{
             asker: k.id,
             device_id: kdev,
             conv: ctx.og
           }) == :ok
  end
end
