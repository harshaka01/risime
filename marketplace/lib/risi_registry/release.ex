defmodule RisiRegistry.Release do
  @moduledoc "Release tasks: `bin/risi_registry eval \"RisiRegistry.Release.setup()\"`."
  @app :risi_registry

  def migrate do
    Application.load(@app)

    for repo <- Application.fetch_env!(@app, :ecto_repos) do
      {:ok, _, _} = Ecto.Migrator.with_repo(repo, &Ecto.Migrator.run(&1, :up, all: true))
    end
  end

  def setup do
    migrate()

    {:ok, _, _} =
      Ecto.Migrator.with_repo(RisiRegistry.Repo, fn _repo -> RisiRegistry.Seeds.run() end)
  end
end
