# Review: reinstalls without reset v1.21, android side

**Reviewer:** android (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-rejoin-without-reset-v1.21.md`.
**Verdict:** accept with the changes below (folded into §12.12 and §10.6.5).

## What I checked
`GroupStore.rejoinPlan` / `queueRejoins` (the only-admin `RESET` branch), the DM repair state
machine (§10.6.5, the 2-minute reset), the group-op executor, the outbox's pending sends.

## Findings and required changes
- **A1. Delete the `RESET` branch of `rejoinPlan`.** It is the root cause of the `grp:c442` resets.
  The plan becomes `REJOIN` always; the reset decision moves to after the rejoin reply (needs
  `candidates`/`exhausted` and, for groups, the op's `created_at`). **Folded in (§12.12.3).**
- **A2. No silent timeout.** Replace the DM 2-minute timer with the reply-driven rule; re-call
  `rejoin` on open/resume/reconnect and every 15 min while the chat is open (well under the
  10/min limit). **Folded in.**
- **A3. Waiting text, not a failure.** "Waiting for <name> to open RisiMe" (DM) / "Waiting for a
  group member to open RisiMe". Sends stay `pending` (clock icon), never `failed`, so the user
  isn't tempted to reinstall again. **Folded in.**
- **A4. `409 rejoin_pending` handling.** Treat as "wait", not an error; don't retry the reset op;
  queue a rejoin instead. Old apps will log it as a failed op; harmless.
- **A5. Cleanup ops.** The executor already commits a `devices` op whose `removed` are this user's
  own leaves (own-user removal, §12.4); verify with a JVM test that an `added: []` op is accepted
  and doesn't trigger a Welcome. **Folded into tests.**
- **A6. Manual reset** in group info (admins), with the confirmation text; disabled while a
  rejoin is pending for this device (the server would answer `409`).
- **A7. Hard rule 9.** The reset path never touches `messages`; keep the existing
  `onReset` (drops MLS group + parked events → gap rows). Restated in §12.12.3.
