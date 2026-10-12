# Risi agent scores (decision 084)

Per agent per day: accuracy (golden sets), acceptance (cards accepted / offered, 👍 share), agreement with
today's route (shadow), latency p50/p95, incidents. Counts, rates and timings only — never chat text.

- **Daily** rows: `RisiMe.Agents.ScoreboardJob` (Oban cron 00:20 UTC) writes `risi_agent_scores`;
  `scripts/risi-eval scores` copies the last 14 days of the pilot here (read-only `bin/risime rpc`);
  `GET /api/v1/risi/agents` shows them to `RISI_DEV_USERS`.
- **Golden sets**: `scripts/risi-eval` (fake model; checks the harness and the hallucination checker) and
  `scripts/risi-eval --live` (risi-l1, read-only, one call at a time) append rows below.

## Baseline (step 0, 2026-10-13) — today's modules, before any agent goes live
Pilot data 2026-10-09 → 2026-10-12 (learning log `risi_llm_calls_by_day`, `risi_turn_steps`, Postgres card
states, Prometheus `risime_risi_turn_duration_milliseconds` over 7 days). Golden numbers from the model eval
of 2026-10-10 (`eval/risi/results/risi-l1*.score.json`, 140 cases en/si/ta/mixed; "toolfirst" = the
schema order now live).

| Module (future agent) | Model calls (tasks) | OK | Call p50 / p95 | End-to-end p50 / p95 | Acceptance | Golden accuracy |
|---|---|---|---|---|---|---|
| `Agent.Turn` chat / @Risi (Planner) | 243 `risi_next_action` + 22 `ask` | 100% | 2.09 s / 4.55 s (`ask` 1.37 / 2.55 s) | **turn 3.3 s / 9.4 s** (77 turns) | 👍 7 / 👎 0 | turns 68.8% ok, 0 false action claims, 3 halluc / 80 (toolfirst); smalltalk, capabilities 100% |
| Ledger detection / extraction (Ledger) | 104 `commitment_extract`, 9 `discussion_summarise`, 1 `note_translate` | 100% | extract 0.42 / 3.0 s; discussion 6.9 / 9.9 s | quiet period 30 min by design | promise cards 14/19 confirmed, edited or done (74%), 5 open | extract 90% |
| Calendar (Calendar) | inside turns | – | tool steps: `risi_calendar_check` 112 / 215 ms (16), `calendar_check` (phone) 164 / 449 ms (7), `calendar_add` 31 / 41 ms (12) | turn as above | confirm cards 12/18 done (67%); proactive "Add to calendar?" offers 10/31 added (32%) | calendar_check 20%, calendar_add 40%, phone calendar 66.7% (toolfirst) |
| Memory / search (Memory) | 31 `memory_extract`, 2 `memory_map` | 100% | extract 0.46 / 4.3 s; map 7.8 / 14.4 s | `memory_search` step 69 / 78 ms (3); `summarise_range` 73 ms / 14.6 s (3) | – | live checks 4/4 (step 2 of Risi Brain) |
| Summaries (Summary) | 24 `summarise`, 11 `daily_summarise`, 3 `summary_rollup`, 12 `report` | 100% | 2.48 / 4.91 s; daily 1.71 / 5.64 s; report 4.42 / 6.01 s | – | – | summary 92.9%, injection 66.7%, nothing-to-say 100% |
| Action items, §38 (Voice) | 0 `extract_actions` (shipped 2026-10-12) | – | – | – | – | no golden set yet |
| Call transcript summary (Voice) | 4 `transcribe`; 4 call discussions (1 item) | 100% | 1.07 / 2.15 s | – | – | ASR: si CER 13.3% (Qwen3-ASR-0.6B-Sinhala), ta 15.2%, en 2.5% (RISI-MODELS.md) |
| Fast router (new, shadow) | – (rules) | – | – | rules < 1 ms | – | 63/63 golden (rules); model: run `scripts/risi-eval --live` |

**Budgets vs baseline:** typing (< 1 s) is `risi_progress` `working`, sent at the start of the turn job —
measured from now on (`first_ms` of the `legacy_turn` rows). Simple answers (3 s / 6 s): the turn p50 of 3.3 s
is just over, the p95 of 9.4 s clearly over → the first target. Multi-step first line (4 s): needs streaming
(`contract/proposals/2026-10-13-risi-streaming.md`).

## Daily
<!-- daily:start -->
No pilot rows yet: the first `ScoreboardJob` runs at 00:20 UTC after the fast-lane deploy of decision 084.
<!-- daily:end -->

## Golden sets
First live run 2026-10-12 20:30 UTC (risi-l1, 81 cases, sequential, 92 s):
- **Router:** rules 63/63 in < 1 ms; the risi-l1 tiny prompt alone 93.7% (misses r-si-06, r-ta-03, r-sg-06,
  r-en-32) at p50 681 ms / p95 950 ms. So rules stay first and the model only decides low-confidence asks.
- **Ledger extraction** 6/12 (en 5/6; si 1/2, ta 0/2, Singlish 0/2), 2 hallucinations; **summary** 4/6
  (failed: the injection case s-en-03 and Sinhala s-si-01), 2 hallucinations. The failures haven't been
  checked by hand yet. Some may be the harness being strict (keywords or a due date in another script), not
  model errors. Review these before any Ledger/Summary agent uses them as its bar.

<!-- golden:start -->
| Run (UTC) | Set | Mode | n | Accuracy | Halluc | Traps caught | p50 ms | p95 ms | Model accuracy | Model p50/p95 ms | Failed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-10-12 20:30 | ledger | fake | 12 | 100.0% | 0 | 0 | 3 | 57 | – | – | – |
| 2026-10-12 20:30 | ledger | live | 12 | 50.0% | 2 | 0 | 3081 | 5700 | – | – | l-en-03, l-si-01, l-ta-01, l-ta-02, l-sg-01, l-sg-02 |
| 2026-10-12 20:30 | router | fake | 63 | 100.0% | 0 | 0 | 0.1 | 0.3 | – | – | – |
| 2026-10-12 20:30 | router | live | 63 | 100.0% | 0 | 0 | 0.4 | 0.7 | 93.7% | 681/950 | – |
| 2026-10-12 20:30 | summary | fake | 6 | 100.0% | 0 | 0 | 2 | 3 | – | – | – |
| 2026-10-12 20:30 | summary | live | 6 | 66.7% | 2 | 0 | 2395 | 3675 | – | – | s-en-03, s-si-01 |
<!-- golden:end -->
