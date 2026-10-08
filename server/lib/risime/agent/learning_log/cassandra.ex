defmodule RisiMe.Agent.LearningLog.Cassandra do
  @moduledoc """
  `RisiMe.Agent.LearningLog` on Cassandra (`priv/cql/007_risi_learning_log.cql`, queries
  Q15–Q20), through the application's `Xandra.Cluster` pool. Not message storage: messages stay
  behind `RisiMe.Messaging.Store`.
  """
  @behaviour RisiMe.Agent.LearningLog

  alias RisiMe.Agent.LearningLog

  @cluster RisiMe.Messaging.Store.Cassandra.Cluster

  @cols ~w(conversation_id task model_alias model provider fallback latency_ms prompt_tokens
           completion_tokens cost confidence status output source_message_ids prompt_sha256)a

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
    output: "text",
    source_message_ids: "list<timeuuid>",
    prompt_sha256: "text"
  }

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

    by_day =
      "INSERT INTO risi_llm_calls_by_day (day, bucket, call_id, chat_id, #{Enum.join(cols, ", ")}) " <>
        "VALUES (?, ?, ?, ?#{String.duplicate(", ?", length(cols))})"

    by_chat =
      "INSERT INTO risi_llm_calls_by_chat (chat_id, month, call_id, #{Enum.join(cols, ", ")}) " <>
        "VALUES (?, ?, ?#{String.duplicate(", ?", length(cols))})"

    run!(
      by_day,
      [
        {"date", LearningLog.day(id)},
        {"int", LearningLog.bucket(id)},
        {"timeuuid", id},
        {"text", chat}
      ] ++ vals
    )

    run!(by_chat, [{"text", chat}, {"text", LearningLog.month(id)}, {"timeuuid", id}] ++ vals)
    :ok
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
        row -> {:ok, to_entry(row)}
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
    |> Enum.map(&to_entry/1)
  end

  # Q17 (a whole partition).
  @impl true
  def list_by_day(day, bucket) do
    run!(
      "SELECT * FROM risi_llm_calls_by_day WHERE day = ? AND bucket = ?",
      [{"date", day}, {"int", bucket}]
    )
    |> Enum.map(&to_entry/1)
  end

  # Q19: lives as long as the call it rates.
  @impl true
  def put_feedback(call_ref, user_id, rating, reason) do
    age_s = div(System.system_time(:millisecond) - RisiMe.TimeUUID.unix_ms(call_ref), 1000)
    ttl = max(LearningLog.ttl_s() - age_s, 1)

    run!(
      "INSERT INTO risi_llm_feedback (call_id, user_id, rating, reason, at) VALUES (?, ?, ?, ?, ?) " <>
        "USING TTL ?",
      [
        {"timeuuid", call_ref},
        {"uuid", user_id},
        {"text", rating},
        {"text", reason},
        {"timestamp", DateTime.utc_now()},
        {"int", ttl}
      ]
    )

    :ok
  end

  # Q20.
  @impl true
  def list_feedback(call_ref) do
    run!("SELECT user_id, rating, reason, at FROM risi_llm_feedback WHERE call_id = ?", [
      {"timeuuid", call_ref}
    ])
    |> Enum.map(fn r ->
      %{user_id: r["user_id"], rating: r["rating"], reason: r["reason"], at: r["at"]}
    end)
  end

  defp to_entry(row) do
    row
    |> Map.new(fn {k, v} -> {String.to_atom(k), v} end)
    |> Map.put_new(:call_ref, row["call_id"])
    |> Map.update(:source_message_ids, [], &(&1 || []))
  end

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
