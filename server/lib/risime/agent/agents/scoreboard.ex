defmodule RisiMe.Agents.Scoreboard do
  @moduledoc """
  The daily per-agent scoreboard (decision 084, PART 6 step 1). For one UTC day, per agent:

    * `n`, `p50_ms`, `p95_ms` — model calls of the agent's tasks from the learning log (counts and
      timings only), and for the planner the whole turn (`legacy_turn` rows);
    * `acceptance` — ledger: promise cards proposed that day and confirmed/edited/done;
      calendar: confirm cards (`risi_pending_writes`) done; voice: offered items added
      (`risi_item_offers`); planner: 👍 / (👍 + 👎) on that day's turns;
    * `agreement`, `incidents` — the agent's own runs (`risi_agent_runs`: shadow agreement with
      today's route, fallbacks);
    * `accuracy`, `halluc` — the latest golden-set run stored for the agent (`source` =
      `eval_live` / `eval_fake`, written by `put_eval/3`).

  Rows go to `risi_agent_scores` (`source = "daily"`), upserted by `RisiMe.Agents.ScoreboardJob`
  (Oban cron 00:20 UTC for yesterday). `markdown/1` renders the table of
  `docs/status/risi-scores.md`.
  """
  import Ecto.Query

  alias RisiMe.Agent.LearningLog
  alias RisiMe.Agents.Runs
  alias RisiMe.Repo

  # Learning-log task → agent.
  @task_agent %{
    "risi_next_action" => "planner",
    "ask" => "planner",
    "commitment_extract" => "ledger",
    "discussion_summarise" => "ledger",
    "discussion_translate" => "ledger",
    "note_translate" => "ledger",
    "memory_extract" => "memory",
    "memory_map" => "memory",
    "summarise" => "summary",
    "daily_summarise" => "summary",
    "summary_rollup" => "summary",
    "report" => "summary",
    "extract_actions" => "voice",
    "action_feedback" => "voice",
    "transcribe" => "voice",
    "transcribe_compare" => "voice",
    "route" => "router"
  }

  @doc "The agent a learning-log task belongs to."
  def agent_of_task(task), do: Map.get(@task_agent, task, "other")

  @doc "Computes the rows of `day` (a `Date`, UTC): `[%{day, agent, source: \"daily\", …}]`."
  def compute(%Date{} = day) do
    from = DateTime.new!(day, ~T[00:00:00], "Etc/UTC")
    to = DateTime.add(from, 86_400, :second)
    calls = calls(day)
    by_agent = Enum.group_by(calls, &agent_of_task(&1.task))
    agents = Enum.uniq(Map.keys(by_agent) ++ (Runs.agents(from, to) -- ["legacy_turn"]))

    for agent <- Enum.sort(agents) do
      cs = Map.get(by_agent, agent, [])
      lat = for c <- cs, c.status == "ok", is_integer(c.latency_ms), do: c.latency_ms
      own = Runs.stats(agent, from, to)
      turn = if agent == "planner", do: Runs.stats("legacy_turn", from, to)

      %{
        day: day,
        agent: agent,
        source: "daily",
        n: max(length(cs), own.n),
        p50_ms: (turn && turn.p50_ms) || Runs.percentile(lat, 0.5) || own.p50_ms,
        p95_ms: (turn && turn.p95_ms) || Runs.percentile(lat, 0.95) || own.p95_ms,
        acceptance: acceptance(agent, from, to, cs),
        agreement: own.agreement,
        incidents: own.incidents,
        detail: %{
          "calls" => length(cs),
          "calls_ok" => Enum.count(cs, &(&1.status == "ok")),
          "call_p50_ms" => Runs.percentile(lat, 0.5),
          "call_p95_ms" => Runs.percentile(lat, 0.95),
          "turns" => turn && turn.n,
          "runs" => own.n,
          "by_task" => cs |> Enum.frequencies_by(& &1.task)
        }
      }
      |> health(agent, day)
    end
  end

  # Decision 088: the Health agent's day: stuck conversations, the share repaired, and the
  # reliability (1 - stuck / conversations) as its accuracy.
  defp health(row, "health", day) do
    s = RisiMe.Agents.Health.day_score(day)

    row
    |> Map.merge(%{n: s.n, acceptance: s.repaired, accuracy: s.reliability})
    |> Map.put(:detail, Map.put(row.detail, "stuck", s.n))
  end

  defp health(row, _agent, _day), do: row

  @doc "Computes and upserts `day`'s rows; returns them."
  def run(%Date{} = day) do
    rows = compute(day)
    Enum.each(rows, &upsert/1)
    rows
  end

  @doc "Stores a golden-set result (`Eval.run/3`) for `agent` on `day`."
  def put_eval(%Date{} = day, agent, %{} = r) do
    upsert(%{
      day: day,
      agent: agent,
      source: "eval_" <> to_string(r[:mode] || "fake"),
      n: r[:n] || 0,
      accuracy: r[:accuracy],
      halluc: r[:halluc],
      p50_ms: r[:p50_ms] && round(r[:p50_ms]),
      p95_ms: r[:p95_ms] && round(r[:p95_ms]),
      incidents: 0,
      detail: %{"failed" => r[:failed] || [], "by_lang" => r[:by_lang] || %{}}
    })
  end

  defp upsert(row) do
    row = Map.merge(%{incidents: 0, detail: %{}}, row) |> Map.put(:updated_at, DateTime.utc_now())
    fields = Map.keys(row) -- [:day, :agent, :source]

    Repo.insert_all("risi_agent_scores", [row],
      on_conflict: {:replace, fields},
      conflict_target: [:day, :agent, :source]
    )

    :ok
  end

  @doc "The stored rows of the last `days` days (newest first)."
  def rows(days \\ 7) do
    since = Date.add(Date.utc_today(), -days)

    Repo.all(
      from s in "risi_agent_scores",
        where: s.day >= ^since,
        order_by: [desc: s.day, asc: s.agent, asc: s.source],
        select: %{
          day: s.day,
          agent: s.agent,
          source: s.source,
          n: s.n,
          accuracy: s.accuracy,
          acceptance: s.acceptance,
          agreement: s.agreement,
          halluc: s.halluc,
          p50_ms: s.p50_ms,
          p95_ms: s.p95_ms,
          incidents: s.incidents
        }
    )
  end

  @doc "The scoreboard as a Markdown table (the stored rows of the last `days` days)."
  def markdown(days \\ 7) do
    head =
      "| Day | Agent | Source | n | Accuracy | Acceptance | Agreement | Halluc | p50 ms | p95 ms | Incidents |\n" <>
        "|---|---|---|---|---|---|---|---|---|---|---|\n"

    head <>
      Enum.map_join(rows(days), "\n", fn r ->
        "| #{r.day} | #{r.agent} | #{r.source} | #{r.n} | #{pct(r.accuracy)} | #{pct(r.acceptance)} | " <>
          "#{pct(r.agreement)} | #{dash(r.halluc)} | #{dash(r.p50_ms)} | #{dash(r.p95_ms)} | #{r.incidents} |"
      end)
  end

  defp pct(nil), do: "–"
  defp pct(x), do: "#{Float.round(x * 100, 1)}%"
  defp dash(nil), do: "–"
  defp dash(x), do: to_string(x)

  ## Sources

  defp calls(day) do
    for b <- 0..(LearningLog.buckets() - 1), row <- safe_list(day, b) do
      %{
        task: row[:task] || row["task"],
        status: row[:status] || row["status"],
        latency_ms: row[:latency_ms] || row["latency_ms"],
        call_id: row[:call_id] || row["call_id"]
      }
    end
  end

  defp safe_list(day, b) do
    LearningLog.list_by_day(day, b)
  rescue
    _ -> []
  catch
    :exit, _ -> []
  end

  defp acceptance("ledger", from, to, _),
    do:
      share(
        "risi_commitments",
        :proposed_at,
        from,
        to,
        :state,
        ~w(confirmed edited done)
      )

  # Confirm cards of writes, and the proactive "Add to calendar?" offers (`RisiMe.Agent.Offers`).
  defp acceptance("calendar", from, to, _) do
    a = counts("risi_pending_writes", :inserted_at, from, to, :state, ~w(done))
    b = counts("risi_item_offers", :inserted_at, from, to, :state, ~w(added))
    {good, all} = {elem(a, 0) + elem(b, 0), elem(a, 1) + elem(b, 1)}
    if all == 0, do: nil, else: good / all
  end

  defp acceptance("planner", _from, _to, cs) do
    ratings =
      cs
      |> Enum.filter(&(&1.task == "risi_next_action" and &1.call_id))
      |> Enum.flat_map(fn c ->
        try do
          LearningLog.list_feedback(c.call_id)
        rescue
          _ -> []
        end
      end)
      |> Enum.map(&(&1[:rating] || &1["rating"]))

    up = Enum.count(ratings, &(&1 == "up"))
    if ratings == [], do: nil, else: up / length(ratings)
  end

  defp acceptance(_, _, _, _), do: nil

  defp share(table, at, from, to, col, good) do
    case counts(table, at, from, to, col, good) do
      {_, 0} -> nil
      {g, n} -> g / n
    end
  end

  defp counts(table, at, from, to, col, good) do
    rows =
      Repo.all(
        from t in table,
          where: field(t, ^at) >= ^from and field(t, ^at) < ^to,
          select: field(t, ^col)
      )

    {Enum.count(rows, &(&1 in good)), length(rows)}
  rescue
    _ -> {0, 0}
  end
end

defmodule RisiMe.Agents.ScoreboardJob do
  @moduledoc """
  Oban cron (00:20 UTC, queue `risi`): yesterday's scoreboard rows (`RisiMe.Agents.Scoreboard`).
  `args: %{"day" => "YYYY-MM-DD"}` recomputes a given day. Nothing while `RISI_AGENTS=legacy`.
  """
  use Oban.Worker, queue: :risi, max_attempts: 3

  @impl true
  def perform(%Oban.Job{args: args}) do
    day =
      case args do
        %{"day" => d} -> Date.from_iso8601!(d)
        _ -> Date.add(Date.utc_today(), -1)
      end

    unless RisiMe.Agents.Flags.legacy?(), do: RisiMe.Agents.Scoreboard.run(day)
    :ok
  end
end
