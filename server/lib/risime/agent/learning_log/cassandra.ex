defmodule RisiMe.Agent.LearningLog.Cassandra do
  @moduledoc """
  `RisiMe.Agent.LearningLog` on Cassandra (`priv/cql/007_risi_learning_log.cql`, queries
  Q15–Q20), through the application's `Xandra.Cluster` pool. Not message storage: messages stay
  behind `RisiMe.Messaging.Store`.

  **Sealed at rest** (`priv/cql/009_risi_sealed_text.cql`): a call's `output` (the model's
  validated JSON: summaries, answers, commitment texts) is written only as `output_sealed`
  (AES-256-GCM under `RISI_DATA_KEY`, AAD `<table>:<call_id>:output`, `RisiMe.Agent.Seal`), and
  a feedback `reason` only as `reason_sealed` (AAD `risi_llm_feedback:<call_id>:<user_id>:reason`).
  The plaintext `output` and `reason` columns stay NULL. Without the key the entry is written
  without its output (and a rating without its reason); a read that can't open a value returns
  `output: nil` / `reason: nil` with `output_unavailable: true` / `reason_unavailable: true`.
  `seal_existing/2` seals rows written before that, keeping each value's remaining TTL.
  """
  @behaviour RisiMe.Agent.LearningLog

  require Logger

  alias RisiMe.Agent.{LearningLog, Seal}

  @cluster RisiMe.Messaging.Store.Cassandra.Cluster

  @cols ~w(conversation_id task model_alias model provider fallback latency_ms prompt_tokens
           completion_tokens cost confidence status source_message_ids prompt_sha256)a

  @types %{
    conversation_id: "text",
    chat_id: "text",
    task: "text",
    model_alias: "text",
    model: "text",
    provider: "text",
    fallback: "text",
    latency_ms: "int",
    prompt_tokens: "int",
    completion_tokens: "int",
    cost: "double",
    confidence: "double",
    status: "text",
    source_message_ids: "list<timeuuid>",
    prompt_sha256: "text"
  }

  @by_day "risi_llm_calls_by_day"
  @by_chat "risi_llm_calls_by_chat"
  @feedback "risi_llm_feedback"

  # Q15 + Q16.
  @impl true
  def record(%{call_id: id, chat_id: chat} = e) do
    e =
      Map.update(
        e,
        :source_message_ids,
        [],
        &Enum.filter(List.wrap(&1), fn x -> timeuuid?(x) end)
      )

    cols = Enum.filter(@cols, &(Map.get(e, &1) != nil))
    vals = for c <- cols, do: {@types[c], cast(c, Map.get(e, c))}

    insert!(
      @by_day,
      [
        {"day", "date", LearningLog.day(id)},
        {"bucket", "int", LearningLog.bucket(id)},
        {"call_id", "timeuuid", id},
        {"chat_id", "text", chat}
      ],
      cols,
      vals,
      sealed_output(@by_day, id, e[:output])
    )

    insert!(
      @by_chat,
      [
        {"chat_id", "text", chat},
        {"month", "text", LearningLog.month(id)},
        {"call_id", "timeuuid", id}
      ],
      cols,
      vals,
      sealed_output(@by_chat, id, e[:output])
    )

    :ok
  end

  defp insert!(table, keys, cols, vals, sealed) do
    extra = if sealed, do: [{"output_sealed", "blob", sealed}], else: []
    names = Enum.map(keys ++ extra, &elem(&1, 0)) ++ Enum.map(cols, &Atom.to_string/1)
    params = Enum.map(keys ++ extra, fn {_, t, v} -> {t, v} end) ++ vals

    run!(
      "INSERT INTO #{table} (#{Enum.join(names, ", ")}) VALUES (?#{String.duplicate(", ?", length(names) - 1)})",
      params
    )
  end

  # The output, sealed for one table's row; nil without an output or without the key (the entry
  # is then kept without it: never plaintext).
  defp sealed_output(_table, _id, nil), do: nil

  defp sealed_output(table, id, output) do
    output = if is_binary(output), do: output, else: Jason.encode!(output)

    case Seal.seal_text(table, id, "output", output) do
      {:ok, sealed} ->
        sealed

      :error ->
        Logger.warning("Risi learning log output not kept: missing_key RISI_DATA_KEY")
        nil
    end
  end

  # Q17 (one call).
  @impl true
  def get(call_ref) do
    if timeuuid?(call_ref) do
      page =
        run!(
          "SELECT * FROM risi_llm_calls_by_day WHERE day = ? AND bucket = ? AND call_id = ?",
          [
            {"date", LearningLog.day(call_ref)},
            {"int", LearningLog.bucket(call_ref)},
            {"timeuuid", call_ref}
          ]
        )

      case Enum.at(page, 0) do
        nil -> :not_found
        row -> {:ok, to_entry(@by_day, row)}
      end
    else
      :not_found
    end
  end

  # Q18.
  @impl true
  def list_by_chat(chat_id, month, limit) do
    run!(
      "SELECT * FROM risi_llm_calls_by_chat WHERE chat_id = ? AND month = ? LIMIT ?",
      [{"text", chat_id}, {"text", month}, {"int", limit}]
    )
    |> Enum.map(&to_entry(@by_chat, &1))
  end

  # Q17 (a whole partition).
  @impl true
  def list_by_day(day, bucket) do
    run!(
      "SELECT * FROM risi_llm_calls_by_day WHERE day = ? AND bucket = ?",
      [{"date", day}, {"int", bucket}]
    )
    |> Enum.map(&to_entry(@by_day, &1))
  end

  # Q19: lives as long as the call it rates.
  @impl true
  def put_feedback(call_ref, user_id, rating, reason) do
    sealed =
      case Seal.seal_text(@feedback, "#{call_ref}:#{user_id}", "reason", reason) do
        {:ok, s} ->
          s

        :error ->
          Logger.warning("Risi feedback reason not kept: missing_key RISI_DATA_KEY")
          nil
      end

    run!(
      "INSERT INTO risi_llm_feedback (call_id, user_id, rating, reason_sealed, at) " <>
        "VALUES (?, ?, ?, ?, ?) USING TTL ?",
      [
        {"timeuuid", call_ref},
        {"uuid", user_id},
        {"text", rating},
        {"blob", sealed},
        {"timestamp", DateTime.utc_now()},
        {"int", remaining_ttl(call_ref)}
      ]
    )

    :ok
  end

  # Q20.
  @impl true
  def list_feedback(call_ref) do
    run!(
      "SELECT user_id, rating, reason, reason_sealed, at FROM risi_llm_feedback WHERE call_id = ?",
      [{"timeuuid", call_ref}]
    )
    |> Enum.map(fn r ->
      base = %{user_id: r["user_id"], rating: r["rating"], at: r["at"]}
      aad_id = "#{call_ref}:#{r["user_id"]}"

      case Seal.open_text(@feedback, aad_id, "reason", r["reason_sealed"]) do
        {:ok, nil} -> Map.put(base, :reason, r["reason"])
        {:ok, reason} -> Map.put(base, :reason, reason)
        :error -> Map.merge(base, %{reason: nil, reason_unavailable: true})
      end
    end)
  end

  defp to_entry(table, row) do
    {sealed, row} = Map.pop(row, "output_sealed")

    entry =
      row
      |> Map.new(fn {k, v} -> {String.to_atom(k), v} end)
      |> Map.put_new(:call_ref, row["call_id"])
      |> Map.update(:source_message_ids, [], &(&1 || []))

    case Seal.open_text(table, row["call_id"], "output", sealed) do
      # Not sealed: no output, or a row not yet sealed by `seal_existing/2`.
      {:ok, nil} -> entry
      {:ok, output} -> Map.put(entry, :output, output)
      :error -> Map.merge(entry, %{output: nil, output_unavailable: true})
    end
  end

  ## Sealing rows written before 009 (idempotent)

  @doc """
  Seals every learning-log `output` (both call tables) and feedback `reason` still held in
  plaintext, then deletes the plaintext cell. Each sealed value keeps the remaining TTL of the
  value it replaces (`TTL(output)`), so nothing lives longer than its 90 days. Walks the day
  partitions of the last 92 days (8 buckets each; no ALLOW FILTERING) and, for every call, its
  by-chat row and its feedback rows. Idempotent (already sealed rows are skipped; safe to re-run
  after a crash). `exec` is `(statement, params, opts) -> {:ok, page} | {:error, e}` (default:
  the application's cluster). Returns `{:ok, counts}` or `{:error, :missing_key}` (nothing is
  changed without the key).
  """
  def seal_existing(exec \\ &cluster_exec/3, today \\ Date.utc_today()) do
    case Seal.data() do
      :error ->
        {:error, :missing_key}

      {:ok, _} ->
        days = Date.range(Date.add(today, -92), Date.add(today, 1))
        zero = %{calls: 0, by_day: 0, by_chat: 0, feedback: 0}

        counts =
          for day <- days, bucket <- 0..(LearningLog.buckets() - 1), reduce: zero do
            acc ->
              exec
              |> pages(
                "SELECT call_id, chat_id, output, TTL(output) AS ttl FROM #{@by_day} " <>
                  "WHERE day = ? AND bucket = ?",
                [{"date", day}, {"int", bucket}]
              )
              |> Enum.reduce(acc, fn row, acc -> seal_call(exec, day, bucket, row, acc) end)
          end

        {:ok, counts}
    end
  end

  defp seal_call(exec, day, bucket, %{"call_id" => id} = row, acc) do
    acc = Map.update!(acc, :calls, &(&1 + 1))

    acc =
      if row["output"] do
        key = [{"date", day}, {"int", bucket}, {"timeuuid", id}]
        where = "day = ? AND bucket = ? AND call_id = ?"
        reseal!(exec, @by_day, id, where, key, row)
        Map.update!(acc, :by_day, &(&1 + 1))
      else
        acc
      end

    acc =
      case row["chat_id"] do
        nil ->
          acc

        chat ->
          key = [{"text", chat}, {"text", LearningLog.month(id)}, {"timeuuid", id}]
          where = "chat_id = ? AND month = ? AND call_id = ?"

          case pages(
                 exec,
                 "SELECT output, TTL(output) AS ttl FROM #{@by_chat} WHERE #{where}",
                 key
               ) do
            [%{"output" => out} = r] when is_binary(out) ->
              reseal!(exec, @by_chat, id, where, key, r)
              Map.update!(acc, :by_chat, &(&1 + 1))

            _ ->
              acc
          end
      end

    pages(
      exec,
      "SELECT user_id, reason, TTL(reason) AS ttl FROM #{@feedback} WHERE call_id = ?",
      [{"timeuuid", id}]
    )
    |> Enum.filter(&is_binary(&1["reason"]))
    |> Enum.reduce(acc, fn r, acc ->
      {:ok, sealed} = Seal.seal_text(@feedback, "#{id}:#{r["user_id"]}", "reason", r["reason"])
      key = [{"timeuuid", id}, {"uuid", r["user_id"]}]
      ttl = r["ttl"] || remaining_ttl(id)

      exec!(
        exec,
        "UPDATE #{@feedback} USING TTL ? SET reason_sealed = ? WHERE call_id = ? AND user_id = ?",
        [{"int", ttl}, {"blob", sealed}] ++ key
      )

      exec!(exec, "DELETE reason FROM #{@feedback} WHERE call_id = ? AND user_id = ?", key)
      Map.update!(acc, :feedback, &(&1 + 1))
    end)
  end

  # Writes `output_sealed` with the plaintext's remaining TTL, then deletes the plaintext cell.
  defp reseal!(exec, table, id, where, key, %{"output" => out} = row) do
    {:ok, sealed} = Seal.seal_text(table, id, "output", out)
    ttl = row["ttl"] || remaining_ttl(id)

    exec!(
      exec,
      "UPDATE #{table} USING TTL ? SET output_sealed = ? WHERE #{where}",
      [{"int", ttl}, {"blob", sealed}] ++ key
    )

    exec!(exec, "DELETE output FROM #{table} WHERE #{where}", key)
  end

  defp remaining_ttl(call_id) do
    age_s = div(System.system_time(:millisecond) - RisiMe.TimeUUID.unix_ms(call_id), 1000)
    max(LearningLog.ttl_s() - age_s, 1)
  end

  # Every row of a (paged) query.
  defp pages(exec, statement, params) do
    Stream.unfold(nil, fn
      :done ->
        nil

      state ->
        opts = [page_size: 1000] ++ if(state, do: [paging_state: state], else: [])
        page = exec!(exec, statement, params, opts)
        {Enum.to_list(page), page.paging_state || :done}
    end)
    |> Enum.concat()
  end

  defp exec!(exec, statement, params, opts \\ []) do
    case exec.(statement, params, [timeout: 30_000] ++ opts) do
      {:ok, page} -> page
      {:error, error} -> raise error
    end
  end

  defp cluster_exec(statement, params, opts),
    do: Xandra.Cluster.execute(@cluster, statement, params, opts)

  defp cast(:latency_ms, v), do: trunc(v)
  defp cast(:cost, v), do: v / 1
  defp cast(:confidence, v), do: v / 1
  defp cast(_c, v), do: v

  defp timeuuid?(<<_::binary-size(14), "1", _::binary-size(21)>>), do: true
  defp timeuuid?(_), do: false

  defp run!(statement, params) do
    case Xandra.Cluster.execute(@cluster, statement, params, timeout: 10_000) do
      {:ok, page} -> page
      {:error, error} -> raise error
    end
  end
end
