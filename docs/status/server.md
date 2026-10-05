# Server status — Release 0.2 (in progress)

**READY** — 0.1 (S1–S7) done. Gate green on `main`: `mix format --check-formatted && mix compile --warnings-as-errors && mix test` (63 tests).

## 0.2 progress
- [x] Version from the repo `VERSION` file: `Application.spec(:risime, :vsn)` matches it, and the
  server logs `RisiMe server <version> starting` on boot. A compile alias in `mix.exs` rebuilds
  `risime.app` when only VERSION changed.
- [x] Oban 2.24 (Postgres): queue `maintenance`. The daily cron job `RisiMe.Workers.PruneAccounts`
  (03:17 UTC) deletes OTP challenges older than 24 h and tokens revoked more than 30 days ago.
  See `docs/decisions/004-oban-background-jobs.md`.
- [ ] Presence / last seen + typing (contract v1.2), phase B.

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
# server (127.0.0.1:4000 only)
tmux new -d -s risime-server 'cd ~/development/risime/server && ~/.local/bin/mise exec -- mix phx.server'
```
Restart: `tmux kill-session -t risime-server`, then run the `tmux new` line again.
Config comes from the repo `.env`: `POSTGRES_PASSWORD`, `SECRET_KEY_BASE`, `OTP_DEV_LOG`, `SMTP_*`.

## Allowlist
```bash
cd server
~/.local/bin/mise exec -- mix risime.allow --phone +94… --email … --name "…" --company Rise
~/.local/bin/mise exec -- mix risime.allow.list
```
Harsha's entry is allowlisted in `risime_dev`. **The test partner's entry is still missing**:
add it with the command above before the two-person smoke test.

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
- Oban job args are stored in plain text in Postgres: never put secrets, OTPs or message bodies
  in them.
- The `inbox_events` partition grows per user. Monthly bucketing is in the backlog (0.4).
