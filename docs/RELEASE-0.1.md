# Release 0.1 — Two people chat one-to-one

## Goal
Two allowlisted people each log in with their phone number plus an email code, see each other
in Contacts, and exchange text messages in real time with ✓ / ✓✓ / read ticks. Messages that
arrive while the app is closed are delivered when it reopens.

## Out of scope (do NOT build in 0.1)
E2EE (0.3) · push notifications/FCM (0.2) · groups · media · presence/typing ·
the Risi agent · on-device model · prod environment · iOS.

Exception: a local-only behaviour event log on Android ships now (see A6), so the client agent
learns from day one.

---

## Server (Elixir/Phoenix on spark2) — folder `server/`

### S1. Project
```
mix phx.new server --app risime --module RisiMe --binary-id \
  --no-html --no-assets --no-live --no-dashboard --no-gettext
```
- Run this from the repo root so the project lives in `server/`.
- Dev endpoint binds to `127.0.0.1:4000`.
- Socket `check_origin: false` in dev (mobile clients).
- Config is read from the repo `.env`: `POSTGRES_PASSWORD`, `SECRET_KEY_BASE`, `OTP_DEV_LOG`,
  and the `SMTP_*` settings.
- Dependencies: `xandra`, `uniq` (UUIDv1/v4), `swoosh` (comes with Phoenix), `jason`.
  Rate limiting: Hammer, or a small ETS limiter (record the choice in `docs/decisions/`).

### S2. Postgres (Ecto migrations)
| table | columns |
|---|---|
| `allowlist` | id, phone (unique, E.164), email, display_name, company, timestamps |
| `users` | id (uuid), phone (unique), email, display_name, company, timestamps |
| `otp_challenges` | id, phone, code_hash, expires_at, attempts, consumed_at, inserted_at |
| `user_tokens` | id, user_id, token_hash (sha256), device_name, last_seen_at, revoked_at, inserted_at |

- `code_hash` = HMAC-SHA256(secret_key_base, phone <> code). Codes expire after 5 minutes,
  allow at most 5 attempts, and are single-use.
- Tokens are 32 random bytes, base64url-encoded. Only the sha256 hash is stored.
- The user record is created on first successful verify, copying fields from the allowlist.
- Mix tasks:
  - `mix risime.allow --phone +94… --email … --name "…" --company CodeGen` (upsert)
  - `mix risime.allow.list`

### S3. Cassandra (`priv/cql/*.cql`, applied by `mix risime.cql.migrate [--keyspace risime_test]`)
Track applied files in a `cql_migrations` table inside the keyspace.

```sql
CREATE KEYSPACE IF NOT EXISTS risime_dev
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

-- Q1: SELECT * FROM inbox_events WHERE user_id = ? AND event_id > ? LIMIT ?
CREATE TABLE IF NOT EXISTS inbox_events (
  user_id uuid, event_id timeuuid, kind text, payload text,
  PRIMARY KEY ((user_id), event_id)
) WITH CLUSTERING ORDER BY (event_id ASC)
  AND default_time_to_live = 2592000
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};

-- Q2: INSERT ... IF NOT EXISTS  (idempotent send)
CREATE TABLE IF NOT EXISTS sent_dedupe (
  sender_id uuid, client_msg_id uuid, message_id timeuuid,
  conversation_id text, server_ts timestamp,
  PRIMARY KEY ((sender_id, client_msg_id))
) WITH default_time_to_live = 86400;

-- Q3: SELECT/UPDATE by message_id (route status to sender, forward-only status)
CREATE TABLE IF NOT EXISTS message_index (
  message_id timeuuid PRIMARY KEY, sender_id uuid, recipient_id uuid,
  client_msg_id uuid, conversation_id text, status text
) WITH default_time_to_live = 2592000;
```
The `inbox_events` partition grows per user. That's acceptable for 0.1; monthly bucketing is in
the backlog (0.4).

### S4. Modules
- `RisiMe.Accounts`: allowlist, users, OTP, tokens.
- `RisiMe.Messaging`: `send/2`, `ack/3`, `fetch_events/3`.
- `RisiMe.Messaging.Store` (behaviour), implemented by `RisiMe.Messaging.Store.Cassandra`
  (a supervised Xandra pool). This is the only Cassandra access for messages.
- `RisiMeWeb`: `AuthController`, `MeController`, `ContactsController`, `UserSocket`
  (token auth), `InboxChannel`.
- Live delivery: on `Phoenix.PubSub` topic `inbox:<user_id>`, the channel pushes `"event"`.

**Send flow:** validate → `sent_dedupe` LWT (if it already exists, return the original reply) →
generate `message_id` (UUIDv1) → write `message_index` → write the recipient's `inbox_events`
row (kind `message`) → broadcast → reply ok.

**Ack flow:** for each id, load `message_index` → check the socket user is the recipient →
apply the forward-only status → write the sender's `inbox_events` row (kind `status`) → broadcast.

### S5. Email OTP
- Swoosh. In dev, use `Swoosh.Adapters.Local`, and when `OTP_DEV_LOG=true` also log
  `[DEV OTP] <phone>: <code>`. Never log codes when `OTP_DEV_LOG` is not true.
- SMTP adapter config is read from `SMTP_*` env vars; it's wired up but unused until 0.2.

### S6. Tests (ExUnit; use the `risime_test` keyspace, truncated between tests)
Accounts:
- OTP happy path, wrong code, expiry, attempt limit;
- a non-allowlisted pair still gets 200 but no email;
- token revoke.

Channel:
- A sends, B (connected) receives the `event` live;
- B offline, A sends, B joins with `since: null` and gets it;
- the cursor works (`since` excludes older events);
- `has_more` pagination;
- delivered/read acks reach A as status events, and status never goes backwards;
- an idempotent resend returns the same `message_id`;
- joining another user's inbox → unauthorized;
- error cases: too_long, empty_body, unknown_recipient.

Contract: every file in `contract/v1/examples/` is decoded and checked against the server's
encoders and decoders.

### S7. Run
- `tmux new -d -s risime-server 'cd ~/development/risime/server && ~/.local/bin/mise exec -- mix phx.server'`
- Ask Harsha once for two phone/email pairs, then run `mix risime.allow` for each.

---

## Android (Kotlin, built on spark2, run on the laptop emulator or USB phone) — folder `android/`

### A1. Project
- Package `lk.codegen.risime`. minSdk 26; target and compile at the latest stable API installed.
- Single `app` module. Gradle Kotlin DSL with a version catalog.
- Dependencies: Compose BOM (Material 3), navigation-compose, kotlinx-serialization-json,
  OkHttp, Room (KSP), DataStore, coroutines, lifecycle-process, and
  `io.michaelrocks:libphonenumber-android`.
- Phoenix client: use `com.github.dsrees:JavaPhoenixClient` if it works cleanly. Otherwise
  implement a minimal Phoenix V2 client on OkHttp WebSocket (join/push/reply refs, heartbeat,
  reconnect). Either way, hide it behind a `RealtimeClient` interface.
- No DI framework in 0.1 (a manual `AppContainer`).
- Debug network security config: cleartext allowed **only** for `10.0.2.2` and `127.0.0.1`.

### A2. Data
Room entities:
- `messages`: client_msg_id PK, message_id?, conversation_id, from, to, body, server_ts?,
  local_ts, status;
- `contacts`;
- `sync_state` (last_event_id);
- `behaviour_events` (see A6).

- **Outbox:** a message is inserted as `pending` first, pushed when connected, and every pending
  message is resent in `local_ts` order after a reconnect (same `client_msg_id`).
- **Incoming:** store with dedupe → batch-ack `delivered` → ack `read` when visible on the chat
  screen.
- **Cursor:** the last processed `event_id` is stored after each event is applied.

### A3. Connection
- Connect while the app is in the foreground (ProcessLifecycleOwner); disconnect in the
  background (0.1 only).
- Reconnect backoff: 1, 2, 5, 10, 30 s.
- Every (re)join sends `since`, then calls `sync` until `has_more` is false.

### A4. Screens
1. **Login:** "RisiMe" wordmark with the tagline "Talk. Connect. Act."; a debug-only Server URL
   field (default `http://10.0.2.2:4000`); phone (default `+94`, normalised with
   libphonenumber); email.
2. **OTP:** a 6-digit field with a resend timer.
3. **Chats:** registered contacts with initials avatar, name, company, last-message preview and
   time; unregistered contacts greyed out.
4. **Chat:**
   - bubbles: mine on the right in `primaryContainer`, theirs on the left in `surfaceVariant`;
   - time and ticks per the contract §3; auto-scroll; input bar with a send button;
   - a **persistent top banner: "Dev build — not end-to-end encrypted"**.

### A5. Design
- Material 3, light and dark themes, dynamic colour off.
- Seed colours: primary deep teal `#0B6E69`, accent saffron `#F2A93B` (also used for read ticks).
- Clean, generous spacing; a full design system comes in a later release.

### A6. Behaviour log (client agent, day one)
- Room table `behaviour_events(id, type, peer_hash, at, meta_json)`.
- Event types: `app_open`, `chat_open`, `message_sent` (length only), and `reply_latency_ms`
  (time from receiving a peer message to sending a reply in that chat).
- `peer_hash` = sha256(peer_id + a per-install salt).
- **Local only.** It is never uploaded and has no UI in 0.1.

### A7. Tests
- Parse every `contract/v1/examples/*.json` file.
- Phone normalisation.
- The outbox state machine and the status forward-only rule.
- The conversation_id function.

---

## Definition of done (root runs this)
1. Both `docs/status/*.md` files say READY.
2. Root reruns both test gates on `main`, and the server is restarted from `main`.
3. Manual smoke test, with the emulator as person A and a USB phone as person B through the tunnel:
   - both log in with a code from the server log;
   - A→B and B→A messages appear within 1 s;
   - ticks go ✓ → ✓✓ → read;
   - kill B's app, A sends 3 messages, reopen B: all 3 arrive in order with no duplicates;
   - toggle airplane mode on A while sending: the message stays pending, then sends on
     reconnect, with no duplicate;
   - the encryption banner is visible.
4. Tag `v0.1.0`, write `docs/releases/v0.1.0.md`, copy the APK to `~/risime-releases/v0.1.0/`.
