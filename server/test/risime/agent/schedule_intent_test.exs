defmodule RisiMe.Agent.ScheduleIntentTest do
  @moduledoc "v1.35 §34.1/§34.4/§34.5: every case of `contract/v1/risi_routing_cases.json`."
  use ExUnit.Case, async: true

  alias RisiMe.Agent.{NameCheck, ScheduleIntent}

  @fixture Path.expand("../../../../contract/v1/risi_routing_cases.json", __DIR__)
  @cases @fixture |> File.read!() |> Jason.decode!()
  # now = 2026-10-11T10:00 local (a Sunday).
  @today ~D[2026-10-11]

  for {c, i} <- Enum.with_index(@cases["routing"]) do
    @c c
    test "routing #{i}: #{c["text"]}" do
      c = @c

      case {c["expect"], ScheduleIntent.classify(c["text"], @today)} do
        {"schedule_read", {:schedule_read, r}} ->
          assert Date.to_iso8601(r.from) == c["range"]["from"]
          assert Date.to_iso8601(r.to) == c["range"]["to"]

        {"risi_items", got} ->
          assert got == :risi_items

        {"none", got} ->
          assert got == :none

        {want, got} ->
          flunk("#{c["text"]}: expected #{want}, got #{inspect(got)}")
      end
    end
  end

  test "names: every fixture case" do
    friends =
      for {n, i} <- Enum.with_index(@cases["names"]["friends"]),
          do: %{name: n, user_id: "u#{i}", rank: i}

    for c <- @cases["names"]["cases"] do
      got = NameCheck.match(c["said"], friends)

      case c["expect"] do
        "exact" ->
          assert got == :exact, c["said"]

        "pass" ->
          assert got == :pass, c["said"]

        "clarify" ->
          assert {:clarify, opts} = got, c["said"]
          assert Enum.map(opts, & &1.name) == c["options"]
      end
    end
  end

  test "more ranges" do
    assert {:schedule_read, %{from: ~D[2026-10-12], to: ~D[2026-10-15]}} =
             ScheduleIntent.classify("am I free Monday to Wednesday", @today)

    assert {:schedule_read, %{from: ~D[2026-10-11], now?: true}} =
             ScheduleIntent.classify("am I free?", @today)

    # A date and a weekday that disagree: the date wins.
    assert {:schedule_read, %{from: ~D[2026-10-13]}} =
             ScheduleIntent.classify("meetings on Monday 13 Oct", @today)

    assert ScheduleIntent.classify("feel free to summarise this", @today) == :none
    assert ScheduleIntent.classify("schedule a message to Kumu at 6", @today) == :none
  end
end
