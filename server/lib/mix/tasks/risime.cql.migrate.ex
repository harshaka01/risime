defmodule Mix.Tasks.Risime.Cql.Migrate do
  @shortdoc "Applies priv/cql/*.cql to a Cassandra keyspace"
  @moduledoc """
  Creates the keyspace if needed and applies every `priv/cql/*.cql` file once, in name order.
  Applied files are tracked in the keyspace's `cql_migrations` table.

      mix risime.cql.migrate                        # keyspace from config (risime_dev in dev)
      mix risime.cql.migrate --keyspace risime_test
      mix risime.cql.migrate --quiet
  """
  use Mix.Task

  @requirements ["app.config"]

  @impl true
  def run(argv) do
    {opts, _} = OptionParser.parse!(argv, strict: [keyspace: :string, quiet: :boolean])
    config = Application.fetch_env!(:risime, :cassandra)
    keyspace = opts[:keyspace] || config[:keyspace]
    info = if opts[:quiet], do: fn _ -> :ok end, else: &Mix.shell().info(&1)

    unless Regex.match?(~r/^[a-z][a-z0-9_]*$/, keyspace),
      do: Mix.raise("bad keyspace #{keyspace}")

    {:ok, _} = Application.ensure_all_started(:xandra)
    [node | _] = config[:nodes]
    {:ok, conn} = Xandra.start_link(nodes: [node])

    exec!(conn, """
    CREATE KEYSPACE IF NOT EXISTS #{keyspace}
      WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
    """)

    exec!(conn, "USE #{keyspace}")

    exec!(
      conn,
      "CREATE TABLE IF NOT EXISTS cql_migrations (name text PRIMARY KEY, applied_at timestamp)"
    )

    %Xandra.Page{} = page = exec!(conn, "SELECT name FROM cql_migrations")
    applied = MapSet.new(page, & &1["name"])

    files = Path.wildcard(Application.app_dir(:risime, "priv/cql/*.cql")) |> Enum.sort()

    for file <- files, name = Path.basename(file), name not in applied do
      for statement <- statements(File.read!(file)), do: exec!(conn, statement)

      {:ok, _} =
        Xandra.execute(
          conn,
          "INSERT INTO cql_migrations (name, applied_at) VALUES (?, ?)",
          [{"text", name}, {"timestamp", DateTime.utc_now()}]
        )

      info.("#{keyspace}: applied #{name}")
    end

    info.("#{keyspace}: up to date")
    GenServer.stop(conn)
  end

  @doc false
  def statements(cql) do
    cql
    |> String.split("\n")
    |> Enum.reject(&String.starts_with?(String.trim_leading(&1), "--"))
    |> Enum.join("\n")
    |> String.split(";")
    |> Enum.map(&String.trim/1)
    |> Enum.reject(&(&1 == ""))
  end

  defp exec!(conn, statement) do
    case Xandra.execute(conn, statement, [], timeout: 30_000) do
      {:ok, result} -> result
      {:error, error} -> Mix.raise("CQL failed: #{Exception.message(error)}\n#{statement}")
    end
  end
end
