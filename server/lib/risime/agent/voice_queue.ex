defmodule RisiMe.Agent.VoiceQueue do
  @moduledoc """
  The retry queue of Risi-chat voice notes (proposal 2026-10-14-voice-queue, §37.8 amendment;
  P1 hotfix, Harsha 2026-10-14): when the speech service is down, busy, out of memory or times
  out, the note is **not** answered with "please type it". `RisiMe.Agent.Voice` posts "Got your
  voice note. I'll reply in a moment." and the note waits here; this job retries with backoff and,
  once the service answers, the note gets its normal transcript card and answer. After
  `max_ms` (default 30 min, `config :risime, :risi_voice_queue, max_ms:`) it gives up honestly:
  the `failed` card and "I couldn't listen to your voice note…" (never "type it").

  **What is kept, and how (§37.6 no-storage rules).** Only what the server already holds for
  this path: the blob reference (id, size, SHA-256 of the ciphertext, which the blob store
  keeps anyway) and the note's key, **sealed** with AES-256-GCM under `RISI_DATA_KEY` in one
  `risi_voice_queue` row (AAD = `risi_voice_queue:<conversation>:<note id>`, so it can't be moved
  to another note). Never audio, never plaintext, never a transcript; the job's args hold ids
  only. Each attempt fetches the ciphertext from the blob store and decrypts **in memory**
  exactly as the first attempt did. The row is deleted when the note is transcribed or given
  up, and when the note was deleted for everyone meanwhile (then nothing is posted).
  """
  use Oban.Worker, queue: :risi_timers, max_attempts: 60, unique: [period: 3_600, keys: [:note]]

  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Clock, Seal, Speech, Voice}
  alias RisiMe.Repo

  @table "risi_voice_queue"

  @doc "How long a queued note is retried before the honest give-up (ms)."
  def max_ms,
    do: :risime |> Application.get_env(:risi_voice_queue, []) |> Keyword.get(:max_ms, 1_800_000)

  @doc "The wait before attempt `n + 1` (s): 15, 30, 60, 120, then every 5 min."
  def backoff_s(n) when is_integer(n) and n >= 1, do: min(15 * Integer.pow(2, n - 1), 300)

  @doc """
  Queues a Risi-chat note (`note` as `Voice.validate/1` returns it): the sealed row and the job.
  `{:ok, job}` or `{:error, reason}` (then the caller answers as before, never silent).
  """
  def enqueue(conv, note_id, sender, note, request_id) do
    with {:ok, key} <- Seal.data() do
      sealed = Seal.seal(key, aad(conv, note_id), dump(note))

      Repo.insert_all(
        @table,
        [
          %{
            note_message_id: note_id,
            conversation_id: conv,
            sender: Ecto.UUID.dump!(sender),
            sealed: sealed,
            inserted_at: DateTime.utc_now()
          }
        ],
        on_conflict: {:replace, [:sealed, :inserted_at]},
        conflict_target: [:note_message_id]
      )

      until = DateTime.add(Clock.now(), max_ms(), :millisecond)

      %{
        "conv" => conv,
        "note" => note_id,
        "sender" => sender,
        "request_id" => request_id,
        "until" => DateTime.to_iso8601(until)
      }
      |> new(schedule_in: backoff_s(1))
      |> Oban.insert()
    else
      _ -> {:error, :no_data_key}
    end
  rescue
    e ->
      Logger.warning("Risi voice note not queued in #{conv}: #{inspect(e.__struct__)}")
      {:error, :exception}
  end

  @impl Oban.Worker
  def perform(%Oban.Job{args: args, attempt: attempt, meta: meta}) do
    # A snooze keeps `attempt` (Oban's Basic engine); the count is in `meta["snoozed"]`.
    n = attempt + ((is_map(meta) && meta["snoozed"]) || 0)
    %{"conv" => conv, "note" => id, "sender" => sender, "request_id" => rid} = args
    {:ok, until, _} = DateTime.from_iso8601(args["until"])
    over? = DateTime.compare(Clock.now(), until) != :lt

    case load(conv, id) do
      :none ->
        :ok

      :error ->
        drop(id)
        Voice.give_up_answer(conv, sender, rid)
        :ok

      {:ok, note} ->
        cond do
          # Deleted for everyone while it waited: nothing of it is kept or answered.
          Voice.note_gone?(id) ->
            drop(id)
            :ok

          over? ->
            drop(id)
            Voice.give_up(conv, id, sender, note, rid)
            :ok

          not healthy?() ->
            {:snooze, backoff_s(n)}

          true ->
            case Voice.retry(conv, id, sender, note, rid) do
              :retry ->
                {:snooze, backoff_s(n)}

              :done ->
                drop(id)
                :ok
            end
        end
    end
  end

  @doc "True while a note waits in the queue (tests, the admin view)."
  def queued?(note_id),
    do: Repo.exists?(from q in @table, where: q.note_message_id == ^note_id)

  defp healthy? do
    Speech.healthy?()
  rescue
    _ -> false
  end

  defp load(conv, id) do
    case Repo.one(
           from q in @table,
             where: q.note_message_id == ^id and q.conversation_id == ^conv,
             select: q.sealed
         ) do
      nil ->
        :none

      sealed ->
        with {:ok, key} <- Seal.data(),
             {:ok, m} <- Seal.open(key, aad(conv, id), sealed),
             {:ok, note} <- load_note(m) do
          {:ok, note}
        else
          _ -> :error
        end
    end
  end

  defp drop(id), do: Repo.delete_all(from q in @table, where: q.note_message_id == ^id)

  defp aad(conv, id), do: "#{@table}:#{conv}:#{id}"

  defp dump(note),
    do: %{
      "blob_id" => note.blob_id,
      "size" => note.size,
      "sha256" => Base.encode64(note.sha256),
      "key" => Base.encode64(note.key),
      "plain_size" => note.plain_size,
      "mime" => note.mime,
      "duration_ms" => note.duration_ms
    }

  defp load_note(m) do
    with {:ok, sha} <- Base.decode64(m["sha256"] || ""),
         {:ok, key} <- Base.decode64(m["key"] || "") do
      {:ok,
       %{
         blob_id: m["blob_id"],
         size: m["size"],
         sha256: sha,
         key: key,
         plain_size: m["plain_size"],
         mime: m["mime"],
         duration_ms: m["duration_ms"]
       }}
    else
      _ -> :error
    end
  end
end
