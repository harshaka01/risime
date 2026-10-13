# 085: Intelligence first (agent-oriented architecture, policy as language, Intelligence Index)

**Status:** accepted 2026-10-13 (Harsha's architect brief). Principles in `docs/ARCHITECTURE-PRINCIPLES.md`
(read at the start of every session). This builds on decision 084 (deep agents) and does not replace it.

## Decision
1. **AOA first.** Every capability is owned by an agent (084's behaviour). Modules and services are its tools.
   Deterministic engines remain only for exactness: MLS crypto, call media, storage, delivery, auth. Every
   new decision doc carries an **Agent design** section, which says why AOA, SOA or a hybrid was chosen.
2. **Policy as language.** Org structure, roles, escalation and rules are written in words or uploaded as
   documents. The Policy agent (Part 8) interprets them, reads its understanding back, asks about ambiguities,
   and acts only after the admin confirms. Policies are versioned. Forms exist only where exactness is legally
   needed.
3. **Intelligence Index**, defined below. Recorded daily per capability; a change that lowers it rolls back.
4. **Consequential actions** (money, pay, HR, deletion, legal) are never executed by an agent. Risi asks
   the named human to approve and records the decision.

## Intelligence Index (Part 7)
`Index = 0.7 × Eval + 0.3 × Live`, both scored 0–100. Each capability's Eval comes from a held-out,
**human-checked** golden set, refreshed from real cases, which a model never grades on its own. Live is the
acceptance over the last 7 days. With fewer than 20 live signals in 7 days, the Index is shown as
"Eval only (n live)" and uses Eval alone.

| Capability | Eval (70 %) | Live (30 %, last 7 days) |
|---|---|---|
| Transcription en / si / ta (one Index each) | 100 − WER on **real** speech: Harsha/Shenika recordings + consented pilot clips; public sets only as a labelled fallback until ≥ 20 real clips per language | transcript lines kept unedited ÷ lines shown (corrections, "that's wrong", language fixes count against) |
| Voice understanding (voice → action items) | item F1 on golden calls/notes (owner, kind, due must match; invented names/amounts/dates = 0 for that case) | items confirmed ÷ (confirmed + Not mine + edited) |
| Chat understanding (Ledger) | promise detection F1 + owner/due accuracy on the golden set; hallucination traps | promise cards confirmed ÷ offered |
| Scheduling | correct answer/slot/clash on golden asks; honesty (no "free" without a real check) | events kept unchanged 24 h after creation ÷ created |
| Memory recall | recall@5 + answer correctness on golden questions | 👍 ÷ (👍 + 👎 + "not what I meant") |
| Summaries | rubric score (coverage, no hallucination, length) by a human-checked golden set | 👍 rate, expanded/read rate |
| Routing | routing accuracy on the golden set (63 cases today, decision 084) and p95 within budget | 1 − (re-routes + rephrasings) ÷ asks |
| Policy following (Part 8) | correct triggers and escalation steps on scenario tests; false escalations count double | 1 − ("that wasn't a violation" + admin corrections) ÷ escalations |
| Image editing (Part 11f) | human-rated golden edits (mask IoU + instruction followed) | drafts sent ÷ drafts shown |
| Twin suggestions (on-device only) | golden reply style/acceptance set on the phone | suggestions accepted ÷ shown (the phone computes it and sends only the number, if the user opts in) |

**Calibration (Harsha's real-use estimates 2026-10-13):** English transcription 80, Sinhala 30, Tamil 45. When
the measured Index differs by more than 15 points, the eval set does not match real calls. Fix the set (more
real clips) first, then report.

**Where it shows:**
- in the Risi chat, as "what's your intelligence" in any language: today's score per capability, with the
  change against yesterday and last week;
- Settings → Risi → Intelligence, with a sparkline per capability;
- the admin screen;
- `docs/status/risi-scores.md`;
- agent-activity events.

**Coach agent (deep):** owns raising every Index. Nightly, in the GPU window, it collects hard cases
(corrections, rejections, low-confidence outputs). It then proposes and runs improvements (prompt
optimisation, LoRA, model swaps, ASR fine-tunes on consented clips), evaluates them and promotes only winners
under the promotion rule. Downloads over 2 GB still need Harsha's OK.

## Agent design (this decision)
- **Owners:** Coach (Index, nightly improvement), Policy (Part 8), Voice (Part 9), Meeting (Part 10),
  Image (Part 11f), plus the 084 agents.
- **Why AOA:** these capabilities are judgement calls that must improve with feedback, and a fixed service
  can't learn. Infrastructure stays SOA (crypto, media, storage, delivery, auth), because exactness there
  beats judgement.

## Consequences
- Each new feature's decision doc has an Agent design section, and reviewers reject one without it.
- The scoreboard (084) gains the Index columns. Rollback on a drop in Index joins the promotion rule.
- No-regression rules, Official-only and rule 9 are unchanged.
