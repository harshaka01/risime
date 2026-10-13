defmodule RisiMe.Agent.CallTranscribe.Summary do
  @moduledoc """
  After a transcribed 1:1 Official call (contract v1.39 §36.7): at `ended`, or `stopped`
  without `discard`, when the session has **≥ 30 s of speech from ≥ 2 speakers**, the §27.2 call
  extraction runs on its transcript (`RisiMe.Agent.Ledger.call_ended/2`, `source: "call"`):
  the per-person `discussion_summary` (§27.3), the Official `discussion_card` (§27.4), notes
  and calendar cards as the existing path makes them. `made_by.also` lists every speech model
  with `task: "transcribe"`.

  **Language, reader first:** the Official card is written in the call's majority language and
  carries it as `lang`; each reader's copy carries the language that reader spoke most (else
  `en`); its key points are translated when it differs (`RisiMe.Agent.Ledger`).

  The transcript comes from the sealed 24-h buffer (`call_seg` rows of the session); the rows
  are deleted after the extraction (§36.8).
  """
  require Logger

  alias RisiMe.Agent.{Ledger, Transcript}
  alias RisiMe.Agent.CallTranscribe.Session
  alias RisiMe.{Repo, TimeUUID}

  @min_speech_ms 30_000
  @min_speakers 2

  @doc "Oban result."
  def run(conv, session_id) do
    case Repo.get(Session, session_id) do
      %Session{conversation_id: ^conv, discard: false, state: state} = s
      when state in ["ended", "stopped"] ->
        summarise(conv, s)

      _ ->
        :ok
    end
  end

  defp summarise(conv, s) do
    rows = segments(conv, s)
    ids = Enum.map(rows, & &1.message_id)

    by_speaker =
      Enum.reduce(rows, %{}, fn r, acc -> Map.update(acc, r.speaker, r.ms, &(&1 + r.ms)) end)

    total = by_speaker |> Map.values() |> Enum.sum()

    cond do
      total < @min_speech_ms or map_size(by_speaker) < @min_speakers ->
        drop(conv, ids)
        :ok

      # v1.42 §38: with RISI_ACTION_ITEMS on, the extraction makes the action-items cards.
      # PART 9: `canary` = a dev user among the call's speakers.
      RisiMe.Agent.ActionItems.on_for?(Map.keys(by_speaker)) ->
        case RisiMe.Agent.ActionItems.from_call(conv, s, rows) do
          :ok ->
            drop(conv, ids)
            :ok

          other ->
            other
        end

      true ->
        legacy(conv, s, rows, ids, by_speaker)
    end
  end

  defp legacy(conv, s, rows, ids, by_speaker) do
    call = %{
      call_id: s.call_id,
      media: "audio",
      duration_s: duration_s(s),
      transcript:
        Enum.map(rows, &%{message_id: &1.message_id, sender_id: &1.speaker, text: &1.text}),
      also: also(rows),
      lang: majority(rows),
      langs:
        Map.new(
          Map.keys(by_speaker),
          &{&1, majority(Enum.filter(rows, fn r -> r.speaker == &1 end))}
        )
    }

    case Ledger.call_ended(conv, call) do
      :ok ->
        drop(conv, ids)
        :ok

      other ->
        other
    end
  end

  @doc "`made_by.also`: every speech model of the rows, `task: \"transcribe\"`."
  def also(rows) do
    rows
    |> Enum.map(& &1.model)
    |> Enum.reject(&is_nil/1)
    |> Enum.uniq()
    |> Enum.map(&%{"model" => &1, "provider" => "risime", "task" => "transcribe"})
  end

  @doc "The buffered segments of session `s`, oldest first."
  def segments(conv, %Session{} = s) do
    since = TimeUUID.at(DateTime.add(s.started_at || s.requested_at, -60, :second))

    for row <- Transcript.list(conv, since, 5_000),
        {:ok, %{"type" => "call_seg", "session_id" => sid} = e} <- [Jason.decode(row.plaintext)],
        sid == s.session_id,
        is_binary(e["text"]) and e["text"] != "" do
      {:ok, a, _} = DateTime.from_iso8601(e["start"])
      {:ok, b, _} = DateTime.from_iso8601(e["end"])

      %{
        message_id: row.message_id,
        speaker: row.sender_id,
        text: e["text"],
        language: e["language"] || "en",
        model: e["model"],
        ms: max(DateTime.diff(b, a, :millisecond), 0),
        # v1.42 §38: the turn, its chunks, the gate's facts (absent on older rows).
        start: a,
        turn: e["turn"],
        parts: e["parts"] || [],
        p: e["p"],
        romanised: e["romanised"] == true
      }
    end
  end

  @doc "The majority language of rows (by speech time)."
  def majority([]), do: "en"

  def majority(rows) do
    rows
    |> Enum.reduce(%{}, fn r, acc -> Map.update(acc, r.language, r.ms, &(&1 + r.ms)) end)
    |> Enum.max_by(fn {_l, ms} -> ms end)
    |> elem(0)
  end

  @doc false
  def duration_s(%Session{started_at: a, ended_at: b}) when not is_nil(a) and not is_nil(b),
    do: max(DateTime.diff(b, a), 0)

  def duration_s(_), do: nil

  defp drop(_conv, []), do: :ok

  defp drop(conv, ids) do
    Transcript.delete(conv, ids)
    :ok
  rescue
    e -> Logger.warning("call transcribe: buffer rows not dropped: #{inspect(e.__struct__)}")
  end
end
