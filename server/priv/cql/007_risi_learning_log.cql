-- Contract v1.24 §24.12 (decision 066), server S6: Risi's learning log. Every model call is one
-- row in each of the two call tables (same columns, two partitionings), written by
-- RisiMe.Agent.LearningLog.Cassandra. Queries first, one table per query, no ALLOW FILTERING,
-- no secondary index. Additive.
-- The input of a call is stored ONLY as source_message_ids plus prompt_sha256 (hex SHA-256 of
-- the exact request messages): never the raw chat text. `output` is the derived JSON the model
-- returned (validated against the task's schema). `call_id` is a TimeUUID and doubles as the
-- `call_ref` of §24.11, so a call is found from its ref alone: day = the UTC date of the
-- TimeUUID, bucket = a hash of the id (0..7).
-- Q15: INSERT INTO risi_llm_calls_by_day (...) VALUES (...)                     (USING TTL 90 d)
-- Q16: INSERT INTO risi_llm_calls_by_chat (...) VALUES (...)                    (USING TTL 90 d)
-- Q17: SELECT ... FROM risi_llm_calls_by_day WHERE day = ? AND bucket = ? [AND call_id = ?]
-- Q18: SELECT ... FROM risi_llm_calls_by_chat WHERE chat_id = ? AND month = ? LIMIT ?
-- Q19: INSERT INTO risi_llm_feedback (call_id, user_id, rating, reason, at) VALUES (...)
--        USING TTL <what is left of the call's 90 days>        (a repeat replaces the rating)
-- Q20: SELECT user_id, rating, reason, at FROM risi_llm_feedback WHERE call_id = ?
-- Daily TWCS windows: whole SSTables expire 90 days after their window closed.
CREATE TABLE IF NOT EXISTS risi_llm_calls_by_day (
  day date, bucket int, call_id timeuuid,
  conversation_id text, chat_id text, task text,
  model_alias text, model text, provider text, fallback text,
  latency_ms int, prompt_tokens int, completion_tokens int, cost double, confidence double,
  status text, output text, source_message_ids list<timeuuid>, prompt_sha256 text,
  PRIMARY KEY ((day, bucket), call_id)
) WITH CLUSTERING ORDER BY (call_id DESC)
  AND default_time_to_live = 7776000
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};

CREATE TABLE IF NOT EXISTS risi_llm_calls_by_chat (
  chat_id text, month text, call_id timeuuid,
  conversation_id text, task text,
  model_alias text, model text, provider text, fallback text,
  latency_ms int, prompt_tokens int, completion_tokens int, cost double, confidence double,
  status text, output text, source_message_ids list<timeuuid>, prompt_sha256 text,
  PRIMARY KEY ((chat_id, month), call_id)
) WITH CLUSTERING ORDER BY (call_id DESC)
  AND default_time_to_live = 7776000
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};

CREATE TABLE IF NOT EXISTS risi_llm_feedback (
  call_id timeuuid, user_id uuid, rating text, reason text, at timestamp,
  PRIMARY KEY ((call_id), user_id)
) WITH default_time_to_live = 7776000
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};
