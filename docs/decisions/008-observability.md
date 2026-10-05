# 008 — Server observability: JSON logs, telemetry metrics, /health

## Context
Before prod and the load test, we need machine-readable logs, counters and latencies for the
realtime path, and a health probe for ops. None of these may leak message bodies, OTP codes or
tokens.

## Decision
- **Logs.** A small in-repo `:logger` formatter, `RisiMe.JSONLogFormatter`, writes one JSON
  object per line (`time`, `level`, `msg`, plus allowlisted metadata: `request_id`, `mfa`,
  `line`, `pid`, `domain`, `user_id`, `event_id`). We use it instead of the `logger_json` dep
  because we need about 60 lines.
  - **Opt-in by environment:** `LOG_FORMAT=json` turns it on. In prod it is the default, and
    `LOG_FORMAT=text` turns it off. Dev and test keep the readable console.
  - `RisiMe.Application.start/2` installs the formatter on the default handler.
- **No secrets in logs.**
  - `config :phoenix, :filter_parameters` adds `code`, `token`, `body` and `secret` to
    `password`. That covers request params, socket connect params and channel `handle_in` debug
    logs; `msg:send` bodies are filtered.
  - Only allowlisted metadata reaches JSON output. The dev-only `[DEV OTP]` line (when
    `OTP_DEV_LOG=true`) is the only place a code is ever logged.
- **Telemetry events** (all `:telemetry`, all tagged without content):
  - `[:risime, :message, :send, :start | :stop | :exception]`: a span; `:stop` carries
    `duration` and `result` (`:ok` or the error reason);
  - `[:risime, :message, :ack]`: `count` of valid ids, with `status`;
  - `[:risime, :socket, :connect]`: `result` `:ok | :refused`;
  - `[:risime, :socket, :disconnect]`: `duration` of the connection, `reason`. It comes from
    `RisiMe.SocketTracker`, which monitors the connection process (with Bandit, the process
    that runs `connect/3` becomes the WebSocket process);
  - `[:risime, :socket, :open]`: `count`, from the telemetry poller every 10 s;
  - `[:risime, :inbox, :join]`: `result`.
  - All of them are defined as `Telemetry.Metrics` in `RisiMeWeb.Telemetry.metrics/0`: counters,
    a send-latency summary and distribution, and the open-socket gauge. No reporter is attached
    yet. A Prometheus exporter (or LiveDashboard) is the next step when prod monitoring exists;
    it only needs adding as a child with `metrics()`.
- **`GET /health`**, outside `/api/v1` (it is ops, not wire protocol), with no auth.
  - It runs `SELECT 1` on Postgres and `SELECT release_version FROM system.local` on Cassandra,
    each with a 2 s timeout. The Cassandra check goes through the `Store` behaviour's new
    `health/0` callback, so the storage boundary holds.
  - It returns 200 `{"status":"ok","version":…,"checks":{"postgres":"ok","cassandra":"ok"}}`, or
    503 with `"error"` for each failing check. Details go only to the log, at warning level.
  - In prod, `/health` is excluded from `force_ssl` redirects.

## Consequences
- `LOG_FORMAT=json` works in any env. A log shipper can parse prod logs as-is.
- New log lines must keep content out. New metadata keys must be added to the formatter's
  allowlist on purpose.
