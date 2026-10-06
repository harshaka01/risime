# Nightly release cycle — runbook (orchestrator)

The rules are in `docs/decisions/003-nightly-release-cycle.md`. The cycle fires nightly at about
20:13 UTC in the orchestrator session (cc-root window 0) and can also be run by hand.

## Queue (backlog items that don't need Harsha; top = next)
| # | Item | Roles | Status |
|---|---|---|---|
| 1 | Release plumbing (VERSION, signing, `.debug` suffix, release network config) | server, android | done in v0.2.0-nightly.1 |
| 2 | Settings screen (server URL, profile, version, log out) | android | done in v0.2.0-nightly.1 |
| 3 | Oban + prune jobs | server | done in v0.2.0-nightly.1 |
| 4 | Presence / last seen + typing (contract v1.2) | root, server, android | done in v0.2.0-nightly.1 |
| 5 | Chat polish (unread, day separators, copy, retry/delete, search) | android | done in v0.2.0-nightly.1 |
| 6 | Design system pass + a11y | android | done in v0.2.0-nightly.1 |
| 7 | Observability (JSON logs, telemetry, /health) | server | done in v0.2.0-nightly.1 |
| 8 | Load test, 200 users plus a 2000-user stress run, with fixes | server | done in v0.2.0-nightly.1 |
| 9 | Prod prep (compose, Dockerfile, systemd, backup/restore, PROD.md) | root/ops | done (not deployed) |
| 10 | E2EE spike: `crypto/risime-mls` on OpenMLS, decision 012 | root/crypto | done; next steps wait on Harsha's answers in 012 |

**Phase: sign-in and distribution through RisiCloud** (decisions 013–017; contract proposal v1.3):
| # | Item | Roles | Status | Blocked on |
|---|---|---|---|---|
| 11 | Review the v1.3 proposal (server + android), then root merges it with placeholders | root, server, android | next | — |
| 12 | Server: allowlist keyed on email+phone; Keycloak JWT auth (JWKS cache, claim checks), `/me` upsert, 403/409, `DEV_LOCAL_AUTH` gating, socket JWT + `auth:refresh`. Tested with a local test JWKS | server | next | — |
| 13 | Android: AppAuth + PKCE, Keystore/BiometricPrompt refresh token, dev login behind a debug switch, `auth:refresh`. Unit tests | android | next | — (a live sign-in needs the Keycloak client) |
| 14 | `scripts/nightly-release` publish step (mirror of the last 5, `version.json`, `index.html`, rsync to `spark1`), plus `--publish-only` / `--required` | root | after 12–13 | rrsync key on spark; RisiWork `version.json` example |
| 15 | Android in-app updater (`version.json`, sha256 and cert pin, PackageInstaller, blocking `required`) | android | after 14 | RisiWork `version.json` example |
| 16 | Server reachability for testers | root | — | Harsha's choice (decision 017); build against Tailscale until then |
| 17 | Proof of done: books@codegen.co.uk signs in by email code, then by fingerprint the next day; installs from `/app/risime/`; the app updates itself on the next release | Harsha + tester | last | 12–16, Keycloak client, Caddy route, tester's phone on the allowlist |


Needs Harsha, so never picked automatically:
- FCM push (a Firebase project);
- real SMTP (credentials);
- prod deploy (`.env.prod`, linger, the backup host);
- Tailscale install (sudo);
- E2EE next steps (the decision 012 questions; the NDK download).

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
   the version, runs the gates, signs and publishes. Only then does it switch the test server to
   the new tag via `scripts/run-dev-server v<next>`.
   - **Test server rule** (decision 006): the server Harsha tests against (`risime-server`,
     :4000) runs from `~/risime-run/<tag>`, never from the shared checkout.
   - Sessions never restart it. For live testing of unreleased code, they start a temporary
     instance from `main` on another loopback port (for example :4100) and stop it afterwards.
6. **Morning summary:** `docs/status/overnight.md` (what was built, tests, decisions, needs
   Harsha, laptop steps). Update the queue statuses above.
7. If anything fails and can't be fixed safely: don't release, leave `main` green, and explain in
   the summary.
