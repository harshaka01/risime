defmodule RisiMe.Agent.GoogleReconnectV131Test do
  @moduledoc "v1.31 §31.8: the `google_reconnect` card (once per 24 h, in the Risi chat)."
  use RisiMe.DataCase, async: false

  import RisiMe.CalendarHelpers
  import RisiMe.GroupHelpers, only: [api: 5]

  alias RisiMe.Agent.GoogleLink
  alias RisiMe.Repo

  @moduletag capture_log: true

  @put %{
    "connect" => true,
    "state" => "connected",
    "read_calendars" => 2,
    "write_calendar" => true,
    "mirror" => true
  }

  @url "/api/v1/risi/calendar/google"

  setup do
    ctx = calendar_world!()
    gcal_on!()
    Map.put(ctx, :hg, gcal_device!(ctx.harsha.user))
  end

  defp put!(ctx, extra),
    do: {200, _} = api(:put, @url, ctx.harsha.token, Map.merge(@put, extra), ctx.hg)

  defp reauth!(ctx), do: put!(ctx, %{"connect" => false, "state" => "reauth_needed"})
  defp cards, do: posts() |> of_kind("google_reconnect")

  test "one card on the change to reauth_needed; none for a second change within 24 h", ctx do
    put!(ctx, %{})
    assert cards() == []
    reauth!(ctx)
    [card] = cards()

    assert card["reason"] == "reauth_needed" and card["device_id"] == ctx.hg
    assert card["buttons"] == ["reconnect"] and card["notify"] == [ctx.h]
    assert card["made_by"]["model"] == nil and card["call_ref"] == nil

    # Reconnect, then needs reconnecting again within 24 h: no second card.
    put!(ctx, %{})
    reauth!(ctx)
    assert cards() == []

    # After 24 h: a new one.
    day_ago = DateTime.add(DateTime.utc_now(), -90_000, :second)
    Repo.update_all(GoogleLink, set: [reconnect_card_at: day_ago])
    put!(ctx, %{})
    reauth!(ctx)
    assert [_] = cards()
  end

  test "the body names the Google device", ctx do
    put!(ctx, %{})
    reauth!(ctx)

    assert [{_, body, _}] =
             posts() |> Enum.filter(fn {_, _, r} -> r["kind"] == "google_reconnect" end)

    assert body =~ "Google Calendar needs reconnecting."
    assert body =~ "Reconnect it on "
    assert body =~ "Settings → Risi skills → Calendar."
  end

  test "a PUT that stays reauth_needed posts nothing more", ctx do
    put!(ctx, %{})
    reauth!(ctx)
    assert [_] = cards()
    reauth!(ctx)
    assert cards() == []
  end

  test "none for a user without a Risi chat" do
    RisiMe.MLSHelpers.with_attestation_key(%{})
    u = RisiMe.Fixtures.logged_in_user(display_name: "Nobody")
    d = gcal_device!(u.user)
    {200, _} = api(:put, @url, u.token, @put, d)

    {200, _} =
      api(:put, @url, u.token, %{@put | "connect" => false, "state" => "reauth_needed"}, d)

    assert cards() == []
  end
end
