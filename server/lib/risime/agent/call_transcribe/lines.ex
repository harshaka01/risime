defmodule RisiMe.Agent.CallTranscribe.Lines do
  @moduledoc """
  The three Risi kinds of contract v1.39 §36.5, as `{body, risi}` pairs for
  `RisiMe.Agent.Out.post/4` in the Official conversation:

    * `call_transcribe`: the consent line, once on `on` and once per final transition;
    * `call_transcript`: one merged speaker turn (silent);
    * `call_transcript_compare`: one chunk, every model side by side (developer compare mode).

  Names in `body` are the server's display names and times are in the chat's zone; the phone
  renders its own. Nothing here logs text.
  """
  alias RisiMe.Agent.{Clock, MadeBy, Secretary}
  alias RisiMe.Agent.CallTranscribe.Session

  @max_text 2000

  @doc "The consent line of session `s` (its current state)."
  def consent(%Session{} = s) do
    names = names(s.conversation_id)
    tz = Secretary.chat_tz(s.conversation_id)

    risi = %{
      "kind" => "call_transcribe",
      "call_id" => s.call_id,
      "session_id" => s.session_id,
      "state" => s.state,
      "requested_by" => s.requested_by,
      "consents" =>
        Enum.map(s.participants, fn p ->
          %{"user_id" => p["user_id"], "consent" => p["consent"], "at" => p["at"]}
        end),
      "by" => s.by,
      "reason" => s.reason,
      "discard" => s.discard,
      "started_at" => Clock.ts(s.started_at),
      "ended_at" => Clock.ts(s.ended_at),
      "compare" => s.compare,
      "call_ref" => nil,
      "made_by" => MadeBy.rule(),
      "notify" => []
    }

    {consent_body(s, names, tz), risi}
  end

  defp consent_body(%Session{state: "on"} = s, names, tz) do
    others =
      s.participants
      |> Enum.reject(&(&1["user_id"] == s.requested_by))
      |> Enum.map(&name(names, &1["user_id"]))

    "#{name(names, s.requested_by)} asked Risi to transcribe this call; " <>
      "#{Enum.join(others, ", ")} allowed (#{hm(s.started_at, tz)}). " <>
      "Audio is sent to Risi for transcription."
  end

  defp consent_body(%Session{state: "declined"} = s, names, tz),
    do: "#{name(names, s.by)} declined transcription (#{hm(s.ended_at, tz)})."

  defp consent_body(%Session{state: "expired"} = s, _names, tz),
    do: "No answer to the transcription request (#{hm(s.requested_at, tz)})."

  defp consent_body(%Session{state: "stopped", by: by} = s, names, tz) when is_binary(by) do
    if s.discard,
      do:
        "#{name(names, by)} stopped transcription and deleted the transcript " <>
          "(#{hm(s.ended_at, tz)}).",
      else: "#{name(names, by)} stopped transcription (#{hm(s.ended_at, tz)})."
  end

  defp consent_body(%Session{state: "stopped"} = s, _names, tz),
    do: "Transcription stopped#{why(s.reason)} (#{hm(s.ended_at, tz)})."

  defp consent_body(%Session{state: "ended"} = s, _names, tz),
    do:
      "Transcription ended with the call " <>
        "(#{hm(s.started_at || s.requested_at, tz)}–#{hm(s.ended_at, tz)})."

  defp why("official_off"), do: ": Official was turned off"
  defp why("switch_off"), do: ": call transcription was turned off on this server"
  defp why("unavailable"), do: ": the speech service is unavailable"
  defp why(_), do: ""

  @doc """
  One merged turn (§36.5): `turn` = `%{n, speaker, start, end, texts, language, model}` and,
  v1.40 §36.11, `romanised` (true → the optional `"romanised": true`).
  """
  def transcript(%Session{} = s, turn) do
    names = names(s.conversation_id)
    tz = Secretary.chat_tz(s.conversation_id)
    text = turn.texts |> Enum.join(" ") |> String.trim() |> String.slice(0, @max_text)

    risi = %{
      "kind" => "call_transcript",
      "call_id" => s.call_id,
      "session_id" => s.session_id,
      "turn" => turn.n,
      "speaker" => turn.speaker,
      "start" => Clock.ts(turn.start),
      "end" => Clock.ts(turn.end),
      "text" => text,
      "language" => turn.language,
      "model" => turn.model,
      "call_ref" => nil,
      "made_by" => %{
        "model" => turn.model,
        "provider" => "risime",
        "at" => Clock.ts(Clock.now()),
        "also" => []
      },
      "notify" => []
    }

    # v1.40 §36.11: only when a Sinhala chunk stayed out of Sinhala script after the retry.
    risi = if Map.get(turn, :romanised) == true, do: Map.put(risi, "romanised", true), else: risi

    {"#{name(names, turn.speaker)} #{hm(turn.start, tz)}: #{text}", risi}
  end

  @doc """
  One chunk in compare mode (§36.5): `c` = `%{seq, speaker, start, end, language_detected,
  chosen, results}`; the chosen model's line comes first in `body`.
  """
  def compare(%Session{} = s, c) do
    results =
      Enum.map(c.results, fn r ->
        %{
          "model" => r.model,
          "text" => r.text && String.slice(r.text, 0, @max_text),
          "language" => r.language,
          "elapsed_ms" => r.elapsed_ms,
          "error" => r.error
        }
        |> put_script(r)
      end)

    {chosen, rest} = Enum.split_with(results, &(&1["model"] == c.chosen))

    body =
      (chosen ++ rest)
      |> Enum.map_join("\n", fn r ->
        "#{r["model"]} (#{r["elapsed_ms"]} ms): #{r["text"] || r["error"] || ""}"
      end)

    risi = %{
      "kind" => "call_transcript_compare",
      "call_id" => s.call_id,
      "session_id" => s.session_id,
      "seq" => c.seq,
      "speaker" => c.speaker,
      "start" => Clock.ts(c.start),
      "end" => Clock.ts(c.end),
      "language_detected" => c.language_detected,
      "chosen" => c.chosen,
      "results" => results,
      "call_ref" => nil,
      "made_by" => %{
        "model" => nil,
        "provider" => "risime",
        "at" => Clock.ts(Clock.now()),
        "also" =>
          Enum.map(
            results,
            &%{"model" => &1["model"], "provider" => "risime", "task" => "transcribe"}
          )
      },
      "notify" => []
    }

    {body, risi}
  end

  # v1.40 §36.11: the service's Sinhala script check, when it made one (a Sinhala chunk).
  defp put_script(m, r) do
    m =
      case Map.get(r, :script) do
        %{} = sc ->
          Map.put(m, "script", %{
            "expected" => sc.expected,
            "sinhala_chars" => sc.sinhala_chars,
            "latin_chars" => sc.latin_chars,
            "ok" => sc.ok
          })

        _ ->
          m
      end

    case Map.get(r, :romanised) do
      b when is_boolean(b) -> Map.put(m, "romanised", b)
      _ -> m
    end
  end

  defp names(conv), do: Map.new(Secretary.members(conv), &{&1.user_id, &1.name})

  defp name(names, id), do: Map.get(names, id) || "A member"

  defp hm(nil, _tz), do: "--:--"
  defp hm(dt, tz), do: dt |> Clock.local(tz) |> Calendar.strftime("%H:%M")
end
