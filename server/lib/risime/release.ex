defmodule RisiMe.Release do
  @moduledoc """
  Release tasks. A release has no Mix, so `mix ecto.migrate` and `mix risime.cql.migrate`
  are not available in prod. Run these instead (see docs/PROD.md):

      bin/risime eval "RisiMe.Release.migrate()"          # Ecto (Postgres) + CQL (Cassandra)
      bin/risime eval "RisiMe.Release.migrate_ecto()"
      bin/risime eval "RisiMe.Release.migrate_cql()"
      bin/risime eval "RisiMe.Release.rollback(RisiMe.Repo, 20261005000000)"
      bin/risime eval "RisiMe.Release.backfill_sender_copies()"   # v1.10, dry run by default
      bin/risime eval "RisiMe.Release.name_pending_device_ops()"  # v1.14, dry run by default
      bin/risime eval "RisiMe.Release.dm_device_ops()"            # v1.16, dry run by default
      bin/risime eval "RisiMe.Release.stale_device_ops()"         # v1.21, dry run by default
      bin/risime eval "RisiMe.Release.risi_preflight()"           # read-only Risi key/NIF check
      bin/risime eval "RisiMe.Release.risi_offers_backfill()"     # item 8, dry run by default

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
    seal_risi_learning_log()
    migrate_friendships()
    seed_risi()
    risi_offers_backfill(dry_run: false)
    :ok
  end

  @doc """
  Item 8 (2026-10-09), one-shot and idempotent: "Add to calendar?" (and "Remind me?") cards for
  the existing live items with a concrete future time (`RisiMe.Agent.Offers.backfill/1`; an item
  already offered to a user is skipped). **Dry run by default** (counts only). Posting needs the
  running node (Risi's MLS sender), so the real run from `eval` queues one `offers_backfill`
  job (unique: queued once while one exists) that the running server executes (its log shows
  the counts); inside the node it runs at once. `migrate/0` queues it on every deploy (a no-op
  once everything was offered). Prints counts only.

      bin/risime eval "RisiMe.Release.risi_offers_backfill()"                # dry run
      bin/risime eval "RisiMe.Release.risi_offers_backfill(dry_run: false)"  # offers
  """
  def risi_offers_backfill(opts \\ []) do
    dry_run = Keyword.get(opts, :dry_run, true)

    job = fn ->
      RisiMe.Workers.Risi.new(%{"kind" => "offers_backfill"},
        queue: :risi_timers,
        # 5 min: from `migrate/0` the previous release may still be running (it would take
        # the job and drop it, not knowing the kind); the new one runs it.
        schedule_in: 300,
        unique: [period: :infinity, keys: [:kind], states: [:available, :scheduled, :retryable]]
      )
    end

    result =
      cond do
        dry_run and Process.whereis(RisiMe.Repo) != nil ->
          RisiMe.Agent.Offers.backfill(dry_run: true)

        dry_run ->
          with_repo(fn -> RisiMe.Agent.Offers.backfill(dry_run: true) end)

        Process.whereis(RisiMe.Repo) != nil and RisiMe.Agent.Out.ready?() ->
          RisiMe.Agent.Offers.backfill()

        Process.whereis(RisiMe.Repo) != nil ->
          Oban.insert(job.())
          :queued_for_the_running_server

        true ->
          with_repo(fn ->
            # Oban isn't running under `eval`: the job row is written directly.
            RisiMe.Repo.insert!(job.())
            :queued_for_the_running_server
          end)
      end

    line = "risi offers backfill (dry_run=#{dry_run}): #{inspect(result)}"
    Logger.info(line)
    IO.puts(line)
    {:ok, result}
  end

  @doc """
  Privacy fix 2026-10-09 (`priv/cql/009_risi_sealed_text.cql`): seals the learning-log outputs
  and feedback reasons still held in plaintext with `RISI_DATA_KEY`, keeping each value's
  remaining TTL (`RisiMe.Agent.LearningLog.Cassandra.seal_existing/2`). Idempotent. Without the
  key it changes nothing and says so (Risi doesn't run without it either; the rows expire within
  90 days). Prints counts only. Options: `:keyspace`, `:nodes`.

      bin/risime eval "RisiMe.Release.seal_risi_learning_log()"
  """
  def seal_risi_learning_log(opts \\ []) do
    load_app()
    {:ok, _} = Application.ensure_all_started(:xandra)
    config = Application.fetch_env!(@app, :cassandra)
    [node | _] = opts[:nodes] || config[:nodes]
    keyspace = opts[:keyspace] || config[:keyspace]
    {:ok, conn} = Xandra.start_link(nodes: [node], keyspace: keyspace)

    result =
      try do
        RisiMe.Agent.LearningLog.Cassandra.seal_existing(&Xandra.execute(conn, &1, &2, &3))
      after
        GenServer.stop(conn)
      end

    line = "risi learning log sealed (#{keyspace}): #{inspect(result)}"
    Logger.info(line)
    IO.puts(line)
    result
  end

  @doc "v1.24 §24.11: seeds the Risi agent user and device while `RISI` is on (idempotent)."
  def seed_risi do
    load_app()

    for repo <- repos() do
      {:ok, _, _} = Ecto.Migrator.with_repo(repo, fn _ -> RisiMe.Risi.seed() end)
    end

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

  @doc """
  Contract v1.10 §13.5, one-off after the v1.10 deploy: copies each plaintext DM still inside
  the TTL into its sender's inbox (same `event_id` and payload, the source's remaining TTL and
  write time), only when both the sender and the recipient still exist in `users`. Idempotent;
  logs and prints counts only. Returns `{:ok, counts}`.

      bin/risime eval "RisiMe.Release.backfill_sender_copies()"                # dry run
      bin/risime eval "RisiMe.Release.backfill_sender_copies(dry_run: false)"  # writes

  Options: `:dry_run` (default **true**), `:keyspace`, `:nodes` (default: the `:cassandra`
  config), `:page_size`.
  """
  def backfill_sender_copies(opts \\ []) do
    load_app()
    {:ok, _} = Application.ensure_all_started(:xandra)
    config = Application.fetch_env!(@app, :cassandra)
    [node | _] = opts[:nodes] || config[:nodes]
    keyspace = opts[:keyspace] || config[:keyspace]
    dry_run = Keyword.get(opts, :dry_run, true)
    [repo] = repos()

    {:ok, existing, _} =
      Ecto.Migrator.with_repo(repo, fn _ ->
        import Ecto.Query
        MapSet.new(RisiMe.Repo.all(from u in RisiMe.Accounts.User, select: u.id))
      end)

    {:ok, conn} = Xandra.start_link(nodes: [node], keyspace: keyspace)

    {:ok, counts} =
      try do
        RisiMe.Messaging.Store.impl().backfill_sender_copies(
          fn from, to -> MapSet.member?(existing, from) and MapSet.member?(existing, to) end,
          conn: conn,
          dry_run: dry_run,
          page_size: opts[:page_size] || 5000
        )
      after
        GenServer.stop(conn)
      end

    line = "sender copies (#{keyspace}): #{inspect(counts)}"
    Logger.info(line)
    IO.puts(line)
    {:ok, counts}
  end

  @doc """
  Contract v1.14 §12.11, one-off pilot recovery: names a committer (§12.4a) for every pending
  `devices` op that waits with none. **Dry run by default** (reads only, changes nothing).
  Idempotent; logs and prints counts only (no ids, no phone numbers). Returns `{:ok, counts}`.

      bin/risime eval "RisiMe.Release.name_pending_device_ops()"                # dry run
      bin/risime eval "RisiMe.Release.name_pending_device_ops(dry_run: false)"  # names

  Naming needs the running node (presence, PubSub, push), and the release has no distribution
  (no `rpc`): from `eval`, the real run queues a one-off `GroupTimer` `name_pending` job that the
  running server executes (its log shows the counts); inside the node it runs at once.
  """
  def name_pending_device_ops(opts \\ []) do
    dry_run = Keyword.get(opts, :dry_run, true)

    result =
      cond do
        Process.whereis(RisiMe.Repo) != nil ->
          RisiMe.Groups.Ops.name_waiting(dry_run: dry_run)

        dry_run ->
          with_repo(fn -> RisiMe.Groups.Ops.name_waiting(dry_run: true) end)

        true ->
          with_repo(fn ->
            RisiMe.Repo.insert!(RisiMe.Workers.GroupTimer.new(%{"kind" => "name_pending"}))
            :queued_for_the_running_server
          end)
      end

    line = "pending device ops (dry_run=#{dry_run}): #{inspect(result)}"
    Logger.info(line)
    IO.puts(line)
    {:ok, result}
  end

  @doc """
  v1.16 one-off pilot recovery (proposal 2026-10-07-dm-device-readd §7): creates the DM `devices`
  ops for every e2ee DM whose participants' receiving MLS devices aren't in the group, and names
  their committers. **Dry run by default** (reads only, changes nothing). Idempotent; logs and
  prints counts only (no ids, no phone numbers): `dms`, `affected_dms`, `missing_devices`,
  `superseded_leaves`, `with_candidates`, `without_candidates` (those heal by the client's reset),
  and in a real run `ops`, `named`. Returns `{:ok, counts}`.

      bin/risime eval "RisiMe.Release.dm_device_ops()"                # dry run
      bin/risime eval "RisiMe.Release.dm_device_ops(dry_run: false)"  # creates + names

  Like `name_pending_device_ops/1`, the real run from `eval` queues a one-off `GroupTimer`
  `dm_ops` job that the running server executes (naming needs presence, PubSub and push).
  """
  def dm_device_ops(opts \\ []) do
    dry_run = Keyword.get(opts, :dry_run, true)

    result =
      cond do
        Process.whereis(RisiMe.Repo) != nil ->
          RisiMe.MLS.DmOps.recover(dry_run: dry_run)

        dry_run ->
          with_repo(fn -> RisiMe.MLS.DmOps.recover(dry_run: true) end)

        true ->
          with_repo(fn ->
            RisiMe.Repo.insert!(RisiMe.Workers.GroupTimer.new(%{"kind" => "dm_ops"}))
            :queued_for_the_running_server
          end)
      end

    line = "dm device ops (dry_run=#{dry_run}): #{inspect(result)}"
    Logger.info(line)
    IO.puts(line)
    {:ok, result}
  end

  @doc """
  v1.21 §12.12.7 one-off pilot recovery: for every pending `devices` op (groups and DMs) applies
  the §12.12.5 pruning, clears all strikes, creates the §12.12.6 cleanup ops, and in a real run
  names committers. **Dry run by default** (everything is rolled back; nothing changes).
  Idempotent. Logs and prints counts only: `ops`, `pruned_devices`, `done_ops`, `cleanup_ops`,
  `stale_leaves`, `named`, `waiting`, `exhausted`. Returns `{:ok, counts}`.

      bin/risime eval "RisiMe.Release.stale_device_ops()"                # dry run
      bin/risime eval "RisiMe.Release.stale_device_ops(dry_run: false)"  # prunes, creates, names

  Like `dm_device_ops/1`, the real run from `eval` queues a one-off `GroupTimer`
  `stale_device_ops` job that the running server executes (naming needs presence and push).
  """
  def stale_device_ops(opts \\ []) do
    dry_run = Keyword.get(opts, :dry_run, true)

    result =
      cond do
        Process.whereis(RisiMe.Repo) != nil ->
          RisiMe.Workers.StaleLeaves.recover(dry_run: dry_run)

        dry_run ->
          with_repo(fn -> RisiMe.Workers.StaleLeaves.recover(dry_run: true) end)

        true ->
          with_repo(fn ->
            RisiMe.Repo.insert!(RisiMe.Workers.GroupTimer.new(%{"kind" => "stale_device_ops"}))
            :queued_for_the_running_server
          end)
      end

    line = "stale device ops (dry_run=#{dry_run}): #{inspect(result)}"
    Logger.info(line)
    IO.puts(line)
    {:ok, result}
  end

  @doc """
  P0 2026-10-08: the **read-only** Risi preflight, run before `RISI=on` or after changing a Risi
  key:

      bin/risime eval "RisiMe.Release.risi_preflight()"

  Checks `RISI_MLS_KEK`/`RISI_DATA_KEY` (present, base64 of 32 bytes), that the MLS NIF loads,
  the key check of the sealed store in `risi_mls_kv` against `RISI_MLS_KEK`, and that the open of
  Risi's rows would succeed (in memory: nothing is written, seeded or registered). A
  `RISI_DATA_KEY` that differs from the buffer's key is a note, not a failure (those rows are
  dropped). Prints `RISI PREFLIGHT OK` or `RISI PREFLIGHT FAIL <reason>` and halts with status 1
  on failure (`halt: false` returns `{:error, reason}` instead). Never prints key material.
  """
  def risi_preflight(opts \\ []) do
    result =
      try do
        if Process.whereis(RisiMe.Repo) != nil,
          do: with_attestation(&RisiMe.Agent.preflight/0),
          else: with_repo(fn -> with_attestation(&RisiMe.Agent.preflight/0) end)
      rescue
        e -> {:error, RisiMe.Agent.exception_reason(e)}
      catch
        :exit, _ -> {:error, :db_error}
      end

    case result do
      {:ok, notes} ->
        for n <- notes, do: IO.puts("  " <> n)
        IO.puts("RISI PREFLIGHT OK")
        :ok

      {:error, reason} ->
        IO.puts("RISI PREFLIGHT FAIL " <> RisiMe.Agent.describe(reason))
        if Keyword.get(opts, :halt, true), do: System.halt(1)
        {:error, reason}
    end
  end

  # The trust anchors come from RisiMe.MLS.Attestation (not running under `eval`).
  defp with_attestation(fun) do
    case Process.whereis(RisiMe.MLS.Attestation) do
      nil ->
        {:ok, pid} = GenServer.start(RisiMe.MLS.Attestation, :ok, name: RisiMe.MLS.Attestation)

        try do
          fun.()
        after
          GenServer.stop(pid)
        end

      _ ->
        fun.()
    end
  end

  defp with_repo(fun) do
    load_app()
    [repo] = repos()
    {:ok, result, _} = Ecto.Migrator.with_repo(repo, fn _ -> fun.() end)
    result
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
