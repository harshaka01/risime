defmodule RisiMe.Agents.Runs do
  @moduledoc """
  `risi_agent_runs` (decision 084): one row per orchestrated agent run, plus one per legacy turn
  (agent `legacy_turn`, its timing only). Codes, counts and timings: never text. `user_ref` is
  `RisiMe.Agents.Activity.user_ref/1`, never a user id.

  An **incident** is a run that fell back to the legacy path (`fell_back`), or whose status is
  `error | timeout | over_budget`. `record/1` never raises: a failed write is one warning line.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Repo

  @table "risi_agent_runs"
  @fields ~w(agent mode legacy_route decision agreed status fell_back reason latency_ms first_ms budget_ms
             learned_version by lang user_ref)a

  @doc "Writes one run row (`fields` as in the table; `:user` is hashed into `user_ref`)."
  def record(fields) do
    fields =
      fields
      |> Map.new()
      |> then(fn f ->
        if Map.has_key?(f, :user),
          do: Map.put(f, :user_ref, RisiMe.Agents.Activity.user_ref(f.user)),
          else: f
      end)

    row =
      @fields
      |> Map.new(&{&1, normalise(&1, Map.get(fields, &1))})
      |> Map.merge(%{
        id: Ecto.UUID.bingenerate(),
        at: Map.get(fields, :at) || DateTime.utc_now(),
        fell_back: Map.get(fields, :fell_back) == true,
        status: to_string(Map.get(fields, :status) || "ok"),
        mode: to_string(Map.get(fields, :mode) || "legacy")
      })

    Repo.insert_all(@table, [row])
    :ok
  rescue
    e ->
      Logger.warning("risi agents: run row not written (#{inspect(e.__struct__)})")
      :error
  catch
    :exit, _ -> :error
  end

  defp normalise(_k, nil), do: nil
  defp normalise(k, v) when k in [:agreed, :fell_back], do: v == true
  defp normalise(k, v) when k in [:latency_ms, :first_ms, :budget_ms], do: trunc(v)
  defp normalise(_k, v) when is_atom(v), do: Atom.to_string(v)
  defp normalise(_k, v) when is_binary(v), do: String.slice(v, 0, 120)
  defp normalise(_k, v), do: to_string(v)

  @doc """
  Stats of `agent`'s runs in `[from, to)` (UTC datetimes): `%{n, p50_ms, p95_ms, agreement,
  incidents, by_mode, by_status}`. `agreement` is the share of runs with `agreed` set that agree.
  """
  def stats(agent, %DateTime{} = from, %DateTime{} = to) do
    rows =
      Repo.all(
        from r in @table,
          where: r.agent == ^agent and r.at >= ^from and r.at < ^to,
          select: %{
            latency_ms: r.latency_ms,
            agreed: r.agreed,
            status: r.status,
            fell_back: r.fell_back,
            mode: r.mode
          }
      )

    lat = rows |> Enum.map(& &1.latency_ms) |> Enum.reject(&is_nil/1)
    judged = Enum.reject(rows, &is_nil(&1.agreed))

    %{
      n: length(rows),
      p50_ms: percentile(lat, 0.5),
      p95_ms: percentile(lat, 0.95),
      agreement:
        if(judged == [], do: nil, else: Enum.count(judged, & &1.agreed) / length(judged)),
      incidents: Enum.count(rows, &incident?/1),
      by_mode: Enum.frequencies_by(rows, & &1.mode),
      by_status: Enum.frequencies_by(rows, & &1.status)
    }
  end

  @doc "The latest incidents (newest first), at most `limit`."
  def incidents(limit \\ 50) do
    Repo.all(
      from r in @table,
        where: r.fell_back == true or r.status in ["error", "timeout", "over_budget"],
        order_by: [desc: r.at],
        limit: ^limit,
        select: %{
          agent: r.agent,
          mode: r.mode,
          status: r.status,
          reason: r.reason,
          latency_ms: r.latency_ms,
          budget_ms: r.budget_ms,
          learned_version: r.learned_version,
          at: r.at
        }
    )
  end

  @doc "The agents that have runs in `[from, to)`."
  def agents(%DateTime{} = from, %DateTime{} = to) do
    Repo.all(
      from r in @table,
        where: r.at >= ^from and r.at < ^to,
        distinct: true,
        select: r.agent
    )
  end

  defp incident?(r), do: r.fell_back or r.status in ["error", "timeout", "over_budget"]

  @doc "The `q` percentile (nearest rank) of a list of numbers, or nil."
  def percentile([], _q), do: nil

  def percentile(xs, q) do
    s = Enum.sort(xs)
    Enum.at(s, max(0, min(length(s) - 1, ceil(q * length(s)) - 1)))
  end
end
