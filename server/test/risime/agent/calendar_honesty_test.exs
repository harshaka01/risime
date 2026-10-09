defmodule RisiMe.Agent.CalendarHonestyTest do
  @moduledoc """
  P0 2026-10-09 (Harsha's phone said "Your calendar is clear" with a full Google Calendar): the
  deterministic post-check on a turn's final answer. No read → never "clear"; a read with 0
  events → "clear" allowed and the calendar named; an old phone (no `sources`) → never clear.
  """
  use RisiMe.DataCase, async: true

  alias RisiMe.Agent.CalendarHonesty, as: H

  @tz "Asia/Colombo"
  @window %{"from" => "2026-10-12T08:30:00.000Z", "to" => "2026-10-12T09:30:00.000Z"}
  @work %{"name" => "Work", "account_type" => "com.google", "events" => 0}
  @google_api %{
    "source" => "google_api",
    "calendars" => [],
    "read_ok" => false,
    "reason" => "not_connected"
  }

  defp phone(cals, ok, reason \\ nil),
    do: %{"source" => "phone_provider", "calendars" => cals, "read_ok" => ok, "reason" => reason}

  defp ok(blocks, sources),
    do: H.check("ok", Map.merge(@window, %{"blocks" => blocks, "sources" => sources}), nil)

  @clear "Your calendar is clear on Monday at 2 pm."
  @connect "Connect it in Settings → Risi skills → Calendar."

  test "a read with 0 events: clear is allowed and names the calendar" do
    out = H.enforce(@clear, [ok([], [phone([@work], true), @google_api])], @tz)

    assert out ==
             @clear <>
               "\n\nI checked: Phone calendar — Work (Google) 0 events (Mon 12 Oct, 14:00–15:00)."
  end

  test "no read: never clear" do
    for check <- [
          ok([], [phone([], false, "no_calendars"), @google_api]),
          ok([], [phone([@work], false, "api_error"), @google_api]),
          # read_ok but no calendar at all
          ok([], [phone([], true), @google_api]),
          H.check("no_permission", nil, nil),
          H.check("declined", nil, nil),
          H.check("timeout", nil, nil),
          H.check("failed", nil, "calendar_unavailable")
        ] do
      out = H.enforce(@clear, [check], @tz)
      assert out =~ ~r/^I couldn't read your Google Calendar on this phone \(.+\)\. /
      assert String.ends_with?(out, @connect)
      refute out =~ ~r/\bclear\b|\bfree\b/i, out
    end
  end

  test "the phone's own reason wins over google_api not_connected" do
    out = H.enforce(@clear, [ok([], [@google_api, phone([], false, "no_calendars")])], @tz)

    assert out ==
             "I couldn't read your Google Calendar on this phone (no calendars on this phone). #{@connect}"
  end

  test "an old phone (no sources): never clear" do
    out =
      H.enforce("You're free then.", [H.check("ok", Map.put(@window, "blocks", []), nil)], @tz)

    assert out =~ "this version of the app doesn't say which calendars it read"
    refute out =~ ~r/\bfree\b/i
  end

  test "free without any check: rewritten; other answers untouched" do
    assert H.enforce("Your calendar is clear tomorrow.", [], @tz) =~ "I couldn't read"
    assert H.enforce("Feel free to ask me anything.", [], @tz) == "Feel free to ask me anything."
    assert H.enforce("Done. I set the reminder.", [], @tz) == "Done. I set the reminder."
  end

  test "busy blocks contradict a free claim: rewritten from the blocks" do
    busy = %{
      "start" => "2026-10-12T08:30:00.000Z",
      "end" => "2026-10-12T09:30:00.000Z",
      "busy" => true,
      "all_day" => false
    }

    w = %{@work | "events" => 1}
    out = H.enforce(@clear, [ok([busy], [phone([w], true), @google_api])], @tz)

    assert out ==
             "You're not free then: your calendar has 1 busy time (Mon 12 Oct, 14:00–15:00)." <>
               "\n\nI checked: Phone calendar — Work (Google) 1 event (Mon 12 Oct, 14:00–15:00)."
  end

  test "a non-free answer after a failed read gets the reason added" do
    out = H.enforce("Here's what I found.", [H.check("no_permission", nil, nil)], @tz)

    assert out ==
             "Here's what I found.\n\nI couldn't read your Google Calendar on this phone " <>
               "(calendar permission is off). #{@connect}"
  end

  test "answer sources name what was read" do
    assert [
             %{"type" => "calendar_source", "source" => "phone_provider", "names" => ["Work"]},
             %{"source" => "google_api", "read_ok" => false, "reason" => "not_connected"}
           ] = H.answer_sources([ok([], [phone([@work], true), @google_api])])

    assert H.answer_sources([H.check("no_permission", nil, nil)]) == []
  end
end
