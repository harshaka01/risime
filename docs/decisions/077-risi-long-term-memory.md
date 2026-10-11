# 077: Risi long-term memory of Official chats (overrides the 24-hour buffer rule)

**Status:** accepted 2026-10-11, decision by Harsha ("Risi Brain v2"). Overrides decision 068's "the transcript
lives only in the sealed 24 h buffer" and contract §24.12 / §25 / §27 / §30 wording that limits Risi to 24 hours.

## Decision
- **What is kept:** Official chats (1:1 Official tabs and Official groups, where Risi is a visible MLS member) are
  retained for Risi as long-term memory: the raw message text Risi decrypted as a member, plus derived knowledge.
  Kept until the user deletes it. **Private is never read or stored** (Risi has no leaf there; unchanged).
- **Sealing:** everything is sealed with `RISI_DATA_KEY` (AES-GCM, per-row AAD), like the other Risi tables.
- **Retrieval index:** pgvector embeddings (computed on spark2 by our own embedding model, never a third party) and a
  structured knowledge store: people, projects, decisions, promises, meetings, deadlines, open questions. Each item
  links to its source message ids.
- **Controls, per user:**
  - "What Risi knows" lists the items;
  - delete an item, a chat's memory, or everything;
  - "Stop Risi learning from my messages": any participant, per chat or globally. Their messages are then not
    stored or extracted from that point; existing rows are deleted on request.
  - Deletion removes the raw rows, derived items and embeddings.
- **Backfill (later, needs an APK):** with the chat owner's consent, the phone uploads the existing Official history
  of a chat, encrypted to the Risi member, so Risi learns the past.
- **Autonomy:** Oban ingest workers extract promises, deadlines, meetings, decisions and changes on every Official
  message, propose calendar events per participant (each participant's skill setting decides: Allowed = add, Ask =
  card), post quiet-period summaries to the chat plus per-participant Risi Notes, propagate cancellations, nudge
  before and after deadlines, and send a 07:00 daily brief.
- **Honesty rules unchanged:** no claim without a tool result; "added" only after the read-back.

## Why
Harsha: Risi is useless with a 24-hour memory and no initiative. The product is an autonomous secretary and digital
clone. Official is the tab where every member knowingly works with Risi (a visible member, the "Risi is listening"
strip), so long-term memory there is within the users' expectation, with deletion and opt-out controls.

## Consequences
- The contract gains §35 (v1.36): the retention change, memory controls REST, the opt-out flag, summary-range tools.
  Backfill is specified when step 5 starts.
- Backups: Risi memory is server data, covered by the server DB backup with `RISI_DATA_KEY` kept with spark2's `.env`.
- The privacy note shown to users must say: Risi remembers Official chats until you delete them; Private, never.
