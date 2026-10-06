# Proposal: PROTOCOL v1.6 — invites and friends

**Status:** merged into `contract/v1` as **v1.6** on 2026-10-06, after review by the server and
android roles (decision 029). It is **additive**: v1.5 apps keep working. `/contacts` returns
friends only, and the `friend` signal is ignored by older apps, which ignore unknown signal kinds
(§2.3).

## 0. Principles
- **Invite-only sign-up**, with the admin allowlist kept as a second path.
- **Privacy:** a user's friends, requests and blocks are visible only to them.
  - **No response reveals whether a phone number or email belongs to a member.** Invites and
    friend requests get identical replies and do the same work on every path; the per-user rate
    limits are the real defence against probing.
  - Declines, removals and blocks are silent to the other side.
- **Only accepted friends** can message, type to each other, or see each other's presence. A
  block in either direction ends all of that.

## 1. Membership and invites
- **Membership** (replaces the §6.1 step-1 allowlist re-check): a user is a member if their phone
  is on the allowlist, **or** they joined by invite and haven't been disabled by an admin.
  Non-members get `403 not_allowlisted`.
- **`POST /api/v1/invites`** `{"phone": "+94…", "email": "…", "name": "Kamal Perera"}` →
  `201 {"invite": Invite}`. The reply is identical in every case:
  - **Phone or email already a member:** the invite row is still stored and stays `pending` in
    `GET /invites` until it expires, and a friend request to that phone is made silently.
  - **Your own phone:** the reply is the same and nothing is stored.
  - **Errors:** `422 invalid_phone` | `invalid_email` | `invalid_name` (1–64 characters).
  - **Limits:** `429 rate_limited` with `Retry-After`, at 10 invites per user per 24 h and 50
    pending per user, both counted in Postgres.
  - Any E.164 phone is accepted, but SMS verification (§7) only reaches `+94` numbers.
- **`GET /api/v1/invites`** → `{"invites": [Invite]}`, newest first.
  **`DELETE /api/v1/invites/{id}`** → `204` (revoke; a no-op if it isn't pending).
- `Invite = {"id", "phone", "email", "name", "status": "pending" | "accepted" | "revoked" | "expired", "expires_at", "inserted_at", "subject", "share_text", "link"}`
  - Invites expire after 30 days.
  - `share_text` is plain text, at most 300 characters, holding the link and the email to sign in
    with. `subject` is a share-sheet subject line. `link` comes from server config (default
    `https://risicloud.ai/app/risime/`).
  - **The server never emails or texts invitees.** The inviter shares via the device.
- **Redemption** happens during Keycloak first use; mapping order: bound `sub` → allowlist by email
  → pending, unexpired invite by email.
  - Redemption **only creates a new user**, with the oldest pending invite's phone and the name
    from the token (falling back to the invite's name); company is empty.
  - Every pending invite for that email with the same phone becomes `accepted`, and each of those
    inviters becomes a friend. Invites naming a different phone just expire.
  - If a user with that phone already exists, or the phone is on the allowlist under another
    email: **`409 identity_conflict`**. That prevents account takeover.
  - **Allowlisted users** who sign in while invites for their email are pending also accept those
    invites and become friends with the inviters.
- **`User` gains `"vouched_by": {"user_id", "display_name"} | null`.** It is set while the user
  joined by invite and their phone isn't SMS-verified; otherwise it is null.

## 2. Friends (REST)
- **`POST /api/v1/friends/requests`** `{"phone": "+94…"}` → **always `202 {"status": "requested"}`.**
  - The reply is the same for an unregistered number, an existing friend, someone who blocked you,
    or yourself.
  - A request is stored against the **phone** you entered. For an unregistered number it waits 30
    days and appears in their incoming list once a user with that phone exists.
  - Repeating a pending or declined request is a silent no-op.
  - **Crossing requests** (A→B while B→A is pending) **auto-accept**.
  - A request from someone the target has blocked is dropped silently.
  - **Errors:** `422 invalid_phone`; `429 rate_limited` with `Retry-After` (30 per user per 24 h).
  - Clients then offer "Not on RisiMe yet? Send an invite".
- **`GET /api/v1/friends`** → `{"friends": [Friend], "incoming": [Request], "outgoing": [Request], "blocked": [Blocked]}`
  - `Friend = {"user_id", "phone", "display_name", "company", "vouched_by", "since"}`
  - `Request = {"id", "phone", "user_id", "display_name", "company", "inserted_at"}`
    - **Incoming** requests carry the requester's `user_id`, `display_name` and `company`.
    - **Outgoing** requests carry only the `phone` you entered; `user_id`, `display_name` and
      `company` are **null**, so they never reveal registration. A declined request still shows as
      outgoing until it expires.
  - `Blocked = {"user_id", "phone", "display_name"}`
- **`POST /api/v1/friends/requests/{id}/accept`** → `200 {"friend": Friend}`.
  **`POST …/{id}/decline`** → `204`.
  **`DELETE /api/v1/friends/requests/{id}`** → `204` (cancel my own outgoing request).
  All three return `404 not_found` for an unknown request or one that isn't yours.
- **`DELETE /api/v1/friends/{user_id}`** → `204`: unfriend both ways, silently.
- **`POST /api/v1/blocks`** `{"user_id"}` → `204`. It ends any friendship and pending requests
  both ways. **`DELETE /api/v1/blocks/{user_id}`** → `204`; unblocking doesn't restore the
  friendship.
- **`GET /api/v1/contacts`** (v1.0) returns **friends only**. `registered` means "can be messaged";
  while the phone gate (§7) is on, an unverified friend shows `false`.

## 3. Realtime
- **`msg:send` and `typing`** add the error reason **`not_friends`**. It's returned for every
  recipient who isn't a friend, unknown ids included. `unknown_recipient` stays only for a
  malformed id or yourself.
  - An idempotent resend of an already-sent `client_msg_id` still returns the original reply.
  - `msg:ack` for messages already received is always accepted.
  - Events queued before an unfriend or block are still delivered on sync.
- **`presence:watch`** replies, and presence signals, cover friends only. On unfriend or block the
  server drops the pair from each other's watch sets at once.
- **The `signal` kind `friend`:**
  `{"kind": "friend", "data": {"action": "request_received" | "request_accepted", "request_id": "uuid", "user": {"user_id", "phone", "display_name", "company"}}}`
  - It goes only to the affected user's live channels.
  - `request_accepted` also covers auto-accepts and invite redemptions; for an invite,
    `request_id` is the invite id.
  - **Clients refetch `GET /friends` on this signal and after every (re)join**, because signals
    aren't stored.
  - Declines, cancels, removals and blocks send no signal.

## Migration (server, at deploy)
`RisiMe.Release.migrate_friendships/0` is idempotent:
- it scans Cassandra `message_index` (paged; it needs no `ALLOW FILTERING`);
- it keeps the distinct pairs whose users both still exist;
- it inserts them as friendships.

Today that makes Harsha ↔ Shenika friends, so no existing chat breaks. It runs in the deploy
step after `migrate()`.

## Examples (added to `contract/v1/examples/`)
`invite_create.json`, `invite_reply.json`, `invites_reply.json`, `friend_request.json`,
`friend_request_reply.json`, `friends_reply.json`, `friend_accept_reply.json`, `block.json`,
`signal_friend.json`, `error_not_friends.json`, `user_vouched.json`.
