defmodule RisiMe.Messaging.Store.Cassandra do
  @moduledoc """
  `RisiMe.Messaging.Store` on Cassandra, through a supervised `Xandra.Cluster` pool.
  Tables live in `priv/cql/`; each query below names the table built for it.
  """
  @behaviour RisiMe.Messaging.Store

  @cluster __MODULE__.Cluster

  def child_spec(_opts) do
    config = Application.fetch_env!(:risime, :cassandra)

    opts =
      [
        name: @cluster,
        nodes: config[:nodes],
        keyspace: config[:keyspace],
        pool_size: config[:pool_size] || 4
      ] ++ Keyword.take(config, [:sync_connect])

    %{id: __MODULE__, start: {Xandra.Cluster, :start_link, [opts]}, type: :supervisor}
  end

  @impl true
  def get_sent(sender_id, client_msg_id) do
    page =
      run!(
        "SELECT message_id, conversation_id, server_ts FROM sent_dedupe " <>
          "WHERE sender_id = ? AND client_msg_id = ?",
        [sender_id, client_msg_id]
      )

    case Enum.at(page, 0) do
      nil -> :not_found
      row -> {:ok, to_sent(row)}
    end
  end

  @impl true
  def claim_send(sender_id, client_msg_id, sent) do
    page =
      run!(
        "INSERT INTO sent_dedupe (sender_id, client_msg_id, message_id, conversation_id, server_ts) " <>
          "VALUES (?, ?, ?, ?, ?) IF NOT EXISTS",
        [sender_id, client_msg_id, sent.message_id, sent.conversation_id, sent.server_ts]
      )

    case Enum.at(page, 0) do
      %{"[applied]" => true} ->
        :ok

      %{"[applied]" => false} = row ->
        {:exists, to_sent(row)}
    end
  end

  defp to_sent(row) do
    %{
      message_id: row["message_id"],
      conversation_id: row["conversation_id"],
      server_ts: row["server_ts"]
    }
  end

  @impl true
  def put_message(%{recipient_id: nil, recipients: recipients} = m) when is_list(recipients) do
    # v1.9 group message: no recipient_id cell, the recipients set instead.
    run!(
      "INSERT INTO message_index (message_id, sender_id, client_msg_id, conversation_id, status, recipients) " <>
        "VALUES (?, ?, ?, ?, ?, ?)",
      [
        m.message_id,
        m.sender_id,
        m.client_msg_id,
        m.conversation_id,
        m.status,
        MapSet.new(recipients)
      ]
    )

    :ok
  end

  def put_message(m) do
    # `kind` is written only for non-message rows (no null cell, so no tombstone).
    case m[:kind] do
      nil ->
        run!(
          "INSERT INTO message_index (message_id, sender_id, recipient_id, client_msg_id, conversation_id, status) " <>
            "VALUES (?, ?, ?, ?, ?, ?)",
          [
            m.message_id,
            m.sender_id,
            m.recipient_id,
            m.client_msg_id,
            m.conversation_id,
            m.status
          ]
        )

      kind ->
        run!(
          "INSERT INTO message_index (message_id, sender_id, recipient_id, client_msg_id, conversation_id, status, kind) " <>
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
          [
            m.message_id,
            m.sender_id,
            m.recipient_id,
            m.client_msg_id,
            m.conversation_id,
            m.status,
            kind
          ]
        )
    end

    :ok
  end

  @impl true
  def get_message(message_id) do
    page =
      run!(
        "SELECT message_id, sender_id, recipient_id, client_msg_id, conversation_id, status, kind, recipients " <>
          "FROM message_index WHERE message_id = ?",
        [message_id]
      )

    case Enum.at(page, 0) do
      nil ->
        :not_found

      row ->
        {:ok,
         %{
           message_id: row["message_id"],
           sender_id: row["sender_id"],
           recipient_id: row["recipient_id"],
           client_msg_id: row["client_msg_id"],
           conversation_id: row["conversation_id"],
           status: row["status"],
           kind: row["kind"],
           recipients: row["recipients"] && Enum.to_list(row["recipients"])
         }}
    end
  end

  @impl true
  def compare_and_set_status(message_id, expected, new) do
    page =
      run!(
        "UPDATE message_index SET status = ? WHERE message_id = ? IF status = ?",
        [new, message_id, expected]
      )

    case Enum.at(page, 0) do
      %{"[applied]" => true} -> :ok
      %{"[applied]" => false} = row -> {:conflict, row["status"]}
    end
  end

  @impl true
  def put_group_receipt(message_id, user_id, delivered_at, read_at) do
    # Only the given columns are written (no null cells).
    {sets, values} =
      [{"delivered_at", delivered_at}, {"read_at", read_at}]
      |> Enum.reject(fn {_, v} -> v == nil end)
      |> Enum.unzip()

    if sets != [] do
      run!(
        "UPDATE group_receipts SET " <>
          Enum.map_join(sets, ", ", &(&1 <> " = ?")) <> " WHERE message_id = ? AND user_id = ?",
        values ++ [message_id, user_id]
      )
    end

    :ok
  end

  @impl true
  def list_group_receipts(message_id) do
    "SELECT user_id, delivered_at, read_at FROM group_receipts WHERE message_id = ?"
    |> run!([message_id])
    |> Enum.map(
      &%{user_id: &1["user_id"], delivered_at: &1["delivered_at"], read_at: &1["read_at"]}
    )
  end

  @impl true
  def append_event(user_id, %{event_id: event_id, kind: kind, data: data}) do
    run!(
      "INSERT INTO inbox_events (user_id, event_id, kind, payload) VALUES (?, ?, ?, ?)",
      [user_id, event_id, kind, Jason.encode!(data)]
    )

    :ok
  end

  @impl true
  def list_events(user_id, nil, limit) do
    "SELECT event_id, kind, payload FROM inbox_events WHERE user_id = ? LIMIT ?"
    |> run!([user_id, limit])
    |> Enum.map(&to_event/1)
  end

  def list_events(user_id, since, limit) do
    "SELECT event_id, kind, payload FROM inbox_events WHERE user_id = ? AND event_id > ? LIMIT ?"
    |> run!([user_id, since, limit])
    |> Enum.map(&to_event/1)
  end

  @doc "Test helper: empties the message tables of the configured keyspace."
  def truncate! do
    for table <- ~w(inbox_events sent_dedupe message_index group_receipts) do
      {:ok, _} = Xandra.Cluster.execute(@cluster, "TRUNCATE #{table}", [], timeout: 60_000)
    end

    :ok
  end

  @doc """
  Every distinct `{sender_id, recipient_id}` pair in `message_index` for which `keep?` is true
  (filtered while streaming, so memory stays small), over a given connection
  (used by `RisiMe.Release.migrate_friendships/0`, which runs without the app). A full,
  paged scan of the table, so no `ALLOW FILTERING`.
  """
  def conversation_pairs(conn, keep? \\ fn _, _ -> true end, page_size \\ 1000) do
    conn
    |> Xandra.stream_pages!("SELECT sender_id, recipient_id FROM message_index", [],
      page_size: page_size
    )
    |> Stream.flat_map(& &1)
    |> Stream.map(&{&1["sender_id"], &1["recipient_id"]})
    |> Stream.filter(fn {a, b} -> keep?.(a, b) end)
    |> Enum.into(MapSet.new())
  end

  @min_backfill_ttl 60

  @doc """
  `c:RisiMe.Messaging.Store.backfill_sender_copies/2` over the Xandra connection `opts[:conn]`
  (the release task runs without the app). A paged full scan of `inbox_events` (no
  `ALLOW FILTERING`); each copy is one primary-key read and, unless it exists or `dry_run`,
  one `INSERT … USING TTL ? AND TIMESTAMP ?`. Options: `:conn` (required), `:dry_run`
  (default true), `:page_size` (default 5000).
  """
  @impl true
  def backfill_sender_copies(keep?, opts) do
    conn = Keyword.fetch!(opts, :conn)
    dry_run = Keyword.get(opts, :dry_run, true)

    {:ok, exists} =
      Xandra.prepare(conn, "SELECT event_id FROM inbox_events WHERE user_id = ? AND event_id = ?")

    {:ok, insert} =
      Xandra.prepare(
        conn,
        "INSERT INTO inbox_events (user_id, event_id, kind, payload) VALUES (?, ?, ?, ?) " <>
          "USING TTL ? AND TIMESTAMP ?"
      )

    zero = %{
      scanned: 0,
      candidates: 0,
      skipped_missing_user: 0,
      skipped_ttl: 0,
      existing: 0,
      copied: 0,
      dry_run: dry_run
    }

    counts =
      conn
      |> Xandra.stream_pages!(
        "SELECT user_id, event_id, kind, payload, TTL(payload) AS ttl, " <>
          "WRITETIME(payload) AS wt FROM inbox_events",
        [],
        page_size: Keyword.get(opts, :page_size, 5000),
        timeout: 60_000
      )
      |> Stream.flat_map(& &1)
      |> Enum.reduce(zero, fn row, acc ->
        acc = %{acc | scanned: acc.scanned + 1}

        case copy_source(row) do
          nil ->
            acc

          sender ->
            acc = %{acc | candidates: acc.candidates + 1}

            cond do
              not keep?.(sender, row["user_id"]) ->
                %{acc | skipped_missing_user: acc.skipped_missing_user + 1}

              not (is_integer(row["ttl"]) and row["ttl"] >= @min_backfill_ttl) ->
                %{acc | skipped_ttl: acc.skipped_ttl + 1}

              Enum.any?(Xandra.execute!(conn, exists, [sender, row["event_id"]])) ->
                %{acc | existing: acc.existing + 1}

              dry_run ->
                %{acc | copied: acc.copied + 1}

              true ->
                Xandra.execute!(conn, insert, [
                  sender,
                  row["event_id"],
                  row["kind"],
                  row["payload"],
                  row["ttl"],
                  row["wt"]
                ])

                %{acc | copied: acc.copied + 1}
            end
        end
      end)

    {:ok, counts}
  end

  # The sender of a plaintext DM `message` event in its recipient's partition, else nil. Only
  # `message` payloads are decoded.
  defp copy_source(%{"kind" => "message", "user_id" => owner, "payload" => payload}) do
    case Jason.decode(payload) do
      {:ok, %{"body" => body, "from" => from, "to" => ^owner, "conversation_id" => "dm:" <> _}}
      when is_binary(body) and is_binary(from) and from != owner ->
        from

      _ ->
        nil
    end
  end

  defp copy_source(_row), do: nil

  @impl true
  def health do
    case Xandra.Cluster.execute(@cluster, "SELECT release_version FROM system.local", [],
           timeout: 2_000
         ) do
      {:ok, _} -> :ok
      {:error, error} -> {:error, error}
    end
  catch
    :exit, reason -> {:error, reason}
  end

  defp to_event(row) do
    %{event_id: row["event_id"], kind: row["kind"], data: Jason.decode!(row["payload"])}
  end

  # Backoff (ms) when every pooled connection is at its in-flight limit. Xandra fails such a
  # request at once instead of queueing it, so a burst would otherwise crash the caller's
  # channel (docs/status/loadtest.md).
  @overload_backoff [2, 10, 25, 50, 100]

  defp run!(statement, params, backoff \\ @overload_backoff) do
    # One pool checkout per query: prepare (served from the connection's prepared cache after the
    # first time) and execute on the same connection. Separate Cluster.prepare/execute calls
    # meant two trips through the cluster process, the hottest serial process under load.
    Xandra.Cluster.run(@cluster, fn conn ->
      with {:ok, prepared} <- Xandra.prepare(conn, statement) do
        Xandra.execute(conn, prepared, params)
      end
    end)
    |> case do
      {:ok, result} ->
        result

      {:error, %Xandra.ConnectionError{reason: :too_many_concurrent_requests}}
      when backoff != [] ->
        [ms | rest] = backoff
        Process.sleep(ms + :rand.uniform(ms))
        run!(statement, params, rest)

      {:error, error} ->
        raise error
    end
  end
end
