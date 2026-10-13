# RisiMe architecture principles (permanent)

Harsha, 2026-10-13 (architect brief), recorded in decision 085. **Every session reads this at start**
(CLAUDE.md points here). From now on RisiMe is judged on **intelligence**, not only on features. These
principles sit on top of CLAUDE.md's golden rules. Part 6 (decision 084: deep agents, shadow mode, the
promotion rule, flags, the legacy switch) and the NO-REGRESSIONS rules apply to everything here.

## 1. Agent-oriented architecture (AOA) first
- **Every capability is owned by an agent.** The agent has a goal, reasoning, tools, memory, an eval, an
  **Intelligence Index** and a learning loop (decision 084's `RisiMe.Agents.Agent` behaviour).
- **Modules and services are tools that agents use; they never make the decisions.**
- **Deterministic engines stay only where exactness is required:** MLS crypto, call media, storage,
  delivery, auth. They are infrastructure, not intelligence. Agents call them through the tool registry
  and never reimplement them.
- **Every new feature's decision doc has an "Agent design" section:**
  - which agent or agents own it;
  - what they learn (signals, level L1/L2/L3);
  - how it is measured (eval set, live acceptance, Index formula);
  - why AOA, SOA or a hybrid was chosen: the best option, with the reason.
  Plain infrastructure, such as a crypto or media change, says "no agent: infrastructure" and gives the reason.

## 2. Policy as language
- **Behaviour is described in words.** Org structure, roles, escalation paths and rules are written as plain
  sentences or uploaded documents (PDF/DOCX). Agents interpret them, read their understanding back, ask
  about every ambiguity, and act only after the admin confirms.
- **Don't build fields, forms, workflow builders or escalation tables** for behaviour that can be said in
  words.
- **Forms only where exactness is legally needed:** identity, permissions, consent switches.

## 3. Measured intelligence that rises every day
- **Each capability has an Intelligence Index (0–100), recorded daily** (Part 7; formulas in decision 085).
  The owning agent's job is to raise it.
- **A change that lowers an Index rolls back automatically.** This is the promotion rule of decision 084,
  scored on the Index.
- **No model ever grades itself without a human-checked golden set.**
- **Transcription is scored on real speech, not public sets.** Public sets are only a fallback, and are
  labelled as such.

## 4. Still binding
- Part 6 shadow mode, the promotion rule, per-agent flags and `RISI_AGENTS=legacy`.
- **Official only on the server, never Private.** Private content reaches Risi only through an explicit,
  visible, per-item user action, such as "Edit with Risi" on one image.
- **Visible agents:** an agent that joins or acts in a chat is visible to every member.
- **Honesty guards:** never invent names, amounts or dates; ask. Never claim encryption, success or a
  check that didn't happen.
- **Rule 9:** an update never deletes local chats.
- **Consequential actions are never executed by an agent.** This covers money, pay, HR, deletion and anything
  legal. Risi asks the named responsible human to approve, records the decision, and only then may a human,
  or a tool the human triggered, act.

## How to apply (checklist for every feature)
1. Name the owning agent, or say "infrastructure".
2. Write its Agent design section: goal, tools, memory, signals, eval and golden set, Index formula, learning
   levels, flags, and shadow → canary (Harsha first) → on.
3. Write the contract first for wire changes; keep server and phone on Official/Private correctly.
4. Gate it: functional device gate, plus the agent's eval, plus parity tests for what it replaces.
5. Put the Index on the scoreboard from day one.
