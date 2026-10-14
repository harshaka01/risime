defmodule RisiMe.Agent.SpeechStats do
  @moduledoc """
  Today's speech-to-text numbers for the admin console's Speech page (incident 2026-10-14):
  codes, counts and timings only, never text.

  * `models/1`: per model, from the learning log's `transcribe` rows of the UTC day (voice notes
    and call chunks): calls, ok, failed (`failed` / `none`), p50 / p95 latency.
  * `notes/1`: voice notes end to end, from `risi_agent_runs` (agent `voice`, mode `stt`, written
    by `RisiMe.Agent.Speech.transcribe_note/2`): per answering model, n, p50 / p95, and the
    fallbacks (`timeout_phone`, `timeout_ctc`, …) and failures.
  """
  import Ecto.Query

  alias RisiMe.Agent.LearningLog
  alias RisiMe.Agents.Runs
  alias RisiMe.Repo

  @doc "Per-model rows of `day` (default today, UTC), busiest first."
  def models(day \\ Date.utc_today()) do
    rows =
      for b <- 0..(LearningLog.buckets() - 1),
          r <- safe_list(day, b),
          (r[:task] || r["task"]) == "transcribe",
          do: %{
            model: r[:model] || r["model"] || "none",
            status: r[:status] || r["status"],
            latency_ms: r[:latency_ms] || r["latency_ms"]
          }

    rows
    |> Enum.group_by(& &1.model)
    |> Enum.map(fn {model, rs} ->
      lat = for r <- rs, r.status == "ok", is_integer(r.latency_ms), do: r.latency_ms

      %{
        model: model,
        n: length(rs),
        ok: Enum.count(rs, &(&1.status == "ok")),
        failed: Enum.count(rs, &(&1.status == "failed")),
        p50_ms: Runs.percentile(lat, 0.5),
        p95_ms: Runs.percentile(lat, 0.95)
      }
    end)
    |> Enum.sort_by(&(-&1.n))
  end

  @doc """
  Voice notes of `day` end to end: `%{n, fallbacks, failed, by_model: [...], by_reason: %{}}`.
  """
  def notes(day \\ Date.utc_today()) do
    from = DateTime.new!(day, ~T[00:00:00], "Etc/UTC")
    to = DateTime.add(from, 86_400, :second)

    rows =
      Repo.all(
        from r in "risi_agent_runs",
          where: r.agent == "voice" and r.mode == "stt" and r.at >= ^from and r.at < ^to,
          select: %{
            model: r.decision,
            status: r.status,
            fell_back: r.fell_back,
            reason: r.reason,
            latency_ms: r.latency_ms
          }
      )

    by_model =
      rows
      |> Enum.group_by(&(&1.model || "none"))
      |> Enum.map(fn {model, rs} ->
        lat = rs |> Enum.map(& &1.latency_ms) |> Enum.reject(&is_nil/1)

        %{
          model: model,
          n: length(rs),
          p50_ms: Runs.percentile(lat, 0.5),
          p95_ms: Runs.percentile(lat, 0.95),
          fallbacks: Enum.count(rs, & &1.fell_back)
        }
      end)
      |> Enum.sort_by(&(-&1.n))

    %{
      n: length(rows),
      fallbacks: Enum.count(rows, &(&1.fell_back and &1.status == "ok")),
      failed: Enum.count(rows, &(&1.status != "ok")),
      timeouts: Enum.count(rows, &(is_binary(&1.reason) and &1.reason =~ ~r/^timeout/)),
      by_model: by_model,
      by_reason: rows |> Enum.filter(& &1.reason) |> Enum.frequencies_by(& &1.reason)
    }
  rescue
    _ -> %{n: 0, fallbacks: 0, failed: 0, timeouts: 0, by_model: [], by_reason: %{}}
  end

  defp safe_list(day, b) do
    LearningLog.list_by_day(day, b)
  rescue
    _ -> []
  catch
    :exit, _ -> []
  end
end
