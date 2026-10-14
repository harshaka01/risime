# Proposal 2026-10-14: Risi-chat voice notes wait for a busy speech service (§37.8 amendment)

Status: **implemented on the server (fast lane), awaiting root to apply to PROTOCOL.md.**
Origin: P1 hotfix from Harsha's phone, 2026-10-14 07:01 SLT. On 13 Oct 20:11 UTC Harsha's Risi chat
answered two voice notes with "I can't listen to voice notes right now. Please type it." (the switch was
`off` on the pilot). Harsha's rule: Risi never asks the user to type because its own speech service is
off, down, busy or out of memory.

**Wire impact: none.** All new lines are ordinary rule-made `answer`s (§24.11) with the note's
`request_id`, as the §25 offline notice already does ("the later reply is a second Risi message for the same
request"). No new kind, field or event; old apps show `body`. Only prescribed wording and the §37.6 storage
rule change.

## 1. §37.8, replace the `unclear`/`failed` bullet with

**Harsha 2026-10-14: Risi never says "please type it", anywhere.**

- **Very short:** a note under **1 000 ms** (500–999 ms pass §37.2) is not transcribed; Risi answers **"That
  was very short. Could you say it again?"**. An `unclear` transcript with no speech (empty or noise only)
  gets the same answer.
- **`unclear`** otherwise (low language probability, still romanised): the transcript card says so, and Risi
  answers **"I couldn't hear that clearly. Could you say it again?"** (was "… Please try again or type it.").
- A blob that can't be fetched or verified: the `failed` card and **"I couldn't open that voice note. Could
  you send it again?"**.
- Over 5 min: **"That voice note is over 5 minutes, too long for me. Could you send a shorter one?"**.
- **The speech service down, busy, out of memory or timing out** (both §37.6 attempts failed): **no**
  `failed` card yet and **never** "type it". Risi at once answers **"Got your voice note. I'll reply in a
  moment."** (an `answer` with the note's `request_id`, then `risi_progress` `done`) and queues the note.
  If the service already reports unhealthy when the note arrives, the acknowledgement goes out before the
  first attempt (the note is still tried, PART 4d).
- **The retry queue:** an Oban job retries with backoff (15 s, 30 s, 60 s, 120 s, then every 5 min) while
  the speech service is unhealthy or failing. When it answers, the note gets its normal `voice_transcript`
  card and is handled as the §24.11 `ask` (a second `answer` for the same `request_id`).
- **Bounded give-up** after **30 min** (server config): the `failed` card, and Risi answers **"I couldn't
  listen to your voice note: my speech service isn't answering. Please send it again a bit later."**
  No tools run.
- A note deleted for everyone while it waits is dropped silently.

## 2. §37.8, add: the switch off for this user

When `RISI_VOICE_TRANSCRIBE` is `off`, or `canary` and the sender isn't in `RISI_DEV_USERS`, a Risi-chat
note gets the answer **"Voice notes to Risi are coming soon for your account."** (was "I can't listen to
voice notes right now. Please type it."). `canary` covers the Risi chat path: a `RISI_DEV_USERS` sender's notes
are transcribed there.

## 3. §37.6 step 4, add after "Never written to disk, logs or the learning log."

> **Exception, the §37.8 retry queue only:** while a Risi-chat note waits for the speech service, the
> server keeps the note's blob reference (id, size, ciphertext SHA-256) and its key in one row of
> `risi_voice_queue`, **sealed** with AES-256-GCM under `RISI_DATA_KEY` (AAD
> `risi_voice_queue:<conversation_id>:<message_id>`). Never audio (plaintext or ciphertext), never
> a transcript; the job's arguments hold ids only. Each attempt reads the ciphertext from the blob store and
> decrypts in memory exactly as the first. The row is deleted when the note is transcribed, given up
> (≤ 30 min), or found deleted for everyone. Official-group notes are not queued (a failure there is the
> `failed` card, as before).

## 4. Pilot default

`infra/pilot/pilot.env` gains `RISI_VOICE_TRANSCRIBE=canary` (reviewed config default; `RisiMe.Agents.Flags`
reads `canary` from the OS environment). Only `RISI_DEV_USERS` change behaviour; `/auth/config`
`voice_transcribe` stays `off` under `canary` (decision 048 honesty), so no other phone changes.

## Tests

`server/test/risime/agent/voice_test.exs`: the ack at once while unhealthy; speech down → ack, sealed row
(key never in clear, job args ids only), snooze with backoff, answered when back; the bounded give-up (failed
card + honest answer, never "type"); canary covers the Risi chat for dev users, the "coming soon" line for
others; under 1 s and no-speech get the "very short" line (no speech call for < 1 s); low confidence gets
"say it again"; every fixed reply is asserted not to contain "type".
