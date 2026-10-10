defmodule RisiMe.Agent.WriteHonestyTest do
  use ExUnit.Case, async: true

  alias RisiMe.Agent.{NextActions, WriteHonesty}

  test "claims are removed unless the turn ran a done write" do
    assert {"Anything else?", true} =
             WriteHonesty.enforce("I've added it to your calendar. Anything else?", 0, false)

    assert {t, true} = WriteHonesty.enforce("Done! Your reminder is set.", 0, true)
    assert t =~ "nothing is done until you tap Add"
    assert {"I've added it.", false} = WriteHonesty.enforce("I've added it.", 1, false)

    assert {"You have two meetings.", false} =
             WriteHonesty.enforce("You have two meetings.", 0, false)
  end

  test "next_actions: settings become open, questions ask, the rest is dropped (max 3)" do
    assert NextActions.build([
             "Turn on the Calendar skill",
             "Allow calendar access",
             "Turn on notifications",
             "Connect your calendar in Settings",
             "Whatever"
           ]) == [
             %{
               "label" => "Turn on the Calendar skill",
               "action" => "open",
               "target" => "settings.risi_skills"
             },
             %{
               "label" => "Allow calendar access",
               "action" => "open",
               "target" => "settings.calendar_permission"
             },
             %{
               "label" => "Turn on notifications",
               "action" => "open",
               "target" => "settings.notifications"
             }
           ]

    assert [%{"action" => "ask", "text" => "Show my promises"}] =
             NextActions.build(["Show my promises", "Open Settings"])

    assert NextActions.build(nil) == []
  end
end
