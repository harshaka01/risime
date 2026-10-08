# Review: profile photos v1.17, android side

**Reviewer:** android (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-profile-photos-v1.17.md`.
**Verdict:** accept with the required changes below.

## What I checked
- The unknown-type branch of the MLS payload decoder (§10.3): stores nothing visible; the event is
  marked seen and passed by the cursor.
- §13.3 pre-install rules and the §17.2 gap index: they run on any undecryptable e2ee `message`.
- The per-conversation send lane (§16.4), image receive rules (§14.7), the fingerprint lock
  (decision 014) and content-free notifications.

## Required changes
- **A1. One sender per user, not every device.** "New device in a conversation → send" makes
  every device of every member user send (a DM with my phone and tablet: both send my photo). Use
  the §10.3 pattern: each device waits a random **5–30 s** and sends only if no `profile_photo`
  from its own user with a `ver` ≥ its own arrived in that conversation after the trigger (it sees
  its siblings' sends through the sender copy).
- **A2. Groups: lazy, but not "never".** A lurker never sends a next message, so a new device
  would show initials for them for good. Groups mark the conversation **dirty** on join or on a
  new leaf, and send before the next own message there **or when the user opens that chat**. A
  change (set/remove) still goes out at once, paced.
- **A3. Pre-install photo envelopes must not create markers.** A reinstalled phone sees every old
  `profile_photo` as an undecryptable pre-install message and would add "Earlier messages aren't
  available on this device" and gap rows to chats that had nothing else. With the server's
  `silent` flag (server S1) on the event: a pre-install `silent` event adds no marker and no gap
  row, and is not counted in a history request.
- **A4. Not a message, everywhere.** No row, unread count, notification, chat-list preview,
  search hit, ack (delivered or read), reaction target, delete target, or history-bundle entry
  (§17.7). A `profile_photo` arriving in a `message` event of a plaintext DM is impossible (no
  ciphertext); in an e2ee one it is applied, never shown.
- **A5. Remove means remove.** On `photo: null` (or a newer photo) the receiver deletes the old
  key and cached ciphertext in the same transaction. The sender deletes its old blob only for
  "Remove photo" (a change leaves the 24-h server expiry, so late receivers still fetch).
- **A6. Notifications.** The decrypted photo may appear in a notification (`Person` icon,
  CallStyle) only where the notification already shows the name; never in the content-free
  notifications of a locked session (decision 014, §16.8).
- **A7. Picker and crop.** System Photo Picker, a square crop the user sees, then §14.7 Sending 2
  (software sRGB bitmap, no metadata) at 512×512, or the shorter side if smaller; below 64 px:
  "Choose a larger photo". JPEG q85, stepped down until it fits the cap.
- **A8. Fresh install.** Without another own device, the app doesn't know its own photo: Settings
  shows none and peers keep the old one until the next change. With another own device, A1/A2 bring
  it back through the sender copy. Say it; it is not data loss (decision 055 counts messages).
- **A9. Blocked users** show initials on this device, whatever the receiver has stored.

## Answers
- *Storage:* one Room table `profile_photos(user_id PK, ver, blob_id, size, sha256, key_sealed,
  plain_size, w, h, fetched)`, an additive migration.
- *Decoding:* JPEG only, sniffed; header ≤ 512 px and equal to `w`/`h` before allocating; decoded
  to display size off the main thread; bitmaps in memory only.
