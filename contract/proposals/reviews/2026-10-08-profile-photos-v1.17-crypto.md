# Review: profile photos v1.17, crypto side

**Reviewer:** crypto (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-profile-photos-v1.17.md`.
**Verdict:** accept with the required changes below. No MLS core change.

## What I checked
- `media.rs` (`A256GCM-S64K`, fresh `K` generated inside encrypt, no caller-supplied key) and the
  `risime-media-v1` label.
- The sender identity path: the core's authenticated leaf (§10.3), `authenticated_data` rules
  (§15.3).
- What a server, a removed member, or a member of one chat can do with the envelope.

## Required changes
- **K1. Deterministic tie-break.** "Keep the one already stored" on equal `ver` makes devices
  diverge by arrival order. Rule: higher `ver` wins; on equal `ver`, a `null` photo wins (a
  removal is never undone by a race); otherwise the greater `blob.sha256` (as base64 strings).
- **K2. Bound `ver`.** A sender clock far in the future would freeze its photo. Receivers drop an
  envelope with `ver` > their server-corrected now + 24 h. Senders use server-corrected time
  (`offset`, §16.3), never the raw device clock.
- **K3. Subject from MLS only.** The photo belongs to the authenticated sender's user (the
  verified leaf's `user_id`). A member can't set another member's photo; the server can't set
  anyone's (no group key). Add a negative test: an envelope whose JSON carries a `user_id` field
  is applied to the MLS sender, the field ignored.
- **K4. One encryption per photo.** Re-sends reuse the same blob and key (one ciphertext, so no
  nonce reuse); every new photo, even the same picture, is a new encrypt call with a fresh key
  inside the core. Nothing derives `K` from the image or the user.
- **K5. Empty `authenticated_data`** (§15.3): a `profile_photo` with a non-empty one is dropped.
- **K6. Say the audience honestly.** Whoever gets one envelope keeps that photo; the server reader
  rule (friends and group co-members) is housekeeping. A member removed from a group keeps the
  photo they saw and doesn't get later ones unless they share another chat. The UI says "Your
  photo is visible to your friends and people in your groups."
- **K7. `silent` is metadata.** It tells the server that a message is a control message, not
  which one. Clients set it only on `profile_photo` in v1.17; never on anything that should
  notify.
- **K8. 0-click decode surface.** JPEG only, sniffed from the bytes and matching `mime`; header
  dimensions checked against `w`/`h` and ≤ 512 before any pixel allocation; software decode with a
  timeout; size, SHA-256 and every AEAD segment verified first (§14.7 Receiving 3–4).

## Answers
- *Can the server roll a photo back?* Not across a `ver` (inside MLS), and not inside a blob
  (`blob_id` + `sha256` + the AEAD key pin the bytes). It can delete the blob: initials.
- *Linkability:* the same blob id in several chats lets the server link the uploads to one owner,
  which it knows anyway (the uploader).
