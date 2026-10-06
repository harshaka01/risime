# 023 — Public pilot served directly from spark2 at https://risime.risicloud.ai

**Status:** accepted 2026-10-06 (Harsha). **Supersedes option 2b of decision 017** (no Tailscale,
no proxy on spark) and **amends decision 006** (the test server becomes a prod-mode release).

## Decision
- **Edge:** **Caddy on spark2** (a host package, systemd) serves `risime.risicloud.ai` with
  automatic HTTPS (Let's Encrypt), and reverse-proxies `/api/*`, `/socket/*` and `/health` to
  **`127.0.0.1:4000`**. Everything else is 404 at the edge.
  - The config is `infra/caddy/Caddyfile` (installed as `/etc/caddy/Caddyfile`), validated with
    Caddy 2. Caddy's admin API stays on 127.0.0.1.
  - **Phoenix stays bound to 127.0.0.1** (golden rule 6). Only Caddy listens on public ports.
- **Network:** DNS `A risime.risicloud.ai → 203.115.26.139` (spark2's public IP, NAT'd to
  `10.20.20.15`). The network firewall must pass inbound **TCP 80 and 443** to spark2. Port 80 is
  needed for the ACME HTTP-01 challenge and the HTTPS redirect. ufw on spark2 allows 80/tcp and
  443/tcp.
- **The app runs in prod mode from a release:** `MIX_ENV=prod mix release`, built from the
  **latest green tag** in its worktree `~/risime-run/<tag>` and started by `scripts/run-server
  <tag>`. `scripts/nightly-release` switches it only after the gates pass. There is no
  `mix phx.server`, no dev routes (`/dev/mailbox`), no debug error pages, and no Erlang
  distribution/epmd. `check_origin` is limited to `https://risime.risicloud.ai`. Secrets come from
  the repo `.env`; non-secret pilot settings come from `infra/pilot/pilot.env`.
- **Data:** the pilot release uses the **existing databases** (Postgres `risime_dev`, Cassandra
  keyspace `risime_dev`), so the allowlist, users and history carry over. The separate prod
  environment (decision 010, its own containers and `risime_prod`) remains the target for the
  real launch. The pilot is the only consumer of these databases besides root's tooling.
- **Dev login** (`DEV_LOCAL_AUTH=true`) stays enabled only until Keycloak is live. Codes appear
  **only in the server log** (`[DEV OTP]`, `OTP_DEV_LOG=true`), never in an HTTP response.
  Disable it the day `OIDC_ENABLED=true` works.
- **Abuse controls:**
  - All existing rate limits stay, plus a **per-IP limit on `POST /auth/request`**. The client IP
    comes from `X-Forwarded-For` only when the request arrives from loopback (Caddy); Caddy
    replaces any client-supplied XFF.
  - Every auth failure writes one plain line to `~/risime-logs/auth.log`:
    `<ISO time> risime auth_failure ip=<ip> kind=<kind> path=<path>`. It never contains a phone,
    email, token or code.
  - **fail2ban** (`infra/fail2ban/`, filter tested with `fail2ban-regex`) bans an IP through ufw
    after 10 failures in 10 minutes, for 1 hour. Loopback and the LAN are never banned.
- **Release clients:** the default server URL is **`https://risime.risicloud.ai`**. Downloads and
  `version.json` stay at `https://risicloud.ai/app/risime/` (decision 016).
- **Tailscale is removed from spark2** (Harsha, sudo). `docs/TAILSCALE.md` and the 2b config in
  `infra/risicloud/` are kept only as history.

## Consequences
- spark2 now has public ports 80 and 443. The attack surface is Caddy plus the proxied app paths.
  Postgres, Cassandra, Phoenix and epmd stay on loopback; Docker publishes only `127.0.0.1:…`.
- Let's Encrypt needs ports 80 and 443 reachable from the internet before the first certificate.
  Until DNS and the firewall are in place, Caddy retries.
- `/health` is public. It exposes only ok/error states and the version, never balances or secrets.
