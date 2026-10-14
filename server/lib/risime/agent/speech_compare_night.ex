defmodule RisiMe.Agent.SpeechCompareNight do
  @moduledoc """
  The night run of the speech compare mode (Harsha 2026-10-14 09:54 SLT): candidates that were
  "skipped: memory" on a compared note are **never dropped**. They run on the note's sealed audio
  (`SpeechGold`) after 16:30 UTC, and the results go to the note's recipients right after their
  morning brief.

  * **`run`** (Oban cron 16:45 UTC): only between 16:30 and 00:30 UTC. Only while the eval guard says go:
    no pause file (`SpeechGuard.paused?/0`), the live speech service healthy, MemAvailable ≥
    5 120 MB (`:risi_night_min_mb`). It holds the **`scripts/heavy` lock** (`flock` on
    `~/risime-logs/heavy.lock`) while it works, so it never runs next to another heavy job.
    Otherwise it snoozes 20 min. Each note's `pending_models` are run (7B streamed allowed), the
    candidates and scores are updated (against the label, else Google), and the note is marked for
    delivery.
  * **`deliver`** (Oban cron 01:35 UTC = 07:05 SLT, after the 07:00 brief): one short post per
    recipient in their Risi chat with the overnight CERs. Numbers only. Notes are marked delivered.
  * Codes and numbers only in logs.
  """
  use Oban.Worker, queue: :maintenance, max_attempts: 40

  require Logger

  alias RisiMe.Agent.{SpeechCompare, SpeechGold, SpeechGuard}

  @impl Oban.Worker
  def perform(%Oban.Job{args: %{"kind" => "run"}}) do
    cond do
      not night?() -> :ok
      not go?() -> {:snooze, 1_200}
      true -> with_heavy_lock(&run_all/0)
    end
  end

  def perform(%Oban.Job{args: %{"kind" => "deliver"}}), do: deliver()

  @doc "Between 16:30 and 00:30 UTC (CLAUDE.md rule 10's night window), or always in tests."
  def night?(now \\ DateTime.utc_now()) do
    Application.get_env(:risime, :risi_night_any_time, false) or
      (fn {h, m} -> h >= 17 or (h == 16 and m >= 30) or (h == 0 and m < 30) end).(
        {now.hour, now.minute}
      )
  end

  defp go? do
    not SpeechGuard.paused?() and RisiMe.Agent.Speech.healthy?() and
      SpeechCompare.mem_available_mb() >= Application.get_env(:risime, :risi_night_min_mb, 5_120)
  rescue
    _ -> false
  end

  # flock on the scripts/heavy lock: held while the port lives (`cat` waits on stdin).
  defp with_heavy_lock(fun) do
    case Application.get_env(
           :risime,
           :risi_heavy_lock,
           Path.join([System.user_home() || "/tmp", "risime-logs", "heavy.lock"])
         ) do
      false ->
        fun.()

      lock ->
        flock = System.find_executable("flock")

        if flock do
          port =
            Port.open({:spawn_executable, flock}, [
              :binary,
              :exit_status,
              args: [lock, "sh", "-c", "echo locked; cat"]
            ])

          receive do
            {^port, {:data, "locked" <> _}} ->
              try do
                fun.()
              after
                Port.close(port)
              end
          after
            1_800_000 ->
              Port.close(port)
              {:snooze, 1_200}
          end
        else
          {:snooze, 1_200}
        end
    end
  end

  @doc false
  def run_all do
    notes = for m <- SpeechGold.all_meta(), (m["pending_models"] || []) != [], do: m

    for m <- notes do
      run_note(m)
    end

    Logger.info("speech compare night: #{length(notes)} notes")
    :ok
  end

  defp run_note(m) do
    id = m["note_id"]
    lang = m["lang"] || "si"

    with {:ok, audio} <- SpeechGold.audio(id) do
      google =
        Enum.find_value(m["candidates"] || [], fn c ->
          c["model"] == "phone-google" && c["text"]
        end)

      ran =
        for model <- m["pending_models"],
            {label, where, ^model} <- [SpeechCompare.spec(lang, model)] do
          SpeechCompare.candidate(label, where, model, audio, m["duration_ms"], google, lang,
            night: true
          )
          |> Map.new(fn {k, v} -> {to_string(k), v} end)
        end

      done = for c <- ran, c["status"] != "skipped: memory", do: c["model"]

      SpeechGold.update(id, fn meta ->
        cands =
          Enum.map(meta["candidates"] || [], fn c ->
            Enum.find(ran, c, &(&1["model"] == c["model"]))
          end)

        meta
        |> Map.put("candidates", cands)
        |> Map.put("pending_models", (meta["pending_models"] || []) -- done)
        |> then(fn mm ->
          if is_binary(mm["label"]),
            do: Map.put(mm, "scores", SpeechCompare.scores(cands, mm["label"])),
            else: mm
        end)
        |> then(fn mm ->
          if google,
            do:
              Map.put(
                mm,
                "vs_google",
                SpeechCompare.scores(
                  Enum.reject(cands, &(&1["model"] in ["phone-google", "chat"])),
                  google
                )
              ),
            else: mm
        end)
        |> Map.put("night", %{
          "ran_at" => DateTime.to_iso8601(DateTime.utc_now()),
          "models" => done
        })
        |> Map.put("night_delivered", done == [])
      end)
    end
  rescue
    e -> Logger.warning("speech compare night: a note failed (#{inspect(e.__struct__)})")
  end

  @doc false
  def deliver do
    notes =
      for m <- SpeechGold.all_meta(),
          m["night_delivered"] == false,
          (get_in(m, ["night", "models"]) || []) != [],
          do: m

    by_user =
      Enum.reduce(notes, %{}, fn m, acc ->
        Enum.reduce(m["recipients"] || [], acc, fn r, a -> Map.update(a, r, [m], &[m | &1]) end)
      end)

    for {user, ms} <- by_user,
        SpeechCompare.post_on?(user),
        rc = RisiMe.RisiChat.active_id(user),
        rc != nil do
      lines =
        for m <- Enum.reverse(ms) do
          ref = if is_binary(m["label"]), do: "your correction", else: "Google"
          sc = if is_binary(m["label"]), do: m["scores"] || %{}, else: m["vs_google"] || %{}

          parts =
            for model <- get_in(m, ["night", "models"]) do
              case sc[model] do
                %{"cer" => c} ->
                  "#{model} CER #{round(c * 100)}%"

                _ ->
                  st =
                    Enum.find_value(m["candidates"] || [], "done", fn c ->
                      c["model"] == model && c["status"]
                    end)

                  "#{model} #{if st == "ok", do: "done (no reference yet)", else: st}"
              end
            end

          "• note of #{Float.round((m["duration_ms"] || 0) / 1000, 1)} s (vs #{ref}): #{Enum.join(parts, ", ")}"
        end

      SpeechCompare.post(rc, user, Enum.join(["Overnight speech compare results:" | lines], "\n"))
    end

    for m <- notes, do: SpeechGold.update(m["note_id"], &Map.put(&1, "night_delivered", true))
    :ok
  end
end
