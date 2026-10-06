# 047 — Delete messages and chats (delete for me / for everyone, clear / delete chat)

**Status:** accepted 2026-10-06 (root), after the server, android and crypto reviews. Contract:
PROTOCOL §15 (v1.12); proposal `contract/proposals/2026-10-06-delete-v1.12.md`.

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
   the server clock) and the landed group role, read in the same short critical section as the
   `stale_epoch` check. Receivers compare the deleter's attested `user_id` with the target's
   verified sender, or use the core's `sender_is_admin` (admin at the control's epoch, recorded by
   the core at every merge), and require the `authenticated_data`, envelope and event target sets
   to be equal. Nothing new is signed: MLS sender authentication of an application message is the
   signature; binding the targets into MLS `authenticated_data` lets the server refuse a mismatch
   before anything is deleted.
3. **`gone` is success.** A target that is unknown, expired, already deleted, of another
   conversation, or not a normal message is reported as `gone`, not as an error. This makes
   retries and concurrent deletes idempotent and never reveals whether an id exists. Real refusals
   (`not_sender`, `too_old`, `not_admin`) are all-or-nothing with a per-target `failures` list,
   because the encrypted envelope must name exactly the set the server acted on.
4. **The server deletes rows; it does not rewrite them in place.** The target's own inbox rows are
   point deletes (`event_id = message_id`; the partitions come from `message_index`). Plaintext
   reactions (other ids) are found through a new `message_refs ((message_id), user_id, event_id)`
   table written before them; content-free `status`/`group_receipt` events stay until their TTL.
   The image blob is deleted after an owner check (blob owner = target sender, same conversation);
   a mismatched blob id is skipped, never a refusal. `message_index`
   keeps a content-free tombstone (`deleted_at`, `deleted_by`) so retries, acks and resends of the
   original `client_msg_id` behave. Offline devices get placeholders from the `delete` event's
   per-target `from`/`server_ts` (only for users who had the target), instead of an in-place
   tombstone event, which would need TTL arithmetic and a kind change on existing rows. Deletion is
   logical: purged by the 30-day TTL windows, backups keep the bytes up to 14 days, stated in the
   privacy note.
5. **No required update; a `deletes` capability instead** (decision 016: `required` only when the
   server drops a protocol). A v1.11 app keeps a deleted message on screen (for good, if it skipped
   the event) but loses nothing else. `deletes_ready`/`missing_deletes` let the dialog say "People
   on older app versions may still see it". The receive side ships in a nightly before the send UI
   is enabled.
6. **Clear chat and Delete chat are local, and also remove my own inbox copies** (`chat:clear`,
   `scope: "me"`), so a reinstall doesn't resurrect them. They never touch `mls_*`/`group_*`
   events or MLS state. `chat:clear` scans the caller's one partition with an app-side filter (a
   background job) rather than adding a per-conversation index table; revisit if partitions grow.
7. **Admins have no age limit** inside the 30-day retention; members' own messages have 48 h.
8. **Accepted limits:** E2EE reaction ciphertext to a deleted message stays until its TTL (the
   server can't tell what it targets; every client drops it); status/receipt events stay until
   their TTL (no content); removed group members and old apps keep their local
   copies; "Delete for me" doesn't sync to my other devices yet.

## Conflicts between the reviews and the proposal (resolved)
- **Rollout:** no `required` update (server R1, android R1, decision 016) over the proposal's
  `required` update; the `deletes` capability (android A4, server suggestion) over "no capability".
- **Admin at epoch:** the core answers it (`sender_is_admin`, crypto R2) over per-epoch admin lists
  kept by the app (proposal, android consequence).
- **Hidden tombstones:** re-checked after decrypting the late message (crypto R3) over dropping the
  message unseen (proposal §5.3).
- **AAD binding:** adopted now (crypto S1; root: cheap and safer) although receiver binding alone
  is safe; the server parses the PrivateMessage header for it.
- **Lock:** no lock across the fan-out (server S4), but the epoch check, landed-role read and
  event-id planning share the short per-conversation critical section (crypto C2).
- **`message_refs`:** plaintext reactions only (server suggestion) over status/receipt refs too.
- **Wording:** "You deleted this message" on the deleter's devices (android A1).

## Deferred
- A one-time catch-up replay of `delete` events after upgrading (android S-g).
- Syncing "Delete for me" and Clear chat to the user's other devices (A6).
- `out_of_order_tolerance` 32 (crypto S4), decided with v1.13; MLS padding (crypto S5).

## Consequences
- Cassandra: `004_delete.cql` adds `message_refs`, two `message_index` columns,
  `inbox_events.conversation_id`, `sent_dedupe.kind`, and `gc_grace_seconds = 86400` on
  `inbox_events`, `message_refs` and `group_receipts` (single node).
- Server: an MLS header parser for the AAD check, a finishing Oban job enqueued at the claim, the
  `deletes` readiness, every `message_index` reader made tombstone-aware.
- Crypto core: per-epoch admin records and `sender_is_admin`, `encrypt_with_aad` and
  `authenticated_data`, `maximum_forward_distance` 20 000 with a migration of stored groups.
- Android: tombstone rows, a hidden-tombstone store, a delete outbox and a serial
  encrypt-and-push lane, the `cleared_upto` watermark, silent notification rebuilds, a server-clock
  offset, `secure_delete`, multi-select, Clear/Delete chat (a Room migration).
- No learning-log entries (no model call).
