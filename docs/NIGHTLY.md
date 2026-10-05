# Nightly release cycle — runbook (orchestrator)

The rules are in `docs/decisions/003-nightly-release-cycle.md`. The cycle fires nightly at about
20:13 UTC in the orchestrator session (cc-root window 0) and can also be run by hand.

## Queue (backlog items that don't need Harsha; top = next)
| # | Item | Roles | Status |
|---|---|---|---|
| 1 | Release plumbing: VERSION wiring, release signing, `.debug` id suffix, release network config | server, android | night 1 |
| 2 | Settings screen: server URL (every build), profile (display name via `PATCH /me`), app version, log out | android | night 1 |
| 3 | Oban: Postgres-backed jobs and cron. First jobs prune expired OTP challenges and stale revoked tokens | server | night 1 |
| 4 | Presence / last seen + typing indicator (contract v1.2, `contract/proposals/2026-10-06-presence-typing.md`) | root, server, android | night 1 (phase B) |
| 5 | Design system pass: tokens (colour, type, spacing, shape), components, light/dark, accessibility | android | night 2 |

Needs Harsha, so never picked automatically: FCM push (Firebase project), real SMTP (credentials),
prod environment, Tailscale install (sudo).

## Each night
1. **Preflight.**
   - `scripts/risi-resume root`; check the tree is clean, the server is up, and
     `~/risime-keys/keystore.properties` exists.
   - Re-create the nightly `CronCreate` job, which resets its 7-day expiry.
2. **Pick** the top queue items whose status isn't done. Give each role a background subagent
   with a precise brief. Roles run in parallel; each commits small green chunks to its own paths.
3. **Contract changes come first:** root merges the accepted proposal into `contract/v1` (version
   bump and changelog), together with the examples, right before both sides implement it.
   Between that commit and the two implementations, only the "every example is covered" checks
   may fail.
4. **Integrate.**
   - Both gates on `main`.
   - Live interop: the Android JVM clients against the dev server, using throwaway users that are
     deleted afterwards.
   - Fix integration breakage as root, and say so in the commit message.
5. **Release:** write `docs/releases/v<next>.md`, then run `scripts/nightly-release`. It bumps
   the version, runs the gates, signs, publishes and restarts the server.
6. **Morning summary:** `docs/status/overnight.md` (what was built, tests, decisions, needs
   Harsha, laptop steps). Update the queue statuses above.
7. If anything fails and can't be fixed safely: don't release, leave `main` green, and explain in
   the summary.
