# Review: profile photos v1.17, server side

**Reviewer:** server (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-profile-photos-v1.17.md`.
**Verdict:** accept with the required changes below.

## What I checked
- `msg:send` (§10.3, §12.9) and the inbox push path (§8, the 10-s coalescer, §16.8 note on FCM
  deprioritisation).
- The blob controller and `blobs` table (§12.10, §14.8): `conversation_id` is set for every
  purpose today; `expires_at` null already means "current group icon".
- Readers per purpose (§14.2), the free-space guard (§14.5), the weekly orphan pass (§14.8).

## Required changes
- **S1. A photo change must not push.** Every `profile_photo` is an ordinary `msg:send`, so a
  change into 40 conversations sends up to 40 × (members) content-free FCM pushes that end in no
  visible notification. FCM deprioritises an app whose high-priority messages show nothing, and
  that is exactly what hurts call ringing (§16.8). Add an optional cleartext **`"silent": true`**
  to the e2ee `msg:send` (DM and group): the server stores and delivers the event as usual but
  sends **no §8 push** for it, live or coalesced. The `message` event carries `"silent": true`
  (absent = false), so replays know it too. `silent` that isn't a boolean, or `silent: true` with
  `body` or `reaction` → `bad_request`. It counts against the normal message rate (§4).
- **S2. `avatar` rows have no conversation.** `blobs.conversation_id` becomes nullable (only for
  `avatar`). Say it, with the index `(owner, purpose)` already there for "the current avatar".
- **S3. "Current" must be race-free.** Two uploads in flight: the one whose row commits last is
  current; the switch (new row `expires_at` null, the previous current row `expires_at` = now +
  24 h) happens in the commit transaction under the per-owner lock that §14.5 already uses for
  quotas. An idempotent `200` replay of an older `client_blob_id` never makes that blob current
  again.
- **S4. Reader rule, precisely and cheaply.** On `GET`/`HEAD` of an `avatar`: owner; or an
  accepted friendship with no block either way; or a group in which both are `active` or
  `pending_add`. Two indexed lookups (`friendships` by pair, `group_members` by user); no cache
  needed at pilot scale. Every other case `404` (no `403`, nothing revealed).
- **S5. Limits and guards.** 3 uploads per hour and 10 per day per user (`429` with
  `Retry-After`), replays not counted; concurrency shares the 3-per-user slots; `avatar` keeps
  working down to the lower free-space guard, like `icon`; not in `GET /blobs/usage`.
- **S6. Cleanup.** Expired replaced avatars go through the existing sweep. A deleted user's blobs
  go with the user. Nothing else is stored: no per-user "photo version" on the server.

## Answers
- *Does the server learn the photo?* No: ciphertext of a padded size, the owner and who downloads
  it (who looked at whose photo, roughly). Logged like any blob access, ids only.
- *New tables?* None. One nullable column and one purpose value.
- *New events?* None. `silent` is one optional field on an existing push and event.
