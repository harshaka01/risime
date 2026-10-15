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
