-- Contract v1.12 §15.8 (decision 047): deleting messages and chats. Queries first; no
-- ALLOW FILTERING, no secondary index. Additive.

-- Q7: SELECT user_id, event_id, ref_id FROM message_refs WHERE message_id = ? [AND user_id = ?]
-- Q8: INSERT INTO message_refs ... (before a plaintext reaction event is appended)
-- One partition per target message: the plaintext reaction events that reference it, per inbox
-- partition, and the reaction's own message_index id.
CREATE TABLE IF NOT EXISTS message_refs (
  message_id timeuuid, user_id uuid, event_id timeuuid, ref_id timeuuid,
  PRIMARY KEY ((message_id), user_id, event_id)
) WITH default_time_to_live = 2592000 AND gc_grace_seconds = 86400
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};

-- Q3 (extended) / Q3d: UPDATE message_index USING TTL <remaining> SET deleted_at = ?,
-- deleted_by = ? WHERE message_id = ?  (the content-free index tombstone)
ALTER TABLE message_index ADD deleted_at timestamp;
ALTER TABLE message_index ADD deleted_by uuid;

-- Q1s: SELECT event_id, kind, conversation_id FROM inbox_events WHERE user_id = ? AND
-- event_id <= ? (chat:clear). Written on every append from v1.12; null for older rows.
ALTER TABLE inbox_events ADD conversation_id text;

-- Q2: 'delete' for msg:delete claims (null = a msg:send claim).
ALTER TABLE sent_dedupe ADD kind text;

-- Single node: no repair to protect, so expired TTL cells are purged sooner.
ALTER TABLE inbox_events WITH gc_grace_seconds = 86400;
ALTER TABLE group_receipts WITH gc_grace_seconds = 86400;
