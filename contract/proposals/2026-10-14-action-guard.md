# Proposal 2026-10-14: the consequential-action guard on the wire (principle 4, audit G9)

**Status:** proposed (root). **No wire change ships with the guard itself**: server v0
(`server/lib/risime/agent/action_guard.ex`, flag `RISI_ACTION_GUARD`, default `on`) uses only
existing fields. This proposal is for the next step, once a consequential tool exists or the
Policy agent can name a responsible human other than the asker.

## What the server does today (no contract change)
- Every Risi tool call and write goes through one guard. It classifies the call as ordinary or
  consequential (money, pay, HR, deletion, legal; `unknown` for an unreviewed write).
- A consequential **write** that today's tools can propose (e.g. a `schedule_message` whose text
  accepts a contract or says "you're hired") is **always asked**: the existing `confirm` card
  (§25.4) is the approval request, `for` = the asker. It never runs under "Allowed" (§26.3).
- Any other consequential call is **never run**: the step's `StepStatus` is `denied` (an existing
  value, §25.1) and the model is told it needs approval by the responsible person.
- Each decision is a server row (`risi_action_guard`: ids, tool, category, basis, decision, args
  SHA-256; never text).

## Proposed additions (v1.47 candidates, all optional fields)
1. **`confirm.consequential`**: `null | {"category": "money"|"pay"|"hr"|"deletion"|"legal"|"unknown"}`
   on a `confirm` card the guard flagged. The app shows a warning row ("This is a legal action:
   only you can approve it") and **never** offers "Always allow" on such a card. Absent or `null` =
   today's card.
2. **A new `risi.kind: "approval"`** card for an approver who is **not** the asker (e.g. a manager
   who approves leave), posted to the approver's Risi chat:
   `{"v": 1, "kind": "approval", "approval_id": uuid, "category", "summary" (server-built, no model
   text), "requested_by": user_id, "for": [approver_id], "buttons": ["approve", "decline"],
   "expires_at", "notify": [approver_id]}`.
   Actions `approval_approve` / `approval_decline` (`target` = `approval_id`) are honoured only from
   the user in `for`, before `expires_at`. Approving **records the decision**; the action itself is
   still done by the human, or by a tool that human triggers from the card. Risi never runs it on
   its own.
3. **`StepStatus` `needs_approval`** (instead of `denied`) so the progress bubble can say
   "Waiting for approval". Old apps show unknown statuses as `denied`.

## Agent design
- **Owner:** the Policy agent (Part 8) names the approver per category from policy text; until
  then the approver is the asker.
- **Measured:** false holds (a human said "this wasn't consequential") vs. misses (a consequential
  call that reached a card without the flag), on a golden set of tool calls.
- **Why a deterministic guard, not a model:** an agent must never be the one deciding whether it
  may move money or fire someone. The allow and deny lists are reviewed code; the model only
  explains.

## Open questions for Harsha
- Who is the named human per category before the Policy agent exists (the asker, or the
  conversation's admin for HR and money)?
- Should a guarded `schedule_message` card also be blocked in Private chats (today it is only
  asked)?
