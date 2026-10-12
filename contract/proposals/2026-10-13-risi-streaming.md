# Proposal 2026-10-13: streaming Risi's answer (first useful line within 4 s)

Status: **proposed** (risi role, decision 084 PART 6 step 2). Nothing implemented; needs root/Harsha.
Typing and step progress already exist and need nothing new (`risi_progress` `queued`/`working`/`step`,
§25.4). Only the *partial answer text* needs a wire change.

## Problem
Budget (decision 084): a multi-step answer shows its **first useful line within 4 s**, then progress.
Today the answer text arrives only once, at the end of the turn (pilot: turns p50 3.3 s, p95 9.4 s). The
bubble says "Checking your calendar…", but no words of the answer appear until the turn is done.

`risi_progress` can't carry the text: it is a server **signal** (plaintext over TLS, §2.3), while Risi's
answers in Official are **MLS-encrypted** posts by a visible member. Putting answer text in a signal would
break "never claim encryption for a plaintext chat" (decision 048) for an encrypted chat.

## Proposal (additive, behind a capability)
1. **Capability `risi_stream`** (advertised by a device, like `risi_tools`). Without it nothing changes.
2. **`risi_partial` envelope** (an MLS application message from Risi's device in the conversation of the
   request, like `answer`):
   ```
   {"v": 1, "type": "risi_partial", "request_id", "seq": int, "text": str, "final": false}
   ```
   - `text` is the **whole answer so far** (a replacement, not a delta), at most 2 000 characters.
   - At most one every 700 ms per request, and only once the first sentence is complete (no
     half-words); the first one may be a plan line the server writes ("I'll check your calendar, then
     add the event.").
   - **Ephemeral:** delivered only to connected `risi_stream` sockets of the asker's devices, never
     queued for store-and-forward and never stored by the server or the client history.
   - Clients drop a `seq` lower than one already shown, and replace the partial bubble with the real
     `answer` (same `request_id`) when it arrives; the `answer` stays the only stored, authoritative
     message (cards, sources, `made_by` unchanged).
   - A partial never contains a write confirmation or a claim of an action; writes still go through
     confirm cards.
3. **Server:** the model call of the final step streams (vLLM `stream: true`); the server cuts
   sentences, runs the same post-checks it runs on the full answer that are possible on a prefix
   (labels stripped, language/script), and stops sending partials if a check fails; the final
   `answer` is checked as today.
4. **Examples:** `envelope_risi_partial.json`; tests: ordering, drop on lower `seq`, no storage,
   capability gate, replacement by `answer`.

## Cost
One MLS encryption per partial (≤ 1.5 per second per active turn). Learning log unchanged (one row per
model call; partials are not logged).

## Open questions for Harsha / root
- OK to show a partial line in a group's Official chat, or only in the Risi chat (the asker's own)?
- Should old apps get nothing (as proposed) or a progress `step` with a generic "Writing…" label?
