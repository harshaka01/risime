defmodule RisiMe.Agent.GoogleSkillsV131Test do
  @moduledoc "v1.31 §26.1 (amended): the Calendar skill's OAuth permissions and texts."
  use RisiMe.DataCase, async: false

  import RisiMe.CalendarHelpers
  import RisiMe.RisiHelpers, only: [skills_on!: 0]
  import RisiMe.GroupHelpers, only: [api: 5]

  @moduletag capture_log: true

  @example Path.expand("../../../../contract/v1/examples/risi_skills_reply_v131.json", __DIR__)

  setup do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    events_on!()
    skills_on!()
    RisiMe.TabsHelpers.risi_tools_on!()
    u = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    %{u: u, old: calendar_device!(u.user), google: gcal_device!(u.user)}
  end

  defp calendar(ctx, dev) do
    {200, %{"skills" => skills}} = api(:get, "/api/v1/risi/skills", ctx.u.token, nil, dev)
    Enum.find(skills, &(&1["id"] == "calendar"))
  end

  test "a google_calendar device gets the v1.31 entry (the contract example)", ctx do
    ex = Enum.find(Jason.decode!(File.read!(@example))["skills"], &(&1["id"] == "calendar"))
    c = calendar(ctx, ctx.google)

    keys = ~w(id kind title description can cannot permissions tools where modes undo available)
    assert Map.take(c, keys) == Map.take(ex, keys)
    assert Enum.map(c["permissions"], & &1["scope"]) == ~w(android android oauth oauth)
    assert Enum.all?(c["permissions"], & &1["runtime"])
  end

  test "an older device keeps the v1.30 entry", ctx do
    c = calendar(ctx, ctx.old)
    assert c["description"] == "Checks when you're free and adds events to your phone's calendar."
    assert Enum.map(c["permissions"], & &1["scope"]) == ~w(android android)
  end

  test "PATCH client_permission granted with no Android permission is valid for calendar", ctx do
    change = %{"id" => "calendar", "state" => "ask", "client_permission" => "granted"}

    assert {200, %{"skills" => [c]}} =
             api(
               :patch,
               "/api/v1/risi/skills",
               ctx.u.token,
               %{"changes" => [change]},
               ctx.google
             )

    assert c["state"] == "ask" and c["client"]["permission"] == "granted"
    assert Enum.map(c["permissions"], & &1["scope"]) == ~w(android android oauth oauth)
  end
end
