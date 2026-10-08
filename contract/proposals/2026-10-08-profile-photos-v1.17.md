# Proposal 2026-10-08: profile photos (and group photo UI), v1.17

**Status: merged into `contract/v1` as v1.17 on 2026-10-08** (PROTOCOL §18, with cross-references
in §13.3, §14.2 and §14.5), after review by server, android and crypto
(`reviews/2026-10-08-profile-photos-v1.17-*.md`). Every required change was applied in §18; the
draft below is kept as written. The main changes from review: the `silent` flag on the e2ee
`msg:send` and the `message` event (no push; no pre-install marker) (server S1, android A3); a
deterministic winner rule and a `ver` window (crypto K1, K2); one sender per user with a 5–30 s
sibling wait, and dirty groups sent on the next message or chat open (android A1, A2); the
race-free "current" switch and the precise reader rule (server S3, S4); notification and blocked
user rules (android A6, A9).

From: root, 2026-10-08. Roadmap step 1 (UX polish), approved by Harsha.

## 1. Goal and constraints
People see each other's photo in chats, the chat list, group member lists and headers. Group
icons already exist (§12.2 `group_meta.icon`, §14.4, purpose `icon`). Constraints:
- **The server never sees content** (§10.0): no plaintext photo on the server, no key on the
  server, no photo in a push.
- **Reuse what exists:** the `A256GCM-S64K` blob format and the media label (§14.3), the blob REST
  (§14.2), the icon object shape (§14.4), ordinary e2ee `msg:send`. No MLS core change.
- **Additive:** old apps ignore the new envelope type (§10.3) and the new fields (§0).

## 2. Design in one paragraph
A user's photo is a 512×512 JPEG, re-encoded and stripped on the device, encrypted under a fresh
key into one `avatar` blob owned by the user. The blob reference and its key travel in an MLS
application envelope **`profile_photo`**, sent by the user's device into each of the user's e2ee
DMs and groups: on every change, when a conversation becomes e2ee or the user joins one, and when
a new device joins a conversation. Receivers keep **one photo per user**, the one with the
highest `ver`, and take the user from the MLS sender, never from JSON. Plaintext DMs send nothing;
there a photo shows only if the receiver already has it from an e2ee conversation, else initials.

## 3. The `profile_photo` envelope (MLS application message)
`profile_photo_payload.json`:
```json
{"v": 1, "type": "profile_photo", "ver": 1791292530123,
 "photo": {"blob": {"blob_id": "…", "size": 61456, "sha256": "<b64>"},
           "enc": {"alg": "A256GCM-S64K", "key": "<b64 32 bytes>", "plain_size": 61000},
           "mime": "image/jpeg", "w": 512, "h": 512}}
```
- `photo` is the §14.4 icon object, or `null` for "no photo" (`profile_photo_payload_removed.json`).
- `ver`: the sender's server-corrected time (`device_now + offset`, §16.3) in milliseconds at the
  change, made strictly greater than the last `ver` this device sent. A re-send of the same photo
  keeps its `ver`.
- The subject is the **MLS sender's user** (§10.3). There is no user field.
- **Validation** (or drop and log, as an unknown type): `ver` an integer ≥ 1; `photo` null or:
  `alg` exactly `A256GCM-S64K`, `key` 32 bytes, `sha256` 32 bytes, `plain_size ≥ 1` and its
  `cipher_size` equal to `blob.size`, `blob.size` ≤ the `avatar` cap, `mime` exactly
  `image/jpeg`, `w == h`, `64 ≤ w ≤ 512` (`profile_photo_payload_bad.json`, `w` 1024, must be
  dropped).
- **Highest `ver` wins** per user, across all conversations. Equal `ver` with a different blob:
  keep the one already stored.

## 4. The `avatar` blob purpose (extends §14.2, §14.5)
`POST /api/v1/blobs?purpose=avatar&client_blob_id=<uuid-v4>` (no `conversation_id`) →
`201 {"blob_id", "size", "sha256", "expires_at": null}` (`blob_upload_avatar_reply.json`).
- Any signed-in user. A `conversation_id` with `avatar` → `400 bad_request`.
- Cap **512 KiB** ciphertext; **3 per hour, 10 per day**; not counted in a quota.
- **One current avatar per user.** A new upload becomes current; the previous one expires
  **24 h** later. `DELETE` by the owner removes it at once (used for "Remove photo").
- **Readers:** the owner; an accepted friend with no block either way; a user who shares a group
  with the owner where both are `active` or `pending_add`. Everyone else `404`. Checked on every
  read, so an unfriended non-co-member loses access at once (housekeeping, not cryptography).

## 5. When a device sends `profile_photo`
Only into e2ee conversations where the user is an active member. Through the normal e2ee
`msg:send` (§10.3, §12.9) and the per-conversation lane (§16.4).
1. **Change** (set or remove): into every such conversation, paced at most one per second.
2. **New conversation:** the epoch-0 commit of a DM or group accepted, or a Welcome applied.
3. **New device in a conversation** (any added leaf, own user's included): DMs at once; groups
   before this device's next message there.

Never more than 3 changes per hour (client).

## 6. Receiving
- Not a message: no row, no unread, no notification, no preview, no ack.
- Download at once (small), verify as §14.7 Receiving 3, decode JPEG only, header ≤ 512 px.
- The key is sealed like an image key; the ciphertext is cached; nothing decoded on disk.

## 7. Group photo UI (no wire change)
Admins set or remove the group photo from group info (the existing `meta_changed` commit with
`purpose=icon`). On `metadata_changed` the client diffs old and new `group_meta` and writes a
local line: "<name> changed the group photo" / "removed the group photo" / "changed the group name
to "<name>"".

## 8. Old apps
v1.7–v1.16 apps ignore `profile_photo` (§10.3) and see initials. Nothing else changes.

## 9. Examples
`profile_photo_payload.json`, `profile_photo_payload_removed.json`, `profile_photo_payload_bad.json`,
`blob_upload_avatar_reply.json`.
