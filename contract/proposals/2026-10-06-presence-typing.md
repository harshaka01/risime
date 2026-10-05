# Proposal: presence / last seen and typing indicator (PROTOCOL v1.2)

**Status:** accepted by root (orchestrator) on 2026-10-06, revised the same day with the server and Android reviews (grace period, `typing:false` exempt from the rate limit, registered-only watch) under the nightly mandate (backlog 0.2:
"presence and last seen, typing indicator"). It is merged into `contract/v1` as v1.2 when both
sides start implementing it. It is additive, so v1.1 clients keep working.

## Design
Presence and typing are **ephemeral**: they are not stored in the inbox, have no `event_id`, and
never move the cursor. They travel in a second server→client push, **`signal`**, on the same
`inbox:<own id>` topic. `event` stays the only stored, cursor-bearing push.

### 2.5 Presence (online / last seen)
- A user is **online** while at least one of their sockets has joined their own `inbox:` topic.
- **Grace period.** When a user's last inbox channel leaves, the server waits **5 s** before it
  publishes them as offline. A reconnect within that time publishes nothing, so brief reconnects
  don't flap.
- `last_seen` is the time their last inbox channel left (disconnect or leave), not the end of the
  grace period. It is stored on the server and also refreshed on join, so a crash leaves it
  roughly right.
- **`presence:watch`** (client → server) `{"user_ids": ["uuid", ...]}`, with at most 200 ids.
  - The watch list **replaces** the previous one for this channel. `[]` stops watching.
  - The reply ok is `{"presences": [Presence, ...]}`, one per **registered** user id. Ids that
    aren't registered users (allowlisted people who have never logged in, unknown ids) are left out.
    Watching yourself is allowed.
  - While watched, every online/offline change of those users is pushed as a `signal` of kind
    `presence`.
- `Presence = {"user_id": "uuid", "online": true|false, "last_seen": "<ISO-8601 ms>" | null}`
  - `last_seen` is null while the user is online, and also for someone who has never connected.
- Clients watch the contacts they show (the Chats list, the open chat), and send the watch again
  after every (re)join.

- **Online means the app is open.** 0.x clients keep a socket only while the app is in the
  foreground, so a backgrounded app shows offline (after the grace period).
- Clients send `presence:watch` right after **every** successful join reply, before or during the
  `sync` loop. Watches don't survive a rejoin.
- A watched id that is missing from the reply means "unknown", not "offline". The UI shows nothing
  for it.
- `last_seen` can be arbitrarily old. Clients format it as a date when it's not today.

### 2.6 Typing
- **`typing`** (client → server) `{"to": "<user uuid>", "typing": true|false}`, reply ok `{}`.
  - Send `true` when the user starts typing, and again at most every 3 s while they keep typing.
  - Send `false` when they stop for 3 s, clear the input, or send the message.
  - The server forwards it only to the recipient's connected inbox channels. It is dropped if
    the recipient is offline. `typing: true` pushes above 2 per second per user are dropped
    silently (reply ok). `typing: false` is never rate-limited, so a stop is never lost.
  - `unknown_recipient` and `bad_request` behave as in `msg:send`.
  - Typing is never queued: a client that isn't connected drops it.
- The recipient shows "typing…" until it receives `typing: false`, a message from that user, or
  **6 s pass** without a refresh.

### `signal` push (server → client, on `inbox:<own id>`)
`Signal = {"kind": "presence" | "typing", "data": {...}}`
- kind `presence`: `data` is a `Presence`.
- kind `typing`: `{"from": "uuid", "conversation_id": "dm:…", "typing": true|false}`

Clients ignore signals of unknown kinds.

### Examples (added to `contract/v1/examples/`)
- `presence_watch.json`: `{"user_ids":["0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"]}`
- `presence_watch_reply.json`: `{"presences":[{"user_id":"0b9d7e8a-…","online":false,"last_seen":"2026-10-06T08:10:00.000Z"}]}`
- `signal_presence.json`: `{"kind":"presence","data":{"user_id":"0b9d7e8a-…","online":true,"last_seen":null}}`
- `typing.json`: `{"to":"0b9d7e8a-…","typing":true}`
- `signal_typing.json`: `{"kind":"typing","data":{"from":"7e3f1a2b-…","conversation_id":"dm:0b9d…_7e3f…","typing":true}}`

## Out of scope
- Hiding last seen (a privacy setting) is for later; in 0.2 everyone on the allowlist sees it.
- Multi-node presence (Phoenix.Presence over distributed PubSub) works unchanged when we
  cluster.
