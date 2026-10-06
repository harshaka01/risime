# 024 — Server: the prod-mode release, per-IP auth limits and a fail2ban auth log

## Context
Harsha moved the public instance to `https://risime.risicloud.ai`, served directly from
spark2: Caddy on spark2 terminates TLS and proxies to `127.0.0.1:4000`. It runs as a prod-mode
release against the existing `risime_dev` databases until the prod environment exists (decision
023, root). The server is now reachable from the internet, so the auth endpoints need per-IP
limits and a log fail2ban can read.

## Decision
- **Runtime config is env-driven** (`config/runtime.exs`, prod):
  - `PHX_HOST` (default `risime.risicloud.ai`), `PHX_PATH` (default `/`);
  - `PHX_ORIGINS` is a comma-separated list for `check_origin`, default `https://$PHX_HOST`;
  - `PORT` defaults to 4000;
  - **`PHX_BIND` must be a loopback address.** The default is `127.0.0.1`; anything else
    (`0.0.0.0`, `::`, a LAN address) makes boot raise;
  - Postgres comes from `DATABASE_URL`, or from `POSTGRES_PASSWORD` plus
    `POSTGRES_HOST/PORT/DB/USER`;
  - `CASSANDRA_NODES` and `CASSANDRA_KEYSPACE`;
  - `SECRET_KEY_BASE` is required;
  - `OTP_DEV_LOG` is honoured in prod: codes go to the server log only.
- **No Erlang distribution.** `rel/env.sh.eex` sets `RELEASE_DISTRIBUTION=none` (so epmd is
  never started), plus `ERL_EPMD_ADDRESS=127.0.0.1` in case distribution is ever turned on
  deliberately. `bin/risime remote` is unavailable by design; use `bin/risime eval`.
- **Prod safety:**
  - `dev_routes: false` (no `/dev/mailbox`), `debug_errors: false`, no code reloader;
  - without SMTP, the mailer is `RisiMe.Mailer.Disabled`: it sends nothing and logs one line
    without recipient, subject or body (the subject carries the code);
  - `DEV_LOCAL_AUTH=true` stays allowed in prod until Keycloak is live, with the boot warning.
  - Tests prove that codes never appear in an HTTP response or in `/health`.
- **Client IP** (`RisiMeWeb.ClientIP`). `X-Forwarded-For` is trusted only when the TCP peer is
  loopback (Caddy on the same host); its last entry is used. From any other peer the header is
  ignored. Sockets use `connect_info` (`:peer_data`, `:x_headers`) the same way.
- **Per-IP limits.** All existing limits are kept. In addition:
  - `POST /auth/request`: 10 per IP per 15 min;
  - `POST /auth/verify`: 20 per IP per 15 min (each code allows only 5 tries, but an IP could
    spread guesses over many phones);
  - both answer `429 rate_limited` with `Retry-After`.
  - Refused socket upgrades are **logged, not limited** in the app. Clients legitimately
    reconnect after refusals (expiry, `auth:expired`, phone gate), so an in-app ban would hurt
    real users. fail2ban on the log bans abusive IPs at the firewall instead.
- **Auth log** (`RisiMe.AuthLog`): one line per failure, appended to `RISIME_AUTH_LOG` (default
  `~/risime-logs/auth.log`) and also written to the normal log at info:

      2026-10-06T08:15:30Z risime auth_failure ip=203.0.113.9 kind=invalid_code path=/api/v1/auth/verify

  - Kinds: `invalid_code`, `too_many_attempts`, `rate_limited`, `invalid_token`,
    `not_allowlisted`, `identity_conflict`, `socket_refused`, `phone_code_invalid`.
  - The line is built only from the time, the IP, a fixed kind and the route path. Values are
    stripped to `[A-Za-z0-9.:/_-]` (no spaces, newlines or query strings), so a phone, email,
    token or code can never reach it.
  - fail2ban `failregex`: `^.* risime auth_failure ip=<HOST> `. **`ignoreip` must include
    `127.0.0.1/8 ::1`**: requests that reach the server without Caddy's header are logged as
    the loopback peer.

## Consequences
- The in-app limiter is still per node and in memory, so it resets on restart. fail2ban's bans
  persist at the firewall.
- Whoever operates the pilot must keep Caddy as the only path to the server. Binding stays on
  loopback, and boot refuses anything else.
