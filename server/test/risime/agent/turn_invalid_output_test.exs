defmodule RisiMe.Agent.TurnInvalidOutputTest do
  @moduledoc """
  Fix 2026-10-09 (release gate): with calendar permission off the server stops offering
  `calendar_check`; a model that emits it anyway must not loop until timeout. One corrective
  retry, then a deterministic answer: the honesty no-read answer for calendar tools
  (`skill_needed` when the skill is off), a short "unavailable" answer for other tools, never
  "free"/"clear". Also: a repeated failed step ends the turn; a model down for over
  `wait_s` gets an answer instead of another snooze.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5]

  alias RisiMe.Agent.TurnSteps
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  defmodule FlakyTools do
    @moduledoc false
    def tools,
      do: [
        RisiMe.Agent.Tools.capabilities(),
        %{
          name: "flaky",
          description: "always fails",
          where: :server,
          personal: false,
          write: false,
          finds: true,
          args: %{
            "type" => "object",
            "properties" => %{"q" => %{"type" => "string"}},
            "additionalProperties" => false
          },
          run: fn _args, _ctx -> {:error, "failed"} end
        }
      ]
  end

  setup do
    restore_on_exit([:risi_tool_registry, :risi_turn])
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
    og = risi_chat!([harsha.user, kamal.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    dev = skills_device!(harsha.user)
    rc = own_risi_chat!(harsha.user)
    %{harsha: harsha, og: og, dev: dev, rc: rc}
  end

  defp set!(ctx, id, state, perm) do
    change = %{"id" => id, "state" => state, "client_permission" => perm}

    {200, _} =
      api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, ctx.dev)
  end

  defp ask!(ctx, text, args \\ %{}) do
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
      ctx.dev
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    t0 = System.monotonic_time(:millisecond)
    result = perform_job(Job, Map.merge(job.args, args))
    {result, System.monotonic_time(:millisecond) - t0}
  end

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  defp always(action), do: fake_llm!(fn _, _ -> action end)

  defp check(from \\ "2026-10-12T14:00:00+05:30", to \\ "2026-10-12T15:00:00+05:30"),
    do: %{"tool" => "calendar_check", "args" => %{"from" => from, "to" => to}}

  defp last_user(body), do: List.last(body["messages"])["content"]

  defp refute_free(body) do
    refute body =~ ~r/\b(free|clear)\b/i
  end

  test "permission off, calendar_check repeatedly: one honesty answer within seconds", ctx do
    set!(ctx, "calendar", "ask", "denied")
    always(check())

    {:ok, ms} = ask!(ctx, "Monday at 2pm ok for the call?")
    assert ms < 5_000

    [{rc, body, a}] = posts()
    assert rc == ctx.rc and a["kind"] == "answer"

    assert body ==
             "I couldn't read your calendar on this phone (calendar permission is off). " <>
               "Connect it in Settings → Risi skills → Calendar."

    refute_free(body)
    assert a["made_by"]["model"] == nil
    assert a["steps"] == [%{"tool" => "calendar_check", "status" => "denied"}]

    # The first call and exactly one corrective retry; no blind schema retry.
    [first, second] = llm_requests()
    refute "calendar_check" in tools_of(first)
    assert last_user(second) =~ "calendar_check is not available in this turn"
    assert last_user(second) =~ "never say the person is free"

    assert [%{tool: "calendar_check", status: "denied"}, %{tool: "final"}] =
             TurnSteps.list(a["turn_ref"])
  end

  test "the skill off, calendar_check repeatedly: skill_needed with the honesty text", ctx do
    set!(ctx, "calendar", "off", "granted")
    always(check())

    {:ok, ms} = ask!(ctx, "Monday at 2pm ok for the call?")
    assert ms < 5_000
    [{_, body, n}] = posts()
    assert n["kind"] == "skill_needed" and n["skill_id"] == "calendar" and n["reason"] == "off"
    assert body =~ "I couldn't read your calendar on this phone (the Calendar skill is off)"
    refute_free(body)
    assert length(llm_requests()) == 2
  end

  test "the corrective retry works: the model's own final is used", ctx do
    set!(ctx, "calendar", "ask", "denied")

    fake_llm!(fn _, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: %{
          "tool" => "final",
          "answer" => "I can't see your calendar right now.",
          "sources" => [],
          "next_steps" => []
        },
        else: check()
    end)

    {:ok, _} = ask!(ctx, "Monday at 2pm ok for the call?")
    [{_, body, a}] = posts()
    assert body == "I can't see your calendar right now."
    assert a["kind"] == "answer"
  end

  test "a final saying 'you're free' after the correction is still rewritten", ctx do
    set!(ctx, "calendar", "ask", "denied")

    fake_llm!(fn _, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: %{
          "tool" => "final",
          "answer" => "Your calendar is clear on Monday at 2pm.",
          "sources" => [],
          "next_steps" => []
        },
        else: check()
    end)

    {:ok, _} = ask!(ctx, "Monday at 2pm ok for the call?")
    [{_, body, _}] = posts()
    assert body =~ "I couldn't read your calendar"
    refute_free(body)
  end

  test "an unknown tool repeatedly: a short answer saying what's unavailable", ctx do
    always(%{"tool" => "send_email", "args" => %{"to" => "x"}})
    {:ok, ms} = ask!(ctx, "Email Kamal the notes")
    assert ms < 5_000
    [{_, body, a}] = posts()
    assert body =~ "I can't use send_email here right now"
    assert a["kind"] == "answer" and a["made_by"]["model"] == nil
    assert length(llm_requests()) == 2
  end

  test "a disabled skill's tool repeatedly: skill_needed", ctx do
    always(%{"tool" => "set_alarm", "args" => %{"time" => "07:00"}})
    {:ok, _} = ask!(ctx, "Wake me at 7")
    [{_, _body, n}] = posts()
    assert n["kind"] == "skill_needed" and n["skill_id"] == "alarm"
    assert length(llm_requests()) == 2
  end

  test "output that isn't JSON repeatedly: one answer, no snooze", ctx do
    always({:raw, "Sure! Let me check that."})
    {:ok, ms} = ask!(ctx, "hello")
    assert ms < 5_000
    [{_, body, _}] = posts()
    assert body == "I couldn't put together an answer this time. Ask me again."
    assert length(llm_requests()) == 2
  end

  test "a step repeating an earlier failed step is not run again", ctx do
    Application.put_env(:risime, :risi_tool_registry, FlakyTools)
    always(%{"tool" => "flaky", "args" => %{"q" => "x"}})
    {:ok, _} = ask!(ctx, "look it up")
    [{_, body, a}] = posts()
    assert body == "I tried flaky, but it didn't work (failed), so I stopped."

    assert a["steps"] == [
             %{"tool" => "flaky", "status" => "failed"},
             %{"tool" => "flaky", "status" => "skipped"}
           ]

    assert length(llm_requests()) == 2
  end

  test "the model down for longer than wait_s: an answer, not another snooze", ctx do
    fake_llm!(fn _, _ -> {:status, 503} end)
    {result, _} = ask!(ctx, "hello", %{"t0" => System.system_time(:second) - 600})
    assert result == :ok
    [{_, body, _}] = posts()
    assert body =~ "I couldn't reach my model"
  end

  defp tools_of(body) do
    for alt <- body["response_format"]["json_schema"]["schema"]["anyOf"],
        do: hd(alt["properties"]["tool"]["enum"])
  end
end
