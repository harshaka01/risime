defmodule RisiMe.TimeUUIDTest do
  use ExUnit.Case, async: true

  alias RisiMe.TimeUUID

  @v1 ~r/^[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

  test "version 1, RFC variant, strictly increasing timestamps" do
    ids = for _ <- 1..20_000, do: TimeUUID.generate()
    assert Enum.all?(ids, &(&1 =~ @v1))
    assert length(Enum.uniq(ids)) == length(ids)

    stamps = Enum.map(ids, &raw_timestamp/1)
    assert stamps == Enum.sort(stamps)
    assert stamps == Enum.uniq(stamps)
  end

  test "the timestamp is the current time" do
    dt = TimeUUID.to_datetime(TimeUUID.generate())
    assert abs(DateTime.diff(dt, DateTime.utc_now(), :millisecond)) < 1000
  end

  test "Cassandra-compatible: a known Cassandra timeuuid decodes to its time" do
    # From the contract example (2025-10-06 era); just checks the decoding direction.
    assert %DateTime{year: year} = TimeUUID.to_datetime("c1a2b3c4-a0b1-11f0-8000-0242ac120002")
    assert year in 2025..2026
  end

  defp raw_timestamp(uuid) do
    <<tlo::binary-8, "-", tmid::binary-4, "-1", thi::binary-3, _::binary>> = uuid
    String.to_integer(thi <> tmid <> tlo, 16)
  end
end
