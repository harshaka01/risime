defmodule RisiMe.MixProject do
  use Mix.Project

  # One version for the whole repo (docs/decisions/003-nightly-release-cycle.md).
  @version "../VERSION" |> Path.expand(__DIR__) |> File.read!() |> String.trim()

  def project do
    [
      app: :risime,
      version: @version,
      elixir: "~> 1.17",
      elixirc_paths: elixirc_paths(Mix.env()),
      start_permanent: Mix.env() == :prod,
      aliases: aliases(),
      deps: deps(),
      listeners: [Phoenix.CodeReloader]
    ]
  end

  # Configuration for the OTP application.
  #
  # Type `mix help compile.app` for more information.
  def application do
    [
      mod: {RisiMe.Application, []},
      extra_applications: [:logger, :runtime_tools]
    ]
  end

  def cli do
    [
      preferred_envs: [precommit: :test]
    ]
  end

  # Specifies which paths to compile per environment.
  # dev/ holds dev-only tooling (the load test); never compiled into prod.
  defp elixirc_paths(:test), do: ["lib", "dev", "test/support"]
  defp elixirc_paths(:dev), do: ["lib", "dev"]
  defp elixirc_paths(_), do: ["lib"]

  # Specifies your project dependencies.
  #
  # Type `mix help deps` for examples and options.
  defp deps do
    [
      {:phoenix, "~> 1.8.15"},
      {:phoenix_ecto, "~> 4.5"},
      {:ecto_sql, "~> 3.13"},
      {:postgrex, ">= 0.0.0"},
      {:swoosh, "~> 1.16"},
      {:req, "~> 0.5"},
      {:telemetry_metrics, "~> 1.0"},
      {:telemetry_poller, "~> 1.0"},
      {:jason, "~> 1.2"},
      {:dns_cluster, "~> 0.2.0"},
      {:bandit, "~> 1.5"},
      {:xandra, "~> 0.20.0"},
      {:uniq, "~> 0.6.3"},
      {:gen_smtp, "~> 1.3"},
      {:oban, "~> 2.24"},
      # Decision 075: metrics and LiveDashboard on a separate loopback-only listener.
      {:prom_ex, "~> 1.12"},
      {:phoenix_live_dashboard, "~> 0.9"},
      {:jose, "~> 1.11"},
      # Load-test WebSocket client (mix risime.loadtest); never in prod builds.
      {:mint_web_socket, "~> 1.0", only: [:dev, :test]}
    ]
  end

  # Aliases are shortcuts or tasks specific to the current project.
  # For example, to install project dependencies and perform other setup tasks, run:
  #
  #     $ mix setup
  #
  # See the documentation for `Mix` for more info on aliases.
  defp aliases do
    [
      setup: ["deps.get", "ecto.setup"],
      compile: [&sync_app_version/1, "compile"],
      "ecto.setup": ["ecto.create", "ecto.migrate", "run priv/repo/seeds.exs"],
      "ecto.reset": ["ecto.drop", "ecto.setup"],
      test: [
        "ecto.create --quiet",
        "ecto.migrate --quiet",
        "risime.cql.migrate --quiet",
        "test"
      ],
      precommit: ["compile --warnings-as-errors", "deps.unlock --unused", "format", "test"]
    ]
  end

  # Mix only regenerates risime.app when mix.exs or config changes, not when ../VERSION does.
  # Drop a .app built with another version so `compile` writes a fresh one.
  defp sync_app_version(_args) do
    app_file = Path.join(Mix.Project.compile_path(), "risime.app")

    with {:ok, [{:application, :risime, props}]} <- :file.consult(app_file),
         vsn when vsn != @version <- props |> Keyword.get(:vsn) |> to_string() do
      File.rm!(app_file)
    end

    :ok
  end
end
