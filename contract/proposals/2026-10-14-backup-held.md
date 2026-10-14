# Proposal: held ("Waiting for encryption") messages are in backups and are sent after a restore (2026-10-14, root worker)

**Status:** APPLIED as **v1.49 §22.5 / §22.6** (root, 2026-10-14). Was: proposal for root/Harsha, to be applied as a **§22.5/§22.6 amendment** in the next free version. Harsha
2026-10-14 06:09 SLT, hard rule 9 ("an update never deletes local chats"). Android owns the bundle; it is implemented
against this text. Additive: **the bundle `schema` stays 1 (or 2 with Official)**, no capability, no server change.

## Why
§22.5's never-list contains "outbox rows". Since decision 048 (nightly.5+) a 1:1 text with no MLS group on the phone is
**held** in the outbox ("Waiting for encryption…") until the chat is encrypted or the user taps "Send without encryption".
Held rows can live for hours, they are the user's own words, and the user sees them in the chat; but a backup dropped them,
so a restore lost them. The upgrade gate saw it as `dm|text:7->4` (and `scripts/upgrade-test` had to tap "Send without
encryption" during seeding to get past it).

## Proposed PROTOCOL.md text

### §22.5 amendment: outbox lines (vN)
- **`message` lines gain an optional `outbox`** (`backup_entry_message_outbox.json`) for the user's own **unsent text**
  rows (never accepted by the server: no `message_id`):
  `{"type": "message", "conversation_id", "message_id": null, "client_msg_id", "from": <me>, "from_device", "server_ts": null,
  "payload": {"v": 1, "type": "text", "body", …}, "origin": null, "shared_by": null, "status": null,
  "outbox": {"state": "pending" | "failed", "client_ts": ts}}`.
  - `state: "pending"`: the row was waiting to be sent: held for encryption ("Waiting for encryption…"), or queued
    offline. `"failed"`: it showed "Not sent — tap to retry".
  - `client_ts`: when the user wrote it (the row's local time), ISO-8601 UTC with milliseconds.
  - `payload`: the same `text` envelope the outbox would send (`reply_to`, `forwarded`, `link_preview` kept; §33.9/§39).
  - **Only text.** Unsent photos, files and voice notes stay out (their bytes are local files, never in a backup), and
    so do reactions, deletes, `risi_ctl` rows and control messages.
- **Never carried:** the "Send without encryption" consent (`messages.e2ee = plain_ok`). It was given on the old phone for one
  moment; after a restore the message is held again and the user decides again (decision 048: never plaintext on its own).
- The never-list now reads "…outbox rows **other than unsent text (§22.5 `outbox`)**, drafts, …".
- The header's `counts.messages` includes outbox lines.

### §22.6 amendment: restoring outbox lines
- An `outbox` line is imported only when `from` is the restoring user, `message_id` is null, `payload` is a valid `text`
  (§10.3/§33), `state` is `pending` or `failed`, and **no local row has its `client_msg_id`** (whatever its state: an
  already-sent copy wins). `client_ts` at or before the conversation's `cleared_upto` → skipped (as any row).
- It becomes an outgoing row with the **same `client_msg_id`**, local time `client_ts`, no `message_id`, origin `"backup"`:
  - `pending` → **PENDING**. In a 1:1 the bubble shows "Waiting for encryption…" at once; the normal outbox then sends it
    **encrypted** as soon as the chat's MLS group is ready on this phone (§16, decision 048), after the 30-s wait
    "Send without encryption" is offered again; nothing goes in plaintext without that tap.
  - `failed` → **FAILED** ("Not sent — tap to retry"); it is sent only on the user's tap.
- **No duplicates.** The id is the original `client_msg_id`, so: a second restore skips it (`existing_row`); a copy that the
  old phone managed to send meanwhile arrives with the same `client_msg_id` and merges with the row (the §2.2/§10.3
  idempotent resend: the same `client_msg_id` from the same sender within 24 h returns the original reply, so the server
  stores it once).
- **Old apps** read an `outbox` line as a `message` without a `message_id` and skip it as malformed (exactly today's result:
  the held row is not restored). Nothing else changes for them; `schema` is not bumped.
- **Old backups** (no `outbox` anywhere) restore exactly as before.

### Upgrade gate (§22.13, hard rule 9)
Held and failed outgoing texts are rows of the chat: the per-conversation and per-chat counts **include them**, before an
update, after it, and after a backup → restore. The restore check compares the full counts with no outbox exception.

## Example (for root to add to `contract/v1/examples/`)
`backup_entry_message_outbox.json`:
```json
{"type":"message","conversation_id":"dm:2c1e4b7a-0d9f-4e3a-9b8c-7f6e5d4c3b2a_9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","message_id":null,"client_msg_id":"5e1b2c3d-4f5a-4b6c-8d7e-9f0a1b2c3d4e","from":"2c1e4b7a-0d9f-4e3a-9b8c-7f6e5d4c3b2a","from_device":null,"server_ts":null,"payload":{"v":1,"type":"text","body":"I'll bring the samples on Monday"},"origin":null,"shared_by":null,"status":null,"outbox":{"state":"pending","client_ts":"2026-10-14T00:41:07.512Z"}}
```

## Gate
Android unit tests (Robolectric, real Room SQL): a bundle with held and failed rows round-trips (same `client_msg_id`, body,
local time; held → PENDING showing "Waiting for encryption", failed → FAILED); a second import and a row already sent on this
phone are skipped (no double send: the outbox holds exactly one row per `client_msg_id`); a bundle without `outbox` (old
backup) restores unchanged; the upgrade-gate counts (`BackupDao.counts()`) after a round trip equal the counts before,
held rows included; `plain_ok` is never carried. Release: APK gates (storage format). The upgrade gate's restore check drops
its outbox exception.

## Agent design (decision 085)
No agent: infrastructure (local storage and backup format; rule 9 data safety).
