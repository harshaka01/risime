# 041 — MLS groups (contract v1.9)

**Status:** accepted 2026-10-06 (root, merging the crypto, server and android reviews of the v1.9
groups proposal). The contract text is PROTOCOL §12.

## Context
The groups proposal had passed its crypto and server reviews. The android review then raised
12 blocking wire gaps (R1–R12) and 8 suggestions (S1–S8). This records the choices that aren't
obvious from the contract text.

## Decision
- **Group-ready means a groups-capable app (R1).** Being MLS-capable isn't enough: a v1.8 app
  would drop a `message` event that has no `to`. Devices advertise
  `"capabilities": ["groups"]` when they register their MLS device. Readiness requires that every
  app instance in the 30-day census has the capability; otherwise the reason is `legacy_app`
  ("needs to update"). The server filters every `grp:` event and signal out of sockets whose
  device lacks the capability.
- **One committer, many authorised (R4, R5).** Every membership or role change is a server-side
  *pending op* with exactly one named committer:
  - first the requesting admin's device (or the user's own other device for device churn);
  - then online admin devices, each with 60 s.
  Only the named device acts, so 255 members never race, burn key packages and retry. The
  server still accepts a completing commit from **any** authorised device, so an in-flight commit
  is never wasted. `group_op` events carry the naming. For `grp:`, they replace `mls_membership`.
- **Own devices by anyone, other users by admins (R3).** Device churn of your own user (a new
  phone, a removed install) needs no admin. Anything touching another user is admin-only,
  including removing a member's stale device and the removal after a leave. The same rule runs in
  the MLS core and on the server from `contract/v1/group_policy_cases.json`.
- **No implicit successor (S7).** The crypto review's "lowest `leaf_index` becomes admin" can't be
  checked by the server, because group commits are PrivateMessage. And after the last admin left,
  no admin would be left to commit the removal. Instead, the last admin can't leave
  (`409 last_admin`); the client first promotes someone (the UI suggests the longest-standing
  member). This supersedes the successor rule in the proposal's review outcomes.
- **Removal is immediate on the server, cryptographic at the commit.** `pending_remove` users get
  no more group events and their sends are refused straight away. `remove`, `devices` and
  `rebuild` ops never expire; `add` and `role` ops expire after 24 h.
- **Rejoin as well as reset (R7, R8).** Reset is admin-only, as decided. A non-admin whose own
  state is broken, or who gets `410 log_expired`, would otherwise be stuck. `POST …/rejoin`
  re-adds just that device through a `devices` op. Admins reset only when the group as a whole is
  broken. On reset, the server's roles as of the last accepted epoch win over any local
  `group_meta`.
- **Generic blobs, minimal (R9).** `POST/GET/DELETE /api/v1/blobs` store opaque, client-encrypted
  bytes with an owner, a purpose, a conversation scope, a 2 MiB cap and a 30-day TTL. v1.9 uses
  only `purpose=mls`, for commits and Welcomes over 64 KiB. **The encrypted-images slice extends
  this API** (a `media` purpose, larger caps, a per-file key inside the MLS payload) rather than
  adding a second store.
- **Receipts are aggregated only (R10).** There are no per-member `status` events in groups, so a
  256-member group creates at most a few `group_receipt` events per message. The per-member
  detail is pulled on demand (`GET …/receipts`).
- **Caps (R12):** 256 users and 768 leaves. These keep the ratchet tree under about 0.7 MB and the
  Welcome under its 2 MiB cap, well inside Android's 2 MB `CursorWindow`.
- **Suggestions applied:** S1 (`client_group_id`; repeats are idempotent), S2 (live
  `display_name`), S3 (the removed user gets `404`; clients keep their local snapshot), S4 (an
  "added you" notification with a push), S5 (the 1-per-3-s typing limit), S6 (the UI shows server
  roles), S7 (see above), S8 (mute is local-only).
- **Deferred:** the half of S5 that sends typing only to members who have the chat open. The server
  has no "chat open" state; this would need a focus signal. Clients send typing only while the
  composer has focus.

## Consequences
- The server implements pending ops, committer naming (an Oban timer), blobs, receipts and the
  socket filter. Android implements the review's chunk order on top of §12.
- Both role suites must run `group_policy_cases.json`. A change to the policy is a contract
  change.
- Group-ready status depends on a v1.9 app on **every** device a user used in the last 30 days, as
  in the v1.7 rollout. A `required` update precedes enabling groups for the pilot.
