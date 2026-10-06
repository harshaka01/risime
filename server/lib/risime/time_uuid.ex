defmodule RisiMe.TimeUUID do
  @moduledoc """
  RFC 4122 version-1 UUIDs (Cassandra `timeuuid`) that are strictly increasing on this node.

  Cassandra orders timeuuids by their timestamp, and inbox cursors depend on that order, so
  timestamps must be real 100 ns intervals since 1582-10-15 and must never repeat or go
  backwards. (`Uniq.UUID.uuid1/0` 0.6.x does not meet this: its timestamp wraps every 0.1 s.)

  The clock sequence and node id are random per boot (node id with the multicast bit set,
  as RFC 4122 §4.5 requires for non-MAC node ids).
  """

  # 100 ns intervals between 1582-10-15 and 1970-01-01.
  @gregorian_offset 0x01B21DD213814000
  @key __MODULE__

  @doc "Called once at application start."
  def init do
    ref = :atomics.new(1, signed: false)
    <<clock_seq::14, _::2>> = :crypto.strong_rand_bytes(2)
    <<node_hi::7, _::1, node_lo::40>> = :crypto.strong_rand_bytes(6)
    node = <<node_hi::7, 1::1, node_lo::40>>
    :persistent_term.put(@key, {ref, clock_seq, node})
  end

  @doc "A new timeuuid string, later than every one generated before it on this node."
  def generate do
    {ref, clock_seq, node} = :persistent_term.get(@key)
    format(next_timestamp(ref), clock_seq, node)
  end

  @doc "The timeuuid's timestamp as a UTC DateTime (microsecond precision)."
  def to_datetime(<<_::binary-size(36)>> = uuid) do
    <<tlo::32, tmid::16, _v::4, thi::12, _::binary>> =
      uuid |> String.replace("-", "") |> Base.decode16!(case: :mixed)

    <<ts::60>> = <<thi::12, tmid::16, tlo::32>>
    DateTime.from_unix!(div(ts - @gregorian_offset, 10), :microsecond)
  end

  @doc "The timeuuid's raw timestamp (100 ns intervals since 1582-10-15): compare times with this."
  def timestamp(<<_::binary-size(36)>> = uuid) do
    <<tlo::32, tmid::16, _v::4, thi::12, _::binary>> =
      uuid |> String.replace("-", "") |> Base.decode16!(case: :mixed)

    <<ts::60>> = <<thi::12, tmid::16, tlo::32>>
    ts
  end

  @doc "The timestamp of `timestamp/1` as Unix milliseconds (truncated)."
  def unix_ms(uuid), do: div(timestamp(uuid) - @gregorian_offset, 10_000)

  @doc """
  A timeuuid for the given time (random clock sequence and node). Not ordered against
  `generate/0`; for tests and backfills only.
  """
  def at(%DateTime{} = dt) do
    ts = DateTime.to_unix(dt, :microsecond) * 10 + @gregorian_offset
    <<clock_seq::14, _::2>> = :crypto.strong_rand_bytes(2)
    <<node_hi::7, _::1, node_lo::40>> = :crypto.strong_rand_bytes(6)
    format(ts, clock_seq, <<node_hi::7, 1::1, node_lo::40>>)
  end

  defp next_timestamp(ref) do
    now = div(System.system_time(:nanosecond), 100) + @gregorian_offset
    last = :atomics.get(ref, 1)
    candidate = max(now, last + 1)

    case :atomics.compare_exchange(ref, 1, last, candidate) do
      :ok -> candidate
      _changed -> next_timestamp(ref)
    end
  end

  defp format(ts, clock_seq, node) do
    <<thi::12, tmid::16, tlo::32>> = <<ts::60>>
    <<clock_hi::6, clock_lo::8>> = <<clock_seq::14>>

    <<tlo::32, tmid::16, 1::4, thi::12, 0b10::2, clock_hi::6, clock_lo::8, node::binary>>
    |> Base.encode16(case: :lower)
    |> then(fn <<a::binary-8, b::binary-4, c::binary-4, d::binary-4, e::binary-12>> ->
      Enum.join([a, b, c, d, e], "-")
    end)
  end
end
