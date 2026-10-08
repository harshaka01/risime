defmodule RisiMe.Groups.Strikes do
  @moduledoc """
  v1.21 §12.12.4: the naming budget of `devices` ops (groups and DMs).

  A strike is a naming whose 60 s ran out without an accepted commit completing the op. They are
  stored per op as `strikes: %{device_id => [count, last_at_iso]}`. A device with
  #{3} strikes on an op is out of budget for it until the op is widened (every strike of the op
  is cleared), the device re-advertises different capabilities or connects with a different
  `app_version` (its strikes on every op are cleared, `clear_device/1`), or 24 h passed since its
  last strike on that op. An op is exhausted when it has at least one candidate and every
  candidate is out of budget.
  """
  alias RisiMe.Repo

  @max 3
  @window_s 24 * 3600

  def max, do: @max

  @doc "The effective strike count of a device on an op (0 once 24 h passed since the last)."
  def count(strikes, device_id, now \\ DateTime.utc_now()) do
    case (strikes || %{})[device_id] do
      [n, at] when is_integer(n) and is_binary(at) ->
        case DateTime.from_iso8601(at) do
          {:ok, t, _} -> if DateTime.diff(now, t) < @window_s, do: n, else: 0
          _ -> 0
        end

      _ ->
        0
    end
  end

  @doc "True if the device is out of budget on the op."
  def out?(strikes, device_id, now \\ DateTime.utc_now()),
    do: count(strikes, device_id, now) >= @max

  @doc "Adds one strike for the device (a stale count starts again at 1)."
  def add(strikes, device_id, now \\ DateTime.utc_now()) do
    Map.put(strikes || %{}, device_id, [
      count(strikes, device_id, now) + 1,
      DateTime.to_iso8601(now)
    ])
  end

  @doc "The candidates still within their budget."
  def in_budget(strikes, refs, now \\ DateTime.utc_now()),
    do: Enum.reject(refs, fn {_u, d} -> out?(strikes, d, now) end)

  @doc "At least one candidate, and every candidate out of budget."
  def exhausted?(strikes, refs, now \\ DateTime.utc_now()),
    do: refs != [] and in_budget(strikes, refs, now) == []

  @doc "When the earliest out-of-budget device of `refs` gets its budget back (or nil)."
  def next_expiry(strikes, refs) do
    refs
    |> Enum.flat_map(fn {_u, d} ->
      case (strikes || %{})[d] do
        [n, at] when is_integer(n) and n >= @max ->
          case DateTime.from_iso8601(at) do
            {:ok, t, _} -> [DateTime.add(t, @window_s, :second)]
            _ -> []
          end

        _ ->
          []
      end
    end)
    |> Enum.min(DateTime, fn -> nil end)
  end

  @doc """
  The device may have updated (new capabilities or `app_version`): clears its strikes on every
  group and DM op. Best effort; returns the number of ops changed.
  """
  def clear_device(device_id) when is_binary(device_id) do
    %{num_rows: a} =
      Repo.query!(
        "UPDATE group_ops SET strikes = strikes - $1::text WHERE strikes ? $1::text",
        [device_id]
      )

    %{num_rows: b} =
      Repo.query!(
        "UPDATE mls_dm_ops SET strikes = strikes - $1::text WHERE strikes ? $1::text",
        [device_id]
      )

    a + b
  end

  def clear_device(_), do: 0
end
