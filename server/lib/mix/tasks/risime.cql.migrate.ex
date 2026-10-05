defmodule Mix.Tasks.Risime.Cql.Migrate do
  @shortdoc "Applies priv/cql/*.cql to a Cassandra keyspace"
  @moduledoc """
  Creates the keyspace if needed and applies every `priv/cql/*.cql` file once, in name order.
  Applied files are tracked in the keyspace's `cql_migrations` table. This is a thin wrapper around
  `RisiMe.Release.migrate_cql/1`, which a release runs without Mix (docs/PROD.md).

      mix risime.cql.migrate                        # keyspace from config (risime_dev in dev)
      mix risime.cql.migrate --keyspace risime_test
      mix risime.cql.migrate --quiet
  """
  use Mix.Task

  require Logger

  @requirements ["app.config"]

  @impl true
  def run(argv) do
    {opts, _} = OptionParser.parse!(argv, strict: [keyspace: :string, quiet: :boolean])

    level = if opts[:quiet], do: :warning, else: :info
    Logger.put_module_level(RisiMe.Release, level)

    try do
      {:ok, _applied} = RisiMe.Release.migrate_cql(Keyword.take(opts, [:keyspace]))
    rescue
      e in [ArgumentError, RuntimeError] -> Mix.raise(Exception.message(e))
    after
      Logger.delete_module_level(RisiMe.Release)
    end
  end
end
