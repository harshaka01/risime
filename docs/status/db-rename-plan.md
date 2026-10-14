# Plan: rename the pilot's databases `risime_dev` → `risime_prod` (Harsha item 7, audit G13)

**Status:** plan only, 2026-10-14 (root). **Nothing has been renamed.** A timed rehearsal ran on
throwaway copies (`risime_rh1014_*`, since dropped); the live DB and keyspace were only read
(one normal `scripts/backup`, the same one every deploy runs).

## 1. What is named `risime_dev` today (read-only inventory)

| Where | What | Changes in the window? |
|---|---|---|
| Postgres container `risime-dev-postgres-1` | DB `risime_dev`: 30 MB, 81 tables, pgvector, Oban. Owner role `risime` (superuser in the container) | **yes**, renamed |
| Cassandra container `risime-dev-cassandra-1` | keyspace `risime_dev`: SimpleStrategy RF 1, 12 tables, ~600 MB on disk (`inbox_events` ≈ 2.29 M rows, `message_index` ≈ 0.95 M, the rest small) | **yes**, copied |
| `infra/pilot/pilot.env` (loaded by `risime.service` straight from the main checkout) | `POSTGRES_DB=risime_dev`, `CASSANDRA_KEYSPACE=risime_dev` | **yes**, in the window only |
| `.env` | holds only `POSTGRES_PASSWORD` among DB-related names: no DB or keyspace name | no |
| `server/config/runtime.exs` (prod) | reads `POSTGRES_DB` / `CASSANDRA_KEYSPACE`, default **`risime_prod`** already | no |
| `server/config/config.exs`, `dev.exs` | dev defaults `risime_dev` (`RISIME_DEV_DB`/`RISIME_DEV_KEYSPACE`) for `mix phx.server` | no (dev stays dev) |
| `scripts/backup`, `scripts/restore` | `--env dev` hard-codes `DB=risime_dev`, `KS=risime_dev` (`run-server` calls `scripts/backup --env dev` before every deploy) | **prep PR** |
| `scripts/rollback` | prints `--confirm risime_dev` | prep PR |
| `scripts/capture-diag` | `psql -d risime_dev` and `datname='risime_dev'` | prep PR |
| `infra/monitoring/docker-compose.monitoring.yml` | postgres-exporter DSN `${POSTGRES_DB:-risime_dev}` | prep PR + recreate the exporter |
| `infra/docker-compose.dev.yml` | `POSTGRES_DB: risime_dev` (used only when the volume is first created) and the healthcheck `pg_isready -d risime_dev` (`pg_isready` doesn't fail on a missing DB) | optional cosmetic |
| `~/risime-backups/` | 7 × `risime-dev-<stamp>/` (~460 MB each), MANIFEST says `db=risime_dev keyspace=risime_dev` | kept; still restorable with `--target-db/--target-keyspace` |
| Docs | `CLAUDE.md` "Environments", `docs/PROD.md`, decisions 023/024 | doc PR after the window |
| Phones | **nothing**: clients never see DB names | no |

Also connected to `risime_dev` today: 14 Postgres connections, all from the host (the release's pool,
the postgres-exporter). No other BEAM on the host uses `risime_dev` (the ui-entry instance uses
`risime_uientry_rel`). Timers: `risime-watchdog` (every 30 s; skips while `~/risime-run/.deploy.lock`
is held), `risime-mon-textfile`, `risime-health`.

## 2. Target names
- Postgres DB **`risime_prod`**, Cassandra keyspace **`risime_prod`**: the defaults already in
  `runtime.exs`, `scripts/backup --env prod` and `docs/PROD.md`. Neither name exists on the dev
  containers today (checked).
- Containers keep their names (`risime-dev-*`) for now. Moving to the separate prod containers of
  `docs/PROD.md` is a later, bigger step; this rename only stops "every score row lives in a DB
  named dev".

## 3. Method

**Postgres: `ALTER DATABASE risime_dev RENAME TO risime_prod`** (recommended).
- Instant (rehearsal: < 0.1 s), same OID, no copy, nothing to re-index. Needs **no connection** to
  `risime_dev`: stop the server and the postgres-exporter first.
- Rollback = rename back (instant), and it keeps every row written after the switch.
- Dump/restore (`pg_dump -Fc` → new DB, 5 s at 30 MB) also works and keeps the original untouched,
  but it has to be re-run if anything wrote in between and gains nothing over the rename plus the
  pre-window backup. `CREATE DATABASE … TEMPLATE risime_dev` is the middle way if Harsha wants the
  old DB kept as a frozen copy.

**Cassandra: a copy, since Cassandra can't rename a keyspace.** Recommended: **a local snapshot plus
`nodetool import`** (single node, RF 1):
1. `nodetool flush risime_dev` and `nodetool snapshot -t rename-<stamp> -- risime_dev`
   (hard links, instant);
2. `DESCRIBE KEYSPACE risime_dev`, `sed` the name, then `cqlsh -f` to create `risime_prod`
   (same tables, TWCS/TTL settings, table options);
3. for each table, copy the snapshot's SSTables into a staging directory **named
   `<stage>/risime_prod/<table>-<id>/`**, then run `nodetool import risime_prod <table> <dir>`.
   **Rehearsal finding:** with any other directory layout, `nodetool import` exits 0 but logs
   "No new SSTables were found" and imports **nothing**. This is why the row-count check below
   is mandatory, not a formality;
4. clear the snapshot. **Keep `risime_dev` untouched** as the rollback copy for 14 days.

- `sstableloader` is meant for streaming to another cluster and adds nothing on a single node.
- A dual-write copy needs server code and days of overlap, which this data size doesn't justify.
- An already tested alternative: `scripts/backup` then `scripts/restore cassandra <backup>
  --target-keyspace risime_prod --confirm risime_prod` (the tarball path). In the rehearsal it
  took 21 s and is equally valid.
- SSTables keep their write timestamps and TTLs, so inbox events still expire on schedule.

## 4. Backup and verification
- **Before:** `scripts/backup --env dev --dest ~/risime-backups --keep 7 --blobs ~/risime-blobs/prod`
  with the server stopped, so it is a consistent point, then `scripts/restore verify <dir>`.
- **Postgres:** exact `count(*)` of all 81 tables before the rename and after it, from one SQL
  statement (`query_to_xml` per table), which must be **identical**. Rehearsal: identical, < 1 s.
- **Cassandra:** per-table row counts of `risime_dev` vs `risime_prod`, taken back to back with the
  server stopped. `SELECT COUNT(*)` **times out** server-side on `inbox_events` (rehearsal), so use
  `cqlsh -e "COPY <ks>.<t> TO '/dev/null' WITH PAGETIMEOUT=120"`. It pages through the table
  and prints "N rows exported" (~25 s for the whole keyspace).
  - Tables without a TTL (`message_index`, `cql_migrations`, `sent_dedupe`) must be identical.
  - TTL tables (`inbox_events`, `risi_buffer`, `risi_llm_*`, `risi_turn_steps`, `group_receipts`)
    may only drop by rows that expired between the two counts. The rehearsal saw a few rows expire
    over ~5 min. Count the copy first and the source second, and require `copy ≥ source`.
- **Smoke after start:** `/health` ok, the release's `RisiMe.Release.migrate()` is a no-op
  (`cql_migrations` copied, Ecto `schema_migrations` intact), then Harsha's phone:
  - sends a 1:1 message, a group message and a photo;
  - opens an old chat (history intact);
  - asks Risi one question.
- **Rule 9 (no local chat is ever lost):** nothing on the phones is touched; they keep their Room
  history and queue sends while the server is down. After the window, Harsha's phone runs the
  upgrade-gate comparison (per-conversation counts for 1:1, groups, photos and call records, taken
  before and after). Any loss means rollback.

## 5. Window, exact steps, timing

**When:** a quiet slot outside calls, e.g. 22:30–23:15 Sri Lanka time (17:00–17:45 UTC), never
during the nightly release or a fast-lane deploy (all hold `~/risime-run/.deploy.lock`). Check
first that no call is active (LiveKit rooms / coturn sessions = 0).

**Prep PR (any day before, behaviour-identical while `pilot.env` still says `risime_dev`):**
- `scripts/backup`, `scripts/restore`, `scripts/rollback` and `scripts/capture-diag` read the
  pilot's names from `infra/pilot/pilot.env` (`POSTGRES_DB`, `CASSANDRA_KEYSPACE`, falling back to
  `risime_dev`) instead of hard-coding them;
- the monitoring compose takes `POSTGRES_DB` from the same place;
- `scripts/db-rename` implements the steps below with `--dry-run` and refuses any name except
  the two expected ones.

**Do not commit the `pilot.env` change before the window.** `risime.service` reads it straight
from the main checkout, so a `git pull` plus any restart (the watchdog's `safe-restart`) would
point the live server at a DB that doesn't exist yet.

| # | Step | Rehearsed time |
|---|---|---|
| 0 | Announce in the Official chat; `exec 9>~/risime-run/.deploy.lock; flock 9` (pauses the watchdog, deploys and the fast lane) | — |
| 1 | `systemctl --user stop risime.service`; stop `risime-mon-postgres-exporter-1`; check `pg_stat_activity` has 0 rows for `risime_dev` | ~10 s |
| 2 | Backup + verify (above) | 25 s + 4 s |
| 3 | Count `risime_dev` (Postgres, Cassandra) | ~30 s |
| 4 | `ALTER DATABASE risime_dev RENAME TO risime_prod` | < 1 s |
| 5 | Cassandra copy into `risime_prod` (snapshot, schema, import) | 10–14 s |
| 6 | Count `risime_prod`; compare (above). **Stop and roll back on any mismatch** | ~30 s |
| 7 | Set `POSTGRES_DB=risime_prod` and `CASSANDRA_KEYSPACE=risime_prod` in `infra/pilot/pilot.env` (commit `chore(root): pilot DBs renamed to risime_prod`) | 1 min |
| 8 | Recreate the postgres-exporter with the new DSN; `systemctl --user start risime.service` | ~1 min |
| 9 | `/health` 12/12 over 60 s; check the logs for `keyspace`/`database` errors; phone smoke | 5 min |
| 10 | Release the lock; post "done" | — |

**Data steps:** about 2 minutes. **Server down:** about 10–15 minutes, with margin for the phone
smoke. **Book a 45-minute window.**

**Rehearsal (2026-10-14 02:00 UTC, throwaway copies):**
- backup: 25 s (490 MB with blobs);
- verify: 4 s;
- Postgres restore to scratch: 5 s;
- Cassandra tarball restore to scratch: 21 s;
- Postgres rename: < 0.1 s, then 81/81 table counts identical;
- Cassandra snapshot+import copy: 10 s, then 12/12 table counts identical (with the import-path fix;
  the first attempt imported 0 rows and **exited 0**, see §3);
- the scratch DB and keyspaces were dropped afterwards.

## 6. Rollback
- **Before step 8** (nothing new written): `ALTER DATABASE risime_prod RENAME TO risime_dev`; put
  `pilot.env` back (`git revert`); `DROP KEYSPACE risime_prod` (it is only a copy; `risime_dev` is
  untouched); start the server. Takes about 2 minutes.
- **After reopening:**
  - Postgres renamed back keeps every new row.
  - The old Cassandra keyspace doesn't have the messages and events written since. Either copy
    `risime_prod` back the same way (step 5 reversed, after renaming the stale `risime_dev` copy
    away, never dropping it first), or stay forward and fix.
  - The server-side store-and-forward queue is the only thing at risk. Phones keep their history
    (rule 9), so roll back in the first 15 minutes or not at all.
- The pre-window backup restores either store with `scripts/restore … --confirm <name>`.

## 7. What Harsha must do
- **No sudo is needed.** Everything is `docker exec` (harsha is in the docker group),
  `systemctl --user` and a git commit.
- Harsha must:
  1. pick the window (calls off; tell Shenika and the testers);
  2. approve stopping `risime.service` for about 15 minutes. Root sessions never restart it on their
     own;
  3. **after 14 days**, approve dropping the old `risime_dev` keyspace (about 600 MB). That is a
     deletion, so a named human approves it (principle 4).
- Root does the prep PR and runs the steps in the window, with Harsha on the phone for the smoke
  test.
