# Server status — Release 0.2 (in progress)

**READY** — 0.1 (S1–S7), the 0.2 night-1 items and contract **v1.3** (Keycloak sign-in) are
done. Gate green on `main`: `mix format --check-formatted && mix compile --warnings-as-errors &&
mix test` (140 tests).

## 0.2 progress
- [x] Version from the repo `VERSION` file: `Application.spec(:risime, :vsn)` matches it, and the
  server logs `RisiMe server <version> starting` on boot. A compile alias in `mix.exs` rebuilds
  `risime.app` when only VERSION changed.
- [x] Oban 2.24 (Postgres): queue `maintenance`. The daily cron job `RisiMe.Workers.PruneAccounts`
  (03:17 UTC) deletes OTP challenges older than 24 h and tokens revoked more than 30 days ago.
  See `docs/decisions/004-oban-background-jobs.md`.
- [x] Presence / last seen + typing (contract v1.2): `RisiMe.Presence` (ETS + monitors, 5 s
  offline grace), `users.last_seen_at` written on inbox join and leave, `presence:watch`
  (replaces the list, max 200 ids, registered users only, self allowed), `typing` forwarded as an
  ephemeral `signal` (`true` limited to 2/s, `false` never limited). Signals are never stored or
  replayed. The contract tests check all 5 v1.2 examples. Decision 007.
- [x] Observability (decision 008):
  - JSON logs with `LOG_FORMAT=json` (the default in prod); `LOG_LEVEL` overrides the level;
  - Phoenix param filtering for `code`, `token`, `body` and `secret`;
  - `:telemetry` events plus `Telemetry.Metrics` for sends (count, latency), acks, socket
    connect/disconnect/open, and inbox joins;
  - `GET /health`: Postgres + Cassandra, 200/503, no auth.
- [x] Load test (`mix risime.loadtest`, decision 011, results in `docs/status/loadtest.md`):
  - 200 users at 1 msg/s: p99 6.3 ms, 0 errors.
  - It found two bottlenecks, both fixed: the Cassandra pool rejected bursts, which crashed
    channels; and every query made two trips through the Xandra cluster process.
  - Now 3200 msg/s gives p99 47 ms with 0 errors.
- [x] Prod config: `CASSANDRA_NODES` / `CASSANDRA_KEYSPACE` / `CASSANDRA_POOL_SIZE`.
  `mix risime.cql.migrate` delegates to `RisiMe.Release.migrate_cql/1`.
- [x] **v1.3 RisiCloud Keycloak sign-in** (decision 018):
  - JOSE verification against the realm JWKS (cached, 10 min refresh, unknown-`kid` refetch at
    most once per 60 s, fail closed), with every §6.0 claim rule;
  - mapping to phone-first users, `403 not_allowlisted`, `409 identity_conflict`;
  - `GET /api/v1/auth/config`; `/auth/request` and `/auth/verify` exist only with
    `DEV_LOCAL_AUTH`;
  - socket `Authorization: Bearer` (or `token=`), with `auth:expired` and a disconnect at
    `exp + 60 s`, and `auth:refresh`;
  - prod endpoint for `https://risicloud.ai/risime/` (no `force_ssl`; `check_origin`).
  - It needs `mix deps.get` and `mix ecto.migrate` (`users.keycloak_sub`, unique
    `lower(allowlist.email)`).

## How to run (spark2)
```bash
# databases (once)
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d
# first time / after every pull (new deps, new migrations)
cd server
~/.local/bin/mise exec -- mix deps.get
~/.local/bin/mise exec -- mix ecto.migrate   # required after pulling 0.2: creates the Oban tables
~/.local/bin/mise exec -- mix risime.cql.migrate                         # risime_dev
~/.local/bin/mise exec -- mix risime.cql.migrate --keyspace risime_test  # the test alias also runs this
```
**The test server** (tmux `risime-server`, 127.0.0.1:4000) runs from a release-tag worktree
`~/risime-run/<tag>` (decision 006). Start or switch it only with `scripts/run-dev-server [<tag>]`;
`scripts/nightly-release` does this after the gates pass. Never start a server from the shared
checkout on :4000.

For your own live testing, use a temporary instance from the checkout on another loopback port,
and stop it afterwards:
```bash
cd server && PORT=4100 LOG_LEVEL=warning ~/.local/bin/mise exec -- mix phx.server
curl -s http://127.0.0.1:4100/health        # {"status":"ok",...}
~/.local/bin/mise exec -- mix risime.loadtest --users 200 --duration 180 --interval 1000
~/.local/bin/mise exec -- mix risime.loadtest --cleanup   # only after a killed run
```
The load test puts throwaway `+999…` users in the dev allowlist while it runs and deletes them
afterwards. Don't run it while someone is testing against the dev DB.

Env: `LOG_FORMAT=json|text`, `LOG_LEVEL`, `CASSANDRA_POOL_SIZE`; prod also needs
`CASSANDRA_NODES` and `CASSANDRA_KEYSPACE` (docs/PROD.md).

Auth env (decision 018):
| Var | Default | Meaning |
|---|---|---|
| `OIDC_ENABLED` | `false` (test: on) | Accept Keycloak JWTs; `"oidc"` appears in `/auth/config` |
| `OIDC_ISSUER` | `https://risicloud.ai/realms/aoa` | Exact `iss`; discovery base |
| `OIDC_CLIENT_ID` | `risime` | `azp` / `aud` check; returned by `/auth/config` |
| `OIDC_JWKS_URL` | (discovery) | Skip discovery and use this JWKS URL |
| `DEV_LOCAL_AUTH` | dev/test `true`, prod `false` | Dev OTP login + opaque tokens. Prod logs a warning if on |
| `PHX_HOST` / `PHX_PATH` | `risicloud.ai` / `/risime` | Prod public URL; `check_origin` is `https://<PHX_HOST>` |

**Enabling RisiCloud sign-in once the `risime` client exists:**
1. The RisiCloud lead creates client `risime`: public, standard flow + PKCE (S256), the redirect
   URI from the Android build (decision 014), and the default `email` client scope (so `email`
   and `email_verified` are in the *access* token).
2. Verify one real access token (for example from the Android debug build), with
   `RisiMe.Auth.JWT.verify(token)` in `iex -S mix`. Expect `{:ok, %{sub, email, exp}}`. If it
   fails, the error atom names the rule: `:bad_typ`, `:bad_audience`, `:email_not_verified`,
   `:kty_mismatch`, …
3. Make sure every tester's email is on the allowlist (`mix risime.allow`; emails are unique,
   case-insensitive).
4. Start the server with `OIDC_ENABLED=true`. Keep `DEV_LOCAL_AUTH=true` on the test server
   during the switch: both modes then work at once.
5. A tester who gets **409** (their phone is bound to another Keycloak account) needs
   `mix risime.allow --rebind <phone>`, after which they sign in again.
Config comes from the repo `.env`: `POSTGRES_PASSWORD`, `SECRET_KEY_BASE`, `OTP_DEV_LOG`, `SMTP_*`.

## Allowlist
```bash
cd server
~/.local/bin/mise exec -- mix risime.allow --phone +94… --email … --name "…" --company Rise
~/.local/bin/mise exec -- mix risime.allow.list
```
`risime_dev` has 2 allowlist entries (Harsha and the test partner) and 1 registered user
(checked 2026-10-05 after the load tests; no `+999` load-test rows left).

## Reading the dev OTP
With `OTP_DEV_LOG=true`, each code is logged as `[DEV OTP] <phone>: <code>`:
```bash
tmux capture-pane -p -t risime-server -S -500 | grep "DEV OTP" | tail -3
```
The OTP email is also in the Swoosh dev mailbox at `http://127.0.0.1:4000/dev/mailbox`
(from the laptop through the tunnel: `http://127.0.0.1:4400/dev/mailbox`).
Codes are never logged unless `OTP_DEV_LOG=true`.

## Smoke test done (S7, on localhost)
- `/auth/request`: 200 for both allowlisted and unknown pairs; 422 `invalid_phone`.
- `/auth/verify`: 401 `invalid_code` for a wrong code; 200 with token + user for the right one.
- `/me`, `/contacts` (empty, because only one person is allowlisted), and `/auth/logout` 204,
  after which the token gets 401.
- WebSocket: 101 with a valid token and 403 with a bad one. Over a raw V2 WebSocket: join
  `inbox:<own id>` returns `{events, has_more, server_time}`; another user's inbox returns
  `unauthorized`; a self-send returns `unknown_recipient`.

## Notes for Android / root
- A `message` event's `event_id` equals its `message_id`. This makes re-delivery idempotent.
  Clients must not rely on it.
- A `msg:send` / `sync` / `msg:ack` payload that is malformed (for example a `client_msg_id` that
  isn't a UUID, or an unknown ack status) gets error reason `bad_request`, as specified in
  PROTOCOL.md v1.1.
- Sending to yourself is `unknown_recipient`.
- After logout the server sends a `disconnect` to that token's sockets.
- Rate limits: OTP requests 3 per phone per 15 min (counted whether or not the pair is
  allowlisted); sends 20 per 10 s per user. Idempotent resends of an already-sent `client_msg_id`
  don't count. `docs/decisions/001-rate-limiter.md` explains the limiter.

## Known limits
- **v1.3 auth:**
  - Not yet checked against a real token from the realm, because the `risime` client doesn't
    exist yet. Every rule is tested only with locally generated RSA/EC/Ed25519 keys. The temp
    server did fetch the real realm JWKS.
  - JWT mappings are cached per token until expiry, so allowlist removal and `--rebind` take
    effect at the next token (about 5 min) and, on other nodes, only then.
  - A dev-token socket that refreshes with a JWT gets a deadline; its expiry disconnect then
    closes every socket of that dev token.
  - The expiry disconnect relies on Bandit running `connect/3` in the WebSocket process (true
    for HTTP/1 WebSockets).
- **TimeUUIDs:** `uniq` 0.6's `uuid1` timestamps wrap every 0.1 s, which breaks timeuuid
  ordering, so ids come from `RisiMe.TimeUUID` (strictly increasing per node). `uniq` is only
  used for v4 in tests.
- **Cursor race:** event ids are generated just before the write. Two concurrent writers to the
  same inbox (a message plus a status) can commit in the opposite order, a few ms apart. A
  connected client still gets both live. A client that reconnects in that window with a cursor
  past the earlier event would miss it. Fix later with per-inbox write serialisation, or with
  a server-side overlap re-read on join.
- The rate limiter is in-memory and per node, so it resets on restart.
- `/auth/request` takes slightly longer for allowlisted pairs (DB insert + email), a timing
  signal. That's acceptable for the internal 0.1 pilot.
- Tests truncate the Cassandra tables once per run (`test_helper.exs`), not between tests,
  because TRUNCATE is slow. Every test uses fresh user ids, so partitions never overlap.
- SMTP is wired up (`RISIME_MAILER=smtp` plus `SMTP_*`), but dev uses the local mailbox.
- **Presence is per node** and in memory. After a restart everyone shows offline (with their
  stored `last_seen`) until they reconnect. Clustering needs a Tracker-backed `RisiMe.Presence`.
  A hard crash skips the leave write, so `last_seen` falls back to the last join.
- Typing checks the recipient with one Postgres PK lookup per push.
- No metrics reporter is attached yet: the metrics are defined, and Prometheus or LiveDashboard
  comes with prod monitoring.
- Throughput is bounded by Cassandra LWTs (send idempotency, ack CAS) and by per-channel work.
  The dev compose routes Cassandra through `docker-proxy`. See `docs/status/loadtest.md`.
- Oban job args are stored in plain text in Postgres: never put secrets, OTPs or message bodies
  in them.
- The `inbox_events` partition grows per user. Monthly bucketing is in the backlog (0.4).
