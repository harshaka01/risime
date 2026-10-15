defmodule RisiMe.Agent.TimeRangeTest do
  @moduledoc "Root 15 Oct: the write's range from the user's own words (Harsha's 3rd call: 10–12, not 10–11)."
  use RisiMe.DataCase, async: false

  alias RisiMe.Agent.TimeRange

  test "the write's start/end are set from the words (the model's 10–11 becomes 10–12)" do
    now = ~U[2026-10-15 14:15:00Z]

    ctx = %{
      now: now,
      tz: "Asia/Colombo",
      question: "Add an appointment tomorrow, block it out from 10 to 12, RISD"
    }

    a =
      TimeRange.apply(
        "risi_calendar_add",
        %{
          "title" => "RISD",
          "start" => "2026-10-16T04:30:00Z",
          "end" => "2026-10-16T05:30:00Z"
        },
        ctx
      )

    assert a["start"] =~ "2026-10-16T04:30:00"
    assert a["end"] =~ "2026-10-16T06:30:00"

    # No day in the words: the model's day stays; set_reminder is untouched.
    ctx2 = %{ctx | question: "Change the event I just asked you to add: From 9 to 10. racing"}

    a2 =
      TimeRange.apply(
        "risi_calendar_add",
        %{"title" => "x", "start" => "2026-10-18T04:30:00Z"},
        ctx2
      )

    assert a2["start"] =~ "2026-10-18T03:30:00"
    assert a2["end"] =~ "2026-10-18T04:30:00"
    assert TimeRange.apply("set_reminder", %{"when" => "x"}, ctx) == %{"when" => "x"}

    d =
      TimeRange.apply_draft(%{"kind" => "event", "time" => "10:00", "end_time" => "11:00"}, ctx)

    assert d["time"] == "10:00" and d["end_time"] == "12:00" and d["date"] == "2026-10-16"
  end
end
