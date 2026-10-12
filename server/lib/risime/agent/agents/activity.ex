defmodule RisiMe.Agents.Activity do
  @moduledoc """
  `agent-activity-v1` (decision 084): one event per orchestrated step, for a live view.

      {"v": "agent-activity-v1", "run_id", "agent", "mode", "kind", "status", "step",
       "decision", "latency_ms", "learned_version", "user_ref", "at"}

  `kind` = `start` | `step` | `decision` | `fallback` | `done`. Published on `Phoenix.PubSub`
  (`RisiMe.PubSub`, topic `#{"risi:agent_activity"}`) and as the telemetry event
  `[:risime, :agents, :activity]`. **Never** a prompt, a request text, a tool's args or an
  answer: only codes, counts and timings. Server-internal (no client receives it): not wire
  protocol.
  """

  @topic "risi:agent_activity"
  @kinds ~w(start step decision fallback done)

  @doc "The PubSub topic."
  def topic, do: @topic

  @doc "Subscribes the caller to the live events."
  def subscribe, do: Phoenix.PubSub.subscribe(RisiMe.PubSub, @topic)

  @doc """
  Emits one event. `fields`: `run_id`, `agent`, `mode`, `status` and optionally `step`,
  `decision`, `latency_ms`, `learned_version`, `user` (hashed here, never sent raw).
  """
  def emit(kind, fields) when kind in @kinds do
    event = %{
      "v" => "agent-activity-v1",
      "run_id" => fields[:run_id],
      "agent" => fields[:agent],
      "mode" => to_string(fields[:mode] || "legacy"),
      "kind" => kind,
      "status" => to_string(fields[:status] || "ok"),
      "step" => fields[:step],
      "decision" => fields[:decision],
      "latency_ms" => fields[:latency_ms],
      "learned_version" => fields[:learned_version],
      "user_ref" => user_ref(fields[:user]),
      "at" => DateTime.utc_now() |> DateTime.to_iso8601()
    }

    :telemetry.execute([:risime, :agents, :activity], %{count: 1}, event)

    try do
      Phoenix.PubSub.broadcast(RisiMe.PubSub, @topic, {:agent_activity, event})
    rescue
      _ -> :ok
    end

    event
  end

  @doc "A short, stable, non-reversible reference to a user (12 hex of SHA-256), or nil."
  def user_ref(nil), do: nil

  def user_ref(user) when is_binary(user),
    do:
      :crypto.hash(:sha256, "risi-agent:" <> user)
      |> Base.encode16(case: :lower)
      |> binary_part(0, 12)
end
