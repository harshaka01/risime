# RisiMe monitoring (pilot on spark2)

Started for the P0 incident of 2026-10-10 (docs/status/incident-2026-10-10.md). The monitoring console
(decision 075) is described under "Monitoring console" below.

## Watchdog (running)
- `risime-watchdog.timer` (systemd user unit, every 30 s; files in `infra/pilot/systemd/`) runs `scripts/watchdog`.
- Each tick: `http://127.0.0.1:4000/health` and `https://risime.risicloud.ai/health`, 5-s timeout each. Skips
  while a deploy holds `~/risime-run/.deploy.lock`.
- **3 local failures in a row:** `scripts/capture-diag` (a snapshot in `~/risime-logs/diag/<time>/`: health timings,
  journals, memory/swap, BEAM process, containers, Cassandra tpstats/gcstats, Postgres activity, network),
  then `scripts/safe-restart --no-backup` (restart → 60-s watch → one retry → rollback), then an alert:
  `restarted`, `rolled_back` or `gave_up`.
- **Restart limit:** 3 per hour. The 4th time: alert `gave_up`, create `~/risime-run/.watchdog-gaveup` and stop
  restarting. It clears by itself when the server is healthy again (alert `recovered`), or `rm` the file.
- **Public only (local ok) 3 in a row:** diagnostics + one `public_down` alert, no restart (the network path or
  Caddy, which a restart can't fix); `recovered` when it answers again.
- The 5-min `risime-health.timer` (scripts/healthcheck) still runs the outage self-check, but leaves restarts to
  the watchdog while the watchdog timer is active.
- Logs: `~/risime-logs/watchdog.log`, `~/risime-logs/restarts.log`. Test: `scripts/watchdog-test`
  (fake health server, dry mode). `scripts/watchdog --test-alert` sends a harmless test alert.

### Alerts
1. A Risi message to each operator in their own Risi chat (contract §32, rule kind `ops_alert`), through
   `POST /internal/ops-alert` on 127.0.0.1:4000 only, with `OPS_ALERT_TOKEN`; refused through Caddy.
2. If that isn't delivered (server down, Risi off): e-mail via SMTP, and SMS via Notify.lk when `OPS_ALERT_SMS=on`.

`.env` keys (never in Git): `OPS_ALERT_TOKEN` (generated), `OPS_ALERT_PHONES` (comma-separated, E.164),
`OPS_ALERT_EMAIL`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`, `OPS_ALERT_SMS`.

## BEAM internals
The pilot runs with `RELEASE_DISTRIBUTION=none`, so there is no remote shell for run queue, mailbox and pool
statistics. The planned fix is loopback-only distribution (node `risime@127.0.0.1`, `ERL_EPMD_ADDRESS=127.0.0.1`,
`inet_dist_use_interface {127,0,0,1}`) or PromEx metrics; it lands with the monitoring console.

## Monitoring console (decision 075)
Files: `infra/monitoring/` (compose project `risime-mon`, configs, provisioned dashboards, bridge, systemd units).
Everything runs with host networking and each service binds **127.0.0.1 only** through its own flag
(verify: `ss -ltnp | grep -v 127.0.0.1` shows nothing new). Pinned multi-arch images, memory limits on all
(stack total about 0.7 GB RSS idle; limits sum to 3.3 GB).

### Components and ports (all 127.0.0.1)
| Port | What |
|---|---|
| 9090 | Prometheus (retention 15 d / 10 GB), rules in `infra/monitoring/prometheus/rules/` |
| 9093 | Alertmanager (no cluster port); only receiver: the bridge below |
| 9099 | `am-bridge.py` (systemd user unit `risime-am-bridge`): Alertmanager webhook to `POST 127.0.0.1:4000/internal/ops-alert` |
| 9100 | node_exporter (host, swap, disk) plus the textfile collector (GPU, Cassandra, restarts) |
| 9187 | postgres_exporter (dev Postgres on 127.0.0.1:5432; uses the existing `risime` user from `.env`, a read-only `pg_monitor` role would need a DB change: later) |
| 9115 | blackbox_exporter (probes below) |
| 3100 / 9096 | Loki (retention 7 d), http / grpc |
| 12345 | Grafana Alloy (ships `~/risime-logs/*.log`, `server.log` JSON level, and the system journal) |
| 3000 | Grafana (anonymous off; admin password `GRAFANA_ADMIN_PASSWORD` in `.env`) |
| 3001 | Uptime Kuma |
| 4180 / 4181 | oauth2-proxy (profile `oidc`, not started until `MONITOR_OIDC_CLIENT_SECRET` exists): 4180 Grafana + LiveDashboard (`/dashboard/` -> 127.0.0.1:4021), 4181 Uptime Kuma admin |
| 4021 | the server's PromEx `/metrics` + LiveDashboard (server role; scrape is DOWN until it ships) |

Scraped read-only: vLLM `127.0.0.1:8100/metrics` (the container is never touched).
**Cassandra:** JMX is container-local (7199 not reachable), so `scripts/mon-textfile` runs `nodetool gcstats|tpstats|info`
via `docker exec` every 30 s (user timer `risime-mon-textfile.timer`) and writes
`~/risime-monitoring/textfile/risime.prom` (max GC pause since the previous run, pending/blocked tasks, heap). The same
file carries the GPU metrics from `nvidia-smi` (GB10 has no DCGM and no `memory.used`; per-process memory is listed) and
`risime_restarts_logged` (count in `restarts.log`).
**Grafana auth:** `auth.proxy` (header `X-Forwarded-Email` set by oauth2-proxy, auto sign-up, role Admin because only
`risime-admins` get through); Grafana listens on loopback only so the header cannot be forged from outside. The local
`admin` login stays for API use and tunnels.

### Start / stop
```
cd ~/development/risime
docker compose -f infra/monitoring/docker-compose.monitoring.yml --env-file .env up -d          # stack (no login proxy)
docker compose -f infra/monitoring/docker-compose.monitoring.yml --env-file .env --profile oidc up -d   # + oauth2-proxy
docker compose -f infra/monitoring/docker-compose.monitoring.yml --env-file .env down           # stop (volumes kept)
curl -s -XPOST 127.0.0.1:9090/-/reload                                                          # reload Prometheus rules
systemctl --user enable --now risime-am-bridge.service risime-mon-textfile.timer                # (already done; units copied to ~/.config/systemd/user/)
scripts/am-bridge-test                                                                          # bridge unit test
```
Local look without login: `ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 spark2` then open http://127.0.0.1:3000 (admin).
Dashboards (folder RisiMe) are generated by `infra/monitoring/grafana/gen-dashboards.py` (Overview, Risi, Infra).
PromEx metric names in the dashboards and rules (`risime_prom_ex_phoenix_http_request_duration_milliseconds`,
`risime_prom_ex_risi_*`, `risime_jwks_fetch_total{result="error"}`) are best guesses until `:4021/metrics` is live; align
them in the rules and in `gen-dashboards.py` then. vLLM panels already show data.

### Probes (blackbox)
`https://risime.risicloud.ai/health` (body must contain `"status":"ok"`), `http://127.0.0.1:4000/health`,
Keycloak `https://risicloud.ai/realms/aoa` (public) and the **LAN path** `https://10.20.20.14/realms/aoa` (Host/SNI
`risicloud.ai`), TCP TURN `203.115.26.139:3478` (public) and `10.20.20.15:3478` (LAN), LiveKit `127.0.0.1:7880`.
TLS expiry comes from the two HTTPS probes. Note: spark2 is behind the same firewall, so a "public" probe from here may
be hairpinned and faster than a real outside client; Uptime Kuma (below) and an outside check catch what these cannot.

### Alerts (Prometheus -> Alertmanager -> bridge -> contract §32 `alert` / `resolved`, `check` = alert name in lower snake case)
| Alert (`check`) | Fires when |
|---|---|
| HealthDown (`health_down`) | a /health probe fails for 2 min |
| P95Latency (`p95_latency`) | PromEx request p95 > 2 s for 5 min |
| RamHigh (`ram_high`) | RAM used > 90 % for 5 min |
| SwapGrowing (`swap_growing`) | swap used > 50 % and growing (deriv over 30 min > 0) for 10 min |
| DiskHigh (`disk_high`) | `/` > 85 % for 10 min |
| CassandraGc (`cassandra_gc`) | max GC pause > 1 s |
| CertExpiry (`cert_expiry`) | TLS cert < 14 days |
| JwksFetchFailures (`jwks_fetch_failures`) | `risime_jwks_fetch_total{result="error"}` increases in 15 min (best-guess name) |
| PublicConnectSlow (`public_connect_slow`) | public-path connect time > 3 s for 3 min |
| TargetDown (`target_down`) | a core exporter is down 5 min |

Alertmanager groups by alert name, `group_interval` 5 m, `repeat_interval` 4 h (the endpoint allows 30 per hour). The
bridge falls back to e-mail (`SMTP_*` + `OPS_ALERT_EMAIL`, STARTTLS) when `sent < 1` or `held > 0`; the log is
`~/risime-logs/am-bridge.log`. Until the server role deploys `/internal/ops-alert` and `OPS_ALERT_EMAIL` is set in
`.env`, alerts are only logged there.

### What Harsha must do (needs sudo, DNS or Keycloak)
1. **DNS:** A records `monitor.risicloud.ai`, `uptime.risicloud.ai` and `status.risicloud.ai` -> `203.115.26.139`
   (the same VIP as `risime.risicloud.ai`; the FortiGate already forwards 80/443 to spark2).
2. **Keycloak** (realm `aoa`): create group `risime-admins` and add yourself (and other admins). Create client `monitor`:
   OpenID Connect, client authentication ON (confidential), standard flow, valid redirect URIs
   `https://monitor.risicloud.ai/oauth2/callback` and `https://uptime.risicloud.ai/oauth2/callback`. Add a mapper to
   the client's dedicated scope: type "Group Membership", token claim name `groups`, **full group path OFF**, add to
   ID token and access token. Copy the client secret into `.env` as `MONITOR_OIDC_CLIENT_SECRET=...`, then run the
   `--profile oidc up -d` command above.
3. **Caddy** (edit via `sudo`, not by this repo): add to `/etc/caddy/Caddyfile`
```
monitor.risicloud.ai {
	encode zstd gzip
	reverse_proxy 127.0.0.1:4180
}
uptime.risicloud.ai {
	reverse_proxy 127.0.0.1:4181
}
# Public status page only (Uptime Kuma slug "risime" once created); the admin UI is not reachable here.
status.risicloud.ai {
	encode zstd gzip
	@page path /status/* /assets/* /api/status-page/* /icon.svg /upload/* /socket.io/*
	handle @page {
		reverse_proxy 127.0.0.1:3001
	}
	handle / {
		redir /status/risime 302
	}
	handle {
		respond 404
	}
}
```
   then `sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy`.
   (Kuma's websocket `/socket.io/` is also the admin channel: it needs Kuma's own login, which stays on.) Also mirror
   the blocks into `infra/caddy/Caddyfile` (root role does this once you confirm the host names).
4. **Uptime Kuma** (first visit through `uptime.risicloud.ai` or `ssh -L 3001:127.0.0.1:3001 spark2`): create the admin
   account, add monitors (`https://risime.risicloud.ai/health` keyword `ok`, `https://risicloud.ai/realms/aoa`, TURN
   TCP, a push monitor if wanted), create a status page with slug `risime`, publish it.
5. **Caddy log into Loki** (Caddy's log is root-only): `sudo setfacl -m u:harsha:r /var/log/caddy/*.log && sudo setfacl -d -m u:harsha:r /var/log/caddy`
   (or `sudo usermod -aG caddy harsha` and re-login), then add a `/var/log/caddy` mount and a file_match to
   `infra/monitoring/alloy/config.alloy` (root role).
6. **Alerts by e-mail:** set `OPS_ALERT_EMAIL` and the `SMTP_*` keys in `.env` (the bridge re-reads `.env` on every alert).
7. Optional later: a read-only Postgres role (`CREATE ROLE monitor LOGIN PASSWORD '...' IN ROLE pg_monitor;`) so the exporter
   stops using the application user.
