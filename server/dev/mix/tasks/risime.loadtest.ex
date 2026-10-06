defmodule Mix.Tasks.Risime.Loadtest do
  @shortdoc "Load-tests a running dev server with N simulated users (dev only)"
  @moduledoc """
  Simulates concurrent users exchanging DMs over real WebSockets against a running server,
  then prints send→reply and send→recipient-push latency percentiles.

      mix risime.loadtest [--url http://127.0.0.1:4100] [--users 200] [--duration 180]
                          [--interval 1500]
      mix risime.loadtest --cleanup   # delete leftover load-test users only

  Never point it at the server Harsha tests on (:4000). Start a temporary one from this
  checkout (docs/decisions/006):

      PORT=4100 LOG_LEVEL=info mix phx.server

  It uses the dev database for its throwaway users (`+999…` phones) and deletes them at the end.
  `--interval` is each user's send interval in ms. Keep it above 500 (the limit is 20 sends per
  10 s per user).
  """
  use Mix.Task

  require Logger

  @switches [
    url: :string,
    users: :integer,
    duration: :integer,
    interval: :integer,
    cleanup: :boolean,
    json: :string
  ]

  @impl true
  def run(args) do
    {opts, _} = OptionParser.parse!(args, strict: @switches)

    url = opts[:url] || "http://127.0.0.1:4100"
    if URI.parse(url).port == 4000, do: Mix.raise("refusing to load-test :4000 (decision 006)")

    Mix.Task.run("app.config")
    Logger.configure(level: :info)
    {:ok, _} = Application.ensure_all_started([:ecto_sql, :postgrex, :mint, :jason])
    {:ok, _} = RisiMe.Repo.start_link(pool_size: 2)

    if opts[:cleanup] do
      Mix.shell().info("deleted #{inspect(RisiMe.LoadTest.cleanup())} (users, allowlist)")
    else
      load(url, opts)
    end
  end

  defp load(url, opts) do
    n = opts[:users] || 200
    duration = opts[:duration] || 180
    interval = max(opts[:interval] || 1500, 500)

    Mix.shell().info("creating #{n} throwaway users…")
    users = RisiMe.LoadTest.create_users(n)
    :ok = RisiMe.LoadTest.befriend_ring(users)

    try do
      Mix.shell().info("running #{n} users for #{duration} s against #{url}, 1 msg/#{interval} ms each")

      report =
        RisiMe.LoadTest.run(users, url: url, users: n, duration_s: duration, interval_ms: interval)

      Mix.shell().info(inspect(report, pretty: true, limit: :infinity))
      if path = opts[:json], do: File.write!(path, Jason.encode!(report, pretty: true))
    after
      {u, a} = RisiMe.LoadTest.cleanup()
      Mix.shell().info("cleanup: deleted #{u} users, #{a} allowlist entries")
    end
  end
end
