defmodule RisiMe.Agent.Offline do
  @moduledoc """
  "Never silent" (PART 4, Harsha 2026-10-12): a request whose model is down or slow gets one
  timely line in its conversation, **"Risi is offline, I'll reply when I'm back."**, and stays
  queued (its job snoozes); the real reply follows by itself once the model answers.

  * **When:** the model call failed (the turn is snoozed), or one model call of the request has
    been pending for `offline_ms` (default 10 s, `config :risime, :risi_turn, offline_ms:`). The
    clock runs only while the model is being asked (`during_call/1`, wrapped around every
    `RisiMe.Agent.LLM.complete/1` of a running request), never while a turn waits for the phone
    (a client tool) or for anything else.
  * **Once per request:** across the job's snoozes (an ETS mark in the `risi_progress` table,
    `RisiMe.Agent.Progress.init/0`), cleared when the request is answered (`clear/1`).
  * **On the wire:** an ordinary `answer` (§24.11; `request_id`, no refs, `call_ref: null`, so
    `made_by` says a rule made it) notifying the asker only, so every app shows its `body`
    (no protocol change). The later reply is a second Risi message for the same request.
  """
  alias RisiMe.Agent.Out

  @table :risime_risi_progress
  @text "Risi is offline, I'll reply when I'm back."
  @key :risi_offline_request

  @doc "The notice's text."
  def text, do: @text

  @doc "How long one model call of a running request may take before the notice (ms)."
  def after_ms,
    do: :risime |> Application.get_env(:risi_turn, []) |> Keyword.get(:offline_ms, 10_000)

  @doc "A request starts running in this (the job's) process: its model calls are watched."
  def watch(conv, request_id, asker) do
    Process.put(@key, {conv, request_id, asker})
    :ok
  end

  @doc "The request's attempt ended (answered, snoozed or failed)."
  def stop do
    Process.delete(@key)
    :ok
  end

  @doc """
  Runs one model call (`fun`). While a request is watched, a call still pending after
  `after_ms/0` posts the notice (from a watcher process that ends with the call).
  """
  def during_call(fun) do
    case Process.get(@key) do
      {conv, request_id, asker} ->
        parent = self()
        ms = after_ms()

        {:ok, pid} =
          Task.start(fn ->
            ref = Process.monitor(parent)

            receive do
              :cancel -> :ok
              {:DOWN, ^ref, _, _, _} -> :ok
            after
              ms -> notice(conv, request_id, asker)
            end
          end)

        try do
          fun.()
        after
          send(pid, :cancel)
        end

      _ ->
        fun.()
    end
  end

  @doc """
  Posts the notice for a request once (later calls for the same request do nothing). Returns
  `:ok` whether or not it posted.
  """
  def notice(conv, request_id, asker) do
    if first?(request_id) do
      risi = %{
        "kind" => "answer",
        "request_id" => request_id,
        "answer" => @text,
        "refs" => [],
        "confidence" => 1.0,
        "call_ref" => nil,
        "notify" => [asker]
      }

      case Out.post(conv, @text, risi) do
        {:ok, _} -> :ok
        # Not posted (Risi can't post here right now): a later attempt may try again.
        _ -> clear(request_id)
      end
    end

    :ok
  rescue
    _ -> :ok
  end

  @doc "True once the notice went out for this request (until `clear/1`)."
  def sent?(request_id) do
    :ets.member(@table, {:offline, request_id})
  rescue
    ArgumentError -> false
  end

  @doc "The request was answered (or given up): forget its mark."
  def clear(request_id) do
    :ets.delete(@table, {:offline, request_id})
    :ok
  rescue
    ArgumentError -> :ok
  end

  defp first?(request_id) do
    :ets.insert_new(@table, {{:offline, request_id}, true})
  rescue
    ArgumentError -> false
  end
end
