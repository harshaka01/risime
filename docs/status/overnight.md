# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand (updated 2026-10-06 ~06:15 UTC)
- **Public pilot https://risime.risicloud.ai** runs **v0.2.0-nightly.4** (prod release on
  127.0.0.1:4000, behind Caddy, Let's Encrypt). **RisiCloud sign-in is ON**
  (`/auth/config` = oidc + dev). Keycloak realm `aoa`, client `risime`, emails the codes.
- **v0.2.0-nightly.4 is published** (versionCode 20004; public APK sha256 verified). It fixes the
  crash when Keycloak returned to the app (AppAuth redirect activity theme), plus the latent
  Android 8–9 fingerprint-dialog crash, with a merged-manifest regression test.
- **Waiting on Harsha's sign-in with nightly.4.** Then root checks that `/me` bound
  `harsha@codegen.co.uk` → `+94770802222` (user `de9b6235…`, currently unbound).
- **Logs:** the server writes to the tmux pane and to `~/risime-logs/server.log`. Auth failures go
  to `~/risime-logs/auth.log`.
- **Caddy:** the stricter repo Caddyfile isn't live yet (the reload failed on a root-owned access
  log). The fix command is in the conversation and in `docs/PROD.md`.
- A login-theme request was sent to the RisiCloud lead (2026-10-06).

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
