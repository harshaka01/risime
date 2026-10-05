-- Applied by `mix risime.cql.migrate [--keyspace risime_test]`, which creates the keyspace and
-- runs each file once inside it. Statements end with `;`. Query first, then one table per query.

-- Q1: SELECT * FROM inbox_events WHERE user_id = ? AND event_id > ? LIMIT ?
CREATE TABLE IF NOT EXISTS inbox_events (
  user_id uuid, event_id timeuuid, kind text, payload text,
  PRIMARY KEY ((user_id), event_id)
) WITH CLUSTERING ORDER BY (event_id ASC)
  AND default_time_to_live = 2592000
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_unit': 'DAYS', 'compaction_window_size': 1};

-- Q2: INSERT ... IF NOT EXISTS  (idempotent send)
CREATE TABLE IF NOT EXISTS sent_dedupe (
  sender_id uuid, client_msg_id uuid, message_id timeuuid,
  conversation_id text, server_ts timestamp,
  PRIMARY KEY ((sender_id, client_msg_id))
) WITH default_time_to_live = 86400;

-- Q3: SELECT/UPDATE by message_id (route status to sender, forward-only status)
CREATE TABLE IF NOT EXISTS message_index (
  message_id timeuuid PRIMARY KEY, sender_id uuid, recipient_id uuid,
  client_msg_id uuid, conversation_id text, status text
) WITH default_time_to_live = 2592000;
