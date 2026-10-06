# Server status — Release 0.2 (in progress)

**READY** — 0.1 (S1–S7), the 0.2 night-1 items, contract **v1.3** (Keycloak sign-in),
**v1.4** (one-time SMS phone verification), the **prod-mode pilot release** (decision 024) and
**v1.5** (push wake-ups, decision 028), **v1.6** (invites and friends, decision 030) and **v1.7**
(E2EE routing with MLS, decision 034; **off until the attestation key exists**) are done.
Gate green on `main`: `mix format --check-formatted && mix compile --warnings-as-errors && mix test`
(265 tests).

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
- [x] **v1.4 one-time SMS phone verification** (decision 022):
  - `OtpSender` behaviour (Email, NotifyLk, DevLog, Test); the NotifyDEMO guard;
  - `POST /me/phone/verify/request` and `/confirm`, with `Retry-After` on every 429 and
    `attempts_left`; SMS budgets counted in Postgres;
  - the `PHONE_VERIFICATION` gate (default off) on REST and the socket; `auth:refresh`
    `phone_unverified`; `Contact.registered`; sockets closed on reset (Postgres trigger +
    `LISTEN`);
  - `checks.sms` in `/health`.
  - It needs `mix ecto.migrate` (`phone_challenges`, `users.phone_verified_for`, the reset
    trigger).

## Prod pilot release (`https://risime.risicloud.ai`, decisions 023 and 024)
Caddy on spark2 terminates TLS and proxies to `127.0.0.1:4000`. The server runs as a prod-mode
**release** against the existing `risime_dev` databases.

Build (root, from the latest green tag; arm64 on spark2 is verified):
```bash
cd server
MIX_ENV=prod ~/.local/bin/mise exec -- mix deps.get
MIX_ENV=prod ~/.local/bin/mise exec -- mix release --overwrite   # → _build/prod/rel/risime
```

Environment for the pilot. Secrets (`SECRET_KEY_BASE`, `POSTGRES_PASSWORD`, `NOTIFYLK_*`) come
from the repo `.env`, loaded with `set -a; . .env; set +a`, never printed.
| Var | Pilot value | Notes |
|---|---|---|
| `SECRET_KEY_BASE` | from `.env` | required |
| `POSTGRES_PASSWORD` | from `.env` | or `DATABASE_URL` instead of the five `POSTGRES_*` |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_USER` | `127.0.0.1` / `5432` / `risime` | defaults |
| `POSTGRES_DB` | `risime_dev` | default `risime_prod` |
| `CASSANDRA_NODES` | `127.0.0.1:9042` | default |
| `CASSANDRA_KEYSPACE` | `risime_dev` | default `risime_prod` |
| `PHX_HOST` | `risime.risicloud.ai` | default |
| `PHX_PATH` | `/` | default |
| `PHX_ORIGINS` | (unset → `https://risime.risicloud.ai`) | comma-separated `check_origin` list |
| `PHX_BIND` | (unset → `127.0.0.1`) | loopback only; anything else makes boot fail |
| `PORT` | `4000` | default |
| `DEV_LOCAL_AUTH` | `true` until Keycloak is live | boot warning in prod |
| `OTP_DEV_LOG` | `true` while `DEV_LOCAL_AUTH` is on | codes appear only in the server log (`[DEV OTP]`) |
| `SMS_MODE` | `log` until the RisiMe sender ID is approved | prod default is `notifylk` |
| `OIDC_ENABLED`, `PHONE_VERIFICATION` | `false`, `off` for now | see the sections below |
| `RISIME_AUTH_LOG` | (unset → `~/risime-logs/auth.log`) | fail2ban file, see below |
| `LOG_FORMAT` | (unset → `json` in prod) | |

Run:
```bash
cd server/_build/prod/rel/risime
bin/risime eval "RisiMe.Release.migrate()"   # Ecto + CQL, before every start of a new build
bin/risime start                             # foreground; under tmux or systemd
```
- **Stop with SIGTERM** (systemd's default, or `kill -TERM <beam pid>`). The release runs
  without Erlang distribution (`RELEASE_DISTRIBUTION=none`, no epmd), so `bin/risime stop` and
  `remote` don't work by design.
- Checked on :4100 (2026-10-06):
  - it listens on `127.0.0.1:4100` only, and no epmd runs;
  - `/health` returns `ok`;
  - `/dev/mailbox` and unknown routes return a 404 JSON error; a bad body returns a 400 JSON error;
  - tokens show as `[FILTERED]` in the log;
  - SIGTERM shuts it down cleanly.

### E2EE with MLS (v1.7, decision 034)
- **Off on the pilot.** With no attestation key, every MLS endpoint answers
  `503 mls_unavailable` and groups report `ready: false`; plaintext chat is unchanged. Don't
  create the key until rollout.
- **Turning it on (root, at rollout, after the required app update brings everyone to v1.7):**
  1. `mix risime.attestation.gen` (or `bin/risime eval
     'RisiMe.MLS.Attestation.generate("/home/harsha/risime-keys/attestation_ed25519.jwk")'`).
     It writes `~/risime-keys/attestation_ed25519.jwk` with mode 600, refuses to overwrite, and
     prints the kid. **Back the file up with the release keystore; never commit it.**
  2. Restart the server. `ATTESTATION_KEY_FILE` defaults to that path.
     `curl …/api/v1/mls/attestation_keys` should return one key.
  3. Pin the public key (`x`, `kid`) in the app build.
  4. **Rotation:** generate a new key at a new path, put the old public JWK into a file
     `{"keys":[…]}` named by `ATTESTATION_PREVIOUS_KEYS`, point `ATTESTATION_KEY_FILE` at the new
     key, and restart. Both keys are then published.
- **Census:** the socket connect carries `device_id` and `app_version`. Pre-v1.7 apps count as
  `legacy_app`: one instance per dev token, or per user for Keycloak tokens. A conversation is
  ready only when every instance of both members seen in the last 30 days is MLS-capable.
- **Ciphertext cap:** 24 KiB decoded (PROTOCOL §10.3, raised from 16 KiB so a max-size text fits
  in the envelope plus MLS framing; decision 034 still says 16 KiB and is superseded on this
  point).
- **REST calling device:** commits need the `X-Device-Id` header; claims accept it to exclude the
  caller.
- **Load test:** `mix risime.loadtest --e2ee` runs the e2ee send path with opaque ciphertext. It
  needs a server started with `ATTESTATION_KEY_FILE` pointing at a temp key.

### Invites and friends (v1.6, decision 030)
- **Deploy:** `bin/risime eval "RisiMe.Release.migrate()"` runs Ecto, then CQL, then
  **`RisiMe.Release.migrate_friendships/0`**. The last turns every existing conversation pair
  into friends (Harsha ↔ Shenika on the pilot data), idempotently; it takes about 8 s on today's
  dev data. It was already run once on `risime_dev`, creating 1 friendship.
- **Admin:**
  - `mix risime.friends --pair <phoneA> <phoneB>` makes two users friends (fixtures, interop);
  - `mix risime.friends --migrate` runs the backfill by hand;
  - `mix risime.user.disable <phone>` removes an invited (or any) member: sign-in stops and
    their sockets close.
- **Config:** `INVITE_LINK` (default `https://risicloud.ai/app/risime/`) is the link in
  invites. The server never emails or texts invitees.
- Only friends can message, type or see presence. `/contacts` returns friends only.

### Push notifications (v1.5, decision 028)
- **Off** until Harsha provides the Firebase service-account key. Devices register
  (`PUT /api/v1/me/devices/{id}`) regardless, so tokens are stored the moment push turns on.
- **What a push is:** a data-only FCM message `{"type":"inbox","v":"1"}`, sent only when the
  recipient has no live inbox channel. At most one push now and one trailing push per user per
  10 s. Never any content, sender, phone or name.

**Enabling push** (needs Harsha's key):
1. In the Firebase console (project with the Android app `lk.codegen.risime`): Project settings →
   Service accounts → "Generate new private key". This downloads a JSON file.
2. Put it on spark2 at `~/risime-keys/fcm-service-account.json`, outside the repo, and run
   `chmod 600` on it. **Never commit it, and never paste its contents anywhere.**
3. Set in the server's environment (the pilot unit's env file, or the shell for a temp server):
   - `FCM_ENABLED=true`
   - `FCM_SERVICE_ACCOUNT_FILE=/home/harsha/risime-keys/fcm-service-account.json` (this is the
     default, so it can be left out)

   `project_id` is read from the file.
4. Restart the server. The boot log must **not** show "FCM_ENABLED=true but
   FCM_SERVICE_ACCOUNT_FILE is missing".
5. Test: sign in on a phone (an app built with `google-services.json`, decision 026), close the
   app, and send it a message from another account. A wake-up should arrive within seconds.
6. Rollback: `FCM_ENABLED=false` and restart.
- FCM failures are logged with the HTTP status and FCM's error code only, never a push token,
  access token or key. `UNREGISTERED` / `INVALID_ARGUMENT` tokens delete their device.
- Devices unseen for 60 days are pruned daily (Oban).

### fail2ban auth log
One line per authentication failure, in `RISIME_AUTH_LOG` (and in the normal log at info):
```
2026-10-06T08:15:30Z risime auth_failure ip=203.0.113.9 kind=invalid_code path=/api/v1/auth/verify
```
- **Format:** `<UTC ISO-8601 to the second>Z risime auth_failure ip=<client IP> kind=<kind> path=<route path>`.
  Fields contain only `[A-Za-z0-9.:/_-]`; there is never a phone, email, token, code or query
  string.
- **Kinds:** `invalid_code`, `too_many_attempts`, `rate_limited`, `invalid_token`,
  `not_allowlisted`, `identity_conflict`, `socket_refused`, `phone_code_invalid`.
- **Client IP:** `X-Forwarded-For` (its last entry) is used only when the TCP peer is loopback,
  which is Caddy on the same host; otherwise the peer address is used.
- **fail2ban:** `failregex = ^.* risime auth_failure ip=<HOST> `, and `ignoreip = 127.0.0.1/8 ::1`
  (direct local requests are logged with the loopback IP).
- **Per-IP limits** (on top of the existing ones), each answering `429 rate_limited` with
  `Retry-After`: `POST /auth/request` 10 per IP per 15 min, `POST /auth/verify` 20 per IP per
  15 min. Refused socket upgrades are logged for fail2ban, not limited in the app.

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
`~/risime-run/<tag>` (decision 006). Start or switch it only with `scripts/run-server [<tag>]`;
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

Phone verification env (decision 022). The `NOTIFYLK_*` values live only in the repo `.env`;
the server reads them from the OS environment when sending and never logs or prints them.
| Var | Default | Meaning |
|---|---|---|
| `PHONE_VERIFICATION` | `off` | `required` turns on the gate (`/auth/config` advertises it) |
| `SMS_MODE` | prod `notifylk`, else `log` | `log` delivers codes only to the `[DEV OTP]` log (needs `OTP_DEV_LOG=true`) |
| `NOTIFYLK_USER_ID` / `NOTIFYLK_API_KEY` / `NOTIFYLK_SENDER_ID` | (in `.env`) | Notify.lk credentials. While the sender ID is `NotifyDEMO`, OTP SMS are **blocked** (503) |
| `NOTIFYLK_ALLOW_DEMO_OTP` | unset | `true` lets OTPs go out from NotifyDEMO. Harsha's call only; it risks the Notify.lk account |
| `SMS_BALANCE_WARN` | `100` | Below this balance, `checks.sms` is `low_balance` and a warning is logged |

**Turning phone verification on once the `RisiMe` sender ID is approved:**
1. In `.env`, set `NOTIFYLK_SENDER_ID=RisiMe` (no code change).
2. Start the server with `SMS_MODE=notifylk` and `PHONE_VERIFICATION=required`. The boot log
   must show neither "SMS OTP disabled…" nor "PHONE_VERIFICATION=required but no SMS can be
   sent".
3. `curl -s http://127.0.0.1:4000/health`: `checks.sms` should be `ok` within 10 min (the
   background poll), not `demo_sender_blocked`, `inactive` or `low_balance`.
4. Have one tester sign in through Keycloak, tap "Send code", and confirm.
5. Rollback: `PHONE_VERIFICATION=off` (users read as verified; nothing is lost).

Before that, the whole flow can be tried with `PHONE_VERIFICATION=required SMS_MODE=log
OTP_DEV_LOG=true`. The code appears as `[DEV OTP] +9477•••••01: <code>` in the server log.

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
- **E2EE (v1.7):**
  - `generation` is always 1, because group re-creation isn't specified yet.
  - The optional PrivateMessage header check isn't done (the server doesn't parse MLS).
  - The e2ee send path costs about 2–3× plaintext at p99 (a second inbox write plus a group
    lookup); see decision 034.
  - The MLS blobs in the contract examples are placeholders, until crypto supplies real ones.
- **Invites and friends:**
  - The friend-request rate limit (30 per 24 h) is in memory and resets on restart. The invite
    limits are counted in Postgres.
  - Replies are identical, but the per-path timing isn't perfectly constant; the rate limits
    are the real defence against probing.
- **Push:**
  - Never tested against real FCM (no key yet). The token exchange and send are tested against
    `Req.Test` stubs with a generated key, shaped like Google's documented responses.
  - Coalescing and the online check are per node and in memory: a restart can drop one pending
    trailing push, and the app catches up on next open.
- **Prod pilot:**
  - It shares the `risime_dev` databases with the test server (:4000) until the prod
    environment exists (decision 010).
  - The in-app limiter is in memory and resets on restart; fail2ban's firewall bans persist.
- **v1.4 phone verification:**
  - No real SMS has been sent: the sender is still NotifyDEMO, the guard blocks it, and the
    override was never used. Notify.lk success parsing and the status endpoint are tested only
    against `Req.Test` stubs shaped like their documentation.
  - While the gate is on, users who only ever used the dev login count as unregistered to
    Keycloak users (dev verification isn't stored).
  - The SMS budgets count, then insert, so concurrent requests can overshoot a cap by one or
    two.
  - The reset NOTIFY fires on commit. Tests drive the listener directly; the trigger was checked
    live on :4100 (a `--rebind` from another VM closed the open socket).
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
