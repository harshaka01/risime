# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand (updated 2026-10-06 ~05:20 UTC)
- **Public pilot is live: https://risime.risicloud.ai.**
  - Caddy on spark2 has a Let's Encrypt cert and proxies to the **v0.2.0-nightly.3 prod release**
    on 127.0.0.1:4000. There is no epmd, and port 4000 isn't reachable from outside.
  - End-to-end through the public URL: `/health` ok; `/auth/config` = dev mode; dev login (code
    only in the server log, never in a response); `/me` and `/contacts` 200; `wss://` upgrade 101
    with Bearer; inbox join ok; `/dev/mailbox` 404. Throwaway user removed.
- **v0.2.0-nightly.3 is published** at https://risicloud.ai/app/risime/ (versionCode 20003; the
  public APK's sha256 matches). Installed nightly.2 apps get the update offer from the in-app
  updater.
- **Caddy:** the live `/etc/caddy/Caddyfile` is Harsha's minimal one. The stricter repo version
  (`infra/caddy/Caddyfile`, adding `/` → download page) is waiting for Harsha to install it with
  sudo.
- **Device test of v0.2.0-nightly.2:** result not reported yet.

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
