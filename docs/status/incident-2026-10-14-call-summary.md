# Incident 2026-10-14: no summary after Official calls (P0)

**Reported:** 16:25 SLT (10:55 UTC) by Harsha. The pilot was on v0.3.0-nightly.7-srv9. People are named by role;
ids are in the pilot DB, not in this file.

## Symptom
Official 1:1 calls showed live transcription, but after End nothing appeared in the Official chat: no summary, no
action items.

## What the live server showed (read-only rpc and logs, 11:00 UTC)
- **Switches:** RISI_CALL_TRANSCRIBE was on, `CallTranscribe.enabled?` true, `Speech.healthy?` true, `config_on?`
  (the `/auth/config` `call_transcribe`) true.
- **Sessions today:** live transcription worked in every session.

| Call (UTC) | Chat | Result | Chunks | Live lines | Summary job | Posted after the call |
|---|---|---|---|---|---|---|
| 10:29 | another Official chat | ended | 6 | 6 | 3 s | summary card (1 item) |
| 10:41 | Harsha ↔ colleague | ended | 16 | 13 | 1.3 s: `extract_actions` ran, found **0 items** | **nothing** |
| 10:48 | Harsha ↔ colleague | stopped | 2 | 2 | 10 ms: under 30 s of speech | **nothing** |
| 10:50 | Harsha ↔ colleague | ended | 6 | 4 | 40 ms: under 30 s or one speaker | **nothing** |

  In each of these, the transcript buffer was dropped after the job.
- **Speech service and memory:** not the cause. Every chunk was transcribed (whisper 2.3 GB, omniASR 0.3 GB, Kokoro and
  the TTS lab idle; the compare models in Harsha's dev compare mode failed as expected, but the live model answered).
  MemAvailable was about 1 GB while the nightly.8 gates ran redroids.

## Root cause
1. **The action-items path posted nothing when a call had no action items.** Since §38 (b1a2602b, 12 Oct),
   `Summary` sends calls to `ActionItems.from_call` whenever RISI_ACTION_ITEMS is on for a speaker. Its result
   `{:ok, %{items: []}}` returned `:ok` with no post, and the transcript was dropped. That is right for a voice note,
   but a call must always get its summary. Before §38, calls went to `Ledger.call_ended`, which posted the
   discussion card.
2. **The trigger was a flag change, not a release.** RISI_ACTION_ITEMS went from off to **canary** on 14 Oct about
   02:10 UTC (root, at Harsha's request; `docs/status/pilot-ops.md`). Canary covers the dev users, so from then on
   **Harsha's** calls took the new path. Harsha's last summarised call (13 Oct 11:20 UTC) went through the old path.
   The 10:29 call today got its card only because the model found one item. A git bisect isn't meaningful: no
   release changed this behaviour; the flag did.
3. **Two older silent paths** made it worse:
   - a call under 30 s of speech, or with only one speaker, was dropped with no word (§36.7 design);
   - the old summary path also posted nothing for a discussion with no items and fewer than 3 key points
     (fecd4715, 9 Oct).
4. **Why the gates missed it:** `scripts/call-transcribe-test` runs without RISI_ACTION_ITEMS, and its summary-card
   check was non-blocking.

## Fix (server, fast lane)
- `ActionItems.from_call` returns `:no_items` when the extraction finds none. `Summary` then runs the discussion
  summary (the card as before). Voice notes are unchanged (no items → nothing posted).
- `Ledger`: a transcribed call is always summarised when the model gives at least one key point.
- **Never silent** (one plain Risi line in the Official chat):
  - too little speech: "This call had too little speech to summarise."
  - one speaker only: "I only heard one person on this call, so there's no summary."
  - failure: "I couldn't summarise this call just now. I'll try again from what I transcribed." This is said once,
    on the first attempt. The job retries up to 3 times from the buffered segments, which are kept until a summary
    is posted.
- **Speech service down for 60 s** (session `unavailable`): "I couldn't transcribe this call: the speech service was
  busy." Or, when part of the call was transcribed: "I couldn't transcribe the rest of this call: the speech service
  was busy. I'll summarise what I have." The summary of that part follows.
- **Transcription turned off mid-call** (session `stopped`): the card, then "Summary of the first N min; transcription
  was turned off after that."
- **Tests:** `server/test/risime/agent/call_summary_never_silent_test.exs` covers all four cases. The action-items,
  parity (§36.7) and call-transcribe suites pass.
- **New blocking gate:** `scripts/call-summary-test`. Two redroid phones run with the pilot's real switches
  (ACTION_ITEMS canary):
  - a 60 s Official call → the summary card within 30 s, with both zero and one action items;
  - Stop at 30 s → the card plus the "first N min" line;
  - a Private call → nothing.
- **Speech jobs paused while this is verified:** the compare night run and the evals are paused through the
  speech-guard pause file (`~/risime-logs/speech-guard/speech-eval.pause`); RISI_VOICE_TRANSCRIBE stays on.

## Not in this fix (said plainly)
- **"View transcript" and "Export" on the summary card:** the transcript lines are in the chat as live lines, and
  the card has no such buttons today. Adding them is an app change (contract first), for the next nightly.

## Timeline (UTC)
- 12 Oct 17:18: §38 action items land (b1a2602b), off by default.
- 14 Oct ~02:10: RISI_ACTION_ITEMS → canary (Harsha's account).
- 14 Oct 10:41–10:50: three of Harsha's Official calls get no summary.
- 14 Oct 10:55: reported.
- 14 Oct 11:00–11:15: diagnosed and fixed; the server tests pass.
- 14 Oct 12:38: `scripts/call-summary-test` CSTEST OK inside the fast lane (10880611). End → card took 10–13 s on both
  phones, for one action item, zero items and transcription stopped at 37 s; a Private call transcribed nothing.
- 14 Oct 12:40: **live as v0.3.0-nightly.7-srv10** (health 12/12). RISI_ACTION_ITEMS is still canary; calendar v2
  (ac3ad1ea, canary) went out with it.
- The compare night run and the evals stay paused (`~/risime-logs/speech-guard/speech-eval.pause`) until Harsha's
  next real Official call shows its card.

## 15 Oct: long call, items but no summary (P0)
**Reported:** 04:43 SLT 15 Oct by Harsha. A ~57-min Official call with a colleague on v0.3.0-nightly.8 posted action
items but no summary. The call had two transcription sessions: 21:37–21:58 UTC (`stopped`) and 22:00–22:36 (`ended`).

**Evidence (read-only rpc, logs):**
- Summary jobs 4935 and 4989 both completed on attempt 1, in 8 s and 24 s. There were no warnings in `server.log`.
- Each session has one `risi_discussions` row with `source: call` and **0 key points**: 2 and 9 items, and a
  one-line summary of 104 and 181 characters.
- The learning log has one `extract_actions` row per session (risi-l1, `status: ok`):
  - prompt 9,448 and 15,723 tokens, from 195 and 322 lines;
  - completion 252 and 911 tokens, well under `max_tokens` 2,000, so nothing was truncated;
  - there was no `discussion_summarise` call.

**Cause:**
1. With RISI_ACTION_ITEMS on (canary: Harsha is a dev user), a call **with** items took only the §38 path. It
   posted the `action_items` card, and the only "summary" was that card's optional one-line `summary` field. The
   §27 discussion summary (key points) was never made or posted. The 14 Oct fix covered only calls with **zero** items.
2. The whole transcript went to the model in **one** prompt. This call fitted: 36 min was 15.7k tokens, against
   risi-l1's 32k context and 60-s timeout. But `Prompts.render` keeps only the newest 60,000 characters, about
   38 min of talk, so a longer call would silently lose its start.

**Fix (server, fast lane):**
- Every transcribed call is now summarised **in sections**: at most 16,000 characters and 10 min each
  (`CallTranscribe.Sections`).
  - Each section gets its own extraction. Then one small `call_summary_reduce` call merges the key points and
    one-line summaries. If that call fails, the server merges them without the model.
  - Items are merged and de-duplicated.
  - A failed section is skipped, with one line: "I couldn't summarise minutes 10–20 of this call; the summary covers
    the rest."
  - If every section fails, Risi says "I couldn't summarise this call just now…" once, as on 14 Oct, and the job
    retries.
- With items found, the call posts its **summary card** (`discussion_card`: the line and key points) **and** the
  `action_items` card, with the same `summary_id`. The extraction now also returns `key_points`. Both kinds already
  exist in the contract, so there is no wire change. Strictly, §38.1 says `action_items` *replaces*
  `discussion_card`; the contract text should be updated to say "with" (root).
- The action-items-off path (`Ledger.call_ended`) is sectioned the same way.
- **Tests:** `server/test/risime/agent/call_summary_long_call_test.exs` has 7 tests:
  - a call with items gets its summary;
  - a 30-min call is split into 3 sections plus a reduce, and posts one card;
  - a failed section;
  - a failed reduce;
  - all sections failed;
  - the action-items-off path;
  - the split limits.

  The agent suite passes (777 tests).
- **Gate:** `scripts/call-summary-test` now runs with `RISI_CALL_SECTION_S=20` (`CS_SECTION_S`), so its one-minute
  calls take the long-call path. In run 1 (one item), the summary card's key points must show next to the items on
  both phones. A real 30-min call isn't run on redroid because it would hold the gate for 30 min; the 30-min
  transcript is covered by the server test above.
