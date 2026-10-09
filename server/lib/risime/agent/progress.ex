defmodule RisiMe.Agent.Progress do
  @moduledoc """
  The `risi_progress` signal (contract v1.25 §25.4, `signal_risi_progress.json`): ephemeral,
  never stored, sent to the asker's live sockets; the inbox channel delivers it only to
  `risi_tools` sockets (`RisiMe.Groups.Tabs.risi_only?/1`). `seq` grows by 1 per signal of a
  request (an ETS counter created at application start, `init/0`).
  """
  alias RisiMe.Messaging

  @table :risime_risi_progress

  @doc "Creates the counter table (application start)."
  def init do
    if :ets.whereis(@table) == :undefined,
      do: :ets.new(@table, [:named_table, :public, :set, write_concurrency: true])

    :ok
  end

  @doc """
  Sends one signal: `state` = `queued` (with `position`) | `working` | `step` (with
  `step: %{n, tool, status}`) | `waiting_confirm` | `done`.
  """
  def send(asker, request_id, conv, state, opts \\ []) do
    data = %{
      "request_id" => request_id,
      "conversation_id" => conv,
      "state" => state,
      "position" => if(state == "queued", do: Keyword.get(opts, :position)),
      "seq" => next_seq(request_id),
      "step" =>
        case {state, Keyword.get(opts, :step)} do
          {"step", %{} = s} -> %{"n" => s.n, "tool" => s.tool, "status" => s.status}
          _ -> nil
        end,
      "server_ts" => Messaging.iso(DateTime.utc_now())
    }

    Messaging.signal(asker, %{kind: "risi_progress", data: data})
    if state == "done", do: forget(request_id)
    :ok
  end

  defp next_seq(request_id) do
    :ets.update_counter(@table, request_id, {2, 1}, {request_id, 0})
  rescue
    ArgumentError -> 1
  end

  defp forget(request_id) do
    :ets.delete(@table, request_id)
  rescue
    ArgumentError -> :ok
  end
end
