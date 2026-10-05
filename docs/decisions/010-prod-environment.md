# 010 — Prod environment on spark2: compose stack, release image, systemd, backups

## Context
Release 0.2 needs a prod environment next to dev on spark2 (ARM64, public IP), with nightly
off-box backups. The orchestrator asked for the preparation only: nothing is deployed, started
or enabled until Harsha has done the steps that need sudo or his accounts. `docs/PROD.md` is the
runbook.

## Decision
1. **A separate compose project, `risime-prod`** (`infra/docker-compose.prod.yml`). It has its
   own network (`risime-prod`) and its own named volumes (`risime-prod-pgdata`,
   `risime-prod-cassdata`). The database and keyspace are both called `risime_prod`, and the
   Cassandra cluster is called `risime-prod`. It never shares anything with `risime-dev`.
2. **Host ports are loopback-only and different from dev:**

   | Service | Prod | Dev |
   |---|---|---|
   | Postgres | `127.0.0.1:5442` | 5432 |
   | Cassandra | `127.0.0.1:9142` | 9042 |
   | Server | `127.0.0.1:4500` | 4000 |

   Inside the container each service keeps its default port (`host:container`, for example
   `5442:5432`). This lets the images' default configs, healthchecks and `nodetool`/`cqlsh` work
   unchanged. Only Tailscale (`tailscale serve` → 127.0.0.1:4500) exposes the server.
3. **The server runs as a container from `infra/Dockerfile.server`**, a multi-stage
   `mix release`.
   - The builder is `hexpm/elixir:1.19.6-erlang-28.5.0.7-debian-bookworm-20260918-slim` and the
     runner is `debian:bookworm-20260918-slim`. Both are multi-arch (arm64 + amd64) and pinned,
     and they match the Elixir/OTP that dev uses through mise.
   - The runner runs as a non-root user (uid 10001) under tini.
   - The image is tagged `risime-server:<version>`.
   - It is built only from a clean worktree of a release tag, never from the shared checkout
     (decision 006). For that reason the compose file has no `build:` section and uses
     `pull_policy: never`.
4. **Migrations run as a one-shot `migrate` service** from the same image:
   `bin/risime eval "RisiMe.Release.migrate()"` (`server/lib/risime/release.ex`) runs Ecto and
   CQL. The server waits for it to complete successfully, so every `up` migrates first.
   Migrations stay additive (decision 006), so a rollback only changes the image tag.
5. **Pinned data images:** `pgvector/pgvector:0.8.7-pg17-bookworm` and `cassandra:5.0.9`. Dev
   keeps its floating tags.
6. **Resource limits** (spark2 has 20 cores and 121 GB of RAM):
   - Postgres: 2 CPUs, 4 GB;
   - Cassandra: 4 CPUs, 8 GB (4 GB heap);
   - server: 4 CPUs, 2 GB;
   - migrate: 1 CPU, 1 GB.

   Every service has `restart: unless-stopped` (except migrate) and rotated json-file logs
   (5 × 50 MB). The healthchecks are `pg_isready`, `cqlsh` and the server's `GET /health`.
7. **Secrets** live in `.env.prod` (gitignored, mode 600). The committed `.env.prod.example`
   lists every key.
8. **systemd *user* units** in `infra/systemd/`, which are installed but not enabled by this
   change:
   - `risime-prod.service` runs compose up/down;
   - `risime-backup.service` and `.timer` run nightly at 02:30 UTC.

   They need `loginctl enable-linger harsha`, which needs sudo.
9. **Backups** are made by `scripts/backup` and restored with `scripts/restore`:
   - Postgres: `pg_dump -Fc` through `docker exec`;
   - Cassandra: `nodetool snapshot` of the keyspace, then a tar of the snapshot directories
     plus the schema CQL, then `clearsnapshot`;
   - `SHA256SUMS` and `pg_restore --list` verify each backup;
   - the destination is a local directory or `user@host:/path` (rsync over ssh), with
     count-based retention.

   They work against dev or prod (`--env`).

## Consequences
- **Server prerequisite (P1, owned by the server session).** `config/runtime.exs` must read
  `CASSANDRA_NODES` and `CASSANDRA_KEYSPACE` in prod. Until then, a prod release would use the
  `config.exs` defaults (`127.0.0.1:9042` / `risime_dev`), which are unreachable from the
  container. `docs/PROD.md` has the exact snippet. The compose file already passes both
  variables.
- `/health` returns 503 until both databases answer, so `docker compose ps` shows the real
  state. Docker does not restart unhealthy containers; it only restarts ones that exit.
- Off-box backups need a backup host and SSH key from Harsha. Until then, `BACKUP_DEST` is a
  local directory on spark2, which is not a real backup.
