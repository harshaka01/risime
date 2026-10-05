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
  def put_message(m) do
    run!(
      "INSERT INTO message_index (message_id, sender_id, recipient_id, client_msg_id, conversation_id, status) " <>
        "VALUES (?, ?, ?, ?, ?, ?)",
      [m.message_id, m.sender_id, m.recipient_id, m.client_msg_id, m.conversation_id, m.status]
    )

    :ok
  end

  @impl true
  def get_message(message_id) do
    page =
      run!(
        "SELECT message_id, sender_id, recipient_id, client_msg_id, conversation_id, status " <>
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
           status: row["status"]
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
    for table <- ~w(inbox_events sent_dedupe message_index) do
      {:ok, _} = Xandra.Cluster.execute(@cluster, "TRUNCATE #{table}", [], timeout: 60_000)
    end

    :ok
  end

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
