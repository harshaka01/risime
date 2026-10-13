defmodule RisiMe.Agent.Parity.CalendarAnswerTest do
  @moduledoc """
  Parity pins for the schedule-question answer (v1.35 §34.1 forced read, v1.32 §29.7 sync off),
  fixed clock Fri 9 Oct 2026 10:30 Colombo, fake `risi-l1` that always claims "free":

  * a Risi Calendar user asks "what's on my calendar this week" → the server's agenda lists the
    event in the range and the "Checked:" line names the sources; the model's "free" never shows;
  * Risi Calendar off, the phone's sync off → the sync-off sentence and the "Not checked" line,
    no "free" claim (root 2026-10-13: the ui-batch google step, lost since nightly.48-srv1).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.Calendar
  alias RisiMe.Workers.Risi, as: Job

  @moduletag :risi_parity
  @moduletag capture_log: true

  @bound_ms 3_000
  @now ~U[2026-10-09 05:00:00Z]
  @free "You're free all week!"

  defp final(answer),
    do: %{"tool" => "final", "answer" => answer, "sources" => [], "next_steps" => []}

  defp posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      80 -> Enum.reverse(acc)
    end
  end

  defp answer_of(ps), do: Enum.find(ps, fn {_, _, r} -> r["kind"] == "answer" end)

  defp request!(conv, user, dev, text) do
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
      dev
    )

    [job] = for j <- all_enqueued(worker: Job), j.args["request_id"] == rid, do: j
    job
  end

  defp wait_call(user_id, tries \\ 150) do
    case events(user_id, "risi_tool_call") do
      [call | _] ->
        call

      _ when tries > 0 ->
        Process.sleep(20)
        wait_call(user_id, tries - 1)

      _ ->
        flunk("no risi_tool_call event")
    end
  end

  describe "Risi Calendar user" do
    setup do
      RisiMe.CalendarHelpers.calendar_world!()
    end

    test "schedule question → forced read; the agenda lists the event, 'Checked:' names the sources",
         ctx do
      {:ok, _} =
        Calendar.create(ctx.h, %{
          title: "Interview",
          notes: nil,
          tz: "Asia/Colombo",
          start: ~U[2026-10-12 08:30:00.000000Z],
          stop: ~U[2026-10-12 09:30:00.000000Z],
          all_day: false,
          with: [],
          reminder_min: :default,
          source: nil,
          created_by: "user"
        })

      posts()
      fake_llm!(fn _name, _body -> final(@free) end)
      job = request!(ctx.hrc, ctx.harsha.user, ctx.hd, "what's on my calendar this week")
      t = System.monotonic_time(:millisecond)
      assert :ok = perform_job(Job, job.args)
      assert System.monotonic_time(:millisecond) - t < @bound_ms

      {conv, body, a} = answer_of(posts())
      assert conv == ctx.hrc

      assert body ==
               "Fri 9 Oct – Thu 15 Oct:\n- Mon 12 Oct, 14:00–15:00 · Interview (Risi Calendar)" <>
                 "\n\nChecked: Risi Calendar · Risi's items."

      refute body =~ "free"
      assert a["steps"] == [%{"tool" => "risi_calendar_check", "status" => "ok"}]

      assert for(s <- a["sources"], s["type"] == "calendar_source", do: s["source"]) ==
               ["risi_calendar", "risi_items"]

      # The model is called once, after the read, and offered no write tool.
      assert [req] = llm_requests()
      tools = Jason.encode!(get_in(req, ~w(response_format json_schema schema)))
      refute tools =~ "risi_calendar_add"
    end
  end

  describe "Risi Calendar off, the phone's calendar sync off" do
    setup do
      restore_on_exit([:risi_now])
      Application.put_env(:risime, :risi_now, @now)
      RisiMe.MLSHelpers.with_attestation_key(%{})
      harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
      shenika = RisiMe.Fixtures.logged_in_user(display_name: "Shenika")
      _og = risi_chat!([harsha.user, shenika.user])
      RisiMe.TabsHelpers.risi_tools_on!()
      skills_on!()
      dev = skills_device!(harsha.user)
      rc = own_risi_chat!(harsha.user)
      change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

      {200, _} =
        api(:patch, "/api/v1/risi/skills", harsha.token, %{"changes" => [change]}, dev)

      %{harsha: harsha, dev: dev, rc: rc}
    end

    test "the sync-off sentence and the 'Not checked' line; no free claim", ctx do
      fake_llm!(fn _name, _body -> final(@free) end)
      job = request!(ctx.rc, ctx.harsha.user, ctx.dev, "what's on my calendar this week")
      task = Task.async(fn -> perform_job(Job, job.args) end)
      call = wait_call(ctx.harsha.user.id)
      assert call["data"]["tool"] == "calendar_check"

      result = %{
        "blocks" => [],
        "sources" => [
          %{
            "source" => "phone_provider",
            "calendars" => [%{"name" => "Work", "account_type" => "com.google", "events" => 1}],
            "read_ok" => false,
            "reason" => "sync_off"
          },
          %{
            "source" => "google_api",
            "calendars" => [],
            "read_ok" => false,
            "reason" => "not_connected"
          }
        ],
        "connected_sources" => ["phone_provider"]
      }

      assert {204, _} =
               api(
                 :post,
                 "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
                 ctx.harsha.token,
                 %{"status" => "ok", "result" => result},
                 ctx.dev
               )

      assert :ok = Task.await(task)
      {conv, body, a} = answer_of(posts())
      assert conv == ctx.rc

      assert body =~
               "I couldn't read your calendar on this phone (sync is off, so it may be out of " <>
                 "date). Turn on sync in Settings → Risi skills → Calendar → Details."

      assert body =~ "Not checked: Phone calendar (sync is off)."
      refute body =~ ~r/free/i
      refute body =~ "0 events"
      assert a["steps"] == [%{"tool" => "calendar_check", "status" => "ok"}]

      assert %{"source" => "phone_provider", "read_ok" => false, "reason" => "sync_off"} =
               Enum.find(a["sources"], &(&1["source"] == "phone_provider"))
    end
  end
end
