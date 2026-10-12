-- Contract v1.41 §37.4 (decision 081): the `played` receipt of a group voice note. Additive.
-- Q5 (extended): SELECT user_id, delivered_at, read_at, played_at FROM group_receipts WHERE message_id = ?
-- Q6 (extended): UPDATE group_receipts SET played_at = ? WHERE message_id = ? AND user_id = ?
-- DM messages keep their single `status` column (message_index), which may now be `played`.
ALTER TABLE group_receipts ADD played_at timestamp;
