# 068 — Risi with tools: next-action loop, client tools over TLS, confirm-before-write, sealed notes

**Status:** accepted 2026-10-09 (requirements: Harsha, "Risi's intelligence" v2; details: root).
Proposal `contract/proposals/2026-10-09-risi-client-tools.md`, which becomes PROTOCOL §25 (v1.25).

## Decision
- **The loop:** a server-side `RisiMe.Agent.Turn` with a provider-neutral **next-action** step.
  - It uses strict JSON-schema decoding on risi-l1, the path that already works; the vLLM container
    has no tool-call parser enabled.
  - Each step's schema lists only the tools `authorize/3` allows (permission by construction), and
    each call is checked again when it runs.
  - It is bounded: 6 tool steps, 8 model calls, 60 s.
- **Client tools** (the phone's calendar): a per-device `risi_tool_call` event plus a REST result over
  TLS.
  - **Not MLS for now:** Risi shares the server process (§24.11), so MLS adds no protection. It moves
    to MLS in the Risi chat when the agent is its own process.
  - **Minimal data:** free/busy blocks only, or an event id.
- **Writes** (`set_reminder`, `calendar_add`) always go through a confirm card ([Add] by the asker);
  a write without a confirmed `write_id` is refused by the server (409) and by the phone. Group
  reminders reach only those who tap "Me too".
- **The Risi chat:** an Official `grp:` with `chat_kind: "risi"`, one per user. Answers that use
  personal sources go there, never to a group.
- **Notes:** `risi_facts` `kind: "note"`, sealed with `RISI_MEMORY_KEY` (AES-GCM; the S6 facts are
  sealed in the same migration). There are no embeddings for notes, because a plaintext vector leaks
  meaning. `forget` is a hard delete.
- **The queue:** 20 per minute per user with a FIFO, one running turn per user, and round-robin
  across users. Requests are queued, never refused.
- **Routing:** the tool loop, Sinhala/Tamil and drafts go to the commercial model when configured
  (`RISI_COMMERCIAL=on`, with a host allowlist), otherwise risi-l1. Each task moves back to local once
  risi-l1 passes the gate.
- **The learning log** stays hashes-only (decision 066) until Harsha decides otherwise.

## Consequences
- **New keys in `.env`:** `RISI_MEMORY_KEY`, and `RISIWORK_EXCHANGE_SECRET` (or a signed-JWT key).
- **Egress canary:** extended to allow exactly the commercial and RisiWork hosts when they are on.
- **Server-side search of Official** covers the 24 h buffer only.
