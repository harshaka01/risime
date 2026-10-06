# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand (updated 2026-10-06 ~05:00 UTC)
- **Pilot server:** `risime-server` runs **v0.2.0-nightly.3 as a prod-mode release** from
  `~/risime-run/v0.2.0-nightly.3`, on `127.0.0.1:4000` only (no epmd, no dev routes, JSON
  errors, `check_origin` = https://risime.risicloud.ai). `/health` is ok.
  `/auth/config` = `{"modes":["dev"],"phone_verification":"off"}`.
- **Public URL https://risime.risicloud.ai: not live yet.** It waits for the DNS A record, the
  network firewall (80/443 → spark2), and Harsha's sudo steps (Caddy, ufw, fail2ban, removing
  Tailscale). See `docs/PROD.md` "Pilot on spark2".
- **v0.2.0-nightly.3 is tagged but not published.** It goes to risicloud.ai/app/risime/ once the
  public end-to-end check passes. **v0.2.0-nightly.2** is the current download there.
- **Device test of v0.2.0-nightly.2:** installed on the emulator and Harsha's phone; result not
  reported yet.

## Built so far (shipped)
| Release | Contents |
|---|---|
| v0.1.0 | One-to-one chat, store-and-forward, ticks, dev OTP login (smoke-tested on two devices) |
| v0.2.0-nightly.1 | Presence/last seen and typing (contract v1.2), Settings, chat polish (unread, day separators, copy, retry/delete, search), design system + accessibility, Oban, JSON logs/metrics/`/health`, 200- and 2000-user load tests with fixes, prod prep files (not deployed), the E2EE spike (OpenMLS crate) |
| v0.2.0-nightly.2 | RisiCloud sign-in (contract v1.3; dormant until the Keycloak client exists), fingerprint-unlocked refresh tokens, the release-only in-app updater, the publish step to risicloud.ai, the option 2b config (off), the E2EE `.so` built natively on spark2 |

## In progress (item 18)
**One-time SMS phone verification via Notify.lk** (decision 020, proposal v1.4). Both role agents
are reviewing it. Then root merges it, and server and android implement it in parallel.
- **Guard:** Notify.lk's docs warn that OTP content sent with `NotifyDEMO` risks **account
  suspension**. While `NOTIFYLK_SENDER_ID=NotifyDEMO`, OTPs are logged (DevLog), not sent. Real
  SMS starts when the approved ID is in `.env`.

## Blocked, and on whom
| Item | Waiting for | Who |
|---|---|---|
| https://risime.risicloud.ai | DNS `risime.risicloud.ai A 203.115.26.139` | Harsha → DNS owner |
| | Inbound TCP 80+443 to 203.115.26.139 → spark2 10.20.20.15 | Network team |
| | Install Caddy plus `infra/caddy/Caddyfile`; ufw 80/443; fail2ban jail; remove Tailscale (sudo) | Harsha |
| Publish nightly.3 | The public end-to-end check above | root (automatic once unblocked) |
| Real Keycloak sign-in | Keycloak client `risime` (redirects `ai.risicloud.risime[.debug]://callback` and `…://logout`, `email` in access tokens) | RisiCloud lead |
| Real verification SMS | "RisiMe" sender ID approved by Notify.lk | Harsha / Notify.lk |
| Updater field names | RisiWork's `version.json` example | Harsha |

## Notes
- spark key: rrsync works and gives no shell, but the forced command lacks `-wo`, so reads are
  allowed. Adding `-wo` is recommended; publishing doesn't need reads.
- After a release, a fresh install defaults to `https://risicloud.ai/risime`, which isn't live
  yet. Use "Server · Change" until 2b is switched on.
