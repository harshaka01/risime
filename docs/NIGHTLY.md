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

**Phase: sign-in and distribution through RisiCloud** (decisions 013–019; contract v1.3):
| # | Item | Status | Blocked on |
|---|---|---|---|
| 11 | Contract v1.3 (reviewed by server + android, merged) | done (`e0824a9`) | — |
| 12 | Server: Keycloak JWT/JWKS, phone-first mapping, `DEV_LOCAL_AUTH` / `OIDC_ENABLED`, socket Bearer + `auth:refresh`/`auth:expired` | done, in v0.2.0-nightly.2 | a real-token check needs the Keycloak client |
| 13 | Android: AppAuth + PKCE, fingerprint vault, sign-in mode from `/auth/config` | done, in v0.2.0-nightly.2 | a live sign-in needs the Keycloak client |
| 14 | Publish step (`scripts/publish-release`) | done; v0.2.0-nightly.2 is staged in `~/risime-releases/publish/` | **the key on spark** (Harsha); the `/app/risime/` Caddy route (RisiCloud lead) |
| 15 | In-app updater (release-only) | done, in v0.2.0-nightly.2 | RisiWork `version.json` example (field names, Harsha) |
| 16 | Option 2b (decided): Caddy `/risime/` → spark2 `tailscale serve` | prepared in `infra/risicloud/`, off | Tailscale on spark2 (Harsha, sudo) and on spark; `/risime/` route (RisiCloud lead) |
| 17 | Proof of done (books@codegen.co.uk) | waiting | everything above, plus the tester's phone/name/company for the allowlist |

No unblocked items are left in this phase. A nightly run only does the preflight until something
is unblocked.

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
