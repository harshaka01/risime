# Proposal: faster §41.10 fallback when the phone is silent (2026-10-16, android/integration)

Status: proposed (amends v1.53 §41.10 "Server (normative)"; no new wire packets from the phone).

## Why
Harsha's first real call (2026-10-16 03:55Z, call 75ec8683): `stt=phone` at 03:55:29.8, fallback
`stt=server (no_final)` only at 03:55:54. The rule "3 utterances with clear speech and no final within 3 s" cost the
caller three unanswered sentences (about 25 s of silence from Risi), although the phone had sent **nothing at all** on
`risi.stt` since `mode` (no partial, no final, no off).

## Change
1. **Worker → server (Port, not wire):** the worker forwards one `{"ev": "stt", "t": "alive"}` the first time any
   well-formed packet (`partial`, `final` or `off`) arrives from the caller on `risi.stt` after `mode: "phone"`.
   No text. (Partials stay otherwise dropped.)
2. **Server rule (replaces "after 3 utterances" for this case):**
   - If the phone is not yet `alive`, the **first** utterance with clear speech that gets no `final` within 3 s
     switches the call back to the server's speech-to-text (`stt=server (no_packets)`), and that utterance is
     transcribed by the server (its audio is still held) so the caller gets an answer to it.
   - Once `alive`, the existing rule stays: `off`, or 3 clear utterances without a `final`.
3. **Phone (already in the next APK, no contract change needed):** it sends `off` itself when the call's capture gives
   no audio (`unavailable`), or after 2 utterances with clear speech and no words, or after repeated recogniser errors
   (`error`), so the server rarely has to count misses.

## Contract text (suggested)
In §41.10 Server (normative), replace the take-over bullet with:
> The server takes over again (`mode` back to server) after an `off`; after the **first** utterance with clear speech
> on the audio that gets no `final` within 3 s when nothing at all has arrived on `risi.stt` since `mode: "phone"`;
> otherwise after 3 such utterances. The utterance that triggers the take-over is answered from the server's
> speech-to-text.

## Compatibility
Additive and server/worker-only. Old apps that never send anything get the faster fallback (better); apps that send
partials/finals keep today's behaviour. The phone's text never enters logs (the `alive` event carries none).

## Gate
- Server: no packet after `mode` + one clear utterance with no final → fallback `no_packets`, and that utterance gets
  an answer from the server's speech-to-text; with an `alive` phone, 2 misses don't fall back, the 3rd does.
- Worker: `alive` sent once per call, only for the caller's well-formed packets in phone mode.
