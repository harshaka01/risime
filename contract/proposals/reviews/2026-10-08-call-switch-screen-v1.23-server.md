# Review: voice↔video switching and screen sharing v1.23, server side

**Reviewer:** server (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-call-switch-screen-v1.23.md`.
**Verdict:** accept with the required changes below.

## What I checked
- `call:signal` (§16.3, §19.2, §20.3): validation, the cleartext `media`, the delivery and push
  filters, the limits. `POST /calls/rooms` (§20.2): `start`/`join`/`status`, room names derived from
  the start media, the token claims, `ListRooms`/`ListParticipants`/`RemoveParticipant`.
- LiveKit's `RoomService` in v1.13.9: `UpdateRoomMetadata`, and `UpdateParticipant` with a
  `ParticipantPermission` (`can_publish_sources`); narrowing the sources makes the SFU unpublish
  tracks of removed sources, widening takes effect at once for the live connection.
- `RisiMe.Devices` `@known_capabilities` (unknown capabilities are dropped).

## Required changes
- **S1. 1:1 needs no server change; say so.** The cleartext `media` stays the call's start media on
  every signal (switch signals, re-offers and their answers included), so the v1.18 delivery and
  push filters and the binding are unchanged. A re-offer is ~6–8 KiB, within the 20 480-byte bound;
  `call_switch` and `call_media` fit in the 30-per-pair-per-10-s `ring: false` limit. The only server
  change for 1:1 is keeping the two new capabilities.
- **S2. The video cap must hold against races.** `upgrade` counts the union of the identities in
  `ListParticipants` and the identities the server minted a `join` (or `start`) token for in the
  last 600 s that aren't present yet (in memory, per room; lost on restart, accepted: the window is
  one token lifetime). `upgrade` and `join` for one room are serialised (a per-room lock in
  memory). After the upgrade, `join` uses cap 8 from the metadata (LiveKit's own
  `max_participants` stays 32 for a room started as voice; tokens only come from us).
- **S3. Re-join must be a refresh.** Today `join` refuses `call_full` at `num_participants ≥ max`.
  An identity that is already in the room must never be refused (it is a token refresh, not a new
  seat), and `join` from a present identity in a video room re-applies `UpdateParticipant` for it,
  so a participant whose live grant didn't update (a failed call during `upgrade`) recovers without
  reconnecting.
- **S4. Upgrade order and failure.** Lock → `ListRooms` (missing → `404 call_ended`) → the requester
  identity present (`ListParticipants`; else **`409 not_in_call`**) → the cap (**`409
  too_many_for_video`**) → `UpdateRoomMetadata` (`m: "video"`) → `UpdateParticipant` for the requester
  (failure → `503 calls_unavailable`, metadata left as video: harmless, idempotent retry) → the
  others (one retry each, failures logged, see S3) → the reply token. Idempotent: a room already
  video answers `200` with a token and changes nothing.
- **S5. Grants by device capability.** `upgrade` needs a device that advertises `call_switch`
  (else `403 invalid_device`). `screen_share` is added to `canPublishSources` only for identities
  whose device advertises `screen_share`, in every video grant (start, join, upgrade).
- **S6. Limits and logs.** `upgrade` 10 per user per hour (its own bucket). Logs: room, identities,
  counts, outcome; never tokens.

## Answers
- *Do we need readiness fields (`switch_ready`)?* No: 1:1 negotiates per call inside MLS
  (`features`); in groups the server checks the device at `upgrade`. `missing_*` lists are not
  needed for this slice.
- *Do we store anything?* No. The room metadata (in LiveKit's memory) and the 600-s token memory
  are the only state; nothing in Postgres or Cassandra.
- *Data used on the server?* No: the server never sees media and keeps no call table.
