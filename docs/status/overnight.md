# Status / morning summary — 2026-10-06 (orchestrator, cc-root window 0)

## Where things stand (updated 2026-10-06, after the spark2 reboot)
- **Reboot recovery verified.** spark2 rebooted and **everything came back by itself**:
  - Postgres and Cassandra healthy (Docker restart policy);
  - Caddy active;
  - `risime.service` active (systemd user unit with linger), with its health timer;
  - `https://risime.risicloud.ai/health` 200 on nightly.8.

  The orchestrator session and its agents ended with the reboot. Their uncommitted work was
  checked: the v1.8 interop test edits passed the gate and their live checks, and were committed
  (`4e6bb89`). Nothing was discarded.
- **P0 fixed in v0.2.0-nightly.9** (live, not required, versionCode 20009): the "Update required"
  gate locked testers out.
  - **Root causes:** notes passed raw into `Text`; no `Surface` behind the screen, so text was
    black on dark; a non-scrolling column pushed the buttons off-screen.
  - **Fix:** a pinned Update button, scrolling notes, theme colours, a plain-text `summary`
    (published in `version.json`), Open download page, Sign out, errors with Retry.
  - Robolectric Compose UI tests cover long notes, 320×480 dp, 2× font, dark and light mode, and
    the button visible and enabled.
  - Testers install from https://risicloud.ai/app/risime/ in the browser.
- **The interop E2EE "flake" was a test-harness bug**, now fixed. A killed run left its users, so
  the next run reused them and hit a stale MLS group. Setup now purges leftovers; proven by
  SIGKILLing a run.
- **E2EE is on:** pilot key `x-nu0EBHzGv-…`. Chats upgrade once both people run an E2EE-capable
  app (nightly.7+).
- **Emoji and reactions (v1.8) shipped in nightly.9.**
- **v0.2.0-nightly.10 live: end-to-end encrypted groups** (contract v1.9, decision 041;
  versionCode 20010, not required). Both gates passed, live interop passed with 11 new group checks
  (real MLS core, 5 devices), and the backup now includes blobs. The pilot was migrated by
  run-server after the backup.
- **Interop now runs on its own store** (`risime_interop` DB and keyspace, migrated each run). It
  used to write throwaway users into the pilot's `risime_dev`.
- **Server test flake fixed** (`5d4a61a`): test pushes leaked into the next test under load; they
  are now routed by a per-test token. 30/30 under heavy load.
- **Queue:** encrypted images (contract v1.10, extends the v1.9 blob API), then group icons.

## Waiting on Harsha (batched)
- The fail2ban jail and removing Tailscale, if not done yet (`docs/PROD.md`).
- Firebase: `google-services.json` and `fcm-service-account.json` into `~/risime-keys/`.
- Notify.lk "RisiMe" sender approval (SMS phone verification goes live then).
- RisiWork `version.json` example.

## Built so far (shipped)
| Release | Contents |
|---|---|
| v0.1.0 | One-to-one chat, store-and-forward, ticks, dev OTP login (smoke-tested on two devices) |
| v0.2.0-nightly.1 | Presence/last seen and typing (contract v1.2), Settings, chat polish (unread, day separators, copy, retry/delete, search), design system + accessibility, Oban, JSON logs/metrics/`/health`, 200- and 2000-user load tests with fixes, prod prep files (not deployed), the E2EE spike (OpenMLS crate) |
| v0.2.0-nightly.9 | P0 update-screen fix, emoji and reactions (v1.8) |
| v0.2.0-nightly.10 | Encrypted MLS groups (v1.9), blob store, interop store isolation |
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
| Real Keycloak sign-in | Keycloak client `risime` (redirects `ai.risicloud.risime[.debug]://callback` and `…://logout`, `email` in access tokens) | RisiCloud lead |
| Real verification SMS | "RisiMe" sender ID approved by Notify.lk | Harsha / Notify.lk |
| Updater field names | RisiWork's `version.json` example | Harsha |

## Notes
- spark key: rrsync works and gives no shell, but the forced command lacks `-wo`, so reads are
  allowed. Adding `-wo` is recommended; publishing doesn't need reads.
- Release builds from nightly.3 on default to `https://risime.risicloud.ai`. It works once the
  public URL is live; until then, testers use "Server · Change".
