-- Privacy fix 2026-10-09 (server): Risi-derived text in the learning log is sealed at rest.
-- `output` (the model's validated JSON: summaries, answers, commitment texts) and a feedback
-- `reason` are written only as AES-256-GCM blobs under RISI_DATA_KEY (RisiMe.Agent.Seal, AAD
-- "<table>:<call_id>:output" / "risi_llm_feedback:<call_id>:<user_id>:reason"). The plaintext
-- columns stay (additive: an older release still runs on this schema) but are never written
-- again; RisiMe.Release.migrate/0 then seals the rows written before this file
-- (RisiMe.Agent.LearningLog.Cassandra.seal_existing/2: each value keeps its remaining TTL, the
-- plaintext cell is deleted). Queries Q15–Q20 unchanged otherwise. Additive.
ALTER TABLE risi_llm_calls_by_day ADD output_sealed blob;
ALTER TABLE risi_llm_calls_by_chat ADD output_sealed blob;
ALTER TABLE risi_llm_feedback ADD reason_sealed blob;
