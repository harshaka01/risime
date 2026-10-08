# 066 — Risi stage 1: commitments, reminders, digest, @Risi; learn and delete; model cascade

**Status:** accepted 2026-10-08 (Harsha's rules; details: root). PROTOCOL §24.11–24.13.

## Harsha's rules (binding)
- **No promotion:** Risi never pushes or advertises products.
- **Offers:** only as a question with [Yes]/[Not now]; at most about 1 per chat per day; a topic is
  muted after 2 "Not now"s (root: for 90 days); nothing happens without a Yes.
- **Learn and delete:**
  - only derived facts are kept (commitments, dates, people, preferences, topics), in
    Postgres/pgvector;
  - raw plaintext is deleted after processing;
  - "What Risi knows about me" offers per-item and full delete.
- **Scope:** Risi only ever sees Official.

## Root decisions
- **Stage 1 features:**
  - **Commitments:** detection produces a proposed card; the owner or a counterpart confirms ✓,
    declines ✗ or edits; nothing is tracked without ✓; the card expires after 48 h.
  - **Reminders:** at due − 1 h in the owner's timezone (09:00 local when only a date is given).
  - **Escalation:** to the counterparts 24 h after due, at most 2, in the same chat.
  - **Digest:** 09:00 local, only when there are open items.
  - **@Risi:** a composer chip that sends a structured `risi_request` (ask, summarise, report); free
    text is never parsed for intent.
- **Raw buffer:** Official plaintext sits in a sealed Cassandra buffer with TTL 24 h for windowed
  answers and summaries, which therefore cover at most 24 h. A §15 delete propagates to the buffer
  and to facts.
- **Learning log:** call_ref, task, model, provider, latency, tokens, cost, confidence, the derived
  output and feedback. The input is stored as source message ids plus a prompt hash, **never the
  raw text**. TTL 90 days.
- **Model cascade:** `risi-l1` (local vLLM, loopback only) first. A commercial fallback runs only
  when confidence is below the threshold and `RISI_FALLBACK=on`. It defaults to off until a
  zero-retention agreement exists (Needs Harsha).
- **Feedback:** private REST (👍/👎 with a reason), not a visible reaction.
- **Rate limits:** see §24.13.

## Later stages (not in stage 1)
- **Stage 2:** help offers; partner agents over A2A/MCP (Lia, then eDrop); a spec proposal comes
  first.
- **Stage 3:** transcription of Official voice and calls (faster-whisper on spark2), with a visible
  indicator; raw audio deleted.
- **Stage 4:** the digital twin, in draft mode first.
