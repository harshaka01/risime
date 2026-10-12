-- Contract v1.39 §36.8 (decision 080): the speech learning-log rows (`transcribe` per session,
-- `transcribe_compare` per chunk per model) carry their non-text facts in `meta` (JSON: the
-- detected language, audio_ms, chunk count, and for compare rows the input
-- {session_id, seq, speaker}). Never transcript text or audio: compare text is only ever in
-- `output_sealed` (RISI_DATA_KEY), written with a 30-day TTL. Additive.
ALTER TABLE risi_llm_calls_by_day ADD meta text;
ALTER TABLE risi_llm_calls_by_chat ADD meta text;
