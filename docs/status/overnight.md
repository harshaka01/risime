# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand (updated 2026-10-06 ~10:10 UTC)
- **E2EE is ON** (decision 038): https://risime.risicloud.ai runs **v0.2.0-nightly.8**, published as a
  **required** update (versionCode 20008).
  - `/mls/attestation_keys` serves the pinned pilot key `x-nu0EBHzGv-…`.
  - Chats upgrade automatically once both people are on nightly.8. The dev banner stays.
- The service runs under systemd (linger on, so it starts at boot), with the health timer, backups
  and rollback. Caddy runs the repo config (HSTS, `/` redirects to downloads).
- **Next (in order):** emoji and reactions (v1.8 proposal reviewed by server; android review
  next) → MLS groups → encrypted images.

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
