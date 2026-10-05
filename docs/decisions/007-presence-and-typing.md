# 007 — Presence and typing on the server (contract v1.2)

## Context
PROTOCOL.md v1.2 adds presence / last seen (§2.5), typing (§2.6) and the ephemeral `signal`
push (§2.3). The server needs online state with a 5 s offline grace period, a persisted
`last_seen`, per-channel watch lists and rate-limited typing forwarding.

## Decision
- **`RisiMe.Presence`, a small GenServer plus a protected ETS table**, rather than
  Phoenix.Presence. Phoenix.Presence/Tracker gives eventual CRDT diffs with no notion of a grace
  period. Implementing "offline only after 5 s with no channel" on top of its diffs would need
  the same timer bookkeeping anyway. We run one node, so a local GenServer is simpler and exact.
  - Inbox channels call `Presence.track/1` after a successful join. The GenServer monitors the
    channel pid and keeps `{user_id, live_count, grace}` in ETS.
  - 0→1 publishes online, unless the user is inside the grace period, in which case nothing is
    published.
  - 1→0 starts a 5 s timer (`:presence_grace_ms`; 150 ms in test). When it fires, offline is
    published with `last_seen` = the time the last channel went down.
  - `online?/1` reads ETS directly; the process is not involved.
  - **When we cluster,** this module is replaced by a Phoenix.Tracker-based one with the same API.
    The channel code doesn't change.
- **`users.last_seen_at`** (migration `20261005220000`) is written by the inbox channel process
  itself: on join, and in `terminate/2` on leave or disconnect.
  - Writes are best effort: a failure is logged at debug level and never breaks the channel.
  - A hard node crash skips `terminate`, so `last_seen` falls back to the join time. The
    contract allows that ("may be arbitrarily old").
- **Fan-out:** PubSub topic `presence_live:<user_id>` per watched user. `presence:watch` diffs the
  old and new sets, then (un)subscribes before reading state, so no change falls in between.
  - The reply contains only registered users (one `users` query for up to 200 ids) and keeps
    input order.
  - Non-UUID strings are left out like unknown ids. Non-string elements, a missing list or more
    than 200 ids are `bad_request`.
  - A presence message for an id that was just removed from the watch list is dropped in the
    channel.
- **Typing** (`Messaging.typing/2`) checks the recipient with `Accounts.user_exists?/1`. That is
  one indexed PK lookup, and typing is at most 1 per 3 s per user in normal use.
  - `typing: true` goes through the existing `RateLimiter` (bucket `:typing`, 2 per 1 s); over the
    limit it is dropped with reply ok. `typing: false` skips the limiter.
  - The signal is broadcast on the recipient's existing `inbox_live:` topic. With nobody
    subscribed it is dropped. It is never written to Cassandra.

## Consequences
- Presence is per node and resets on restart: everyone looks offline until they reconnect, and
  the persisted `last_seen` is still served.
- Every inbox join and leave costs one Postgres `UPDATE users`.
