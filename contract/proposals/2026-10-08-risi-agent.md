# Proposal: §24 The Risi agent (v1.24): Risi v1, stage 0.5

Status: **proposed, waiting for Harsha** (golden rule 1). Root writes PROTOCOL §24 after approval.
Plan by the orchestrator (2026-10-08). Server placement goes into decision 063 once approved.

## Goal
"Risi, summarise" in a group: Risi joins as a **visible, encrypted MLS member** and posts a summary
with decisions and action items (with owners). It runs on our own model `risi-l1` (vLLM, 127.0.0.1:8100).
No chat text leaves spark2. Every model call goes into the Cassandra learning log.

## What already exists
- §12.0 reserves `kind:"agent"`: agents are never admins, and "its keys never live on the chat server".
- The crypto core has every member-client function. `policy.rs` already accepts an `agents` list, but
  `group.rs` passes `&[]`.
- §17 history sharing only covers gaps from a member's own membership, so Risi gets no history by
  construction.
- Decision 039: extra fields on `type:"text"` are tolerated, and unknown envelope types are ignored.

## Wire changes
**Identity**
- Risi is a user with `kind:"agent"`, a fixed UUID and one device, `risi-1`.
- Its attestation JWS gains `"kind":"agent"`, exposed as `MemberInfo.kind`.
- `group_meta` gains `"agents":[uuid]`. The core enforces two rules: an agent leaf must be listed
  there, and agents are never admins.

**Capability**
- New device capability `agents`.
- Adding Risi requires every member to be agents-capable; otherwise `409 not_ready`/`legacy_app`.
  Old apps therefore never sit in a chat with an agent they can't see.
- While Risi is present, anyone joining must also be agents-capable.

**REST**
- `POST /groups/{id}/agents {"agent":"risi"}` (**admins**) creates an `add` op. The admin's device
  claims Risi's key package (§12.5) and commits with `meta.agents`.
- `DELETE /groups/{id}/agents/risi` (**any active member**) creates a `remove` op that any member device
  may commit. This changes core policy (`policy.rs:79`) and §12.4.
- On remove, the server stops delivering to Risi at once (`pending_remove`), and Risi purges the group.

**Events**
- The existing `group_event` `added`/`removed`, with `Member.kind:"agent"`.

**Request**
- The Summarise button, or the `@Risi` chip, sends the MLS envelope
  `{"v":1,"type":"risi_request","request_id","action":"summarise","scope":{"since":"last_summary"|ts}}`.
- Free-text mentions are not the wire format: they are ambiguous and open to injection.

**Reply**
- A `type:"text"` envelope with a plain `body`, so old apps can read it, plus
  `"risi":{"v":1,"kind":"summary","request_id","call_id","summary","decisions":[{text,refs}],"action_items":[{text,owner_user_id|null,owner_label,due|null,refs}],"open_questions":[],"partial":bool}`.
- On failure, `"kind":"error"` with code `model_unavailable`, `rate_limited` or `nothing_to_summarise`.

**Feedback**
- A §11 👍/👎 reaction on Risi's message, mapped to `call_id`.

**Errors**
- `404 unknown_agent`
- `409 agent_present`
- `409 not_ready`
- `503 agent_unavailable`
- `403 not_admin` (on add)
- `429 rate_limited`

**Examples**
- `device_put_agents.json`
- `group_agent_add.json` and its reply
- `event_group_agent_added.json` and `event_group_agent_removed.json`
- `envelope_risi_request.json`
- `envelope_risi_summary.json`
- agent cases in `group_policy_cases.json`

## Server (decision 063 draft)
**Placement**
- The agent runs in the same release, in an isolated `RisiMe.Agent` supervision tree.
- It talks to the delivery service only through the context functions a channel uses.
- An MLS Rustler NIF (`crypto/risime-mls-nif`) uses a journal store. Values are sealed with AES-256-GCM
  under `RISI_MLS_KEK` (`.env`) and kept in their own table, `risi_mls_kv`.
- §12.0 becomes "agent keys are never readable by the delivery-service store". A separate process is a
  later hardening step.

**Data**
- Postgres: `users.kind`, `agent_memberships`, `risi_mls_kv`, `risi_requests`.
- Cassandra:
  - `risi_transcript` (TWCS, TTL 14 d)
  - `risi_llm_calls_by_day` / `_by_conversation` (TTL 90 d)
  - `risi_llm_feedback_by_day`
- Text columns are sealed with `RISI_DATA_KEY`. The transcript goes only through
  `RisiMe.Messaging.Store`.

**Pipeline**
- An Oban queue `risi`; jobs carry the `request_id` only.
- The window runs from the last summary, capped at 24 h / 500 messages / about 20k tokens, with
  map-reduce above that.
- vLLM guided JSON decoding, thinking off, temperature 0.3.
- Owners are validated against current members.

**Rate limits**
- 1 per group per minute, 20 per group per day
- 10 per user per hour
- A global queue of 16

**LLM client**
- Refuses any base URL that isn't loopback.

## Android
- Group info:
  - "Add Risi" (admins, with an explainer);
  - an "AI agent" badge on Risi's row;
  - "Remove Risi" for every member.
- The consent banner is shown to every member until dismissed:
  "Risi (AI) was added by X. From now on, messages are readable by Risi on RisiMe's server; earlier
  messages are not. [Got it] [Remove Risi]".
- The subtitle reads "Encrypted · Risi can read new messages".
- Risi bubbles are distinct. The summary card has decisions, action items with owner chips and 👍/👎.
- Summarise sits in the chat menu.
- A Room migration that only adds a column (rule 9).

## Gate
- `scripts/interop` with `RISI=1` and `scripts/fake-llm`.
- A canary sent before Risi joins and a canary sent after a non-admin removes it must never appear in
  any LLM input.
- An egress check: agent HTTP goes to loopback only, and the canary appears in no logs, FCM payloads or
  dumps (sealed columns only).

## Questions for Harsha (recommended defaults in bold)
1. Who can add Risi? **Admins only; anyone can remove.**
2. Consent: **a notice banner plus one-tap removal by anyone**, or unanimous opt-in before Risi reads
   anything?
3. Learning-log retention: **full input and output, sealed, 90 days**. The transcript is purged when
   Risi is removed; the log is not.
4. The banner names the place explicitly ("on RisiMe's server"). **Yes.**
5. §12.0: **accept agent keys in-process, sealed by a KEK in `.env`**. A separate process comes later.
