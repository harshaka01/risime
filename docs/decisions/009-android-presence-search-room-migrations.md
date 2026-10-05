# 009 — Android: presence/typing client, local search, Room migrations

## Context
Contract v1.2 adds ephemeral `signal` pushes, `presence:watch` and `typing`. The 0.2 polish adds
unread counts, retry/delete of failed sends and local search. Installed release builds must now
keep their data across upgrades.

## Decision
1. **Signals bypass the event pipeline.** `PhoenixRealtimeClient` hands `signal` frames straight
   from the socket reader to a `SignalSink` (`PresenceTracker`): never buffered behind the sync
   loop, never applied as events, never touching the cursor. Presence and typing live only in
   memory and are cleared when the socket drops (unknown, not offline).
2. **Watch list = registered contacts + the open chat**, open chat first, capped at 200. The client
   keeps it in a `StateFlow` and, per connection, collects it right after the join reply, so the
   watch is resent after every rejoin and on every change. A watch reply replaces the known
   presence map (a missing id means unknown).
3. **Typing** is sent fire-and-forget on the app scope only while `Live` (never queued). The
   composer's `TypingSender` sends `true` at most every 3 s, `false` after 3 s idle, on clear, on
   send and when the chat closes. The receiver's 6 s expiry is a per-user job in `PresenceTracker`.
4. **Search uses SQL `LIKE '%q%'`** on `messages.body` (escaped, newest first, max 100) plus an
   in-memory filter on contact names, not FTS. History is single-device and small; FTS would add
   a virtual table, triggers and a migration for little gain. Revisit when histories grow past
   ~100k messages or ranking is needed.
5. **Room schemas are exported** (Room Gradle plugin → `android/app/schemas/`, committed).
   `AppDatabase.VERSION` and `AppDatabase.MIGRATIONS` are the single place to bump. There is no
   destructive fallback. A JVM test checks that every version has an exported schema and that
   every step n-1 → n has exactly one migration; the migration SQL itself is verified on device
   (Room's `MigrationTestHelper` needs instrumentation). The 0.2 polish needed no schema change
   (unread = incoming rows with status != READ; retry/delete are row updates), so the DB stays at
   version 1.
6. **Retry/delete apply only to `FAILED`** (sends the server rejected). Retry is the one backward
   status step (FAILED → PENDING, local only) and resends with the same `client_msg_id`. Delete
   removes the local row, since the message exists nowhere else.

## Consequences
- A presence change during a reconnect is reflected only after the next watch reply.
- LIKE scans the messages table; fine at current sizes.
