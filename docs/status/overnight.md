# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand
- **Test server:** `risime-server` runs **v0.2.0-nightly.2** from `~/risime-run/v0.2.0-nightly.2`.
  `/health` is ok and `/auth/config` lists `["dev"]`: OIDC stays off until the Keycloak client
  exists.
- **Downloads are live:** https://risicloud.ai/app/risime/ serves the "Get RisiMe" page,
  `version.json` (`no-cache`) and the signed APK. The APK's sha256 was verified end to end from
  the public URL. Every green release publishes automatically and keeps the last five.
- **Device test of v0.2.0-nightly.2:** installed on the emulator and on Harsha's phone. **Result
  not reported yet**: the message arrived with the template placeholder unfilled. Fill it in here
  once known: login / chat both ways / ticks / presence / typing / settings / updater.

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
| Tailscale serve `https://gx10-e23a.tailb47283.ts.net` → 127.0.0.1:4000 | Complete the Serve approval: https://login.tailscale.com/f/serve?node=ni4gU7fRPX11CNTRL (HTTPS certs are enabled, but the node still says "Serve is not enabled on your tailnet") | Harsha |
| Real Keycloak sign-in (proof of done) | Keycloak client `risime`: public, PKCE S256, redirects `ai.risicloud.risime[.debug]://callback` and `…://logout`, `email` in access tokens | RisiCloud lead |
| `https://risicloud.ai/risime/` (option 2b, today 404) | The `/risime/` Caddy route (`infra/risicloud/Caddyfile.risime`); spark joining the tailnet; Serve on spark2 (above) | RisiCloud lead (+ Harsha for Serve) |
| LAN path spark2 ↔ spark (10.20.20.14 unreachable) | No longer blocks anything under 2b | Network team |
| Real verification SMS | "RisiMe" sender ID approved by Notify.lk | Harsha / Notify.lk |
| Updater field names | RisiWork's `version.json` example | Harsha |
| books@codegen.co.uk on the allowlist | The tester's phone, name and company | Harsha |

## Notes
- spark key: rrsync works and gives no shell, but the forced command lacks `-wo`, so reads are
  allowed. Adding `-wo` is recommended; publishing doesn't need reads.
- After a release, a fresh install defaults to `https://risicloud.ai/risime`, which isn't live
  yet. Use "Server · Change" until 2b is switched on.
