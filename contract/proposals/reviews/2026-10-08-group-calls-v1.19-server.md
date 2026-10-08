# Review: group calls with LiveKit v1.19, server side

**Reviewer:** server (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-group-calls-v1.19.md`.
**Verdict:** accept with the required changes below.

## What I checked
- `call:signal` and the `call_signals` store (§16.3), the `call` push (§16.8), group membership
  states and removal (§12.3, §12.4), device removal (§10.1).
- `infra/livekit/livekit.yaml` (commit 383fced): `room.auto_create: true`, `max_participants: 32`,
  `empty_timeout 60`, `departure_timeout 20`, no webhook; Caddy exposes only `/livekit/rtc*`.
- LiveKit's server API (Twirp `RoomService` on 127.0.0.1:7880: `CreateRoom`, `ListRooms`,
  `ListParticipants`, `RemoveParticipant`), signed with the API key.

## Required changes
- **S1. Stale and late joins need a source of truth.** With `auto_create` on, any token for an old
  `call_id` opens a fresh empty room, and a member who opens the chat later can't tell whether the
  call still runs. Without a call table, LiveKit's room list is that truth:
  - `POST /calls/rooms` takes **`"action": "start" | "join" | "status"`**. `start` creates the
    room through `CreateRoom` (idempotent), with `max_participants` (32 voice, 8 video),
    `empty_timeout` and `departure_timeout`, and the room metadata
    `{"c": "<conversation_id>", "m": "<media>"}`; `join` and `status` look it up (`ListRooms`).
    `join` on a missing room → **`404 call_ended`**; `status` → `{"active", "participants"}` with
    no token.
  - LiveKit's **`room.auto_create` must be `false`** (infra change), so only `start` makes rooms.
  - A full room → **`409 call_full`** at `join` (from `num_participants`), before LiveKit refuses.
- **S2. Unguessable, unlinkable room names.** The name is
  `base64url(HMAC-SHA256(K_room, "risime-room-v1" ‖ conversation_id ‖ call_id ‖ media))[0..22]`, with
  `K_room` derived from `LIVEKIT_API_SECRET` (HKDF, label `risime-livekit-room-v1`): no new secret,
  stable across restarts, and the group id appears only in room metadata (same host).
- **S3. Removal must cut access at once.** LiveKit tokens can't be revoked and LiveKit refreshes
  them for connected participants. When a member turns `pending_remove` (removed or left), when one
  of their devices is removed (§10.1), and on a group reset, the server lists the group's rooms (by
  metadata) and calls `RemoveParticipant` for the affected identities. The MLS removal commit then
  rotates the keys (crypto); the server step covers the gap before it lands.
- **S4. The calling device must be a leaf.** `X-Device-Id` must name a `group_calls` device of an
  **active** member that is in the group's MLS device list (`mls_group_devices`), not just any
  device of the user; it becomes the token identity.
- **S5. Group ring cost and limits.** One group `call:signal` writes a row per member user (up to
  256). Limits per user: `ring: true` at most **1 per group per 30 s** and **10 per hour**;
  `ring: false` in a group at most **30 per group per 10 s**; all inside the existing 60 per 10 s.
  `rooms` `start` 10 per user per hour, `join`/`status` 60 per user per minute.
- **S6. Push.** A group ring pushes `call` at once to every `group_calls` device of the other
  members without a live inbox channel. No 3-s fallback in groups: it needs per-member tracking,
  and a member who misses the ring can still join from the chat (S1 `status`).
- **S7. Config and failure.** `LIVEKIT_URL` (public, `wss://risime.risicloud.ai/livekit`),
  `LIVEKIT_API_URL` (`http://127.0.0.1:7880`), `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` from `.env`;
  any missing or LiveKit unreachable → `503 calls_unavailable`. The server's own API JWTs are
  per-request, 60 s, with only the grants the call needs.
- **S8. Nothing persisted.** No call table, no webhook, no participant log; LiveKit's state lives
  in its memory and ends with the room. The server logs ids and counts only.
- **S9. Readiness.** `GET /mls/groups/{grp}` gains `group_calls_ready` (the caller's user and at
  least one other active member have a `group_calls` device) and `missing_group_calls` (members'
  instances without it), as information for the call button.

## Answers
- *Does the server learn more than for 1:1?* It learns who joins a group call (tokens, `status`)
  and, through LiveKit on the same host, IPs, join and leave times and traffic volume. Recorded in
  the privacy section.
