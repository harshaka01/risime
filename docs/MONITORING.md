# RisiMe monitoring (pilot on spark2)

Started for the P0 incident of 2026-10-10 (docs/status/incident-2026-10-10.md). The monitoring console
(monitor.risicloud.ai: Prometheus, Grafana, Loki, Alertmanager, status page) comes next and is added here.

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
