defmodule RisiMe.Agent.GoogleHonestyV131Test do
  @moduledoc """
  v1.31 §31.5: the "Checked:" line with Google Calendar (order, counts, reason words), the
  free-claim rewrite for a connected source that was not read, `answer.sources` `count`/`refs`.
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [events: 2, api: 5]

  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @put %{
    "connect" => true,
    "state" => "connected",
    "read_calendars" => 2,
    "write_calendar" => true,
    "mirror" => true
  }

  setup do
    ctx = calendar_world!()
    gcal_on!()
    restore_on_exit([:risi_tool_deadline_ms])
    Map.put(ctx, :hg, gcal_device!(ctx.harsha.user))
  end

  defp url, do: "/api/v1/risi/calendar/google"

  defp skill!(ctx, state) do
    change = %{"id" => "calendar", "state" => state, "client_permission" => "granted"}

    {200, _} =
      api(:patch, "/api/v1/risi/skills", ctx.harsha.token, %{"changes" => [change]}, ctx.hd)
  end

  defp link!(ctx, extra \\ %{}),
    do: {200, _} = api(:put, url(), ctx.harsha.token, Map.merge(@put, extra), ctx.hg)

  defp check_llm!(answer) do
    fake_llm!(fn _name, body ->
      if Enum.any?(body["messages"], &(&1["role"] == "assistant")),
        do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []},
        else: %{
          "tool" => "risi_calendar_check",
          "args" => %{"from" => "2026-10-12T08:00:00Z", "to" => "2026-10-12T10:00:00Z"}
        }
    end)
  end

  defp wait_calls(user_id, n, tries \\ 150) do
    calls = events(user_id, "risi_tool_call")

    cond do
      length(calls) >= n ->
        calls

      tries > 0 ->
        Process.sleep(20)
        wait_calls(user_id, n, tries - 1)

      true ->
        flunk("expected #{n} risi_tool_call events, got #{length(calls)}")
    end
  end

  defp post(ctx, call, device, result) do
    {204, _} =
      api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => result},
        device
      )
  end

  defp phone_result do
    %{
      "blocks" => [],
      "sources" => [
        %{
          "source" => "phone_provider",
          "calendars" => [%{"name" => "Gzzname", "account_type" => "com.google", "events" => 0}],
          "read_ok" => true,
          "reason" => nil
        }
      ],
      "connected_sources" => ["phone_provider"]
    }
  end

  defp google_result(blocks \\ []) do
    %{
      "blocks" => blocks,
      "sources" => [
        %{
          "source" => "google_api",
          "calendars" => [%{"ref" => "g4k2m7qa", "events" => length(blocks)}],
          "read_ok" => true,
          "reason" => nil
        }
      ],
      "connected_sources" => ["google_api"]
    }
  end

  defp busy(s, e), do: %{"start" => s, "end" => e, "busy" => true, "all_day" => false}

  defp answer_of(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)

  # Runs a turn; the phone answers; the Google device answers `:google` or never.
  defp run!(ctx, opts) do
    check_llm!(Keyword.fetch!(opts, :answer))
    rid = Ecto.UUID.generate()

    envelope!(
      ctx.hrc,
      ctx.harsha.user,
      %{
        "v" => 1,
        "type" => "risi_request",
        "request_id" => rid,
        "action" => "ask",
        "text" => "Am I free Monday 2pm?"
      },
      ctx.hd
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    task = Task.async(fn -> perform_job(Job, job.args) end)
    n = Keyword.get(opts, :calls, 2)

    if n > 0 do
      for c <- wait_calls(ctx.h, n) do
        if c["data"]["args"]["sources"] == ["google_api"] do
          if g = opts[:google], do: post(ctx, c, ctx.hg, g)
        else
          post(ctx, c, ctx.hd, phone_result())
        end
      end
    end

    assert :ok = Task.await(task)
    posts()
  end

  @v130 "Nothing on then.\n\nChecked: Risi Calendar · Phone calendar (Gzzname)."

  test "Google read, busy: calendars have N busy times; the Checked line counts calendars",
       ctx do
    skill!(ctx, "ask")
    link!(ctx)
    blocks = [busy("2026-10-12T08:30:00.000Z", "2026-10-12T09:30:00.000Z")]

    {_, body, a} =
      answer_of(run!(ctx, answer: "You're free then.", google: google_result(blocks)))

    assert body ==
             "You're not free then: your calendars have 1 busy time (Mon 12 Oct, 14:00–15:00).\n\n" <>
               "Checked: Risi Calendar · Google Calendar (2 calendars) · Phone calendar (Gzzname)."

    g = Enum.find(a["sources"], &(&1["source"] == "google_api"))

    assert g == %{
             "type" => "calendar_source",
             "source" => "google_api",
             "names" => [],
             "read_ok" => true,
             "reason" => nil,
             "count" => 2,
             "refs" => ["g4k2m7qa"]
           }

    assert a["next_steps"] == []
  end

  test "Google read, free: the model's text stands; one calendar is singular", ctx do
    skill!(ctx, "ask")
    link!(ctx, %{"read_calendars" => 1})
    {_, body, _} = answer_of(run!(ctx, answer: "Nothing on then.", google: google_result()))

    assert body ==
             "Nothing on then.\n\nChecked: Risi Calendar · Google Calendar (1 calendar) · Phone calendar (Gzzname)."
  end

  test "no_answer with a free claim: rewritten, Retry, made by the rule", ctx do
    skill!(ctx, "ask")
    link!(ctx)
    Application.put_env(:risime, :risi_tool_deadline_ms, 300)
    {_, body, a} = answer_of(run!(ctx, answer: "You're free then."))

    assert body ==
             "Your Risi Calendar and Phone calendar are free then, but I couldn't check Google Calendar (phone didn't answer).\n\n" <>
               "Checked: Risi Calendar · Phone calendar (Gzzname). Not checked: Google Calendar (phone didn't answer)."

    assert a["next_steps"] == ["Retry"]
    assert a["made_by"]["model"] == nil
    g = Enum.find(a["sources"], &(&1["source"] == "google_api"))

    assert %{
             "read_ok" => false,
             "reason" => "no_answer",
             "count" => 2,
             "refs" => [],
             "names" => []
           } =
             g
  end

  test "no_answer without a free claim: the text stands and the source is named", ctx do
    skill!(ctx, "ask")
    link!(ctx)
    Application.put_env(:risime, :risi_tool_deadline_ms, 300)
    {_, body, a} = answer_of(run!(ctx, answer: "You have a meeting at 9."))
    assert String.starts_with?(body, "You have a meeting at 9.\n\nChecked: ")
    assert body =~ "Not checked: Google Calendar (phone didn't answer)."
    assert a["next_steps"] == []
  end

  test "reauth_needed: no call; named with its words; a free claim is rewritten", ctx do
    link!(ctx)
    link!(ctx, %{"connect" => false, "state" => "reauth_needed"})
    skill!(ctx, "ask")
    {_, body, a} = answer_of(run!(ctx, answer: "You're free then.", calls: 1))

    assert body =~
             "but I couldn't check Google Calendar (needs reconnecting)."

    assert body =~ "Not checked: Google Calendar (needs reconnecting)."
    assert a["next_steps"] == ["Retry"]
  end

  test "paused: named with its words", ctx do
    link!(ctx)
    skill!(ctx, "off")
    {_, body, _} = answer_of(run!(ctx, answer: "Nothing on then.", calls: 0))

    assert body ==
             "Nothing on then.\n\nChecked: Risi Calendar. Not checked: Google Calendar (Calendar skill is off) · Phone calendar (Calendar skill is off)."
  end

  test "switch off: exactly the v1.30 line", ctx do
    skill!(ctx, "ask")
    link!(ctx)
    Application.put_env(:risime, :risi_gcal, false)
    {_, body, a} = answer_of(run!(ctx, answer: "Nothing on then.", calls: 1))
    assert body == @v130
    assert Enum.map(a["sources"], & &1["source"]) == ["risi_calendar", "phone_provider"]
  end

  test "a user with no link: the v1.30 line", ctx do
    skill!(ctx, "ask")
    {_, body, _} = answer_of(run!(ctx, answer: "Nothing on then.", calls: 1))
    assert body == @v130
  end
end
