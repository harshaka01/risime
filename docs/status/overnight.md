# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand (updated 2026-10-06 ~08:35 UTC)
- **Live:** https://risime.risicloud.ai runs **v0.2.0-nightly.7** (`risime.service`, health
  timer, backups, rollback). Downloads are at https://risicloud.ai/app/risime/ (versionCode 20007).
- **Invites and friends** (v1.6) are live. Testers onboard via invites (tester instructions sent
  2026-10-06).
- **E2EE (0.3, contract v1.7) is built end to end and proven live:**
  - the crypto core: 47 tests;
  - the server: 265 tests;
  - the app: 162 tests, with the MLS core in all 4 ABIs;
  - the **live interop gate: 32 checks, including 9 real-MLS E2EE checks** against a server with
    E2EE on (temporary key).
  - **Dormant on the pilot** (`mls_unavailable`) until Harsha approves the rollout. The dev banner
    stays.
- **Push** is built (server and app) and dormant until the Firebase files exist.

## Rollout of E2EE (needs Harsha's go-ahead)
1. Create the pilot attestation key (`mix risime.attestation.gen`, in `~/risime-keys`; back it up).
2. Pin its public key in the app (`-Prisime.mlsPinnedKeys`) and release that build as
   **required**.
3. When the census shows every tester on it, conversations upgrade automatically, chat by chat.
4. Remove the dev banner only when decision 012's criteria are met, and record it.

## Waiting on Harsha (batched)
- `sudo loginctl enable-linger harsha` (start at boot).
- The Caddy fix command (chown the access log, install `infra/caddy/Caddyfile`, reload); the
  fail2ban jail; removing Tailscale.
- Firebase: `google-services.json` and `fcm-service-account.json` into `~/risime-keys/`.
- E2EE product decisions a–d (identity binding, per-device members, history for new members,
  at-rest encryption).
- Notify.lk "RisiMe" sender approval (SMS phone verification goes live then).
- RisiWork `version.json` example.

## Built so far (shipped)
| Release | Contents |
|---|---|
| v0.1.0 | One-to-one chat, store-and-forward, ticks, dev OTP login (smoke-tested on two devices) |
| v0.2.0-nightly.1 | Presence/last seen and typing (contract v1.2), Settings, chat polish (unread, day separators, copy, retry/delete, search), design system + accessibility, Oban, JSON logs/metrics/`/health`, 200- and 2000-user load tests with fixes, prod prep files (not deployed), the E2EE spike (OpenMLS crate) |
| v0.2.0-nightly.2 | RisiCloud sign-in (contract v1.3; dormant until the Keycloak client exists), fingerprint-unlocked refresh tokens, the release-only in-app updater, the publish step to risicloud.ai, the option 2b config (off), the E2EE `.so` built natively on spark2 |

## Done since nightly.2 (in v0.2.0-nightly.3)
- **SMS phone verification** (contract v1.4): server and app done. It is **off on the pilot**
  (`PHONE_VERIFICATION=off`) until Notify.lk approves the "RisiMe" sender ID. The NotifyDEMO guard
  blocks OTP sends, per Notify.lk's suspension warning.
- **Prod-mode pilot release** (decisions 023, 024): per-IP auth limits, the fail2ban auth log and
  jail, `scripts/run-server`. The release app defaults to `https://risime.risicloud.ai`.

## Blocked, and on whom
| Item | Waiting for | Who |
|---|---|---|
| Stricter edge config | `sudo install` of `infra/caddy/Caddyfile`, plus the fail2ban jail and removing Tailscale if not done | Harsha |
| Real Keycloak sign-in | Keycloak client `risime` (redirects `ai.risicloud.risime[.debug]://callback` and `…://logout`, `email` in access tokens) | RisiCloud lead |
| Real verification SMS | "RisiMe" sender ID approved by Notify.lk | Harsha / Notify.lk |
| Updater field names | RisiWork's `version.json` example | Harsha |

## Notes
- spark key: rrsync works and gives no shell, but the forced command lacks `-wo`, so reads are
  allowed. Adding `-wo` is recommended; publishing doesn't need reads.
- Release builds from nightly.3 on default to `https://risime.risicloud.ai`. It works once the
  public URL is live; until then, testers use "Server · Change".
