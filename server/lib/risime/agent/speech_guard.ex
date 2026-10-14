defmodule RisiMe.Agent.SpeechGuard do
  @moduledoc """
  Live transcription first (incident 2026-10-14, docs/status/speech-eval-guard.md): when a live
  voice note is slow, falls back or fails, the server writes the shared **pause file** that the
  speech eval and training tools check (`scripts/speech-guard`, `scripts/asr-eval.py`, the
  omniASR eval service): they stop starting work until it expires.

  * **Slow** = the note took more than `max(8 s, 1.5 × its duration)`, or any fallback / failure.
  * **The file** (`RISI_SPEECH_PAUSE_FILE`, default `~/risime-logs/speech-guard/speech-eval.pause`, a directory of its own so
    the omniASR eval container can mount it read-only) holds one
    line `until=<unix s> reason=<code> by=risime-server`; each slow note extends `until` to now +
    10 min (`RISI_SPEECH_PAUSE_S`). A file written by hand without `until=` (an indefinite manual
    pause) is never touched. Codes only: never text, never ids.
  * `config :risime, :risi_speech_pause_file, false` turns it off (tests).
  """
  require Logger

  @default_pause_s 600

  @doc "Observes one live note (`Speech.transcribe_note/2`); pauses evals when it was slow."
  def observe_live(result, elapsed_ms, duration_ms) do
    case reason(result, elapsed_ms, duration_ms) do
      nil -> :ok
      code -> pause(code)
    end
  rescue
    _ -> :ok
  end

  @doc false
  def reason({:error, _}, _elapsed, _dur), do: "live_failed"
  def reason({:ok, %{fallback: f}}, _elapsed, _dur) when is_binary(f), do: "live_fallback_" <> f

  def reason({:ok, _}, elapsed, dur) when is_integer(elapsed) do
    if elapsed > slow_ms(dur), do: "live_slow"
  end

  def reason(_, _, _), do: nil

  @doc "The slow threshold of a note: `max(8 s, 1.5 × duration)`."
  def slow_ms(dur) when is_integer(dur) and dur > 0, do: max(8_000, div(dur * 3, 2))
  def slow_ms(_), do: 8_000

  @doc "Writes (or extends) the pause file for `code`, unless a manual pause is in place."
  def pause(code) do
    case path() do
      nil ->
        :ok

      path ->
        now = System.os_time(:second)
        until = now + pause_s()

        case File.read(path) do
          {:ok, body} ->
            case Regex.run(~r/until=(\d+)/, body) do
              # A manual pause (no `until=`) stays as it is.
              nil -> :ok
              [_, old] -> write(path, max(until, String.to_integer(old)), code)
            end

          {:error, _} ->
            write(path, until, code)
        end
    end
  end

  @doc "True while the pause file says paused (manual, or `until` in the future)."
  def paused? do
    with p when is_binary(p) <- path(),
         {:ok, body} <- File.read(p) do
      case Regex.run(~r/until=(\d+)/, body) do
        nil -> true
        [_, until] -> String.to_integer(until) > System.os_time(:second)
      end
    else
      _ -> false
    end
  end

  defp write(path, until, code) do
    File.mkdir_p(Path.dirname(path))
    File.write(path, "until=#{until} reason=#{code} by=risime-server\n")
    Logger.info("speech guard: evals paused until #{until} (#{code})")
  rescue
    _ -> :ok
  end

  @doc "The pause file's path, or nil when off."
  def path do
    case Application.get_env(:risime, :risi_speech_pause_file) do
      false ->
        nil

      p when is_binary(p) and p != "" ->
        p

      _ ->
        case System.get_env("RISI_SPEECH_PAUSE_FILE") do
          p when is_binary(p) and p != "" ->
            p

          _ ->
            Path.join([
              System.user_home() || "/tmp",
              "risime-logs",
              "speech-guard",
              "speech-eval.pause"
            ])
        end
    end
  end

  defp pause_s do
    case Integer.parse(System.get_env("RISI_SPEECH_PAUSE_S") || "") do
      {n, ""} when n > 0 -> n
      _ -> @default_pause_s
    end
  end
end
