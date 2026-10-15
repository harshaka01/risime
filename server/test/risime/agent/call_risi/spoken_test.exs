defmodule RisiMe.Agent.CallRisi.SpokenTest do
  @moduledoc """
  P0 2026-10-15 (Harsha's Call Risi call, 07:11–07:13Z): the units behind the fixes.

    1. a calendar answer is spoken from the read's facts (`Spoken.render/2`, the model's phrasing
       only when `Spoken.checked/3` passes), never the card's head line or footer;
    2. dates: "today and tomorrow" = two days, "the 18th" = this month's (next month's once past),
       "this week" / "the entire week" = today to Sunday; follow-ups inherit the call's topic;
    3. titles: only through the `titles` hook, for speech; the times-only line otherwise;
    5. "what can you do?" lists only the tools this user has.
  """
  use ExUnit.Case, async: true

  alias RisiMe.Agent.CallRisi.{Brain, Spoken}
  alias RisiMe.Agent.ScheduleIntent

  @today ~D[2026-10-15]

  defp ev(kind, title, {d, h, m}, dur_min \\ 60, opts \\ []) do
    s = NaiveDateTime.new!(d, Time.new!(h, m, 0))

    %{
      kind: kind,
      title: title,
      start: s,
      end:
        if(kind in ~w(reminder scheduled_message),
          do: nil,
          else: NaiveDateTime.add(s, dur_min * 60)
        ),
      all_day: Keyword.get(opts, :all_day, false),
      start_utc: "#{d}T#{h}:#{m}Z",
      end_utc: "#{d}T#{h + 1}:#{m}Z",
      tags: [kind]
    }
  end

  defp facts(from, to, events, unread \\ []),
    do: %{ok?: true, today: @today, from: from, to: to, events: events, unread: unread}

  describe "1. the spoken calendar answer" do
    test "today and tomorrow: both days, real times, Risi's reminder; no card text" do
      f =
        facts(~D[2026-10-15], ~D[2026-10-17], [
          ev("busy", nil, {~D[2026-10-15], 14, 0}),
          ev("busy", nil, {~D[2026-10-16], 10, 0}),
          ev("reminder", "Call Ruwan", {~D[2026-10-16], 17, 0})
        ])

      {text, missing?} = Spoken.render(f)
      assert text =~ "Today: busy from 2 to 3 pm."
      assert text =~ "Tomorrow: busy from 10 to 11 am and a reminder, Call Ruwan, at 5 pm."
      refute text =~ "From:"
      refute text =~ "Thu 15 Oct:"
      assert missing?
    end

    test "an empty range says so (a read happened); unread sources are named" do
      {text, false} =
        Spoken.render(
          facts(~D[2026-10-18], ~D[2026-10-19], [], [
            "I couldn't reach Google Calendar (network)."
          ])
        )

      assert text ==
               "You have nothing on your calendar Sunday the 18th. I couldn't reach Google Calendar (network)."
    end

    test "a week: free days listed once, at most 8 items then the chat" do
      evs = for h <- 8..17, do: ev("busy", nil, {~D[2026-10-16], h, 0}, 30)
      {text, _} = Spoken.render(facts(~D[2026-10-15], ~D[2026-10-19], evs))
      assert text =~ "Tomorrow: busy from 8 to 8:30 am"
      assert text =~ ", and 2 more."
      assert text =~ "Nothing on today, Saturday the 17th, and Sunday the 18th."
      assert text =~ "The full list is in your Risi chat."
    end

    test "a failed read says what failed" do
      assert {"I couldn't read your calendar on this phone (the calendar didn't answer in time).",
              false} =
               Spoken.render(%{
                 ok?: false,
                 failed:
                   "I couldn't read your calendar on this phone (the calendar didn't answer in time)."
               })
    end

    test "the model's phrasing is used only when it keeps every time and adds nothing" do
      r = "Today: busy from 2 to 3 pm. Tomorrow: a reminder, Call Ruwan, at 5 pm."

      good =
        "Today you're busy from 2 to 3 pm, and tomorrow there's a reminder to Call Ruwan at 5 pm."

      assert Spoken.checked(good, r, ["Call Ruwan"]) == good

      # An invented time, a dropped time, a refusal, card text, a dropped title.
      refute Spoken.checked("Today you're busy from 2 to 4 pm; tomorrow at 5 pm Call Ruwan.", r)
      refute Spoken.checked("Today you're busy from 2 to 3 pm.", r)
      refute Spoken.checked("I can't see your calendar, sorry. 2 3 5", r)
      refute Spoken.checked("From: your phone calendars · Risi 2 3 5", r)

      refute Spoken.checked("Busy from 2 to 3 pm today; tomorrow something at 5 pm.", r, [
               "Call Ruwan"
             ])

      refute Spoken.checked(nil, r)
    end

    test "clock and span phrases" do
      assert Spoken.clock(~N[2026-10-15 09:30:00]) == "9:30 am"
      assert Spoken.clock(~N[2026-10-15 12:00:00]) == "12 noon"
      assert Spoken.span(~N[2026-10-15 11:00:00], ~N[2026-10-15 13:00:00]) == "from 11 am to 1 pm"
      assert Spoken.span(~N[2026-10-15 14:00:00], ~N[2026-10-15 15:30:00]) == "from 2 to 3:30 pm"
      assert Spoken.day_label(~D[2026-10-18], @today) == "Sunday the 18th"
    end
  end

  describe "2. dates and follow-ups" do
    test "today and tomorrow is two days (was tomorrow only)" do
      assert %{from: ~D[2026-10-15], to: ~D[2026-10-17], now?: false} =
               ScheduleIntent.range("Check my calendar for today and tomorrow?", @today)
    end

    test "the 18th: this month's when not past, else next month's" do
      assert %{from: ~D[2026-10-18], to: ~D[2026-10-19]} =
               ScheduleIntent.range("Do you have anything on the 18th?", @today)

      assert %{from: ~D[2026-10-15]} = ScheduleIntent.range("anything on the 15th", @today)

      assert %{from: ~D[2026-11-03], to: ~D[2026-11-04]} =
               ScheduleIntent.range("what about the 3rd?", @today)

      # An ordinal that isn't a day stays out when a day is named.
      assert %{from: ~D[2026-10-16]} = ScheduleIntent.range("my 2nd meeting tomorrow", @today)
      # A clock time is not a date.
      refute ScheduleIntent.day_named?("call me on the 5 pm slot", @today)
    end

    test "this week / the entire week = today to Sunday; on a Sunday the coming seven days" do
      for t <- ["what's on this week", "Give me the entire week", "the whole week please"] do
        assert %{from: ~D[2026-10-15], to: ~D[2026-10-19]} = ScheduleIntent.range(t, @today), t
      end

      assert %{from: ~D[2026-10-11], to: ~D[2026-10-18]} =
               ScheduleIntent.range("what's on this week", ~D[2026-10-11])
    end

    test "a follow-up inherits the call's calendar topic" do
      topic = %{route: "calendar_read", range: %{from: @today, to: ~D[2026-10-17], now?: false}}
      ctx = %{today: @today, topic: topic, user_id: "u"}

      assert {"calendar_read", %{from: ~D[2026-10-18], to: ~D[2026-10-19]}} =
               Brain.decide("on the 18th?", ctx)

      assert {"calendar_read", %{from: ~D[2026-10-15], to: ~D[2026-10-17]}} =
               Brain.decide("Why can't you get the calendar information?", ctx)

      assert {"calendar_read", %{from: ~D[2026-10-15], to: ~D[2026-10-19]}} =
               Brain.decide("Give me the entire week… tell me the important", ctx)

      assert {"calendar_read", %{from: ~D[2026-10-16]}} = Brain.decide("what about Friday?", ctx)

      # Without a topic a fragment stays a chat answer; a write is never a read.
      assert {"chat", nil} = Brain.decide("on the 18th?", %{ctx | topic: nil})
      assert {"reminder", nil} = Brain.decide("remind me to call Ruwan at 5", ctx)
      assert {"chat", nil} = Brain.decide("thanks, that's all", ctx)
    end

    test "what can you do? is the capabilities route" do
      ctx = %{today: @today, user_id: "u"}

      for t <- ["What can you do?", "what else can you do", "How can you help me?"],
          do: assert({"capabilities", nil} = Brain.decide(t, ctx), t)
    end
  end

  describe "3. titles (speech only)" do
    test "the titles hook names the phone's event; the stored rendering stays times-only" do
      f = facts(~D[2026-10-16], ~D[2026-10-17], [ev("busy", nil, {~D[2026-10-16], 10, 0})])
      titles = fn "2026-10-16T10:0Z", _ -> "Call with Ruwan" end

      assert {"Tomorrow: Call with Ruwan from 10 to 11 am.", false} =
               Spoken.render(f, titles: titles)

      assert Spoken.titled?(f, titles)
      assert {"Tomorrow: busy from 10 to 11 am.", true} = Spoken.render(f, titles: nil)
      # A hook that fails or knows nothing: times only.
      assert {"Tomorrow: busy from 10 to 11 am.", true} =
               Spoken.render(f, titles: fn _, _ -> raise "boom" end)
    end
  end

  describe "5. capabilities" do
    test "only the tools this user has; writes wait for the Risi chat" do
      text = Spoken.capabilities(~w(risi_calendar_check risi_items set_reminder memory_search))
      assert text =~ "tell you what's on your calendar"
      assert text =~ "reminders, promises and follow-ups"
      assert text =~ "set reminders"
      assert text =~ "waits for your OK in your Risi chat"
      refute text =~ "send a message"

      none = Spoken.capabilities([])
      assert none =~ "chat"
      refute none =~ "calendar for any day"
    end
  end

  test "a card's footer is never spoken" do
    assert Spoken.speakable(
             "Here's what I've set up for you:\n- Reminder: Call Ruwan\n\nFrom: your phone calendars · Risi"
           ) == "Here's what I've set up for you: Reminder: Call Ruwan."
  end
end
