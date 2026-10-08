-- Contract v1.24 §24.12 (decision 066), server S5: Risi's raw buffer of Official plaintext.
-- Queries first; one table; no ALLOW FILTERING, no secondary index. Additive.
-- `body` is sealed by RisiMe.Agent.Transcript (AES-256-GCM under RISI_DATA_KEY, AAD bound to the
-- conversation and message ids); the store never sees plaintext. Rows live 24 h (TTL), so a
-- conversation has at most two `day` partitions (the UTC date of the message's TimeUUID).
-- Q11: INSERT INTO risi_buffer (conversation_id, day, message_id, sender_id, sender_device, body)
--        VALUES (?, ?, ?, ?, ?, ?)
-- Q12: SELECT message_id, sender_id, sender_device, body FROM risi_buffer
--        WHERE conversation_id = ? AND day = ? AND message_id > ? LIMIT ?
-- Q13: DELETE FROM risi_buffer WHERE conversation_id = ? AND day = ?             (purge)
-- Q14: DELETE FROM risi_buffer WHERE conversation_id = ? AND day = ? AND message_id = ?  (§15)
-- gc_grace_seconds = 0 is safe on one node (revisit for a cluster); hourly TWCS windows let
-- whole SSTables expire within a day.
CREATE TABLE IF NOT EXISTS risi_buffer (
  conversation_id text, day date, message_id timeuuid,
  sender_id uuid, sender_device uuid, body blob,
  PRIMARY KEY ((conversation_id, day), message_id)
) WITH CLUSTERING ORDER BY (message_id ASC)
  AND default_time_to_live = 86400 AND gc_grace_seconds = 0
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'HOURS', 'compaction_window_size': 1};
