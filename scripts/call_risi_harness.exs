# Call Risi end-to-end harness driver (decision 090). Run by scripts/call-risi-harness, which starts the
# throwaway loopback LiveKit and passes everything in the environment. It runs the REAL session
# (RisiMe.Agent.CallRisi.Session), the REAL media worker (Port), the REAL speech service (whisper on
# 127.0.0.1:8200, through RisiMe.Agent.Speech and SpeechRoutes on Auto), risi-l1 streamed (8100) and
# risime-tts (8230); a headless caller (infra/voice/harness_caller.py) plays synthetic WAVs.
# Only the §25 tool turn is a stand-in (HarnessTurn: answers after HARNESS_TOOL_MS, default 1200 ms),
# so the filler path is timed without touching any real calendar. Nothing is posted to any chat.
#   MIX_ENV=test mix run --no-start ../scripts/call_risi_harness.exs
defmodule HarnessRuns do
  def record(row) do
    send(:persistent_term.get(:harness_pid), {:run_row, row})
    :ok
  end
end

defmodule HarnessTurn do
  # A calendar question (the brain passes its range as env "__schedule") gets synthetic facts, like
  # RisiMe.Agent.RisiItems.voice_facts/4 (P0 2026-10-15): busy 10:00–11:00 every day of the range,
  # 14:00–15:00 on the first day, a Risi reminder "Call Ruwan" at 17:00 on the second day. The
  # answer text is the v2 card's (head line + footer), which must never be spoken.
  def run(_conv, env, request_id, _user) do
    {pid, _} = Process.get(:risi_voice_sink)
    Process.sleep(String.to_integer(System.get_env("HARNESS_TOOL_MS", "1200")))

    case env["__schedule"] do
      %{from: from, to: to} ->
        days = for i <- 0..(max(Date.diff(to, from), 1) - 1), do: Date.add(from, i)

        ev = fn kind, title, d, {h, m}, mins ->
          s = NaiveDateTime.new!(d, Time.new!(h, m, 0))
          e = if mins, do: NaiveDateTime.add(s, mins * 60)

          %{kind: kind, title: title, start: s, end: e, all_day: false,
            start_utc: NaiveDateTime.to_iso8601(s) <> "Z", end_utc: e && NaiveDateTime.to_iso8601(e) <> "Z", tags: [kind]}
        end

        evs =
          days
          |> Enum.with_index()
          |> Enum.flat_map(fn {d, i} ->
            [ev.("busy", nil, d, {10, 0}, 60)] ++
              if(i == 0, do: [ev.("busy", nil, d, {14, 0}, 60)], else: []) ++
              if(i == 1, do: [ev.("reminder", "Call Ruwan", d, {17, 0}, nil)], else: [])
          end)
          |> Enum.sort_by(&NaiveDateTime.to_iso8601(&1.start))

        facts = %{ok?: true, today: Date.utc_today(), from: from, to: to, events: evs, unread: []}
        send(pid, {:risi_voice_facts, request_id, facts})
        send(pid, {:risi_voice_answer, request_id, Calendar.strftime(from, "%a %-d %b") <> ":\n\nFrom: your phone calendars · Risi"})

      _ ->
        send(pid, {:risi_voice_answer, request_id, "Tomorrow you have a team meeting at ten, and nothing after lunch."})
    end

    :ok
  end
end

defmodule HarnessLog do
  def record(_), do: :ok
end

env = fn k -> System.get_env(k) || raise "#{k} missing" end
for app <- [:logger, :telemetry, :jason, :req], do: {:ok, _} = Application.ensure_all_started(app)
Logger.configure(level: :warning)

lk_port = env.("LK_PORT")

Application.put_env(:risime, :livekit,
  url: "ws://127.0.0.1:#{lk_port}",
  api_url: "http://127.0.0.1:#{lk_port}",
  api_key: env.("LIVEKIT_API_KEY"),
  api_secret: env.("LIVEKIT_API_SECRET")
)

Application.put_env(:risime, :livekit_api, RisiMe.Calls.LiveKit.Twirp)
Application.put_env(:risime, :risi_speech, RisiMe.Agent.Speech.Http)
Application.put_env(:risime, :risi_speech_http, url: "http://127.0.0.1:8200", api_key: env.("WHISPER_API_KEY"))

Application.put_env(:risime, :risi_llm,
  url: "http://127.0.0.1:8100/v1",
  api_key: System.get_env("LLM_API_KEY"),
  fallback: false
)

Application.put_env(:risime, :risi_call_risi_config,
  tts_url: "http://127.0.0.1:8230",
  python: env.("VOICE_PYTHON"),
  worker: env.("VOICE_WORKER"),
  livekit_url: "ws://127.0.0.1:#{lk_port}",
  eot_ms: String.to_integer(System.get_env("HARNESS_EOT_MS", "350"))
)

autos = for l <- ~w(si ta en mixed), k <- ["speech_route_", "tts_voice_"], into: %{}, do: {k <> l, "auto"}

# HARNESS_TTS_VOICE_EN=<engine:voice> (2026-10-15 voice pick): Risi's English voice for this run; a lab
# engine's voice is also made live (RISI_CALL_LIVE_ENGINES), so the worker uses the TTS lab
# (RISI_TTS_LAB_URL) and its Kokoro fallback.
autos =
  case System.get_env("HARNESS_TTS_VOICE_EN") do
    v when is_binary(v) and v != "" ->
      [engine | _] = String.split(v, ":", parts: 2)
      cfg = Application.get_env(:risime, :risi_call_risi_config, [])
      Application.put_env(:risime, :risi_call_risi_config, Keyword.put(cfg, :live_engines, [engine]))
      Map.put(autos, "tts_voice_en", v)

    _ ->
      autos
  end

Application.put_env(:risime, :admin_settings, autos)
Application.put_env(:risime, :risi_agent_flags, %{"RISI_CALL_RISI" => "on", "RISI_AGENTS" => "on"})
Application.put_env(:risime, :risi_call_runs, HarnessRuns)
Application.put_env(:risime, :risi_call_turn, HarnessTurn)
Application.put_env(:risime, :risi_learning_log, HarnessLog)
:persistent_term.put(:harness_pid, self())
# The test env's fixed clock (config/test.exs) would give the model a wrong "now".
Application.delete_env(:risime, :risi_now)

{:ok, _} = RisiMe.Agent.CallRisi.Supervisor.start_link()
alias RisiMe.Calls.LiveKit
{:ok, config} = LiveKit.config()
room = "harness-" <> Base.url_encode64(:crypto.strong_rand_bytes(9), padding: false)

{:ok, _} =
  LiveKit.api().create_room(config, room, %{
    max_participants: 2,
    empty_timeout: 60,
    departure_timeout: 20,
    metadata: Jason.encode!(%{"c" => "grp:harness", "m" => "audio", "k" => "risi"})
  })

user = Ecto.UUID.generate()
dev = Ecto.UUID.generate()
{token, _} = LiveKit.participant_token(config, LiveKit.identity(user, dev), room, "audio", System.os_time(:second))
me = self()

{:ok, _} =
  RisiMe.Agent.CallRisi.start(%{
    conv: "grp:harness",
    call_id: Ecto.UUID.generate(),
    user_id: user,
    device_id: dev,
    room: room,
    metrics_to: me,
    tools?: true,
    # No database here: the call tools "what can you do?" lists, and no §41.8 titles device.
    caps: ~w(risi_calendar_check risi_items set_reminder memory_search),
    cal?: false,
    post: fn _conv, _body, risi -> send(me, {:transcript, risi}) end
  })

caller_args =
  ["-I", env.("CALLER"), "--url", "ws://127.0.0.1:#{lk_port}", "--token", token] ++
    if(b = System.get_env("BARGE_WAV"), do: ["--barge", b], else: []) ++
    if(e = System.get_env("ECHO_DB"), do: ["--echo-db", e], else: []) ++
    ["--rounds", System.get_env("ROUNDS", "1")] ++
    String.split(env.("CALLER_WAVS"), ",")

task = Task.async(fn -> System.cmd(env.("VOICE_PYTHON"), caller_args, stderr_to_stdout: false) end)
{out, 0} = Task.await(task, 600_000)
caller = out |> String.split("\n", trim: true) |> List.last() |> Jason.decode!()

collect = fn collect, acc ->
  receive do
    {:call_risi_turn, m} -> collect.(collect, Map.update(acc, :turns, [m], &[m | &1]))
    {:run_row, r} -> collect.(collect, Map.update(acc, :runs, [r], &[r | &1]))
    {:transcript, t} -> collect.(collect, Map.put(acc, :transcript, t))
  after
    6_000 -> acc
  end
end

acc = collect.(collect, %{})
turns = acc |> Map.get(:turns, []) |> Enum.reverse()

pct = fn xs, p ->
  xs = xs |> Enum.reject(&is_nil/1) |> Enum.sort()
  if xs == [], do: nil, else: Enum.at(xs, min(length(xs) - 1, trunc(p * length(xs))))
end

stage = fn key ->
  xs = Enum.map(turns, &Map.get(&1, key))
  "#{key}: p50=#{pct.(xs, 0.5)} p95=#{pct.(xs, 0.95)} n=#{Enum.count(xs, &(&1 != nil))}"
end

client = Enum.map(caller["turns"], & &1["first_audio_ms"])

IO.puts("== Call Risi harness (#{length(caller["turns"])} turns, echo_db=#{inspect(caller["echo_db"])}) ==")
IO.puts("client first audio after the caller stopped (ms): p50=#{pct.(client, 0.5)} p95=#{pct.(client, 0.95)} all=#{inspect(client)}")
# Silences inside Risi's answers as the caller heard them (>= 100 ms; a sentence pause is ~150 ms,
# anything over 300 ms is dead air between pieces).
gaps = Enum.flat_map(caller["turns"], &(&1["gaps_ms"] || []))

IO.puts(
  "gaps inside answers (ms): p50=#{pct.(gaps, 0.5)} p95=#{pct.(gaps, 0.95)} max=#{Enum.max(gaps, fn -> nil end)} " <>
    "n=#{length(gaps)} over_300=#{Enum.count(gaps, &(&1 > 300))}"
)

for k <- ~w(after_end_ms eot_wait_ms stt_ms first_token_ms first_piece_ms tts_first_ms)a, do: IO.puts("server " <> stage.(k))
IO.puts("routes: #{inspect(Enum.frequencies_by(turns, & &1.route))} statuses: #{inspect(Enum.frequencies_by(turns, & &1.status))}")
IO.puts("stt models: #{inspect(Enum.frequencies_by(turns, & &1.stt_model))}")
IO.puts("per turn: " <> Jason.encode!(Enum.map(turns, &Map.take(&1, ~w(utt route after_end_ms stt_ms first_token_ms tts_first_ms)a))))
IO.puts("caller: " <> Jason.encode!(caller))

if t = acc[:transcript] do
  IO.puts("transcript lines (synthetic test speech):")
  for l <- t["lines"], do: IO.puts("  #{l["who"]}: #{l["text"]}")
end
