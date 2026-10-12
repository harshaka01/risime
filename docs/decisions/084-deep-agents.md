# 084 — Risi becomes fast, self-learning deep agents

**Status:** accepted 2026-10-13 (Harsha, PART 6 "Risi becomes fast, self-learning deep agents", steps 0–2).
Risi role, through the fast lane (rule 12). **No wire change.** Server-only; every new path is behind a
flag that defaults to today's behaviour. Streaming of partial answers needs a wire change and is only a
proposal (`contract/proposals/2026-10-13-risi-streaming.md`).

## Why
Today Risi is one turn loop (`RisiMe.Agent.Turn`, the `risi_next_action` loop) plus a set of pipelines
(ledger extraction, summaries, memory ingest, calendar, voice/call action items) that each call risi-l1
directly. It works, but nothing measures it per job, nothing learns from feedback, and a faster path for
easy asks (one tool, one answer) has to go through the full loop. PART 6 turns these into **agents with a
goal, tools, memory, an eval set and a version of what they learned**, so each one can be measured, made
faster and taught, and switched back to today's code in seconds.

## No-regression rules (binding)
- Chats, calls, Risi chat, @Risi, Ledger, My promises, Calendar, Notes, summaries, transcription and backup
  keep working **exactly as now**. Agents are **added beside** today's modules; today's modules are wrapped,
  never rewritten in place.
- Every agent has a flag; every flag's default is today's path (`off`, or `shadow` where shadow only
  observes). The master switch `RISI_AGENTS=legacy` turns every agent off at once, without a deploy.
- **Parity tests** (`server/test/risime/agent/parity/`, tag `:risi_parity`, part of plain `mix test` and
  so of the normal server gate) pin today's inputs → outputs, cards and timings with the fake model. An
  agent may replace a path only while those tests pass unchanged.

## The agent model
### `RisiMe.Agents.Agent` (behaviour)
Every agent module declares:

| Field | Meaning |
|---|---|
| `name` | flag name (`router` → `RISI_AGENT_ROUTER`) |
| `type` | `:deep` (plans, many steps), `:specialist` (one job, few steps), `:on_device` |
| `goal` | one sentence, what "good" means (the eval set scores exactly this) |
| `tools` | names in the tool registry it may call |
| `memory` | what it reads/writes: `:none`, `:preferences` (L1), `:pgvector` (Risi memory), `:ledger` |
| `eval_set` | its golden set id (`server/test/risime/agent/golden/<id>.jsonl`) |
| `budget` | `%{p50_ms, p95_ms, hard_ms}` (see Speed budgets) |
| `learned_version` | the version of its learned state (prompt/few-shot set, LoRA adapter, rules) |
| `legacy` | today's module(s) it wraps or would replace |

plus `run(input, ctx) :: {:ok, output} | {:error, reason}`. Metrics are not a callback: the orchestrator
records them for every run (below), so no agent can forget them.

### Processes
- `RisiMe.Agents.Supervisor` (started at the end of the Risi agent tree, so it only runs with `RISI=on`)
  supervises one `RisiMe.Agents.Server` per registered agent (holds its loaded learned-state version and
  run counters; a crash restarts only that agent) and a `Task.Supervisor` for the runs themselves. Runs
  do not queue behind the agent's mailbox.
- **Long work goes to Oban** (queue `risi`, as today: ingest, nightly optimisation, scoreboard).
- **Agents call each other only through `RisiMe.Agents.Orchestrator.call/3`.** The orchestrator reads
  the flag, applies the latency budget, falls back to the legacy function, writes the run row and emits
  the activity events. No agent holds another agent's pid.
- Everything works when the supervisor isn't running (tests, `RISI=off`): the orchestrator then runs the
  agent inline under the same budget.

### One tool registry (MCP-style)
`RisiMe.Agents.ToolRegistry` lists every tool as an MCP-style descriptor `{name, description,
inputSchema, annotations: {readOnly, personal, where, skill}}`, built **from today's registries**
(`RisiMe.Agent.Tools.tools/0`: the turn tools, calendar, reminders, notes, memory search, exports…).
`call(name, args, ctx)` runs the existing tool's `authorize/3` and `run/2` unchanged. Nothing is
rewritten: an agent that wants "calendar_check" gets exactly the code a turn runs today, with the same
permission checks (authorised twice, skills, Private never reachable).

### agent-activity-v1 (live view)
Every orchestrated step emits one event, on `Phoenix.PubSub` topic `risi:agent_activity` and as the
telemetry event `[:risime, :agents, :activity]`:
```
{"v": "agent-activity-v1", "run_id", "agent", "mode": "shadow|canary|on|legacy",
 "kind": "start|step|decision|fallback|done", "status": "ok|error|timeout|over_budget|skipped",
 "step": str|null, "decision": str|null, "latency_ms": int|null,
 "learned_version": str, "user_ref": "<sha256(user id) first 12 hex>", "at": iso8601}
```
Never the raw prompt, the request text, a tool's args or an answer (decision 066/068 still hold). It is a
server-internal event: no client sees it, so it is not wire protocol. A future admin live view subscribes
to the topic.

## The agents
| Agent | Type | Goal | Wraps today |
|---|---|---|---|
| **Planner** | deep | multi-step asks: plan, call specialists, one answer, first useful line in 4 s | `Agent.Turn` loop |
| **Ledger** | deep | every real promise in Official chats becomes a correct card; no invented owner/date | `Ledger`, `Commitments`, `Discussion`, `RisiItems` |
| **Voice** | deep | calls and voice notes → transcript, summary and action items in the speaker's language | `CallTranscribe`, `Voice`, `ActionItems`, `Speech` |
| **Fast router** | specialist | send each ask to the right path in < 300 ms (rules) / < 1 s (model) | `ScheduleIntent`, `MemoryIntent` |
| **Calendar** | specialist | correct read/add/remove with the honesty rules | `Calendar*`, `CalendarTools` |
| **Memory** | specialist | find what was said/agreed; never answer beyond memory | `Memory`, `MemoryTools`, `MemoryIntent` |
| **Summary** | specialist | short, faithful summaries in the reader's language | `Secretary` summaries, `DailySummaries`, `PeriodSummary` |
| **Marketplace** | specialist | later (RisiWork) | — |
| **Digital twin** | on-device | learns its user's behaviour on the phone; never leaves the device unless twin sync is on | Android (later) |

Step 2 ships the **Fast router** (shadow). The others are registered specs (`status: :planned`) so the
scoreboard, flags and eval sets exist before their code does.

## Learning
- **L1 — preference memory (per user, online).** Small facts ("call me Harsha", "meetings never before
  10", "Shenika = Shenika Fernando") stored sealed, per user, in the existing facts/memory tables. The user
  can **see, edit and delete** every one (the facts endpoints of §35.3; a "What Risi learned about me"
  screen is an android follow-up). Used as context, never as training data for others.
- **L2 — nightly prompt / few-shot optimisation (per agent).** An Oban job in the GPU window picks
  candidate system-prompt variants and few-shot sets from accepted examples, scores each on the agent's
  held-out golden set with risi-l1, and proposes the best as a new `learned_version`.
- **L3 — weekly LoRA** of the small model (risi-l2, when it exists) in the **22:00–06:00 SLT GPU window**
  (16:30–00:30 UTC), only while no release gate runs (the same yield rule as `scripts/risi-eval`:
  MemAvailable ≥ need + 10 GB, killed under 6 GB). Never a LoRA of risi-l1 beside itself.

### Promotion rule (all levels)
A new learned state goes live only if **(a)** its score on the held-out golden set is **≥ the current
version's** (accuracy and hallucination checks), **and (b)** its p50/p95 latency on the same run is
**within the agent's budget**. Promotion writes the new `learned_version`; every run row and activity
event carries it. **Auto-rollback:** if the live error/fallback rate or p95 of the new version is worse
than the old one's over the first 200 runs (or 24 h), the previous version is restored automatically and
an incident is logged. Versions are kept, so a rollback is a pointer change.

### Training data
- **Learning is ON by default** for Official: Official messages, call transcripts and voice notes Risi
  already holds as a member, plus explicit feedback (👍/👎, card accepted / edited / dismissed).
- **Private is never used on the server** (Risi never sees it; unchanged).
- **Opt-out** (`PUT /api/v1/risi/learning`, per chat or everywhere, §35.3) removes that user's existing
  examples from every training set and stops new ones; a model already trained keeps no per-user rows, the
  next L2/L3 run is built without them.
- Data is **sealed at rest** under `RISI_DATA_KEY` like all Risi data, and **never leaves spark2** (no
  commercial model, no upload; training runs on the box).

## Flags (runtime, no deploy)
- Per agent: **`RISI_AGENT_<NAME>=off|shadow|canary|on`**
  - `off`: the agent never runs; today's path only.
  - `shadow`: the agent runs beside today's path, its decision is logged (run row + activity), **today's
    path answers**.
  - `canary`: the agent answers for the users in `RISI_DEV_USERS` (Harsha first), shadow for everyone
    else; then `on` for all pilot users.
  - `on`: the agent answers for everyone, with auto-fallback.
- Master: **`RISI_AGENTS=on|legacy`** (default `on` = per-agent flags apply). `legacy` makes every agent
  `off` at once: today's path, no shadow work.
- Defaults: `RISI_AGENTS=on`, `RISI_AGENT_ROUTER=shadow` (rules only, no model call, microseconds),
  every other agent `off`.
- **How they flip without a deploy.** `RisiMe.Agents.Flags` resolves a flag as: the row in Postgres table
  `risi_agent_flags` (name → value) **>** the environment (`infra/pilot/pilot.env`) **>** the default.
  Each node caches the table for 5 s (`:persistent_term`), so a change is live everywhere within 5 s and
  survives restarts. To flip:
  ```
  # in the release (loopback epmd):
  ERL_EPMD_ADDRESS=127.0.0.1 ~/risime-run/current/bin/risime rpc 'RisiMe.Agents.Flags.put("RISI_AGENTS", "legacy")'
  # or straight in Postgres:
  docker exec risime-dev-postgres-1 psql -U risime -d risime_dev -c \
    "INSERT INTO risi_agent_flags (name, value, updated_at) VALUES ('RISI_AGENTS','legacy',now())
     ON CONFLICT (name) DO UPDATE SET value = excluded.value, updated_at = now()"
  ```
  `RisiMe.Agents.Flags.clear(name)` (or deleting the row) returns to the env/default. `put` refreshes the
  calling node at once. An unknown value counts as `off`. A DB error reads as the env/default, never as a
  crash.

### Shadow → canary → on
- **Shadow** first. An agent may leave shadow only after **3 days** in which it was **at least as accurate**
  (golden set + agreement with the legacy path on the cases where the legacy path is deterministic) **and
  at least as fast** (p50 and p95) as today's path. The scoreboard shows both side by side.
- **Canary**: Harsha's account first (`RISI_DEV_USERS`), then all pilot users (`on`).
- **Auto-fallback** to the legacy function on an error, a timeout, or a breach of the agent's hard budget.
  Each fallback is an **incident**: a run row with `fell_back = true` and the reason, an activity event
  `kind: fallback`, and one log line `risi agents: incident agent=… reason=…` (no content).

## Speed budgets (p50 / p95)
| Class | Budget | Measured by |
|---|---|---|
| Typing indicator (`risi_progress` `working` after the request reaches the server) | < 1 s | request `server_ts` → `working` |
| Simple answers (one tool or none) | 3 s / 6 s | the turn's duration |
| Multi-step | first useful line within 4 s, then progress | `risi_progress` steps today; a partial line needs the streaming proposal |
| Fast router | rules < 50 ms; model < 1 s (hard 1.5 s → fallback to the legacy route) | run rows |

Today (baseline, see `docs/status/risi.md`): turns p50 3.3 s, p95 9.4 s (Prometheus, 77 turns), so simple
answers already sit at the p50 edge and miss p95: that is the first target of the router + Planner work.

## GPU memory budget (GB10, 121.6 GB unified memory)
Measured figures from `docs/RISI-MODELS.md` (2026-10-10/12):

| Component | GB | Notes |
|---|---|---|
| risi-l1 vLLM (Qwen3.6-35B-A3B-FP8, 0.34 share + CUDA) | **47.4** | resident; never restarted by agent work |
| ASR service (whisper container, `model=auto`) | **≤ 11** budget | large-v3 3.0 + small-si 1.0 + Qwen-si 3.5 + tamil-qwen3 6.7 = 14.2 > 11, so si/ta evict each other |
| Cassandra | 6.8 | |
| Postgres, monitoring, pilot BEAM, LiveKit/coturn | ~1.2 | |
| OS, dockerd, kernel | ~4 | |
| **Resident total** | **~70** | |
| Release gates (3 redroid phones + Gradle/Kotlin daemons) | 20–30 | transient, nightly ~20:13 UTC and on demand |
| Headroom rule | 10 | MemAvailable never planned below 10 GB |
| **Left for risi-l2** | **~11.6 (gates at 30) – 21.6** | |

**risi-l2 budget: ≤ 10 GB all-in** (weights + KV + the ~7 GB CUDA/torch overhead measured for a vLLM
instance), e.g. a 1.5–4 B model in FP8/INT4 with `--max-model-len 4096 --max-num-seqs 4`, or a lighter
runtime with less overhead. A bigger risi-l2 only fits if the ASR set shrinks or gates stop sharing the box.
L3 LoRA training of risi-l2 (≈ 15–25 GB) runs only in the 22:00–06:00 SLT window **and** only while no
release gate runs. Any new model download > 2 GB needs Harsha's OK (rule 10); none is needed for steps 0–2.

## Eval and scoreboard
- **Golden sets** per agent (`server/test/risime/agent/golden/*.jsonl`, hand-checked; English, Sinhala,
  Tamil, Singlish), seeded from `contract/v1/risi_routing_cases.json`, the canary and the model eval set
  (`eval/risi/`), with **hallucination checks** (an answer may not contain a name, amount or date that is
  not in its input).
- `scripts/risi-eval` (no argument) runs them with the fake model; `scripts/risi-eval --live` asks risi-l1
  (sequential, light). Results go to `risi_agent_scores` and `docs/status/risi-scores.md`.
- **Daily scoreboard** (`RisiMe.Agents.Scoreboard`, Oban cron 00:20 UTC): per agent per day — calls,
  accuracy (latest golden run), acceptance (cards confirmed/added vs offered, 👍 vs 👎), agreement with the
  legacy path (shadow), latency p50/p95, incidents. Counts and timings only. `GET /api/v1/risi/agents/scores`
  shows it read-only to `RISI_DEV_USERS`.

## Consequences
- One more Postgres migration (`risi_agent_flags`, `risi_agent_runs`, `risi_agent_scores`), additive.
- One inline call in `Agent.Turn` per turn (the shadow router: rules, microseconds, wrapped so it can never
  fail a turn), and one telemetry handler on the existing turn event.
- A partial-answer stream needs contract work first (proposal filed); typing and step progress already
  exist (`risi_progress`).
