-- Contract v1.9 §12.7 / §12.10: group messages and aggregated receipts. Additive.

-- Q3 (extended): a group message's recipients at send time (the sender excluded), read with
-- the message by message_id on every ack. Null for DMs (recipient_id is set instead).
ALTER TABLE message_index ADD recipients set<uuid>;

-- Q5: SELECT user_id, delivered_at, read_at FROM group_receipts WHERE message_id = ?
--     (aggregation after a restart, and GET /groups/{id}/messages/{message_id}/receipts)
-- Q6: UPDATE group_receipts SET delivered_at = ?, read_at = ? WHERE message_id = ? AND user_id = ?
-- One partition per message; rows are written once per member and status, and expire with the
-- message (30 days), so TWCS drops whole SSTables.
CREATE TABLE IF NOT EXISTS group_receipts (
  message_id timeuuid, user_id uuid, delivered_at timestamp, read_at timestamp,
  PRIMARY KEY ((message_id), user_id)
) WITH default_time_to_live = 2592000
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};
