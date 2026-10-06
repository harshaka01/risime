# 030 — Server: invites, friends and blocks (contract v1.6)

## Context
Contract v1.6 §9 and decision 029: invite-only sign-up alongside the allowlist, and chat,
typing and presence limited to accepted friends. No reply may reveal whether a phone or email
belongs to a member.

## Decision
- **Tables:**
  - `invites` (inviter, phone, lowercased email, name, status, `expires_at` = +30 days);
  - `friendships`, undirected: `(user_a, user_b)` with `CHECK user_a < user_b` and that pair as
    the primary key;
  - `friend_requests`, stored against the **phone** entered (`to_phone`), with a partial unique
    index on `(from_user_id, to_phone) WHERE status IN ('pending','declined')`, so repeats are
    silent no-ops;
  - `blocks`, directional, keyed on `(blocker_id, blocked_id)`;
  - `users.invited_by_id` and `users.disabled_at`.
  - The v1.4 reset trigger now also fires when a user is disabled, which closes their sockets.
- **Race safety:** every request, accept, unfriend and block runs in a transaction holding
  `pg_advisory_xact_lock(hashtext(sorted phone pair))`. Phones are used, not ids, because the
  target may not exist yet.
  - Crossing requests auto-accept: one friendship, both requests accepted, and
    `request_accepted` to both.
- **Membership** (`Accounts.member?/1`): not disabled, and an allowlisted phone or `invited_by`
  set. `mix risime.user.disable <phone>` sets `disabled_at`. Dev tokens of disabled users stop
  working.
- **Mapping:** bound `sub` → allowlist by email → pending invites by email.
  - **Redemption only creates.** The user gets the oldest invite's phone and the token's `name`
    (falling back to the invite's name), with an empty company.
  - Same-phone invites are accepted and their inviters befriended. Other-phone invites expire.
  - If the phone belongs to an existing user or is on the allowlist: `409`. A concurrent
    redemption by the same `sub` resolves to the created user.
  - Allowlisted and already-bound users accept invites pending for their email, and befriend
    those inviters, whenever their mapping is computed (token-cache misses).
- **Identical replies:**
  - `POST /invites` stores the invite on every path except your own phone, and makes a silent
    friend request when the phone or email belongs to a member.
  - `POST /friends/requests` is always 202.
  - Limits: invites at 10 per 24 h and 50 pending, counted in Postgres; requests at 30 per 24 h
    in the in-memory limiter. All of them return `Retry-After`.
- **Realtime:**
  - `msg:send` and `typing` answer `not_friends` for every non-friend (unknown ids included) and
    for friends who can't be messaged while the phone gate is on. `unknown_recipient` is only for
    yourself or a malformed id.
  - The idempotent-resend check runs before the friendship check, so a resend still gets the
    original reply. Acks and queued events are unaffected.
  - `presence:watch` is filtered to friends, plus yourself.
  - Unfriend and block broadcast `{:drop_watch, id}` on both inbox topics, so presence stops at
    once.
  - The `friend` signal goes out on `request_received` and `request_accepted` (invite
    redemptions use the invite id).
- **`/contacts`** returns friends only.
- **`vouched_by`** is the inviter while `invited_by` is set and the phone isn't SMS-verified
  (stored state).
- **Migration:** `RisiMe.Release.migrate_friendships/0`, also run as
  `mix risime.friends --migrate`. **`RisiMe.Release.migrate/0` calls it** after the Ecto and CQL
  migrations on every deploy.
  - It loads the existing user ids first, then does a paged full scan of Cassandra
    `message_index` (no `ALLOW FILTERING`), keeping only pairs whose users both exist (filtered
    while streaming), and inserts friendships with `ON CONFLICT DO NOTHING`.
  - It is idempotent. On the dev data (about 870k load-test rows) it takes about 8 s and stays
    around 110 MB.
- **Fixtures:**
  - `befriend!/2` in tests;
  - the load test befriends each user's ±10 ring neighbours and only sends to them;
  - `mix risime.friends --pair <phoneA> <phoneB>` for root's interop.

## Consequences
- The friendship check costs one primary-key lookup per send and typing push. The load test at
  200 users showed p99 7.0 ms against 6.3 ms before, within noise.
- Each deploy pays the `message_index` scan. Once the load-test rows expire, or in the separate
  prod environment, it takes well under a second. If it ever matters, record a "done" marker.
