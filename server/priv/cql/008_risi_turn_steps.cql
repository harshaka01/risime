-- Contract v1.25 §25.1 (decision 068), server S11: one row per step of a Risi turn (the tool
-- loop of RisiMe.Agent.Turn), part of the learning log (§24.12). HASHES ONLY: never the request,
-- the args, a tool result or the answer (decision 066; 068 keeps it hashes-only until Harsha
-- decides otherwise). A turn's rows are written within its 60 s, so one small partition per
-- turn (`authz`: the authorize result; AUTHORIZE is a CQL keyword); TWCS daily windows expire whole SSTables 90 days after their window closed. Additive.
-- Q21: INSERT INTO risi_turn_steps (turn_id, n, tool, args_sha256, authz, status,
--        latency_ms, result_bytes, call_ref, at) VALUES (...)                 (USING TTL 90 d)
-- Q22: SELECT * FROM risi_turn_steps WHERE turn_id = ?          (a turn's steps, by turn_ref)
CREATE TABLE IF NOT EXISTS risi_turn_steps (
  turn_id uuid, n int,
  tool text, args_sha256 text, authz text, status text,
  latency_ms int, result_bytes int, call_ref timeuuid, at timestamp,
  PRIMARY KEY ((turn_id), n)
) WITH CLUSTERING ORDER BY (n ASC)
  AND default_time_to_live = 7776000
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};
