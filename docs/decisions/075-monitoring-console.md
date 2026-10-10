# 075: Monitoring console, PromEx, loopback-only BEAM distribution

Date: 2026-10-10. Context: P0 incident 2026-10-10 (docs/status/incident-2026-10-10.md); Harsha's step 4.

## Decision
- **Stack:** Prometheus, Alertmanager, Grafana, Loki with Grafana Alloy (the maintained successor of Promtail),
  node_exporter, postgres_exporter, blackbox_exporter (plus an Uptime Kuma status page),
  a GPU exporter from `nvidia-smi`, and Cassandra metrics through the JMX exporter. All of it runs in
  `infra/monitoring/docker-compose.monitoring.yml` (multi-arch images only), with every port on
  `127.0.0.1`.
- **Server metrics:** PromEx (Phoenix, Ecto, Oban, BEAM, plus our own Risi metrics). It serves `/metrics`
  and Phoenix LiveDashboard on a **separate loopback-only port** (`127.0.0.1:4021`). It is never on :4000,
  so Caddy's `risime.risicloud.ai` can't reach either.
- **Access:** `monitor.risicloud.ai` → Caddy → oauth2-proxy → Grafana, Uptime Kuma and LiveDashboard.
  oauth2-proxy uses Keycloak (realm `aoa`, client `monitor`) and admits only the Keycloak group
  `risime-admins` (Harsha + admins). The public status page is the one path without login.
- **Alerts:** Alertmanager → a loopback bridge (`infra/monitoring/am-bridge.py`, systemd user unit) →
  `POST /internal/ops-alert` (contract §32, states `alert` / `resolved`). If the Risi message isn't
  delivered, the bridge falls back to e-mail / SMS, like scripts/watchdog.
- **BEAM shell:** the release may run with `RELEASE_DISTRIBUTION=name`, `RELEASE_NODE=risime@127.0.0.1`,
  `ERL_EPMD_ADDRESS=127.0.0.1`, `-kernel inet_dist_use_interface {127,0,0,1}` and a fixed distribution
  port. Nothing listens beyond loopback. The cookie lives in `.env`. This amends decision 024 (no
  distribution) for diagnostics only.

## Why
The incident showed we couldn't see run queues, mailboxes or pool state, and had no history of
latency, memory or network stalls. Every piece is a standard, multi-arch, self-hosted component.
Keycloak is already our identity provider.
