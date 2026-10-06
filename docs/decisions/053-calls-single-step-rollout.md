# 053 — Calls ship in one release (the §16.14 receive-only nightly is folded in)

- **Status:** accepted (2026-10-06)
- **Decided by:** root, on Harsha's instruction to "release voice separately as soon as it's green".

## Context
§16.14 orders two releases: a nightly that receives call history lines but doesn't advertise
`calls`, and then a nightly that advertises `calls` and enables the button.

## Decision
- Ship one release (nightly.16) that has the pipeline, advertises `calls` and enables the button.
- This is safe because `calls_ready` (one `calls` device per user) and the server's delivery filter
  mean an app without the call pipeline is **never rung** and never gets `call_signal` or a `call`
  push. The caller sees "<name> needs to update RisiMe to take calls".
- The only cost: an older app on a second device stores the durable `call_end` invisibly (§10.3),
  so that device shows no call-history line. The updated device shows it.
- The rollout switch `-Prisime.calls=false` (§16.14) stays available for a receive-only build if a
  problem appears.
