defmodule RisiMe.Agent.SpeechGold do
  @moduledoc """
  The real-speech gold set from Harsha's own voice notes (Harsha 2026-10-14 08:35 SLT: his consent
  for his own notes, **eval only, never training**; docs/status/asr-harsha-gold.md).

  * Only `RISI_DEV_USERS`' own notes, only while the Sinhala compare mode runs for them
    (`RisiMe.Agent.SpeechCompare`).
  * Stored sealed on spark2, never in git: `RISI_ASR_GOLD_DIR` (default
    `~/risime-sealed/eval-real-harsha/`, mode 700), one `<note_id>.audio.sealed` (the decrypted
    Ogg/Opus) and one `<note_id>.json.sealed` (the candidates' texts and timings, the label) per
    note, mode 600, AES-256-GCM under `RISI_DATA_KEY` (`RisiMe.Agent.Seal`, AAD
    `asr_gold:<note_id>:audio|json`). An exception to §37.6 "never on disk", for these notes only,
    by his consent.
  * The directory holds a `NO_TRAINING` marker: training scripts must refuse a directory that
    has it (`scripts/asr-harsha-gold` and the eval tools read it; nothing trains on it).
  * Kept until he deletes them: deleting the note for everyone deletes its gold files
    (`delete/1`, from `Voice.notes_deleted/2`), and `delete_all/0` drops the set.
  * Logs carry ids and codes only.
  """
  require Logger

  alias RisiMe.Agent.Seal

  @marker "NO_TRAINING"

  @doc "The gold directory, or nil when off (`config :risime, :risi_asr_gold_dir, false`)."
  def dir do
    case Application.get_env(:risime, :risi_asr_gold_dir) do
      false ->
        nil

      d when is_binary(d) and d != "" ->
        d

      _ ->
        System.get_env("RISI_ASR_GOLD_DIR") ||
          Path.join([System.user_home() || "/tmp", "risime-sealed", "eval-real-harsha"])
    end
  end

  @doc "Stores a note's audio and its candidates (no label yet). `:ok` or `{:error, reason}`."
  def put(note_id, audio, meta) when is_binary(audio) and is_map(meta) do
    with d when is_binary(d) <- dir(),
         {:ok, key} <- Seal.data(),
         :ok <- ensure_dir(d) do
      write(d, note_id, "audio", %{"ogg_b64" => Base.encode64(audio)}, key)
      write(d, note_id, "json", Map.put(meta, "label", nil), key)
    else
      nil -> {:error, :off}
      e -> {:error, e}
    end
  end

  @doc "The note's sealed meta (candidates, label), `{:ok, map}` or `:error`."
  def get(note_id) do
    with d when is_binary(d) <- dir(),
         {:ok, key} <- Seal.data(),
         {:ok, bin} <- File.read(path(d, note_id, "json")) do
      Seal.open(key, aad(note_id, "json"), bin)
    else
      _ -> :error
    end
  end

  @doc "Sets the note's gold label (`text`, `source`: `pick:<n>` or `typed`)."
  def label(note_id, text, source) do
    with {:ok, meta} <- get(note_id),
         {:ok, key} <- Seal.data() do
      meta =
        Map.merge(meta, %{
          "label" => text,
          "label_source" => source,
          "labelled_at" => DateTime.to_iso8601(DateTime.utc_now())
        })

      write(dir(), note_id, "json", meta, key)
      {:ok, meta}
    else
      _ -> {:error, :not_found}
    end
  end

  @doc "Stores the candidates' scores against the label (numbers only)."
  def put_scores(note_id, scores) do
    with {:ok, meta} <- get(note_id), {:ok, key} <- Seal.data() do
      write(dir(), note_id, "json", Map.put(meta, "scores", scores), key)
    end
  end

  @doc "Every labelled note's meta (newest first)."
  def labelled,
    do: all() |> Enum.filter(&is_binary(&1["label"])) |> Enum.sort_by(& &1["labelled_at"], :desc)

  @doc "Every note's meta without a label yet."
  def unlabelled, do: Enum.reject(all(), &is_binary(&1["label"]))

  defp all do
    with d when is_binary(d) <- dir(),
         {:ok, files} <- File.ls(d) do
      files
      |> Enum.filter(&String.ends_with?(&1, ".json.sealed"))
      |> Enum.map(&String.replace_suffix(&1, ".json.sealed", ""))
      |> Enum.flat_map(fn id ->
        case get(id) do
          {:ok, %{} = m} -> [Map.put(m, "note_id", id)]
          _ -> []
        end
      end)
    else
      _ -> []
    end
  end

  @doc "Deletes a note's gold files (the note was deleted, or he asked)."
  def delete(note_id) do
    with d when is_binary(d) <- dir() do
      for k <- ["audio", "json"], do: File.rm(path(d, note_id, k))
    end

    :ok
  end

  @doc "Deletes the whole set (keeps the marker)."
  def delete_all do
    with d when is_binary(d) <- dir(), {:ok, files} <- File.ls(d) do
      for f <- files, String.ends_with?(f, ".sealed"), do: File.rm(Path.join(d, f))
    end

    :ok
  end

  defp ensure_dir(d) do
    with :ok <- File.mkdir_p(d),
         :ok <- File.chmod(d, 0o700) do
      marker = Path.join(d, @marker)

      unless File.exists?(marker) do
        File.write!(
          marker,
          "Harsha's own voice notes, his consent 2026-10-14: EVAL ONLY. Training scripts must refuse this directory.\n"
        )

        File.chmod(marker, 0o600)
      end

      :ok
    end
  end

  defp write(d, note_id, kind, term, key) do
    p = path(d, note_id, kind)
    File.write!(p, Seal.seal(key, aad(note_id, kind), term))
    File.chmod!(p, 0o600)
    :ok
  rescue
    e ->
      Logger.warning("asr gold: #{kind} of #{note_id} not written (#{inspect(e.__struct__)})")
      {:error, :write}
  end

  defp path(d, note_id, kind), do: Path.join(d, "#{safe(note_id)}.#{kind}.sealed")
  defp aad(note_id, kind), do: "asr_gold:#{note_id}:#{kind}"
  defp safe(id), do: String.replace(id, ~r/[^0-9a-zA-Z-]/, "")
end
