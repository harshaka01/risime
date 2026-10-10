defmodule RisiMeWeb.GcalSwitchV131Test do
  @moduledoc "v1.31 §31.1: the `google_calendar` switch and the device capability."
  use RisiMeWeb.ChannelCase, async: false

  import RisiMe.Fixtures
  import RisiMe.CalendarHelpers
  import RisiMe.MLSHelpers, only: [with_attestation_key: 1]

  alias RisiMe.{Devices, Repo}

  setup :with_attestation_key

  defp config do
    Phoenix.ConnTest.build_conn()
    |> Phoenix.ConnTest.dispatch(RisiMeWeb.Endpoint, :get, "/api/v1/auth/config")
    |> Map.fetch!(:resp_body)
    |> Jason.decode!()
  end

  test "/auth/config google_calendar: on only with RISI_GCAL and RISI_EVENTS (absent = off)" do
    refute Map.has_key?(config(), "google_calendar")
    gcal_on!()
    # RISI_GCAL alone does nothing.
    refute Map.has_key?(config(), "google_calendar")
    events_on!()
    assert config()["google_calendar"] == "on"
  end

  test "the capability is kept only together with risi_events" do
    u = logged_in_user(display_name: "Asha")
    d = gcal_device!(u.user)
    assert "google_calendar" in Repo.get_by!(Devices.Device, device_id: d).capabilities

    odd = RisiMe.TabsHelpers.tabs_device!(u.user, caps: ["groups", "tabs", "google_calendar"])
    refute "google_calendar" in Repo.get_by!(Devices.Device, device_id: odd).capabilities
  end
end
