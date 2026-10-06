defmodule RisiMe.Release do
  @moduledoc """
  Release tasks. A release has no Mix, so `mix ecto.migrate` and `mix risime.cql.migrate`
  are not available in prod. Run these instead (see docs/PROD.md):

      bin/risime eval "RisiMe.Release.migrate()"          # Ecto (Postgres) + CQL (Cassandra)
      bin/risime eval "RisiMe.Release.migrate_ecto()"
      bin/risime eval "RisiMe.Release.migrate_cql()"
      bin/risime eval "RisiMe.Release.rollback(RisiMe.Repo, 20261005000000)"

  `migrate_cql/1` applies `priv/cql/*.cql` exactly like `mix risime.cql.migrate`: it creates the
  keyspace if needed, applies each file once in name order and records it in the keyspace's
  `cql_migrations` table, so the two can be used interchangeably on the same keyspace.
  """
  require Logger

  @app :risime

  @doc """
  Runs all pending Ecto migrations, then all pending CQL migrations, then the idempotent
  friendship backfill (contract v1.6 §9.4).
  """
  def migrate do
    migrate_ecto()
    migrate_cql()
    migrate_friendships()
    :ok
  end

  @doc """
  Contract v1.6 §9.4: every pair of users who already have a conversation (a `message_index`
  row) becomes friends, so no existing chat breaks. Idempotent: pairs that are already friends,
  and pairs whose users no longer exist, are skipped. Returns `{:ok, friendships_created}`.

  Options: `:keyspace`, `:nodes` (default: the `:cassandra` config).
  """
  def migrate_friendships(opts \\ []) do
    load_app()
    {:ok, _} = Application.ensure_all_started(:xandra)
    config = Application.fetch_env!(@app, :cassandra)
    [node | _] = opts[:nodes] || config[:nodes]
    keyspace = opts[:keyspace] || config[:keyspace]

    [repo] = repos()

    # Only pairs whose users both still exist matter; at pilot size that set is tiny, while
    # message_index may hold millions of load-test rows.
    {:ok, existing, _} =
      Ecto.Migrator.with_repo(repo, fn _ ->
        import Ecto.Query
        MapSet.new(RisiMe.Repo.all(from u in RisiMe.Accounts.User, select: u.id))
      end)

    {:ok, conn} = Xandra.start_link(nodes: [node], keyspace: keyspace)

    pairs =
      try do
        RisiMe.Messaging.Store.Cassandra.conversation_pairs(conn, fn a, b ->
          a != b and MapSet.member?(existing, a) and MapSet.member?(existing, b)
        end)
      after
        GenServer.stop(conn)
      end

    {:ok, created, _} = Ecto.Migrator.with_repo(repo, fn _ -> backfill_friendships(pairs) end)
    Logger.info("friendships: #{created} created from #{MapSet.size(pairs)} conversation pair(s)")
    {:ok, created}
  end

  @doc false
  def backfill_friendships(pairs) do
    import Ecto.Query

    ids = pairs |> Enum.flat_map(fn {a, b} -> [a, b] end) |> Enum.uniq()

    existing =
      MapSet.new(
        RisiMe.Repo.all(from u in RisiMe.Accounts.User, where: u.id in ^ids, select: u.id)
      )

    before = RisiMe.Repo.aggregate("friendships", :count)

    for {a, b} <- pairs,
        a != b,
        MapSet.member?(existing, a) and MapSet.member?(existing, b),
        uniq: true do
      if a < b, do: {a, b}, else: {b, a}
    end
    |> Enum.each(fn {a, b} -> RisiMe.Social.make_friends!(a, b) end)

    RisiMe.Repo.aggregate("friendships", :count) - before
  end

  @doc "Runs all pending Ecto migrations for every repo."
  def migrate_ecto do
    load_app()

    for repo <- repos() do
      {:ok, _, _} = Ecto.Migrator.with_repo(repo, &Ecto.Migrator.run(&1, :up, all: true))
    end

    :ok
  end

  @doc "Rolls `repo` back to `version` (Ecto migrations only; CQL migrations are additive)."
  def rollback(repo, version) do
    load_app()
    {:ok, _, _} = Ecto.Migrator.with_repo(repo, &Ecto.Migrator.run(&1, :down, to: version))
    :ok
  end

  @doc """
  Applies pending `priv/cql/*.cql` files to the configured keyspace (or `opts[:keyspace]`).
  Returns `{:ok, applied_file_names}`.

  Options: `:keyspace`, `:nodes` (default: the `:cassandra` config).
  """
  def migrate_cql(opts \\ []) do
    load_app()
    {:ok, _} = Application.ensure_all_started(:xandra)

    config = Application.fetch_env!(@app, :cassandra)
    keyspace = opts[:keyspace] || config[:keyspace]
    [node | _] = opts[:nodes] || config[:nodes]

    unless is_binary(keyspace) and Regex.match?(~r/^[a-z][a-z0-9_]*$/, keyspace),
      do: raise(ArgumentError, "bad keyspace #{inspect(keyspace)}")

    {:ok, conn} = Xandra.start_link(nodes: [node])

    try do
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
      already = MapSet.new(page, & &1["name"])

      applied =
        for file <- cql_files(), name = Path.basename(file), name not in already do
          for statement <- statements(File.read!(file)), do: exec!(conn, statement)

          {:ok, _} =
            Xandra.execute(
              conn,
              "INSERT INTO cql_migrations (name, applied_at) VALUES (?, ?)",
              [{"text", name}, {"timestamp", DateTime.utc_now()}]
            )

          Logger.info("#{keyspace}: applied #{name}")
          name
        end

      Logger.info("#{keyspace}: CQL up to date")
      {:ok, applied}
    after
      GenServer.stop(conn)
    end
  end

  @doc false
  def cql_files,
    do: @app |> Application.app_dir("priv/cql/*.cql") |> Path.wildcard() |> Enum.sort()

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
      {:error, error} -> raise "CQL failed: #{Exception.message(error)}\n#{statement}"
    end
  end

  defp repos do
    Application.fetch_env!(@app, :ecto_repos)
  end

  defp load_app do
    # Ecto and Xandra may need SSL when the DB is remote; harmless otherwise.
    _ = Application.ensure_all_started(:ssl)
    :ok = Application.ensure_loaded(@app)
  end
end
