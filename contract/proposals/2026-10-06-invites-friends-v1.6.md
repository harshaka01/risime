# Proposal: PROTOCOL v1.6 — invites and friends

**Status:** proposed by root on 2026-10-06 (Harsha's priority "invites and friends"; decision 029).
Needs review by the server and android roles before merge. It is **additive**: v1.5 apps keep
working. `/contacts` now returns friends only; the `friend` signal is ignored by older apps, which
ignore unknown signal kinds (§2.3).

## 0. Principles
- **Invite-only sign-up**, with the admin allowlist kept as a second path.
- **Privacy:** a user's friends, requests and blocks are visible only to them.
  - **No response ever reveals whether a phone number is registered**: friend requests get the
    same reply either way.
  - Declines and blocks are silent to the other side.
- **Only accepted friends can chat**, type to each other, or see each other's presence.

## 1. Invites (REST)
- **`POST /api/v1/invites`** `{"phone": "+94…", "email": "…", "name": "Kamal Perera"}` → `201 {"invite": Invite}`.
  - The reply is the same whether or not that phone or email already belongs to a member. If the
    phone is already a member, the server silently turns the invite into a friend request instead.
  - `422 invalid_phone` | `invalid_email` | `invalid_name`.
  - `429 rate_limited` (with `Retry-After`): **10 invites per user per 24 h, 50 pending per user**.
- **`GET /api/v1/invites`** → `{"invites": [Invite]}` (my sent invites, newest first).
- **`DELETE /api/v1/invites/{id}`** → `204` (revoke a pending invite).
- `Invite = {"id": "uuid", "phone": "+94…", "email": "…", "name": "…", "status": "pending" | "accepted" | "revoked" | "expired", "expires_at": "<ISO ms>", "inserted_at": "<ISO ms>", "share_text": "…", "link": "https://risicloud.ai/app/risime/"}`
  - Invites expire after **30 days**.
  - `share_text` is ready to send with the device's share sheet: the RisiMe link, plus "sign in
    with <email>". **The server never emails or texts invitees itself.**
- **Redemption** (extends the §6.1 mapping, step 2): when a Keycloak user's verified email has
  **no allowlist entry** but has a **pending, unexpired invite**:
  - the user is created with the invite's **phone** and **name** (falling back to the token's
    `name`) and an empty company unless the token has one;
  - the invite becomes `accepted`;
  - the **inviter and the invitee become friends** automatically.

  If the invited phone is already bound to another Keycloak account: `409 identity_conflict`.
- **`User` gains `"vouched_by": {"user_id": "uuid", "display_name": "…"} | null`.** It is set
  for users created from an invite while their phone isn't SMS-verified (§7). Clients show
  "vouched by <name>". It is null for allowlisted and verified users.

## 2. Friends (REST)
- **`POST /api/v1/friends/requests`** `{"phone": "+94…"}` → **always** `202 {"status": "requested"}`.
  - The reply is the same whether the number is unregistered, already a friend, blocked by them,
    or yourself.
  - A request to an unregistered number is kept against that phone (for 30 days) and appears to
    them when they join.
  - `422 invalid_phone`; `429 rate_limited` (30 per user per 24 h).
  - Clients should then offer: "If they're not on RisiMe yet, send them an invite".
- **`GET /api/v1/friends`** →
  `{"friends": [Friend], "incoming": [Request], "outgoing": [Request], "blocked": [Blocked]}`
  - `Friend = {"user_id", "phone", "display_name", "company", "vouched_by": {…} | null, "since": "<ISO ms>"}`
  - `Request = {"id": "uuid", "phone": "+94…", "display_name": "…" | null, "company": "…" | null, "inserted_at": "<ISO ms>"}`
    - **Incoming** requests carry the requester's name and company.
    - **Outgoing** requests show only the phone you entered (`display_name` and `company` are
      null), so outgoing requests never reveal registration.
  - `Blocked = {"user_id", "phone", "display_name"}`
- **`POST /api/v1/friends/requests/{id}/accept`** → `200 {"friend": Friend}`.
  **`POST …/{id}/decline`** → `204`; it's silent, and the requester's outgoing request just
  expires. `404 not_found` for an unknown or not-yours request.
- **`DELETE /api/v1/friends/{user_id}`** → `204`. Removes the friendship on both sides, silently.
- **`POST /api/v1/blocks`** `{"user_id": "uuid"}` → `204`.
  - Blocking removes any friendship and pending requests both ways.
  - A blocked user's requests are dropped silently (still `202`), and their `msg:send` and
    `typing` get `not_friends`.
- **`DELETE /api/v1/blocks/{user_id}`** → `204` (unblock; no friendship is restored).
- **`GET /api/v1/contacts`** (v1.0) now returns **only friends**, as `Contact` with
  `registered: true`. Older apps keep working with a private list.

## 3. Realtime
- **`msg:send` to a non-friend:** error `{"reason": "not_friends"}`. The same applies to
  `typing`.
- **`presence:watch`** replies, and signals, only for friends. Ids that aren't friends are left
  out ("unknown").
- **New `signal` kind `friend`** (ephemeral, §2.3):
  `{"kind": "friend", "data": {"action": "request_received" | "request_accepted", "request_id": "uuid", "user": {"user_id", "phone", "display_name", "company"}}}`
  - It is sent only to the affected user's live channels.
  - **Clients refetch `GET /friends` on this signal and after every (re)join**, because signals
    aren't stored.
  - Declines, removals and blocks produce **no** signal.
- Messages and history already stored between users who stop being friends stay on their devices.
  New sends are refused.

## Examples (to add at merge)
`invite_create.json`, `invite_reply.json`, `invites_reply.json`, `friend_request.json`,
`friend_request_reply.json`, `friends_reply.json`, `friend_accept_reply.json`, `block.json`,
`signal_friend.json`, `error_not_friends.json` (`{"reason":"not_friends"}`),
`user_vouched.json` (a `{"user": User}` with `vouched_by`).

## Migration (server, at deploy)
- Every existing pair of users that already has a conversation (any `message_index` row between
  them) becomes **accepted friends**, so existing chats keep working. Today that is Harsha ↔
  Shenika.
- Allowlisted users keep signing in; they start with no friends unless a migrated chat exists.
