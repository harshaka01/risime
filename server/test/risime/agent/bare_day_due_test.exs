defmodule RisiMe.Agent.BareDayDueTest do
  use RisiMe.DataCase, async: true

  alias RisiMe.Agent.{Clock, Momentary}

  @tz "Asia/Colombo"

  test "bare today / tomorrow is 18:00 local" do
    at = ~U[2026-10-16 08:24:00Z]

    assert Clock.bare_day_due("today", @tz, at) |> DateTime.to_unix() ==
             DateTime.to_unix(~U[2026-10-16 12:30:00Z])

    assert Clock.bare_day_due("by Tomorrow.", @tz, at) |> DateTime.to_unix() ==
             DateTime.to_unix(~U[2026-10-17 12:30:00Z])

    assert Clock.bare_day_due("හෙට", @tz, at) |> DateTime.to_unix() ==
             DateTime.to_unix(~U[2026-10-17 12:30:00Z])

    assert Clock.bare_day_due("இன்று", @tz, at) |> DateTime.to_unix() ==
             DateTime.to_unix(~U[2026-10-16 12:30:00Z])
  end

  test "today after 18:00 is end of day; anything with a time is left to the model" do
    late = ~U[2026-10-16 14:00:00Z]

    assert Clock.bare_day_due("today", @tz, late) |> DateTime.to_unix() ==
             DateTime.to_unix(~U[2026-10-16 18:29:00Z])

    assert Clock.bare_day_due("today at 3pm", @tz, late) == nil
    assert Clock.bare_day_due("tomorrow morning", @tz, late) == nil
    assert Clock.bare_day_due(nil, @tz, late) == nil
  end

  test "momentary requests" do
    for t <- ["Turn on the camera", "can you hear me", "Share your screen", "unmute"],
        do: assert(Momentary.request?(t))

    for t <- ["Send the accounts to Ko Jin", "Camera quotation follow-up"],
        do: refute(Momentary.request?(t))
  end
end
