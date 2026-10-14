defmodule RisiMe.Agent.VoiceJobs do
  @moduledoc """
  The running voice-note transcriptions, for "Transcribing (Sinhala)… [Stop]" and "Try again"
  (proposal 2026-10-14-transcribe-progress, to be v1.48 §37.11; incident 2026-10-14).

  * Every transcription task of `RisiMe.Agent.Voice` is started here (`start/3`) and registered
    under `{conversation_id, note_id}` in an ETS table owned by this process, which monitors it.
  * **Signal `voice_transcribing`** (ephemeral, only to the note's sender, only while the flag
    `RISI_VOICE_PROGRESS` is on for the sender): `working` (with the expected language from the
    hint), then `done` | `failed`; `cancelled` after Stop. `seq` grows per note.
  * **Stop** (`cancel/3`, the sender only): kills the task (nothing is posted), keeps the note.
  * **Try again** (`retry/3`): runs `Voice.transcribe/6` again. The validated note (blob id, key,
    sizes) is held **in memory only** for at most 10 min after a stop or failure (and only while
    the flag is on for the sender), then dropped; a restart drops it (§36.4: never on disk).
  * Codes only in logs: never text, never keys.
  """
  use GenServer

  require Logger

  alias RisiMe.Agent.Voice
  alias RisiMe.Agents.Flags

  @table :risime_voice_jobs
  @flag "RISI_VOICE_PROGRESS"
  @keep_ms 600_000
  @sweep_ms 60_000

  def start_link(_), do: GenServer.start_link(__MODULE__, nil, name: __MODULE__)

  @impl true
  def init(_) do
    :ets.new(@table, [:named_table, :public, :set, read_concurrency: true])
    Process.send_after(self(), :sweep, @sweep_ms)
    {:ok, %{}}
  end

  @doc "True while the progress signal and Stop/Try again are on for `user`."
  def enabled_for?(user), do: Flags.feature_for(@flag, user)

  @doc """
  Starts the transcription of a note: `meta` = `%{conv, note_id, sender, note, mode, rid}`;
  `fun` runs in a task of `Voice.TaskSup`. `{:ok, pid}` or `{:error, reason}`.
  """
  def start(%{conv: _, note_id: _} = meta, fun) when is_function(fun, 0) do
    if Process.whereis(__MODULE__),
      do: GenServer.call(__MODULE__, {:start, meta, fun}),
      else: Task.Supervisor.start_child(Voice.TaskSup, fun)
  catch
    :exit, _ -> Task.Supervisor.start_child(Voice.TaskSup, fun)
  end

  @doc "The task's first step: the `working` signal with the expected language of `hint`."
  def working(conv, note_id, sender, hint) do
    lang =
      cond do
        "si" in List.wrap(hint) -> "si"
        "ta" in List.wrap(hint) -> "ta"
        List.wrap(hint) == ["en"] -> "en"
        true -> nil
      end

    update(conv, note_id, &Map.put(&1, :language, lang))
    signal(conv, note_id, sender, "working")
  end

  @doc "The task's last step: `status` (`ok` | `unclear` | `failed`) of the posted transcript."
  def finished(conv, note_id, sender, status) do
    if status == "failed" and enabled_for?(sender) do
      # pid nil: the task's normal exit then doesn't drop the note kept for Try again.
      update(conv, note_id, &Map.merge(&1, %{state: :failed, pid: nil, until: now() + @keep_ms}))
      signal(conv, note_id, sender, "failed")
    else
      signal(conv, note_id, sender, "done")
      drop(conv, note_id)
    end
  rescue
    _ -> :ok
  end

  @doc """
  Stop (the note's sender only): `{:ok, can_retry}` or `{:error, :not_running}`.
  """
  def cancel(conv, note_id, user) do
    if Process.whereis(__MODULE__),
      do: GenServer.call(__MODULE__, {:cancel, conv, note_id, user}),
      else: {:error, :not_running}
  end

  @doc """
  Try again (the note's sender only): `:ok`, or `{:error, :running | :expired | :not_found}`.
  """
  def retry(conv, note_id, user) do
    if Process.whereis(__MODULE__),
      do: GenServer.call(__MODULE__, {:retry, conv, note_id, user}),
      else: {:error, :expired}
  end

  @doc false
  def lookup(conv, note_id) do
    case :ets.lookup(@table, {conv, note_id}) do
      [{_, job}] -> job
      _ -> nil
    end
  rescue
    ArgumentError -> nil
  end

  ## Server

  @impl true
  def handle_call({:start, meta, fun}, _from, s) do
    {:reply, spawn_job(meta, fun), s}
  end

  def handle_call({:cancel, conv, id, user}, _from, s) do
    reply =
      case lookup(conv, id) do
        %{state: :running, sender: ^user, pid: pid} = job ->
          if is_pid(pid), do: Process.exit(pid, :kill)
          keep? = enabled_for?(user)

          if keep?,
            do: put(%{job | pid: nil, state: :cancelled} |> Map.put(:until, now() + @keep_ms)),
            else: drop(conv, id)

          signal(conv, id, user, "cancelled", can_retry: keep?)
          Logger.info("Risi voice: transcription stopped by the sender in #{conv}")
          {:ok, keep?}

        _ ->
          {:error, :not_running}
      end

    {:reply, reply, s}
  end

  def handle_call({:retry, conv, id, user}, _from, s) do
    reply =
      case lookup(conv, id) do
        %{sender: ^user, state: :running} ->
          {:error, :running}

        %{sender: ^user, state: st, until: until} = job when st in [:cancelled, :failed] ->
          if until > now() do
            meta = Map.take(job, [:conv, :note_id, :sender, :note, :mode, :rid])

            case spawn_job(meta, fn ->
                   Voice.transcribe(conv, id, user, job.note, job.mode, job.rid)
                 end) do
              {:ok, _} -> :ok
              _ -> {:error, :expired}
            end
          else
            drop(conv, id)
            {:error, :expired}
          end

        %{} ->
          {:error, :not_found}

        nil ->
          {:error, :expired}
      end

    {:reply, reply, s}
  end

  @impl true
  def handle_info({:DOWN, _ref, :process, pid, reason}, s) do
    # A task that ended without `finished/4` (a crash): failed, kept for Try again while on.
    for {{conv, id}, %{pid: ^pid} = job} <- safe_all() do
      if reason != :normal and enabled_for?(job.sender) do
        put(%{job | pid: nil, state: :failed} |> Map.put(:until, now() + @keep_ms))
        signal(conv, id, job.sender, "failed")
      else
        drop(conv, id)
      end
    end

    {:noreply, s}
  end

  def handle_info(:sweep, s) do
    t = now()

    for {{conv, id}, %{state: st} = job} <- safe_all(),
        st != :running,
        Map.get(job, :until, 0) <= t,
        do: drop(conv, id)

    Process.send_after(self(), :sweep, @sweep_ms)
    {:noreply, s}
  end

  def handle_info(_, s), do: {:noreply, s}

  defp spawn_job(meta, fun) do
    case Task.Supervisor.start_child(Voice.TaskSup, fun) do
      {:ok, pid} ->
        Process.monitor(pid)

        put(
          Map.merge(meta, %{
            pid: pid,
            state: :running,
            seq: (lookup(meta.conv, meta.note_id) || %{seq: 0}).seq,
            language: nil
          })
        )

        {:ok, pid}

      other ->
        other
    end
  end

  ## Signals

  defp signal(conv, id, sender, state, opts \\ []) do
    if enabled_for?(sender) do
      job = lookup(conv, id) || %{seq: 0, language: nil}
      seq = Map.get(job, :seq, 0) + 1
      update(conv, id, &Map.put(&1, :seq, seq))

      data = %{
        "conversation_id" => conv,
        "message_id" => id,
        "state" => state,
        "language" => Map.get(job, :language),
        "seq" => seq,
        "can_cancel" => state == "working",
        "can_retry" =>
          state in ["cancelled", "failed"] and Keyword.get(opts, :can_retry, true) and
            not is_nil(lookup(conv, id)),
        "server_ts" => RisiMe.Messaging.iso(DateTime.utc_now())
      }

      RisiMe.Messaging.signal(sender, %{kind: "voice_transcribing", data: data})
    end

    :ok
  rescue
    _ -> :ok
  end

  ## Table

  defp put(%{conv: c, note_id: id} = job), do: :ets.insert(@table, {{c, id}, job})

  defp update(conv, id, fun) do
    case lookup(conv, id) do
      nil -> :ok
      job -> put(fun.(job))
    end
  rescue
    _ -> :ok
  end

  defp drop(conv, id) do
    :ets.delete(@table, {conv, id})
    :ok
  rescue
    ArgumentError -> :ok
  end

  defp safe_all do
    :ets.tab2list(@table)
  rescue
    ArgumentError -> []
  end

  defp now, do: System.monotonic_time(:millisecond)
end
