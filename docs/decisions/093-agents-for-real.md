# 093: Agents for real, step by step (Harsha 2026-10-15 20:01)

**Status:** decided by Harsha. This extends decisions 084 and 085.

## What exists today (15 Oct)
Of the 10 registered agents (`RisiMe.Agents`), two run on their own: the **Router**, in shadow mode, and **Health**.
The other eight are declared specs over the old Risi code: Planner, Ledger, Voice, Calendar, Memory, Summary,
Marketplace and Twin.

## Decision
There is no big rewrite. Each agent takes over its capability on its own, one step at a time:
1. **New brain code goes into the agent modules**, not into the old Risi code (`turn.ex` and the modules around it).
   A fix to the old path is allowed only while that agent has not taken over. Even then, a fix that adds new logic
   puts it in the agent module and calls it from the old path.
2. **The unified understand-and-act turn becomes the Planner agent.** The Router stays as the fast hint.
3. **Calendar answers move to the Calendar agent, and promises to the Ledger agent.** The Ledger is the next one,
   as already queued.
4. **Takeover:** each agent takes over behind its own switch. The steps are **shadow → canary (Harsha) → on**, and
   each step is judged by that agent's scoreboard and test set. The old path is removed only after the agent wins.
5. **Weekly report to Harsha:** which agents are real, which are still placeholders, and the Index per agent.
   It is sent with `scripts/risi-notify --kind done`.

## First steps
- Claim guard (§41.9): written as one shared module that every output path calls (`RisiMe.Agent.ClaimGuard`).
- The Ledger agent (queue item 3, "the first REAL agent").
- The Calendar agent, which takes over the calendar answers fixed today, with the device-test questions as its set.
- The Planner agent, which wraps the §25 turn.
