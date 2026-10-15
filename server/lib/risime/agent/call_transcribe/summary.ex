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

  **Long calls** (P0 2026-10-15): the transcript is extracted in sections that fit risi-l1 and
  reduced to one summary (`RisiMe.Agent.CallTranscribe.Sections`), on both paths; with
  RISI_ACTION_ITEMS on, a call with items posts its summary card (line and key points) and the
  `action_items` card.

  The transcript comes from the sealed 24-h buffer (`call_seg` rows of the session); the rows
  are deleted after the extraction (§36.8).
  """
  require Logger

  alias RisiMe.Agent.{Ledger, Transcript}
  alias RisiMe.Agent.CallTranscribe.Session
  alias RisiMe.{Repo, TimeUUID}

  @min_speech_ms 30_000
  @min_speakers 2

  @doc """
  Oban result. P0 2026-10-14 (never silent): a call too short to summarise gets one line; a
  summary that fails says so once (`attempt` 1) and the job retries from the buffered segments
  (they are dropped only after a summary is posted); a transcript stopped before the call ended
  is summarised with a line saying which part it covers.
  """
  def run(conv, session_id, attempt \\ 1) do
    case Repo.get(Session, session_id) do
      %Session{conversation_id: ^conv, discard: false, state: state} = s
      when state in ["ended", "stopped"] ->
        case summarise(conv, s) do
          {:error, reason} = err ->
            Logger.warning("call transcribe: summary failed: #{inspect(reason)}")
            if attempt == 1, do: say(conv, s, failed_text())
            err

          other ->
            other
        end

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
        say(conv, s, short_text(total, map_size(by_speaker)))
        :ok

      # v1.42 §38: with RISI_ACTION_ITEMS on, the extraction makes the action-items cards.
      # PART 9: `canary` = a dev user among the call's speakers. P0 2026-10-14: no action
      # items → the §27 discussion summary as before (the call always gets its summary).
      RisiMe.Agent.ActionItems.on_for?(Map.keys(by_speaker)) ->
        case RisiMe.Agent.ActionItems.from_call(conv, s, rows) do
          :ok ->
            partial(conv, s)
            drop(conv, ids)
            :ok

          :no_items ->
            legacy(conv, s, rows, ids, by_speaker)

          other ->
            other
        end

      true ->
        legacy(conv, s, rows, ids, by_speaker)
    end
  end

  ## One-line notices (P0 2026-10-14: never silent)

  @doc false
  def short_text(total_ms, speakers) when speakers < @min_speakers and total_ms >= @min_speech_ms,
    do: "I only heard one person on this call, so there's no summary."

  def short_text(_total_ms, _speakers),
    do: "This call had too little speech to summarise."

  @doc false
  def unavailable_text(true),
    do:
      "I couldn't transcribe the rest of this call: the speech service was busy. I'll summarise what I have."

  def unavailable_text(false),
    do: "I couldn't transcribe this call: the speech service was busy."

  @doc false
  def failed_text,
    do: "I couldn't summarise this call just now. I'll try again from what I transcribed."

  @doc false
  def partial_text(min),
    do: "Summary of the first #{min} min; transcription was turned off after that."

  # A transcript stopped while the call went on (`stopped`, not `ended`): say which part the
  # summary covers.
  defp partial(conv, %Session{state: "stopped"} = s) do
    case duration_s(s) do
      sec when is_integer(sec) -> say(conv, s, partial_text(max(div(sec + 30, 60), 1)))
      _ -> :ok
    end
  end

  defp partial(_conv, _s), do: :ok

  @doc false
  def say(conv, s, text) do
    risi = %{
      "kind" => "answer",
      "request_id" => s.session_id,
      "answer" => text,
      "refs" => [],
      "confidence" => 1.0,
      "call_ref" => nil,
      "notify" => []
    }

    case RisiMe.Agent.Out.post(conv, text, risi) do
      {:ok, _} -> :ok
      other -> Logger.warning("call transcribe: notice not posted: #{inspect(other)}")
    end

    :ok
  rescue
    e -> Logger.warning("call transcribe: notice failed: #{inspect(e.__struct__)}")
  end

  defp legacy(conv, s, rows, ids, by_speaker) do
    call = %{
      call_id: s.call_id,
      # P0 2026-10-15: names the session in a "part of this call" line.
      session_id: s.session_id,
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
        partial(conv, s)
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
