defmodule RisiMe.Agent.TTS.Ratings do
  @moduledoc """
  Voice-quality ratings from the admin console's Voices Test (2026-10-14; decision 085's Index for
  the Voice agent). After a test, the admin rates each voice's reply 👍 or 👎. Each rating is one
  append-only audit entry (`tts_rating`, target = the voice id, after = `"up first_ms=… total_ms=…
  load_ms=…"`: the score and that reply's latency, never the sentence), and the day's ratings are
  summed into the Voice agent's scoreboard row in `risi_agent_scores`:

      agent = "voice", source = "live_tts", n = ratings, acceptance = 👍 ÷ n,
      p50_ms / p95_ms = time to first audio of the rated replies,
      detail = %{"voices" => %{voice_id => %{"up", "down", "p50_ms"}}}

  `source = "live_tts"` is the *live* half of the Voice agent's TTS Index (decision 085:
  Index = 0.7 × Eval + 0.3 × Live), not the Index itself, so `RisiMe.Agent.VoiceAuto` (which looks
  for `index_tts`) still says no Index exists and Auto keeps today's route.
  """
  import Ecto.Query

  alias RisiMe.Admin
  alias RisiMe.Admin.AuditEntry
  alias RisiMe.Repo

  @action "tts_rating"
  @scores ~w(up down)

  @doc """
  Records admin `by`'s `score` (`"up"` | `"down"`) for `voice_id`'s reply with its latency
  (`%{first_audio_ms, total_ms, load_ms}`) and refreshes today's scoreboard row.
  """
  def rate(by, voice_id, score, latency \\ %{})

  def rate(by, voice_id, score, latency)
      when is_binary(voice_id) and score in @scores and is_map(latency) do
    after_ =
      Enum.join(
        [
          score,
          "first_ms=#{int(latency[:first_audio_ms])}",
          "total_ms=#{int(latency[:total_ms])}",
          "load_ms=#{int(latency[:load_ms])}"
        ],
        " "
      )

    :ok = Admin.audit(by, @action, voice_id, nil, after_)
    refresh(Date.utc_today())
  end

  def rate(_, _, _, _), do: {:error, :invalid}

  defp int(n) when is_integer(n) and n >= 0, do: n
  defp int(_), do: 0

  @doc "Recomputes and upserts `day`'s `live_tts` row from the audit log; returns it (or nil)."
  def refresh(%Date{} = day) do
    case compute(day) do
      nil ->
        nil

      row ->
        Repo.insert_all("risi_agent_scores", [Map.put(row, :updated_at, DateTime.utc_now())],
          on_conflict: {:replace, [:n, :acceptance, :p50_ms, :p95_ms, :detail, :updated_at]},
          conflict_target: [:day, :agent, :source]
        )

        row
    end
  end

  @doc "`day`'s row (no write), or nil when nothing was rated that day."
  def compute(%Date{} = day) do
    from_ = DateTime.new!(day, ~T[00:00:00], "Etc/UTC")
    to_ = DateTime.add(from_, 86_400, :second)

    ratings =
      Repo.all(
        from e in AuditEntry,
          where: e.action == @action and e.at >= ^from_ and e.at < ^to_,
          select: {e.target, e.after}
      )
      |> Enum.flat_map(&parse/1)

    if ratings == [] do
      nil
    else
      ups = Enum.count(ratings, &(&1.score == "up"))
      firsts = Enum.map(ratings, & &1.first_ms)

      voices =
        ratings
        |> Enum.group_by(& &1.voice)
        |> Map.new(fn {v, rs} ->
          {v,
           %{
             "up" => Enum.count(rs, &(&1.score == "up")),
             "down" => Enum.count(rs, &(&1.score == "down")),
             "p50_ms" => quantile(Enum.map(rs, & &1.first_ms), 0.5)
           }}
        end)

      %{
        day: day,
        agent: "voice",
        source: "live_tts",
        n: length(ratings),
        acceptance: ups / length(ratings),
        p50_ms: quantile(firsts, 0.5),
        p95_ms: quantile(firsts, 0.95),
        incidents: 0,
        detail: %{"voices" => voices}
      }
    end
  end

  @doc false
  def parse({voice, after_}) when is_binary(voice) and is_binary(after_) do
    case String.split(after_, " ", trim: true) do
      [score | kvs] when score in @scores ->
        kv =
          Map.new(kvs, fn s ->
            case String.split(s, "=", parts: 2) do
              [k, v] -> {k, v}
              [k] -> {k, ""}
            end
          end)

        [%{voice: voice, score: score, first_ms: to_int(kv["first_ms"])}]

      _ ->
        []
    end
  end

  def parse(_), do: []

  defp to_int(nil), do: 0

  defp to_int(s) do
    case Integer.parse(s) do
      {n, ""} -> n
      _ -> 0
    end
  end

  defp quantile([], _), do: nil

  defp quantile(xs, p) do
    xs = Enum.sort(xs)
    Enum.at(xs, min(length(xs) - 1, trunc(p * length(xs))))
  end
end
