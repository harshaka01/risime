# 047 — Delete messages and chats (delete for me / for everyone, clear / delete chat)

**Status:** proposed 2026-10-06 (root), pending the server, android and crypto reviews. Contract
proposal `contract/proposals/2026-10-06-delete-v1.12.md` (PROTOCOL v1.12).

## Context
Harsha wants WhatsApp-like deletion: "Delete for me" for any message, "Delete for everyone" for
own messages within 48 h and for group admins on any message, a "This message was deleted"
tombstone on every device, the server dropping its stored copies, multi-select, and Clear chat /
Delete chat. E2EE bodies are opaque to the server (§10), and inbox events live in Cassandra
partitioned by user (`inbox_events ((user_id), event_id)`), so both "what to delete" and "who may
delete" must come from metadata the server already holds.

## Decision
1. **One push `msg:delete` with `scope: "me" | "everyone"`, and one new event kind `delete`.** In
   E2EE chats the targets travel twice: in cleartext request fields the server authorises, and in
   an MLS application envelope `{"v":1,"type":"delete","targets":[…]}` that receivers authenticate.
   A new event kind (not a `message` event) keeps acks, receipts and reaction targeting away from
   it and lets the server attach target metadata. Old apps ignore it.
2. **Authorisation twice.** The server checks `message_index.sender_id`, the TimeUUID time (48 h on
   the server clock) and the group role *now*. Receivers check the MLS-authenticated deleter
   against the target's MLS-authenticated original sender, or against `group_meta.admins` *at the
   control message's epoch*, and require the envelope's targets to equal the event's. Nothing new
   is signed: MLS sender authentication of an application message is the signature.
3. **`gone` is success.** A target that is unknown, expired, already deleted, of another
   conversation, or not a normal message is reported as `gone`, not as an error. This makes
   retries and concurrent deletes idempotent and never reveals whether an id exists. Real refusals
   (`not_sender`, `too_old`, `not_admin`) are all-or-nothing with a per-target `failures` list,
   because the encrypted envelope must name exactly the set the server acted on.
4. **The server deletes rows; it does not rewrite them in place.** The target's own inbox rows are
   point deletes (`event_id = message_id`; the partitions come from `message_index`). Rows with
   other ids (status, `group_receipt`, plaintext reactions) are found through a new
   `message_refs ((message_id), user_id, event_id)` table written alongside them. The image blob is
   deleted after an owner check (blob owner = target sender, same conversation). `message_index`
   keeps a content-free tombstone (`deleted_at`, `deleted_by`) so retries, acks and resends of the
   original `client_msg_id` behave. Offline devices get placeholders from the `delete` event's
   per-target `from`/`server_ts`, instead of an in-place tombstone event, which would need TTL
   arithmetic and a kind change on existing rows.
5. **No capability flag.** A v1.11 app keeps a deleted message on screen but loses nothing else;
   the rollout is a `required` update as for v1.7/v1.9/v1.11. (Open point: a `deletes_ready` hint.)
6. **Clear chat and Delete chat are local, and also remove my own inbox copies** (`chat:clear`,
   `scope: "me"`), so a reinstall doesn't resurrect them. They never touch `mls_*`/`group_*`
   events or MLS state. `chat:clear` scans the caller's one partition with an app-side filter (a
   background job) rather than adding a per-conversation index table; revisit if partitions grow.
7. **Admins have no age limit** inside the 30-day retention; members' own messages have 48 h.
8. **Accepted limits:** E2EE reaction ciphertext to a deleted message stays until its TTL (the
   server can't tell what it targets; every client drops it); pre-v1.12 status/receipt events have
   no refs row and stay until TTL (no content); removed group members and old apps keep their local
   copies; "Delete for me" doesn't sync to my other devices yet.

## Consequences
- Cassandra: `004_delete.cql` adds `message_refs` and two `message_index` columns; one extra write
  per status/receipt/plaintext-reaction event; point-delete tombstones in a TWCS table (proposed
  `gc_grace_seconds = 86400` on a single node).
- Android: tombstone rows, hidden tombstones for deletes that arrive first, per-epoch admin lists
  for groups, notification withdrawal, multi-select, Clear/Delete chat.
- Crypto: no core change unless the review wants AAD binding or past-epoch `group_meta` access.
- No learning-log entries (no model call).
