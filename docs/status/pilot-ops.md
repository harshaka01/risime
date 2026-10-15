# Pilot operations log

Restarts and config changes on the live pilot (`risime.service`) outside a release deploy.

## 2026-10-13: Erlang distribution on, loopback only (decision 075; Harsha approved 08:16 SLT)
- **Restarts:**
  - 02:47:04 UTC: the `.env` line had no effect, because `infra/pilot/pilot.env` (loaded after `.env`) sets
    `RELEASE_DISTRIBUTION=none`.
  - 02:48:40 UTC: took effect, after `pilot.env` was changed to `RELEASE_DISTRIBUTION=name` and
    `RELEASE_NODE=risime@127.0.0.1`.
  - Health was ok within seconds after each restart.
  - The second restart cut an Official call that was being transcribed (session `5849df48…`, started 02:48:02).
- **Cookie:** `RELEASE_COOKIE` is a random 40-character value. It is only in `.env` (chmod 600), never printed
  and never committed. `.env` was backed up to `~/secrets/env-backup-<MMDDHHMM>` first.
- **`ss -tlnp` after the second restart:** epmd on `127.0.0.1:4369` and `[::1]:4369`, the node (beam.smp) on
  `127.0.0.1:4370`. Nothing listens beyond loopback.
- **Remote rpc** (read the env without printing it; the client needs its own port range, listed first because the
  release's `env.sh` appends the fixed 4370):

  ```
  (set -a; . ./.env; . infra/pilot/pilot.env; set +a;
   ERL_AFLAGS="-kernel inet_dist_listen_min 4371 -kernel inet_dist_listen_max 4399" ERL_EPMD_ADDRESS=127.0.0.1 \
   ~/risime-run/current/bin/risime rpc '<expr>')
  ```
- **Master-switch test (decision 084):**
  1. `Flags.all` showed `RISI_AGENTS=on` (from the default).
  2. `Flags.put("RISI_AGENTS","legacy")` set master `:legacy` and `legacy?` true.
  3. `Flags.clear("RISI_AGENTS")` brought master back to `:on` (`legacy?` false), with no deploy.

## scripts/risi-rpc (2026-10-14)
Use `scripts/risi-rpc '<expr>'` (or `-f file.exs`) for every pilot rpc. It wraps the form above: it loads `.env` and
`infra/pilot/pilot.env` without printing them, uses the client port range 4371–4399 and epmd on 127.0.0.1. The short form
`ERL_EPMD_ADDRESS=127.0.0.1 bin/risime rpc …` fails ("node is not alive") because it doesn't load the distribution settings.

## Flag changes on the pilot
| When (UTC) | Flag | Value | By |
|---|---|---|---|
| 2026-10-14 ~02:10 | RISI_VOICE_TRANSCRIBE | canary | root (Harsha asked, P1) |
| 2026-10-14 ~02:10 | RISI_ACTION_ITEMS | canary | root (Harsha asked); stays canary until Harsha says |
| 2026-10-14 03:52 (09:22 SLT) | RISI_VOICE_TRANSCRIBE | **on** (everyone) | Harsha |
| 2026-10-15 00:52 (06:22 SLT) | RISI_CALL_RISI | canary (Harsha only) | root (Harsha 15 Oct P0-2: device test, his account) |
| 2026-10-15 02:26 (07:56 SLT) | RISI_LIVE_FAST | canary (Harsha's calls) | root (Harsha 07:48: ship the server side) |

### 2026-10-15 ~09:45 UTC (15:15 SLT): pilot-device-test account (allowlist only)
- `RisiMe.Accounts.allow/1` via `scripts/risi-rpc`: allowlist entry `+94770000995` / `pilot-device-test@example.com`
  ("ZZ Pilot Device Test", repo test-number range) for the real-device test (Harsha's 15:00 rule). No user created yet,
  no flag, env or restart change. Not in `RISI_DEV_USERS` (Flags reads it from the application env at boot, so the
  canary switches CALENDAR_V2 / CALL_RISI / LIVE_FAST would not apply to this account without a change).
- Remove: `scripts/risi-rpc 'RisiMe.Repo.delete_all(Ecto.Query.where(RisiMe.Accounts.AllowlistEntry, phone: "+94770000995"))'`.

## 2026-10-15 ~15:50 SLT: root inbox (risi role)
- `.env`: added `RISI_ROOT_NOTIFY_USER` = the existing `RISI_DEV_USERS` entry (Harsha; value not printed). Read at
  runtime by `RisiMe.Agent.RootInbox`; the service picks it up at its next start (the fast-lane deploy of this change).
- `scripts/risi-notify` posts root's question in that user's Risi chat; `scripts/risi-inbox [--install]` fetches the
  replies into `docs/status/inbox.md` (timer `risime-inbox.timer`, not installed yet: root installs it).
- Off: remove the line from `.env` and restart: `risi-notify` refuses and nothing is intercepted.
- 10:16 UTC: the test account signed in (debug build of v0.3.0-nightly.10, dev OTP, Harsha 15:31 "OPTION 2"):
  user `0c0e548c-6572-4b53-8fb8-981442301c28` now exists on the pilot (+94770000995). Undo: delete the user and the
  allowlist row (the row's command is above). `RISI_DEV_USERS` in `.env` was NOT changed and the server was NOT restarted
  (the edit was blocked by a permission check). To do it by hand: append `,0c0e548c-6572-4b53-8fb8-981442301c28` to the
  `RISI_DEV_USERS=` line in `.env`, then `systemctl --user restart risime.service`. Undo: remove that id, then restart.
