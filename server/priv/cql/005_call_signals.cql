-- Contract v1.13 §16.3 (server review R2): ephemeral call signals in their own short-lived
-- table, never in inbox_events. Queries first; no ALLOW FILTERING, no secondary index. Additive.

-- Q9: SELECT event_id, payload FROM call_signals WHERE user_id = ? AND event_id > ? LIMIT ?
-- Q10: INSERT INTO call_signals (user_id, event_id, payload) VALUES (?, ?, ?) [USING TTL 60]
-- A ring row (ring: true) is written USING TTL 60, the others with the 120-s default.
-- gc_grace_seconds = 0 is safe on one node (revisit for a cluster). Whole 10-minute SSTables
-- expire and are dropped within minutes, so physical deletion matches the contract.
CREATE TABLE IF NOT EXISTS call_signals (
  user_id uuid, event_id timeuuid, payload text,
  PRIMARY KEY ((user_id), event_id)
) WITH CLUSTERING ORDER BY (event_id ASC)
  AND default_time_to_live = 120 AND gc_grace_seconds = 0
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'MINUTES', 'compaction_window_size': 10};
