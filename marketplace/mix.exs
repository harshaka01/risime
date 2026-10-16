defmodule RisiRegistry.MixProject do
  use Mix.Project

  # The marketplace has its own version line (separable from RisiMe, decision 001).
  @version "0.1.0"

  def project do
    [
      app: :risi_registry,
      version: @version,
      elixir: "~> 1.17",
      elixirc_paths: elixirc_paths(Mix.env()),
      start_permanent: Mix.env() == :prod,
      aliases: aliases(),
      deps: deps(),
      releases: [risi_registry: [include_executables_for: [:unix]]]
    ]
  end

  def application do
    [
      mod: {RisiRegistry.Application, []},
      extra_applications: [:logger, :crypto, :public_key]
    ]
  end

  def cli, do: [preferred_envs: [precommit: :test]]

  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_), do: ["lib"]

  defp deps do
    [
      {:phoenix, "~> 1.8.15"},
      {:phoenix_ecto, "~> 4.5"},
      {:phoenix_html, "~> 4.1"},
      {:ecto_sql, "~> 3.13"},
      {:postgrex, ">= 0.0.0"},
      {:bandit, "~> 1.5"},
      {:jason, "~> 1.2"},
      {:jose, "~> 1.11"},
      {:req, "~> 0.5"}
    ]
  end

  # The registry role has no CREATEDB (the databases are created once by hand, see STATUS.md),
  # so the test alias only migrates.
  defp aliases do
    [
      setup: ["deps.get", "ecto.migrate", "run priv/repo/seeds.exs"],
      "ecto.reset": ["ecto.rollback --all", "ecto.migrate", "run priv/repo/seeds.exs"],
      test: ["ecto.migrate --quiet", "test"],
      precommit: ["format --check-formatted", "compile --warnings-as-errors", "test"]
    ]
  end
end
