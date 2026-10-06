# 025 — Pilot reliability: systemd user service, pre-deploy backups, rollback, health timer

**Status:** accepted 2026-10-06 (autopilot slice A).

## Decision
- **The service is a systemd *user* unit** (`infra/systemd/user/risime.service`), not a root
  system unit.
  - It runs `~/risime-run/current/bin/risime start`, where `current` is a symlink that
    `scripts/run-server` flips atomically. Env comes from the repo `.env` and
    `infra/pilot/pilot.env`.
  - `Restart=on-failure` (5 s), with a start limit of 10 restarts per 5 min, `KillSignal=SIGTERM`
    (a release without distribution can't be stopped with `bin/risime stop`), and `UMask=0077`.
  - Logs are appended to `~/risime-logs/server.log`.
  - `ExecStartPre=scripts/wait-db` waits up to 180 s for **TCP** on 5432 and 9042, with no
    `docker exec`.
  - **Why a user unit:** day-to-day deploys, rollbacks and restarts need no sudo (golden rule
    4). The one sudo step is `loginctl enable-linger harsha`, so the user manager, and with it
    RisiMe, starts at boot without a login.
- **A health timer** (`risime-health.timer` → `scripts/healthcheck`) runs every 5 min.
  - It checks `http://127.0.0.1:4000/health`. After two failures 15 s apart, it runs
    `systemctl --user restart risime.service`.
  - It also logs a warning when local is ok but `https://risime.risicloud.ai/health` isn't.
  - Output goes to `~/risime-logs/health.log`. It skips while a deploy holds
    `~/risime-run/.deploy.lock`. Crashes are covered by `Restart=`; the timer covers hangs.
- **Backups before every migration or release:** `scripts/run-server` runs `scripts/backup --env
  dev --dest ~/risime-backups --keep 7` (Postgres `pg_dump -Fc` plus a Cassandra snapshot, both
  verified) **before** migrating. A failed backup aborts the deploy. `--no-backup` exists for
  same-tag restarts right after a backup.
- **Rollback in one command:** `scripts/rollback [vX]` re-deploys the previous distinct tag from
  `~/risime-run/HISTORY`, with a fresh backup. It rolls back code only. Migrations are additive,
  so an older release runs on the newer schema. Restoring data stays an explicit
  `scripts/restore … --confirm risime_dev` step, printed by the rollback.
- **Deploys** take a lock, back up, migrate, flip `current`, restart through systemd (falling back
  to tmux when no unit is installed), and then verify: healthy on the expected version, bound to
  127.0.0.1:4000 only, no epmd.

## Verified on spark2 (2026-10-06)
- SIGKILL of the BEAM: systemd restarted it, healthy again after **6 s**.
- `scripts/rollback`: nightly.4 → nightly.3 (healthy) → nightly.4 (healthy), each with a backup.
  Backups are 24K for Postgres and 420M for Cassandra.
- The health timer is active. `healthcheck` returns ok.

## Incident during setup (2026-10-06 06:30:54–~06:33, about 2 min down)
The first systemd handover stopped the tmux server, but the unit's `wait-db` used `docker exec`.
The user manager predates `harsha`'s docker group, so the Docker socket was refused and systemd
killed the pre-start after its default 90 s.
- Service was restored manually in tmux.
- The fix: TCP checks plus `TimeoutStartSec=240`, then proven with a transient unit on :4100 before
  a second, successful handover.
- Clients reconnect and catch up with `since`, so no messages were lost.

**Lesson:** prove a new unit under systemd on a spare port before handing production over.
