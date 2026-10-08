defmodule RisiMe.Agent.Starter do
  @moduledoc """
  Starts Risi's tree without ever failing the boot (P0 2026-10-08: a `:tampered` open in
  `Agent.Mls.init/1` used to stop the whole application in a restart loop).

  Started by the application **after** the endpoint (`RisiMe.Agent.children/1`). Its `init`
  only schedules the work; in `handle_continue` it

    1. stops at `:off` when `RISI` is off, or records `{:not_startable, problems}` (no NIF,
       missing or malformed keys);
    2. compares `RISI_DATA_KEY` with its key check: a mismatch only logs a warning (the
       unreadable `risi_buffer` rows are dropped as they are read; 24-h TTL) and records the new
       key, never stops Risi;
    3. starts `RisiMe.Agent.Supervisor` as a `:temporary` child of the `DynamicSupervisor`
       (`RisiMe.Agent.TreeSup`). A failed start (`kek_mismatch`, `:tampered`, a DB error, …)
       becomes the status; a tree that later gives up (its own restart limit) is noticed by the
       monitor and becomes `{:crashed, reason}`. Nothing is retried: fix the cause and restart.

  Every outcome but `:off` and `:running` logs exactly **one** error line naming the reason
  (`RisiMe.Agent.describe/1`, never key material). Everything here is wrapped: the starter
  itself never crashes.
  """
  use GenServer

  require Logger

  alias RisiMe.Agent
  alias RisiMe.Agent.{KeyCheck, Status}

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @impl true
  def init(opts) do
    Status.put(:starting)
    {:ok, %{sup: Keyword.get(opts, :sup, RisiMe.Agent.TreeSup), tree: nil}, {:continue, :start}}
  end

  @impl true
  def handle_continue(:start, state), do: {:noreply, start_tree(state)}

  @impl true
  def handle_info({:DOWN, ref, :process, _pid, _reason}, %{tree: ref} = state) do
    {:noreply, fail({:crashed, Status.last_failure() || :restart_limit}, %{state | tree: nil})}
  end

  def handle_info(_msg, state), do: {:noreply, state}

  defp start_tree(state) do
    case Agent.problems() do
      [:risi_off | _] ->
        Status.put(:off)
        state

      [] ->
        check_data_key()
        Status.clear_failure()

        spec = Supervisor.child_spec(RisiMe.Agent.Supervisor, restart: :temporary)

        case DynamicSupervisor.start_child(state.sup, spec) do
          {:ok, pid} -> running(pid, state)
          {:error, {:already_started, pid}} -> running(pid, state)
          {:error, reason} -> fail(start_reason(reason), state)
          other -> fail(start_reason(other), state)
        end

      problems ->
        fail({:not_startable, problems}, state)
    end
  rescue
    e -> fail(Agent.exception_reason(e), state)
  catch
    :exit, _ -> fail(:exit, state)
  end

  defp running(pid, state) do
    Status.put(:running)
    Logger.info("Risi agent started")
    %{state | tree: Process.monitor(pid)}
  end

  # Agent.Mls records why its open failed (kek_mismatch, tampered, db_error, …); a failure in
  # another child names the child.
  defp start_reason(reason) do
    case {Status.last_failure(), reason} do
      {why, _} when why != nil ->
        why

      {nil, {:shutdown, {:failed_to_start_child, child, _}}} when is_atom(child) ->
        {:child_failed, child}

      _ ->
        :start_failed
    end
  end

  defp fail(reason, state) do
    Status.put(reason)

    Logger.error(
      "Risi agent unavailable: #{Agent.describe(reason)}. The server keeps running without " <>
        "Risi; fix the cause (RisiMe.Release.risi_preflight()) and restart risime.service"
    )

    state
  end

  defp check_data_key do
    {:ok, key} = Agent.data_key()
    name = KeyCheck.data_name()

    case KeyCheck.compare(name, key) do
      :ok ->
        :ok

      :absent ->
        KeyCheck.record(name, key)

      :mismatch ->
        Logger.warning(
          "RISI_DATA_KEY differs from the key of the risi_buffer rows: they can't be read and " <>
            "are dropped as they are read (24-h buffer). Risi keeps running"
        )

        KeyCheck.record(name, key)
    end
  rescue
    e ->
      Logger.warning("Risi data key check skipped: #{Agent.describe(Agent.exception_reason(e))}")
  end
end
