# RisiMe prod — runbook

> **Today (2026-10-06, decision 023):** the **public pilot** runs on spark2 as a prod-mode release
> behind Caddy at https://risime.risicloud.ai, against the existing dev databases. See "Pilot on
> spark2" below. The containerised prod in the rest of this runbook is the later real launch. Its
> HTTPS edge is now Caddy on spark2, with no Tailscale: route a prod hostname to `127.0.0.1:4500`
> the same way.

**Status (2026-10-05):** prepared, not deployed. Nothing below has been started, installed or
enabled.

- Decision: `docs/decisions/010-prod-environment.md`.
- Files:
  - `infra/docker-compose.prod.yml`, `infra/Dockerfile.server`;
  - `infra/systemd/`;
  - `scripts/backup`, `scripts/restore`;
  - `.env.prod.example`;
  - `server/lib/risime/release.ex`.

## 1. Architecture and ports

```
phones ──HTTPS──▶ tailscale serve (spark2) ──▶ 127.0.0.1:4500 ─▶ server  (container, :4000)
                                                                  │  docker network "risime-prod"
                                         127.0.0.1:5442 ─▶ postgres  (:5432, DB risime_prod)
                                         127.0.0.1:9142 ─▶ cassandra (:9042, keyspace risime_prod)
```

| | Prod (`risime-prod`) | Dev (`risime-dev`) |
|---|---|---|
| Server | container, `127.0.0.1:4500` | `mix phx.server` in tmux, `127.0.0.1:4000` (load test: 4100) |
| Postgres 17 + pgvector | `127.0.0.1:5442`, DB `risime_prod`, volume `risime-prod-pgdata` | 5432, `risime_dev` |
| Cassandra 5 | `127.0.0.1:9142`, keyspace `risime_prod`, volume `risime-prod-cassdata` | 9042, `risime_dev` |
| Secrets | `.env.prod` | `.env` |

- Every published port is loopback-only. spark2 has a public IP, and Docker publishing bypasses
  ufw. The only way in from outside is Tailscale.
- **Images:**
  - `pgvector/pgvector:0.8.7-pg17-bookworm` and `cassandra:5.0.9` (pinned, multi-arch);
  - `risime-server:<version>`, built locally from a release tag (see §4).
- **Limits:**
  - Postgres: 2 CPUs, 4 GB;
  - Cassandra: 4 CPUs, 8 GB (4 GB heap);
  - server: 4 CPUs, 2 GB.

  Logs are json-file, 5 × 50 MB per container.
- **Health:** Postgres uses `pg_isready` and Cassandra uses `cqlsh`. The server uses
  `GET /health`, which is 200 only when both DBs answer.
- **The `migrate` service** runs once on every `up`: `bin/risime eval
  "RisiMe.Release.migrate()"`. The server starts only after it succeeds.

### Directory layout on spark2

| Path | What |
|---|---|
| `~/development/risime/.env.prod` | prod secrets (gitignored, `chmod 600`) |
| `~/risime-run/<tag>/` | clean worktree of a release tag (the same scheme as the dev test server, decision 006) |
| `~/risime-prod/current` | symlink → `~/risime-run/<deployed tag>`; systemd uses this compose file and scripts |
| `~/risime-run/<tag>/.env.prod` | symlink → `~/development/risime/.env.prod` (for `scripts/backup`) |

**Never `git worktree remove` the tag that `~/risime-prod/current` points to.**

## 2. Prerequisites

### P1 — server: prod Cassandra config (server session)
`config/runtime.exs` does not yet configure Cassandra for prod. A release would fall back to
`config.exs` (`127.0.0.1:9042`, keyspace `risime_dev`), which is wrong and unreachable in the
container. Add this to the `if config_env() == :prod do` block (the compose file already sets both
variables):

```elixir
config :risime, :cassandra,
  nodes: "CASSANDRA_NODES" |> System.get_env("127.0.0.1:9042") |> String.split(",", trim: true),
  keyspace: System.get_env("CASSANDRA_KEYSPACE", "risime_prod")
```

`RisiMe.Release.migrate_cql/1` reads the same config, so the migrations follow automatically.

### P2 — SMTP for OTP mail
Prod has no dev mailbox: `swoosh local: false`, and `RISIME_MAILER=smtp` is the compose default.
Fill in `SMTP_*` in `.env.prod`, or OTP emails fail.

### P3 — the first prod tag must include these files
Tags older than this work (≤ v0.1.0) have no `infra/Dockerfile.server` or `RisiMe.Release`. Deploy
0.2.0 or later.

### Needs Harsha (sudo or his accounts)
1. **Linger**, so the user units run without a login and survive reboots:
   `sudo loginctl enable-linger harsha`. It is currently `Linger=no`.
2. **Tailscale.** Expose prod over HTTPS on the tailnet, for example
   `tailscale serve --bg --https=8443 http://127.0.0.1:4500`, or a separate prod hostname / port
   443 if the dev server moves.
   - If `tailscale serve` refuses without root, run `sudo tailscale set --operator=harsha` once.
   - HTTPS certificates must be enabled in the tailnet admin console.
   - Set `PHX_HOST` to the MagicDNS name.
3. **DNS/TLS for a public domain** (later). It isn't needed while access is tailnet-only.
   - If it is ever public: a reverse proxy with TLS in front of 127.0.0.1:4500 that sets
     `X-Forwarded-Proto`. The server's `force_ssl` honours that header.
   - Don't publish 4500 itself.
4. **Off-box backup host.** Provide a host, a user and a path, for example
   `backup@nas:/srv/risime-backups`.
   - Create a key on spark2: `ssh-keygen -t ed25519 -f ~/.ssh/risime_backup`.
   - Add an entry for the host in `~/.ssh/config` with `IdentityFile`.
   - Put the public key in the backup user's `authorized_keys` on the host. Ideally restrict it to
     that path, for example with `rrsync`.
   - The host needs `rsync` and `sha256sum`.
   - Until this exists, `BACKUP_DEST` is a local directory on spark2. That protects against
     operator error, not against losing the disk.
5. **SMTP credentials** (P2).

## 3. Secrets (`.env.prod`)
```bash
cd ~/development/risime
cp .env.prod.example .env.prod && chmod 600 .env.prod
# POSTGRES_PASSWORD, RELEASE_COOKIE: openssl rand -hex 32   (hex only: it goes into DATABASE_URL)
# SECRET_KEY_BASE:                   openssl rand -hex 64
# PHX_HOST, SMTP_*, BACKUP_DEST, BACKUP_KEEP, RISIME_VERSION
```
- Never commit `.env.prod`; it is gitignored.
- Don't reuse dev values.
- Back up `.env.prod` itself out of band, together with the release keystore (decision 003).
  Without it, the backups still restore, but sessions and tokens are invalidated.
- **`POSTGRES_PASSWORD` is only applied when the volume is first initialised.** Changing it later
  means `ALTER USER` inside Postgres as well.

## 4. First deploy
Do these steps after P1–P3 and the Harsha items above.
```bash
TAG=v0.2.0; V=${TAG#v}
cd ~/development/risime && git fetch origin --tags
[ -e ~/risime-run/$TAG ] || git worktree add --detach ~/risime-run/$TAG $TAG
ln -sfn ~/development/risime/.env.prod ~/risime-run/$TAG/.env.prod
mkdir -p ~/risime-prod && ln -sfn ~/risime-run/$TAG ~/risime-prod/current

# 1. image (arm64 on spark2; about 3 min cold, seconds when only lib/ changed)
docker build -f ~/risime-run/$TAG/infra/Dockerfile.server -t risime-server:$V ~/risime-run/$TAG
sed -i "s/^RISIME_VERSION=.*/RISIME_VERSION=$V/" .env.prod

# 2. validate, then start (the first Cassandra boot takes 1–2 min)
C="docker compose --env-file $HOME/development/risime/.env.prod -f $HOME/risime-prod/current/infra/docker-compose.prod.yml"
$C config -q
$C up -d --wait --wait-timeout 600
$C ps                                    # all healthy; migrate "exited (0)"
$C logs migrate                          # "Migrations already up" / "risime_prod: applied 001_messaging.cql"
curl -s http://127.0.0.1:4500/health     # {"status":"ok",...}

# 3. allowlist (no mix in a release: rpc into the running node, same function as mix risime.allow)
docker exec risime-prod-server-1 bin/risime rpc \
  'IO.inspect(RisiMe.Accounts.allow(%{phone: "+94…", email: "…", display_name: "…", company: "Rise"}))'
docker exec risime-prod-server-1 bin/risime rpc 'IO.inspect(RisiMe.Accounts.list_allowlist())'

# 4. systemd (needs linger first)
mkdir -p ~/.config/systemd/user
ln -sf ~/development/risime/infra/systemd/risime-{prod.service,backup.service,backup.timer} ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now risime-prod.service risime-backup.timer
systemctl --user list-timers risime-backup.timer

# 5. tailscale (see the Harsha items), then from a phone: Settings → server URL = https://<PHX_HOST>[:8443]
curl -sI https://<PHX_HOST>[:8443]/health   # must be 200, not a 301 loop (force_ssl + X-Forwarded-Proto)
```
- The allowlist tasks (`mix risime.allow`) are Mix tasks and are not in the release.
  `bin/risime rpc` runs the same context function in the running node.
- Run a first manual backup and a restore drill (§6) before telling the team.

## 5. Migrations
- **In prod:** `RisiMe.Release.migrate()` runs Ecto (`priv/repo/migrations`), then CQL
  (`priv/cql/*.cql`, tracked in `<keyspace>.cql_migrations`, the same bookkeeping as
  `mix risime.cql.migrate`). It runs automatically through the `migrate` service on every `up`.
- **Manually:**

  ```bash
  $C run --rm migrate                                                  # both
  docker exec risime-prod-server-1 bin/risime eval "RisiMe.Release.migrate_ecto()"
  docker exec risime-prod-server-1 bin/risime eval "RisiMe.Release.rollback(RisiMe.Repo, <version>)"
  ```
- **Rules (decision 006):**
  - Migrations are **additive**: new tables, new nullable columns.
  - Destructive changes (drop or rename) take two releases: stop using it first, remove it a
    release later.
  - CQL migrations have no rollback.

  This is what makes image-only rollbacks safe.

## 6. Backups and restore
The `risime-backup.timer` runs `scripts/backup --env prod` nightly at 21:30 UTC (03:00 in Sri
Lanka).

**Each backup** goes to `BACKUP_DEST` as `risime-prod-<UTC stamp>/` and contains:
- `postgres.dump`: `pg_dump -Fc`;
- `postgres.list`: proof that the dump is readable with `pg_restore --list`;
- `postgres-globals.sql`;
- `cassandra-schema.cql`;
- `cassandra-data.tar.gz`: a `nodetool snapshot`, which flushes first; the snapshot is cleared
  afterwards;
- `MANIFEST` and `SHA256SUMS`.

The checksums are verified again after the copy. Retention keeps the newest `BACKUP_KEEP` (14).

```bash
scripts/backup --env prod                              # now, using BACKUP_DEST/BACKUP_KEEP from .env.prod
scripts/backup --env prod --dest backup@nas:/srv/risime-backups --keep 30
journalctl --user -u risime-backup.service -n 50       # last runs
scripts/restore list --env prod                        # what is there
scripts/restore fetch backup@nas:/srv/risime-backups/risime-prod-20261010T213000Z ~/restore
scripts/restore verify ~/restore/risime-prod-20261010T213000Z
```

### Restore drill (monthly, harmless)
Restore into *new scratch* targets next to live, compare, then drop them:
```bash
B=~/restore/risime-prod-<stamp>
scripts/restore postgres  $B --env prod --target-db risime_restore_check       --confirm risime_restore_check
scripts/restore cassandra $B --env prod --target-keyspace risime_restore_check --confirm risime_restore_check
docker exec risime-prod-postgres-1 psql -U risime -d postgres -c 'DROP DATABASE risime_restore_check'
docker exec risime-prod-cassandra-1 cqlsh -e 'DROP KEYSPACE risime_restore_check'
docker exec risime-prod-cassandra-1 nodetool clearsnapshot --all -- risime_restore_check
```
This exact flow was tested against dev on 2026-10-05: 2001 users, and 282 805 inbox events
across 4 tables, restored and counted.

### Real restore (disaster)
```bash
$C stop server                         # no writes during the restore
scripts/restore postgres  $B --env prod --confirm risime_prod     # pg_restore --clean, one transaction
scripts/restore cassandra $B --env prod --confirm risime_prod     # TRUNCATE all tables (auto-snapshot keeps a copy), nodetool import
$C up -d --wait
```
- **The two stores are backed up seconds apart, not atomically.** After a restore, Postgres
  (users, tokens) and Cassandra (messages) can disagree by the writes in that window.
  Store-and-forward clients re-sync, and messages from users who don't exist are harmless.
- On a fresh machine, deploy first (§4) so the volumes, DB and keyspace exist, then restore.

## 7. Upgrades
```bash
TAG=v0.3.0; V=${TAG#v}
scripts/backup --env prod                                             # 1. backup first, always
git -C ~/development/risime fetch --tags
git -C ~/development/risime worktree add --detach ~/risime-run/$TAG $TAG   # if not there yet
ln -sfn ~/development/risime/.env.prod ~/risime-run/$TAG/.env.prod
docker build -f ~/risime-run/$TAG/infra/Dockerfile.server -t risime-server:$V ~/risime-run/$TAG
# 2. read docs/releases/$TAG.md and diff infra/ between the tags (new env vars? → .env.prod)
git -C ~/development/risime diff <old tag> $TAG -- infra/ .env.prod.example server/priv/
# 3. switch
sed -i "s/^RISIME_VERSION=.*/RISIME_VERSION=$V/" ~/development/risime/.env.prod
ln -sfn ~/risime-run/$TAG ~/risime-prod/current
systemctl --user restart risime-prod.service                # migrate runs, then the server is recreated
curl -s http://127.0.0.1:4500/health; $C ps; $C logs --since 5m server | tail
```
- The DB containers are only recreated when their image or config changes. Image bumps for
  Postgres or Cassandra are separate, deliberate upgrades:
  - **Postgres major versions need `pg_upgrade` or a dump/restore.** Never just change the
    `pg17` tag.
  - Cassandra minor upgrades are a tag change plus `nodetool upgradesstables`.
- Downtime is the server recreate: a few seconds. Clients reconnect and re-sync.

## 8. Rollback checklist
1. [ ] Confirm the problem is the new release, not the infrastructure: `$C ps`, `/health`, and
   `$C logs server`.
2. [ ] Set `RISIME_VERSION` back to the previous version in `.env.prod`. Its image is still
   there (`docker images risime-server`). Point `~/risime-prod/current` back at the previous
   tag's worktree.
3. [ ] `systemctl --user restart risime-prod.service`. The migrate step is a no-op, because the
   newer migrations stay applied; they are additive (§5).
4. [ ] Check `/health`, then log in from a phone, then send and receive a message.
5. [ ] Only if a migration broke data: stop the server and restore the pre-upgrade backup (§6
   "Real restore").
   - Use `RisiMe.Release.rollback/2` only for a migration that is known to be reversible.
   - Messages sent since that backup are lost from the server; phones keep their history.
6. [ ] Write down what happened in `docs/releases/<tag>.md` and fix forward on `main`.
7. [ ] Old images are kept until the next successful release; prune them with
   `docker image rm risime-server:<old>`.

## 9. Day-to-day
```bash
C="docker compose --env-file $HOME/development/risime/.env.prod -f $HOME/risime-prod/current/infra/docker-compose.prod.yml"
$C ps; $C logs -f server                                # JSON logs (LOG_FORMAT=json default in prod)
docker exec -it risime-prod-server-1 bin/risime remote  # IEx in the running node
docker exec -it risime-prod-postgres-1 psql -U risime risime_prod
docker exec -it risime-prod-cassandra-1 cqlsh            # USE risime_prod;
docker exec risime-prod-cassandra-1 nodetool status
systemctl --user status risime-prod.service risime-backup.timer
```
Stopping prod (`systemctl --user stop risime-prod`) runs `compose stop` and keeps the containers.
`$C down` removes the containers but keeps the named volumes. **Never** run `down -v`.


## Erlang distribution / epmd
On the host, epmd (port 4369) binds 0.0.0.0 by default, and spark2 has a public IP. Anything that
starts a named node or runs `bin/risime` outside the container must set
`ERL_EPMD_ADDRESS=127.0.0.1`, or `RELEASE_DISTRIBUTION=none` for one-off `eval` commands.
Inside the prod containers this isn't exposed, because no epmd port is published.
On 2026-10-06 a stray host epmd was found on 0.0.0.0:4369, left over from release testing, and
was stopped.


## Pilot on spark2 (decision 023)
**Edge:** Caddy (`infra/caddy/Caddyfile`) → `127.0.0.1:4000`. **App:** a prod-mode release from the
latest green tag (`scripts/run-server <tag>`; `scripts/nightly-release` switches it). **Config:**
secrets from `.env`, settings from `infra/pilot/pilot.env`.

### One-time setup (Harsha, sudo)
```bash
# 1. Caddy (official apt repository)
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
sudo install -m 644 /home/harsha/development/risime/infra/caddy/Caddyfile /etc/caddy/Caddyfile
sudo -u caddy caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
# Always validate AS THE caddy USER: validating as root creates the access log root-owned (0600)
# and the next reload fails with "open /var/log/caddy/risime-access.log: permission denied".

# 2. Firewall on spark2
sudo ufw allow 80/tcp comment 'RisiMe Caddy (ACME + redirect)'
sudo ufw allow 443/tcp comment 'RisiMe Caddy HTTPS'

# 3. fail2ban jail for auth failures
sudo install -m 644 /home/harsha/development/risime/infra/fail2ban/filter.d/risime-auth.conf /etc/fail2ban/filter.d/risime-auth.conf
sudo install -m 644 /home/harsha/development/risime/infra/fail2ban/jail.d/risime.local /etc/fail2ban/jail.d/risime.local
sudo systemctl reload fail2ban && sudo fail2ban-client status risime-auth

# 4. Remove Tailscale (dropped)
sudo tailscale down
sudo apt remove --purge -y tailscale
sudo rm -f /etc/apt/sources.list.d/tailscale.list /usr/share/keyrings/tailscale-archive-keyring.gpg
sudo rm -rf /var/lib/tailscale
# then delete the node "gx10-e23a" in the Tailscale admin console
```

### Outside spark2 (ask)
- **DNS:** `risime.risicloud.ai.  A  203.115.26.139` (TTL 300). Add `AAAA` only if spark2 gets
  public IPv6.
- **Network firewall/NAT:** inbound **TCP 80 and 443** from anywhere to `203.115.26.139`, forwarded
  to spark2 `10.20.20.15:80/443`.

### Checks
```bash
curl -sI https://risime.risicloud.ai/health        # 200, HSTS header, valid Let's Encrypt cert
curl -s  https://risime.risicloud.ai/api/v1/auth/config
curl -s -o /dev/null -w '%{http_code}\n' https://risime.risicloud.ai/dev/mailbox   # 404
ss -ltnp | grep -E ':(80|443|4000|4369) '           # 80/443 caddy; 4000 on 127.0.0.1 only; no 4369
```


### Pilot service, backups, rollback (decision 025)
```bash
# one time (sudo): start the user manager at boot so risime.service runs without a login
sudo loginctl enable-linger harsha
# units (no sudo; already installed on spark2)
for u in risime.service risime-health.service risime-health.timer; do install -Dm644 infra/systemd/user/$u ~/.config/systemd/user/$u; done
systemctl --user daemon-reload && systemctl --user enable --now risime.service risime-health.timer
# day to day
systemctl --user status risime.service          # state
tail -f ~/risime-logs/server.log                # app log (JSON)
tail ~/risime-logs/health.log                   # health-check actions (empty = all ok)
scripts/run-server vX.Y.Z                       # deploy: backup (keep 7) -> migrate -> switch
scripts/rollback                                # previous tag (code only)
scripts/restore list --env dev --dest ~/risime-backups
```
