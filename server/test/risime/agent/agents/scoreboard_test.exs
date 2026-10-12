defmodule RisiMe.Agents.ScoreboardTest do
  @moduledoc """
  Decision 084 step 1: the daily scoreboard (`risi_agent_scores`) from the agents' runs, the
  legacy turns and the golden results; its Markdown; the cron job; the dev-only admin view.
  """
  use RisiMeWeb.ConnCase, async: false
  use Oban.Testing, repo: RisiMe.Repo

  alias RisiMe.Agents.{Runs, Scoreboard, ScoreboardJob}

  @moduletag capture_log: true

  setup do
    restore_on_exit([:risi_agent_flags, :risi_dev_users])
    Application.delete_env(:risime, :risi_agent_flags)
    :ok
  end

  defp restore_on_exit(keys), do: RisiMe.RisiHelpers.restore_on_exit(keys)

  test "a day's rows: router agreement and latency, the planner's turn timings, incidents" do
    today = Date.utc_today()

    for {agreed, ms} <- [{true, 1}, {true, 2}, {false, 3}],
        do: Runs.record(%{agent: "router", mode: :shadow, agreed: agreed, latency_ms: ms})

    Runs.record(%{
      agent: "router",
      mode: :on,
      status: "timeout",
      fell_back: true,
      latency_ms: 1500
    })

    for ms <- [2_000, 3_000, 9_000],
        do: Runs.record(%{agent: "legacy_turn", mode: "legacy", latency_ms: ms})

    rows = Scoreboard.run(today)
    router = Enum.find(rows, &(&1.agent == "router"))
    # `n` also counts the day's `route` model calls in the (shared, not rolled back) learning log.
    assert router.detail["runs"] == 4 and router.n >= 4 and router.incidents == 1
    assert_in_delta router.agreement, 2 / 3, 0.001
    refute Enum.any?(rows, &(&1.agent == "legacy_turn"))

    Scoreboard.put_eval(today, "router", %{
      mode: "fake",
      n: 64,
      accuracy: 1.0,
      halluc: 0,
      p50_ms: 0.1,
      p95_ms: 0.3,
      failed: []
    })

    md = Scoreboard.markdown(1)
    assert md =~ "| #{today} | router | daily | #{router.n} |"
    assert md =~ "| #{today} | router | eval_fake | 64 | 100.0% |"

    # Re-running a day replaces its rows.
    Scoreboard.run(today)
    assert length(Enum.filter(Scoreboard.rows(1), &(&1.source == "daily"))) == length(rows)
  end

  test "the cron job computes yesterday, or a given day" do
    assert :ok = perform_job(ScoreboardJob, %{})
    assert :ok = perform_job(ScoreboardJob, %{"day" => Date.to_iso8601(Date.utc_today())})
  end

  describe "GET /api/v1/risi/agents" do
    test "RISI_DEV_USERS only: specs, flags, scores, incidents; 404 for others", %{conn: conn} do
      %{user: user, token: token} = RisiMe.Fixtures.logged_in_user()
      conn = put_req_header(conn, "authorization", "Bearer " <> token)

      assert conn |> get("/api/v1/risi/agents") |> json_response(404)

      Application.put_env(:risime, :risi_dev_users, [String.downcase(user.id)])
      body = conn |> get("/api/v1/risi/agents") |> json_response(200)
      assert Enum.any?(body["agents"], &(&1["name"] == "router" and &1["mode"] == "shadow"))
      assert Enum.any?(body["flags"], &(&1["name"] == "RISI_AGENTS"))
      assert is_list(body["scores"]) and is_list(body["incidents"])
    end
  end
end
