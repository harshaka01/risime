defmodule RisiMe.Agent.TurnSteps do
  @moduledoc """
  `risi_turn_steps` (contract v1.25 §25.1, `priv/cql/008_risi_turn_steps.cql`, Q21–Q22): one row
  per step of a turn: `turn_id`, `n`, `tool`, `args_sha256`, the `authorize` result, `status`,
  `latency_ms`, `result_bytes`, the step's model `call_ref`. **Hashes only:** never the request,
  the args, a tool result or the answer (decision 066/068). Writes never fail a turn.
  """
  require Logger

  @cluster RisiMe.Messaging.Store.Cassandra.Cluster
  @ttl_s 90 * 86_400

  @doc "SHA-256 (hex) of a step's args, the only trace of them that is kept."
  def args_hash(args),
    do: :crypto.hash(:sha256, Jason.encode!(args || %{})) |> Base.encode16(case: :lower)

  @doc "Q21: records one step (`%{turn_id, n, tool, args_sha256, authorize, status, …}`)."
  def record(%{turn_id: turn_id, n: n} = s) do
    run!(
      "INSERT INTO risi_turn_steps (turn_id, n, tool, args_sha256, authz, status, " <>
        "latency_ms, result_bytes, call_ref, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) USING TTL ?",
      [
        {"uuid", turn_id},
        {"int", n},
        {"text", s.tool},
        {"text", Map.get(s, :args_sha256)},
        {"text", Map.get(s, :authorize)},
        {"text", s.status},
        {"int", trunc(Map.get(s, :latency_ms, 0))},
        {"int", Map.get(s, :result_bytes, 0)},
        {"timeuuid", Map.get(s, :call_ref)},
        {"timestamp", DateTime.utc_now()},
        {"int", @ttl_s}
      ]
    )

    :ok
  rescue
    e -> Logger.warning("risi_turn_steps write failed: #{inspect(e.__struct__)}")
  end

  @doc "Q22: a turn's steps in order."
  def list(turn_id) do
    run!("SELECT * FROM risi_turn_steps WHERE turn_id = ?", [{"uuid", turn_id}])
    |> Enum.map(fn row ->
      row
      |> Map.new(fn {k, v} -> {String.to_atom(k), v} end)
      |> then(fn r -> r |> Map.put(:authorize, r[:authz]) |> Map.delete(:authz) end)
    end)
  end

  defp run!(statement, params) do
    case Xandra.Cluster.execute(@cluster, statement, params, timeout: 10_000) do
      {:ok, page} -> page
      {:error, error} -> raise error
    end
  end
end
