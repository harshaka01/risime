defmodule RisiMe.Agent.CalendarV2Test do
  @moduledoc """
  Proposal 2026-10-14-calendar-v2 (§44; Harsha's P1, 14 Oct 10:55–11:47 SLT, Honor phone):

  * the stale footer: a phone read with `sync_off` (Android's account auto-sync) and calendars
    was put under "Not checked: Phone calendar (sync is off)" and got no `local_events`, so the
    phone could not list its events with titles ("Busy"). Under the switch: a read, the plain
    footer "From: your phone calendars · Risi", `local_events`;
  * permission granted mid-conversation → the next reply has no "not checked" and says
    "✓ I can see your calendars now." once;
  * "free up 18 Oct whole day" → one all-day block, one reminder the day before at 18:00, one
    row in the listing;
  * declined twice → one sentence each, no extra card; no title reaches the server while
    "Only times"; no calendars on the phone → the guide's `step: "account"`;
  * the switch off: today's behaviour (parity).
  """
  use RisiMe.DataCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  import RisiMe.RisiHelpers
  import RisiMe.GroupHelpers, only: [api: 5, events: 2]

  alias RisiMe.Agent.{CalendarV2, DayPart, Ledger}
  alias RisiMe.Workers.Risi, as: Job

  @moduletag capture_log: true

  @caps ~w(groups member_devices tabs risi_tools risi_skills risi_fixit risi_calendar_v2)

  ## Units

  describe "DayPart" do
    test "whole day in en / si / ta / Singlish" do
      for t <- [
            "Free up calendar on 18th Oct whole day",
            "block the entire day on Friday",
            "all day on 18 Oct",
            "full-day workshop",
            "18 වෙනිදා මුළු දවසම නිදහස් කරන්න",
            "හෙට දවස පුරා",
            "நாளை நாள் முழுவதும்",
            "18th mulu dawasama free karanna"
          ] do
        assert DayPart.parse(t) == :all_day, t
      end
    end

    test "parts and durations; unclear when two answers" do
      assert {:part, "morning", ~T[08:00:00], ~T[12:00:00]} = DayPart.parse("tomorrow morning")
      assert {:part, "afternoon", _, _} = DayPart.parse("Friday afternoon")
      assert {:part, "evening", _, _} = DayPart.parse("හෙට හවස")
      assert {:duration, 7200} = DayPart.parse("meeting for 2 hours on Monday")
      assert {:duration, 1800} = DayPart.parse("half an hour call")
      assert {:duration, 5400} = DayPart.parse("90 mins review")
      assert {:duration, 7200} = DayPart.parse("පැය 2 meeting")
      assert {:duration, 10_800} = DayPart.parse("3 மணி நேரம்")
      assert DayPart.parse("whole day, 2 hours") == :unclear
      assert DayPart.parse("morning and evening") == :unclear
      assert DayPart.parse("dentist at 10") == :none
    end

    test "a block, not a deadline" do
      assert DayPart.all_day_block?("Free up calendar on 18th Oct whole day")
      assert DayPart.all_day_block?("on leave the whole day")
      refute DayPart.all_day_block?("send the report by Friday")
      refute DayPart.all_day_block?("workshop all day")
    end
  end

  test "Ledger.due/3: \"whole day\" makes a midnight due all day" do
    tz = "Asia/Colombo"
    assert {_, false} = Ledger.due("2026-10-18T00:00", tz)
    assert {due, true} = Ledger.due("2026-10-18T00:00", tz, true)
    assert {^due, true} = Ledger.due("2026-10-18", tz)
  end

  test "normalize: a sync_off phone read with calendars is a read; without calendars it isn't" do
    read = %{
      "source" => "phone_provider",
      "read_ok" => false,
      "reason" => "sync_off",
      "calendars" => [%{"name" => "Work", "account_type" => "com.google", "events" => 2}]
    }

    empty = %{read | "calendars" => []}

    assert %{"sources" => [%{"read_ok" => true, "reason" => nil}, %{"read_ok" => false}]} =
             CalendarV2.normalize(%{"sources" => [read, empty]})

    # The model's view of risi_calendar_check (calendars as a count).
    assert %{"phone" => %{"sources" => [%{"read_ok" => true}]}} =
             CalendarV2.normalize(%{"phone" => %{"sources" => [%{read | "calendars" => 3}]}})
  end

  test "footer: only what this request read, plain words" do
    assert CalendarV2.footer_line(["phone_provider", "risi_calendar", "risi_items"]) ==
             "From: your phone calendars · Risi"

    assert CalendarV2.footer_line(["risi_items"]) == "From: Risi"
    assert CalendarV2.footer_line([]) == nil
  end

  test "merge: a promise, its reminder and its Risi Calendar event are one row with tags" do
    tz = "Asia/Colombo"

    items = [
      %{
        "kind" => "promise",
        "title" => "Free up calendar on 18th Oct whole day",
        "start" => "2026-10-18T18:29:59.999Z",
        "all_day" => true,
        "group" => "item-1"
      },
      %{
        "kind" => "risi_calendar_event",
        "title" => "Free up calendar",
        "start" => "2026-10-17T18:30:00.000Z",
        "end" => "2026-10-18T18:30:00.000Z",
        "all_day" => true,
        "group" => "item-1"
      },
      %{
        "kind" => "reminder",
        "title" => "Free up calendar on 18th Oct whole day",
        "start" => "2026-10-17T12:30:00.000Z",
        "all_day" => false
      },
      %{
        "kind" => "reminder",
        "title" => "Call the bank",
        "start" => "2026-10-18T04:30:00.000Z",
        "all_day" => false
      }
    ]

    # The ungrouped reminder joins its thing by title.
    items =
      Enum.map(items, fn
        %{"kind" => "promise"} = i -> Map.delete(i, "group")
        i -> i
      end)

    rows = CalendarV2.merge(items, tz)
    assert length(rows) == 3

    rows = CalendarV2.merge(Enum.map(items, &Map.put_new(&1, "group", nil)), tz)
    titles = Enum.map(rows, & &1["title"])
    assert "Call the bank" in titles
  end

  test "merge by title and day; the most specific time wins; tags in words" do
    tz = "Asia/Colombo"

    items = [
      %{
        "kind" => "promise",
        "title" => "Free up calendar on 18th Oct whole day",
        "start" => "2026-10-18T18:29:59.999Z",
        "all_day" => true
      },
      %{
        "kind" => "reminder",
        "title" => "Free up calendar on 18th Oct whole day",
        "start" => "2026-10-17T12:30:00.000Z",
        "all_day" => false
      }
    ]

    assert [row] = CalendarV2.merge(items, tz)
    assert row["kind"] == "promise"
    assert row["tags"] == ["promise", "reminder"]
    assert CalendarV2.tags_text(row, tz) == "promise · reminder Sat 18:00"
  end

  test "conflict: an all-day block on a day with meetings" do
    tz = "Asia/Colombo"

    rows = [
      %{
        "kind" => "promise",
        "title" => "Free up calendar on 18th Oct whole day",
        "start" => "2026-10-17T18:30:00.000Z",
        "all_day" => true
      }
    ]

    others = [
      %{"start" => "2026-10-18T04:30:00.000Z", "all_day" => false},
      %{"start" => "2026-10-18T08:30:00.000Z", "all_day" => false},
      %{"start" => "2026-10-19T08:30:00.000Z", "all_day" => false}
    ]

    assert CalendarV2.conflict_line(rows, others, tz) ==
             "18 Oct already has 2 meetings. Move them, or keep both?"

    assert CalendarV2.conflict_line(rows, [], tz) == nil
  end

  test "learned reminder lead: 15 until 3 samples, then the median" do
    u = RisiMe.Fixtures.logged_in_user(display_name: "Lead").user.id
    assert CalendarV2.lead_min(u) == 15
    :ok = CalendarV2.learn_lead(u, 30)
    :ok = CalendarV2.learn_lead(u, 10)
    assert CalendarV2.lead_min(u) == 15
    :ok = CalendarV2.learn_lead(u, 30)
    assert CalendarV2.lead_min(u) == 30
    assert CalendarV2.learn_lead(u, 5000) == :error
  end

  ## The schedule answer on the phone (turn level)

  describe "a schedule question read from the phone" do
    setup do
      RisiMe.MLSHelpers.with_attestation_key(%{})
      restore_on_exit([:risi_permission_fixit, :risi_calendar_v2])
      Application.put_env(:risime, :risi_permission_fixit, :on)
      Application.put_env(:risime, :risi_calendar_v2, :on)
      harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
      kamal = RisiMe.Fixtures.logged_in_user(display_name: "Kamal")
      _og = risi_chat!([harsha.user, kamal.user])
      RisiMe.TabsHelpers.risi_tools_on!()
      skills_on!()
      dev = RisiMe.TabsHelpers.tabs_device!(harsha.user, caps: @caps)
      rc = own_risi_chat!(harsha.user)

      fake_llm!(fn "risi_next_action", _body ->
        %{"tool" => "final", "answer" => "x", "sources" => [], "next_steps" => []}
      end)

      {200, _} =
        api(
          :patch,
          "/api/v1/risi/skills",
          harsha.token,
          %{
            "changes" => [
              %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}
            ]
          },
          dev
        )

      %{harsha: harsha, dev: dev, rc: rc}
    end

    test "sync_off with calendars: local_events, the plain footer, the first-read line once",
         ctx do
      {_, body, risi} = ask_with_phone!(ctx, "what's on my calendar tomorrow?", sync_off())

      assert risi["local_events"] == %{
               "from" => "2026-10-05T18:30:00.000Z",
               "to" => "2026-10-06T18:30:00.000Z"
             }

      refute body =~ "Not checked"
      refute body =~ "Checked:"
      refute body =~ "sync is off"
      refute body =~ "Busy (phone calendar)"
      assert body =~ "From: your phone calendars · Risi"
      assert String.starts_with?(body, "✓ I can see your calendars now.")

      assert %{
               "v" => 1,
               "read" => ["phone_provider", "risi_items"],
               "phone_calendars" => 3,
               "first_read" => true
             } = risi["footer"]

      # Once: the next answer has no first-read line.
      {_, body2, risi2} = ask_with_phone!(ctx, "what's on my calendar tomorrow?", sync_off())
      refute body2 =~ "I can see your calendars now"
      assert risi2["footer"]["first_read"] == false

      # A new grant (here: the phone reported a denial) says it once more.
      {200, _} =
        api(
          :patch,
          "/api/v1/risi/skills",
          ctx.harsha.token,
          %{"changes" => [%{"id" => "calendar", "client_permission" => "denied"}]},
          ctx.dev
        )

      {200, _} =
        api(
          :patch,
          "/api/v1/risi/skills",
          ctx.harsha.token,
          %{"changes" => [%{"id" => "calendar", "client_permission" => "granted"}]},
          ctx.dev
        )

      {_, body3, _} = ask_with_phone!(ctx, "what's on today?", sync_off())
      assert String.starts_with?(body3, "✓ I can see your calendars now.")
    end

    test "P0 15 Oct: a calendar claim without a read reads the phone, never \"connect it\"",
         ctx do
      fake_llm!(fn "risi_next_action", _body ->
        %{
          "tool" => "final",
          "answer" => "You have nothing on your calendar today, so you're free.",
          "sources" => [],
          "next_steps" => []
        }
      end)

      {_, body, _risi} = ask_with_phone!(ctx, "should I go to the gym later?", sync_off())
      refute body =~ "Connect it"
      refute body =~ "didn't check"
      assert body =~ "From: your phone calendars · Risi"
    end

    test "device test 15 Oct: a dated promise question says there are none, then the events",
         ctx do
      {_, body, _} = ask_with_phone!(ctx, "Did I promise anything today or tomorrow?", sync_off())
      assert body =~ "You have no promises, reminders or follow-ups due then.\n\nMon 5 Oct"

      assert {:schedule_read, _} =
               RisiMe.Agent.ScheduleIntent.classify("What about this week?", ~D[2026-10-15])
    end

    test "P0 15 Oct: Harsha's promise question never blames calendar setup", ctx do
      q = "We see but have I promised you should be able to read what I promised and let me know"
      assert {:memory, %{mode: :agreed}} = RisiMe.Agent.ScheduleIntent.classify(q, ~D[2026-10-15])

      assert {:schedule_read, %{from: ~D[2026-10-15], to: ~D[2026-10-17]}} =
               RisiMe.Agent.ScheduleIntent.classify(
                 "Did I promise anything today or tomorrow?",
                 ~D[2026-10-15]
               )

      _ = ctx
    end

    test "parity: the switch off keeps today's \"Not checked: Phone calendar (sync is off)\"",
         ctx do
      Application.put_env(:risime, :risi_calendar_v2, false)
      {_, body, risi} = ask_with_phone!(ctx, "what's on my calendar tomorrow?", sync_off())
      assert body =~ "Not checked: Phone calendar (sync is off)"
      assert body =~ "Busy (phone calendar)"
      refute Map.has_key?(risi, "local_events")
      refute Map.has_key?(risi, "footer")
    end

    test "no title ever reaches the server: a block with a title is rejected", ctx do
      result =
        put_in(sync_off(), ["blocks"], [
          %{
            "start" => "2026-10-06T04:30:00Z",
            "end" => "2026-10-06T05:30:00Z",
            "busy" => true,
            "all_day" => false,
            "title" => "Design review"
          }
        ])

      {code, _body, _risi} = ask_with_phone!(ctx, "what's on my calendar tomorrow?", result)
      assert code in [400, 422]
      assert CalendarV2.titles(ctx.harsha.user.id) == "times"
    end

    test "meeting prep asks the titles question once; the answer is stored", ctx do
      {_, body, risi} = ask_with_phone!(ctx, "prepare me for tomorrow's meetings", sync_off())
      assert body =~ "Let Risi read meeting titles to help plan your day?"

      assert [%{"label" => "Yes", "target" => "titles"}, %{"label" => "Only times"}] =
               risi["next_actions"]

      {_, body2, _} = ask_with_phone!(ctx, "prepare me for tomorrow's meetings", sync_off())
      refute body2 =~ "meeting titles"
      assert CalendarV2.titles(ctx.harsha.user.id) == "times"

      act!(ctx, "calendar_titles", "titles")
      assert_receive {:risi_post, _, "OK, I'll use your meeting titles" <> _, _}
      assert CalendarV2.titles(ctx.harsha.user.id) == "titles"
    end

    test "no calendars on the phone: the guide's account step, resumed once back", ctx do
      no_cals = %{
        "blocks" => [],
        "sources" => [
          %{
            "source" => "phone_provider",
            "read_ok" => false,
            "reason" => "no_calendars",
            "calendars" => []
          },
          %{
            "source" => "google_api",
            "read_ok" => false,
            "reason" => "not_connected",
            "calendars" => []
          }
        ],
        "connected_sources" => []
      }

      {rid, _, card} = ask_with_phone!(ctx, "what's on my calendar tomorrow?", no_cals)

      assert %{
               "kind" => "needs_permission",
               "permission" => "calendar",
               "step" => "account",
               "text" => "Your Google calendar isn't on this phone yet.",
               "resume_id" => resume_id
             } = card

      act!(ctx, "permission_granted", resume_id)
      assert [_] = all_enqueued(worker: Job, args: %{"kind" => "resume", "request_id" => rid})
    end

    test "declined twice: one sentence each, never a second card for one request", ctx do
      for _ <- 1..2 do
        Application.put_env(:risime, :risi_permission_fixit, :on)

        {200, _} =
          api(
            :patch,
            "/api/v1/risi/skills",
            ctx.harsha.token,
            %{"changes" => [%{"id" => "calendar", "client_permission" => "denied"}]},
            ctx.dev
          )

        {rid, card} = ask_no_permission!(ctx, "what's on my calendar tomorrow?")
        act!(ctx, "permission_declined", card["resume_id"])

        assert_receive {:risi_post, _,
                        "OK, I won't use your calendar. You can change this in Risi skills.",
                        %{"request_id" => ^rid}}

        refute_received {:risi_post, _, _, %{"kind" => "needs_permission"}}
      end

      assert all_enqueued(worker: Job, args: %{"kind" => "resume"}) == []
    end
  end

  ## "Free up calendar on 18th Oct whole day" (Harsha's three rows)

  describe "a whole-day block" do
    setup do
      ctx = RisiMe.CalendarHelpers.calendar_world!()
      restore_on_exit([:risi_calendar_v2])
      Application.put_env(:risime, :risi_calendar_v2, :on)
      ctx
    end

    test "one all-day block, one reminder Sat 18:00, one row in the listing", ctx do
      tz = RisiMe.Agent.Clock.user_tz(ctx.h)
      text = "Free up calendar on 18th Oct whole day"
      {due, true} = Ledger.due("2026-10-18T00:00", tz, DayPart.all_day?(text))

      {:ok, c} =
        RisiMe.Agent.Commitment.seal(%RisiMe.Agent.Commitment{
          id: Ecto.UUID.generate(),
          conversation_id: ctx.og,
          chat_id: ctx.og,
          state: "confirmed",
          item_state: "confirmed",
          text: text,
          due_text: "18th Oct whole day",
          owner_id: ctx.h,
          counterpart_ids: [],
          due: due,
          due_kind: "date",
          all_day: true,
          source_message_ids: [],
          proposed_at: ~U[2026-10-09 05:00:00.000000Z],
          confirmed_at: ~U[2026-10-09 05:00:00.000000Z]
        })

      c = Repo.insert!(c)
      {:ok, c} = RisiMe.Agent.Commitment.open(c)

      RisiMe.Agent.Offers.consider(c)
      RisiMe.Agent.Offers.consider(c)

      evs =
        Repo.all(
          from e in RisiMe.Agent.Calendar.Event,
            where: e.source_item_id == ^c.id and e.state == "active"
        )

      assert [e] = evs
      assert e.all_day
      # Sun 18 Oct 00:00 → Mon 19 Oct 00:00 in Colombo.
      assert e.start_at == ~U[2026-10-17 18:30:00.000000Z]
      assert e.end_at == ~U[2026-10-18 18:30:00.000000Z]
      # No §28.5 "Remind me?" card at 23:45.
      refute Enum.any?(
               RisiMe.CalendarHelpers.posts(),
               &match?({_, _, %{"kind" => "confirm", "tool" => "set_reminder"}}, &1)
             )

      :ok = RisiMe.Agent.LedgerReminders.schedule(c)

      reminders =
        for j <- all_enqueued(worker: Job),
            j.args["kind"] == "item_reminder" and j.args["commitment_id"] == c.id,
            j.args["moment"] in ~w(before at),
            do: {j.args["moment"], j.scheduled_at}

      # Sat 17 Oct 18:00 Colombo = 12:30Z; no "due today" on the day itself.
      assert [{"before", at}] = reminders
      assert DateTime.compare(at, ~U[2026-10-17 12:30:00Z]) == :eq

      check = %{
        status: "ok",
        reason: nil,
        result: %{
          "from" => "2026-10-17T18:30:00.000Z",
          "to" => "2026-10-18T18:30:00.000Z",
          "blocks" => [],
          "sources" => [
            %{"source" => "risi_calendar", "read_ok" => true, "reason" => nil, "calendars" => []},
            %{"source" => "risi_items", "read_ok" => true, "reason" => nil, "calendars" => []}
          ]
        }
      }

      {text_out, _srcs, false} = RisiMe.Agent.RisiItems.agenda_v2([check], ctx.h, tz, false)
      lines = for l <- String.split(text_out, "\n"), String.starts_with?(l, "- "), do: l
      assert [line] = lines
      assert line =~ "all day"
      assert line =~ "Risi Calendar · promise"
      assert text_out =~ "From: Risi"
      refute text_out =~ "Checked:"
    end
  end

  ## Helpers

  defp sync_off do
    %{
      "blocks" => [
        %{
          "start" => "2026-10-06T04:30:00Z",
          "end" => "2026-10-06T05:30:00Z",
          "busy" => true,
          "all_day" => false
        }
      ],
      "sources" => [
        %{
          "source" => "phone_provider",
          "read_ok" => false,
          "reason" => "sync_off",
          "calendars" => [
            %{"name" => "Primary calendar", "account_type" => "com.google", "events" => 1},
            %{"name" => "Primary calendar", "account_type" => "com.google", "events" => 0},
            %{"name" => "Primary calendar", "account_type" => "com.google", "events" => 0}
          ]
        },
        %{
          "source" => "google_api",
          "read_ok" => false,
          "reason" => "not_connected",
          "calendars" => []
        }
      ],
      "connected_sources" => ["phone_provider"]
    }
  end

  defp request!(ctx, text) do
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
    {rid, job}
  end

  # The newest risi_tool_call not handled before in this test.
  defp tool_call!(ctx, tries \\ 250) do
    seen = Process.get(:seen_calls, MapSet.new())

    Enum.find_value(1..tries, fn _ ->
      case for(
             c <- events(ctx.harsha.user.id, "risi_tool_call"),
             not MapSet.member?(seen, c["data"]["tool_call_id"]),
             do: c
           ) do
        [c | _] ->
          Process.put(:seen_calls, MapSet.put(seen, c["data"]["tool_call_id"]))
          c

        [] ->
          Process.sleep(20) && nil
      end
    end)
  end

  # Asks, answers the phone's calendar_check with `result`, returns {request_id | code, body, risi}.
  defp ask_with_phone!(ctx, text, result) do
    {rid, job} = request!(ctx, text)
    task = Task.async(fn -> perform_job(Job, job.args) end)
    call = tool_call!(ctx)
    assert call["data"]["tool"] == "calendar_check"

    {code, _} =
      api(
        :post,
        "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
        ctx.harsha.token,
        %{"status" => "ok", "result" => result},
        ctx.dev
      )

    :ok = Task.await(task, 30_000)

    if code >= 400 do
      {code, nil, nil}
    else
      assert_receive {:risi_post, _, body, %{"request_id" => ^rid} = risi}
      {rid, body, risi}
    end
  end

  defp ask_no_permission!(ctx, text) do
    {rid, job} = request!(ctx, text)
    task = Task.async(fn -> perform_job(Job, job.args) end)

    case tool_call!(ctx, 50) do
      nil ->
        :ok

      call ->
        api(
          :post,
          "/api/v1/risi/tool_calls/#{call["data"]["tool_call_id"]}/result",
          ctx.harsha.token,
          %{"status" => "no_permission", "result" => nil},
          ctx.dev
        )
    end

    :ok = Task.await(task, 30_000)
    assert_receive {:risi_post, _, _, %{"kind" => "needs_permission", "request_id" => ^rid} = c}
    {rid, c}
  end

  defp act!(ctx, action, target) do
    id =
      envelope!(
        ctx.rc,
        ctx.harsha.user,
        %{
          "v" => 1,
          "type" => "risi_action",
          "action" => action,
          "target" => target,
          "edit" => nil
        },
        ctx.dev
      )

    [job] = for j <- all_enqueued(worker: Job), j.args["message_id"] == id, do: j
    perform_job(Job, job.args)
  end
end
