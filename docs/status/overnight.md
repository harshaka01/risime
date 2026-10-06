# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand (updated 2026-10-06 ~07:25 UTC)
- **Live:** https://risime.risicloud.ai runs **v0.2.0-nightly.5 (invites and friends)** as the
  systemd user service `risime.service`, with a health timer, pre-deploy backups (keep 7) and
  `scripts/rollback` (decision 025).
  - RisiCloud sign-in is on. Harsha and Shenika are both bound to Keycloak and are friends.
- **Published:** https://risicloud.ai/app/risime/ (versionCode 20005, sha256 verified). Installed
  apps get the update banner.
- **Every release** now runs both gates plus `scripts/interop` (23 live checks with real Android
  clients, friends flow included), backs up, publishes and switches.

## Built since nightly.5 (on `main`; ships with the next green nightly)
- **Push client** (contract v1.5): local, content-free-to-Google notifications, device
  registration and the notification permission. **Dormant** until `google-services.json` (with
  both `lk.codegen.risime` and `lk.codegen.risime.debug`) and `fcm-service-account.json` are in
  `~/risime-keys/`. `nightly-release` copies the json in automatically.
- **E2EE groundwork:** debug builds made on spark2 package the MLS core (arm64-v8a and x86_64;
  4.5 MB each) with Settings → About → "Crypto self-test" (device check 42). The release APK is
  unchanged (12.7 MB). Known limit: no 32-bit ABIs yet; to be added before 0.3 ships.

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
