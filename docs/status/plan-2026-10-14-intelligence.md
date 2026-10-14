# Plan: making Risi actually learn (Harsha 2026-10-14, after the audit)

Source: docs/status/AUDIT-2026-10-14.md. Order (Harsha), starting right after v0.3.0-nightly.7. Standing rule: never
wait on Harsha; anything needing him goes behind a flag or into a follow-up.

| # | Item | Owner | Ships via | Notes |
|---|---|---|---|---|
| 0 | **Learning log keeps replayable Official input text**: sealed at rest (SealAtRest), fixed retention (default 90 days, an admin setting), purged on the user's opt-out, **Private never** | risi + android (notice/opt-out) | contract first (opt-out setting + notice), then nightly | Harsha's decision: text learning on by default with notice + opt-out. Without it nothing can learn. |
| 1 | Intelligence Index in code + automatic rollback (promotion rule, decision 085) | risi | fast lane | Scoreboard.put_eval wired; Index rows daily; a regression past the threshold rolls back to the previous version. |
| 2 | Test sets: 300+ cases (si / ta / Singlish / en) drafted from realistic, consented pilot messages; Harsha spot-checks ~20 %; real recordings for ASR | root + risi | docs/eval data | No raw customer text in git: the sets live sealed on spark2; git holds ids and synthetic examples only. |
| 3 | **Ledger = the first REAL agent** (reasons, self-checks, never invents; **0 made-up items is a hard gate**), first Coach on it, then the same pattern for Summary | risi | fast lane, shadow → canary → on | The reference pattern for every other agent. |
| 4 | Consequential-action guard (money, pay, HR, deletion, legal): agents never execute; the named human approves | risi | fast lane | Principle 4, decision 085. |
| 5 | Sinhala/Tamil/Singlish TEXT brains: evaluate Gemma 4 and other open models that fit (promise detection, summaries); per-language model choice on the admin console, like STT | risi + server | console page | Models already on disk: gemma4-12b/26b/31b, qwen38-27b. |
| 6 | Honesty: Official never shows the Private lock; wording like "Encrypted · Risi can read" (decision 048) | android | nightly | Audit: Official is E2EE to a Risi whose keys are on the server. |
| 7 | Plan the risime_dev → prod database rename (backup, window, rollback); do it when safe | root | maintenance window | Plan doc first; never during calls; rule 9. |
