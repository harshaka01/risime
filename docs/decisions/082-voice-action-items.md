# 082 — Risi voice → action items: one extraction, one Official card, owner-confirmed

**Status:** accepted 2026-10-12 (Part 5; Harsha's requirements, root's details). PROTOCOL §38 (v1.42).

## Decision
- **No second pipeline.** The §27.2 extraction that already runs on §36 call transcripts gains item kinds
  (`promise`, `task`, `meeting`, `decision`, `question`, `number`) and per-item line references
  (`source_ref`: call `session_id` + `turn` + `offset_ms`, or voice note `message_id` + `offset_ms`), and also
  runs on Official voice-note transcripts (§37.6). Ledger items stay `risi_commitments` rows.
- **Two cards.** `action_items` in the Official conversation (everyone sees what Risi heard; replaces the
  §27.4 `discussion_card` for these sources) and `action_confirm` per owner in their Risi chat with
  Add to My promises / Add to calendar / Remind me / Not mine / Edit. Existing §27.5 actions are reused;
  `item_calendar`, `item_remind`, `item_not_mine` are new. The tap is the consent; nothing is tracked without it.
- **"Couldn't hear clearly"** instead of guessing: language probability < 0.6 or > 25 % romanised Sinhala
  turns → an `unclear` card and no items.
- **Opt-out per speaker** (`action_items: false` in `/me/risi/voice`, or the §35.3 memory opt-out): their
  lines are removed from the input. A discarded call or deleted note cancels its items.
- **Retention** per NEXT-PHASE §B: raw transcript ≤ 24 h sealed; derived non-ledger items 30 days.
- **Learning log:** counts per kind and owner feedback (`confirm`/`edit`/`calendar`/`remind`/`not_mine`/
  `expired`), never text — the training signal for our own L1/L2 extraction models.
- Scope: Official calls and Official voice notes only. Behind `RISI_ACTION_ITEMS` (default off).

## Why
Owners must confirm what is attributed to them (decision 066), but the group should see the same list so
nobody is surprised. Line links make every item checkable.
