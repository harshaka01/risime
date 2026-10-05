defmodule RisiMe.ReleaseTest do
  use ExUnit.Case, async: false

  alias RisiMe.Release

  # A throwaway keyspace, so the shared risime_test keyspace is never touched.
  @keyspace "risime_release_test"

  setup do
    on_exit(fn -> drop_keyspace() end)
    drop_keyspace()
    :ok
  end

  test "migrate_cql/1 creates the keyspace, applies every priv/cql file once, and is idempotent" do
    files = Enum.map(Release.cql_files(), &Path.basename/1)
    assert files != []

    assert {:ok, ^files} = Release.migrate_cql(keyspace: @keyspace)
    assert {:ok, []} = Release.migrate_cql(keyspace: @keyspace)

    recorded =
      with_conn(fn conn ->
        {:ok, page} = Xandra.execute(conn, "SELECT name FROM #{@keyspace}.cql_migrations")
        page |> Enum.map(& &1["name"]) |> Enum.sort()
      end)

    assert recorded == files
  end

  test "migrate_cql/1 rejects unsafe keyspace names" do
    assert_raise ArgumentError, fn -> Release.migrate_cql(keyspace: "x; DROP KEYSPACE y") end
  end

  test "statements/1 drops comment lines and splits on semicolons" do
    cql = """
    -- a comment; with a semicolon
    CREATE TABLE a (id int PRIMARY KEY);

      -- indented comment
    CREATE TABLE b (id int PRIMARY KEY);
    """

    assert Release.statements(cql) == [
             "CREATE TABLE a (id int PRIMARY KEY)",
             "CREATE TABLE b (id int PRIMARY KEY)"
           ]
  end

  defp drop_keyspace do
    with_conn(fn conn ->
      {:ok, _} = Xandra.execute(conn, "DROP KEYSPACE IF EXISTS #{@keyspace}", [], timeout: 60_000)
    end)
  end

  defp with_conn(fun) do
    [node | _] = Application.fetch_env!(:risime, :cassandra)[:nodes]
    {:ok, conn} = Xandra.start_link(nodes: [node])

    try do
      fun.(conn)
    after
      GenServer.stop(conn)
    end
  end
end
