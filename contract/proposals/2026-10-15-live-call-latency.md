# Proposal: live call lines in about 2 s (2026-10-15, P0-1b)

**Status:** APPLIED as **v1.50 §36.12** (2026-10-15, with the Sinhala/Tamil joining added after the first
measurement: a fast session's si/ta chunks of one speaker are transcribed together, ≤ 8 s, see §36.12). Was:
proposal for root/Harsha, to be applied as an additive amendment of **§36** (and §40.9, which
reuses it). Nothing changes on the wire until root edits PROTOCOL.md. The server implements it **behind the
runtime flag `RISI_LIVE_FAST`** (`off` | `canary` | `on`, default `off`; `canary` = a call with a
`RISI_DEV_USERS` participant), and the app behind the developer flag **"Fast live call transcript"** (on unless a
developer turns it off; it only acts when the server asks for it). Old apps and old servers are unaffected.

## Why
Harsha 2026-10-15 (P0-1b): live Official-call transcript lines arrive about 8 s after the words were said; the
target is **≤ 2 s** from speech to the line on the other phone, which 3-way calls need.

Measured before the change (pilot logs 2026-10-14 and `scripts/call-latency-bench`, see `docs/status/ci.md`):

| Step | Today | Where it comes from |
|---|---|---|
| Phone chunk | **12 s fixed** (§36.4: 10–15 s): a word waits 0–12 s (mean 6 s) before its chunk is even cut | `TranscribeChunker` |
| Upload | 15–290 ms (server-side request time, pilot) | |
| Server queue | 0 (2 GPU slots, nothing waiting) | |
| Speech model | large-v3, beam 5: **0.9 s p50 / 1.5 s p95** per 12-s English chunk (pilot, n=671) | `model=auto`, priority `normal` |
| Post + push | 50–360 ms (result → the peers' inbox pushes, pilot) | |
| **Turn merging** | a speaker's chunks wait as one open turn until **another participant's speech** arrives, or **30 s** (§36.5): a lone speaker's line waits up to 30 s | `Call.add_segment` |

So a word reaches the peer ≈ 6 s (chunk) + 1.3 s (server) on average when both people talk in every 12 s
window, and up to 30+ s when one person talks. The speech model is not the bottleneck; the chunk length and the
turn merging are.

Measured with the change (`scripts/call-latency-bench`, 3 speakers, live pilot speech service; full table in
`docs/status/ci.md`): speech→line p50 / p95 **en 2.8 / 4.6 s** (end of phrase 1.6 / 2.7 s), **si 2.7 / 4.2 s**,
**ta 3.1 / 4.8 s** (end of phrase 1.8 / 1.9 s), against 8.6–19 s before. Short chunks transcribed one by one
cost accuracy on si (CER 3.4 % → 21 %) and ta (14 % → 27 %); **resolved in v1.50** by joining a speaker's si/ta
chunks (≤ 8 s, one line): si CER 2.6 % at p50 4.2 s, ta 9.5 % at 4.7 s (en stays per chunk, 2.7 s, WER 7.0 %).
The turbo model loads only when the host has memory (today it mostly falls back to large-v3).

## Proposal (additive)

### 1. The session says "fast" (§36.2)
`Session.chunk` gains two **optional** fields: **`mode`** (`"vad"`) and **`overlap_ms`** (int). A fast session
(the server decides at the `request`; never a compare session) announces:
```json
"chunk": {"min_ms": 1000, "max_ms": 4000, "max_bytes": 262144, "mode": "vad", "overlap_ms": 500}
```
Without `mode` (every session today, and every session while `RISI_LIVE_FAST` is off) nothing changes. A client
that doesn't know `mode`, or sees another value, keeps the §36.4 10–15 s chunks, which a fast session accepts too.

### 2. The phone's chunks in a fast session (§36.4, android, normative)
- The phone cuts its own microphone **at pauses**, with a 20-ms energy gate over an adaptive noise floor:
  - a chunk closes at a pause of **≥ 300 ms** once it is **≥ 2 s** long; at the end of an utterance
    (**≥ 600 ms** of silence after **≥ 200 ms** of speech) even if shorter; and at **4 s** at the latest;
  - it is uploaded as soon as it closes (same endpoint, same parts; `duration_ms` 1–4 000);
  - the next chunk starts up to **`overlap_ms` (500 ms) before** the cut (all of it after a 4-s cut; only the
    trailing silence after a pause), so its `offset_ms` may be **lower than the previous chunk's end**; `seq` still
    grows by 1;
  - silence is not sent, except a short keep-alive (≤ 0.6 s) after **15 s** without a chunk (the 90-s `left`
    rule still holds); muted audio is silence.
- Everything else in §36.4/§36.6 is unchanged (own microphone only, memory only, zeroed, at most 2 held).

### 3. The server in a fast session (§36.4, §36.5, normative)
- **Limits:** **40 chunks per participant per 60 s, burst 8**; **4 queued** chunks per participant (the other
  §36.4 errors are unchanged).
- **De-duplication:** when a chunk starts before the end of the same speaker's previous chunk, the server drops
  the longest head of its text (at most 6 words, compared lower-cased without punctuation) that repeats the end of
  that speaker's previous line. A chunk left with no text posts nothing.
- **Grouping:** each chunk with speech is posted **at once as its own `call_transcript`** (`turn` + 1); there is
  no merging and no 30-s hold. The shape of `call_transcript` is unchanged; `parts` has one entry.
- **Models (§36.5 routing, amended for fast sessions):** the chunk is sent at the speech service's **`live`**
  priority. The language is detected first (as today, `faster-whisper-large-v3`, with the call's hint); `en`
  goes to the **fast English model** (`RISI_SPEECH_FAST_EN`, default **`faster-whisper-large-v3-turbo`**) with
  `language=en`; `si` and `ta` keep their current route. When the service can't load the fast model (memory) it
  answers 503 and the server uses `faster-whisper-large-v3` (one `model=auto` request) for 5 min. `model` and
  `made_by.model` name the model that answered, as today.
- **GPU priority:** while a fast session is on, the server holds the speech-guard pause file
  (`reason=live_call`, extended every minute, 2 min after the last tick), so evals, the night compare run and the
  TTS lab start no GPU work; the speech service (`WHISPER_LIVE_RESERVE=1`) never gives its last free GPU slot to a
  batch request (compare, evals).
- Session rows gain `fast` (bool, default false; additive migration). Learning-log rows are unchanged.

### 4. Old apps, old servers, rollout
- An old app in a fast session ignores `mode`, keeps 12-s chunks, and is served within the higher limits; its
  lines are posted per chunk (no 30-s hold).
- A new app with an old server never sees `mode`: unchanged.
- Rollout: `RISI_LIVE_FAST=canary` (Harsha's calls) → `on`. Kill switch: `off` (the next request is a normal
  session). The developer flag lets a developer keep 12-s chunks on one phone to compare.
- The `WHISPER_LIVE_RESERVE` change needs the speech image rebuilt (root); default 0 (unchanged).

## Agent design
**No new agent: infrastructure.** This is the transport and scheduling of the Call transcription capability's
input (chunking, queueing, GPU priority); the routing decision stays the speech service's, and the model choice
(fast English model) is a configuration of the existing route. Measured by `scripts/call-latency-bench`
(speech→line p50/p95 per language, WER/CER of the joined lines on the test clips) and the pilot's
upload→post times; the transcription Index's accuracy term must not drop (the bench's WER/CER before vs after).

## Examples (for root to add)
- `call_transcribe_reply_fast.json`: `call_transcribe_reply.json` with the fast `chunk` above.
- `envelope_risi_call_transcript_fast.json`: a one-chunk turn (`parts` with one entry,
  `"model": "faster-whisper-large-v3-turbo"`).

## Gate
- **Server:** fast off → unchanged (chunk, merging); on → the fast `chunk`, per-chunk posts, the de-duplication,
  the 40/8/4 limits, `fast`/`priority: live` reach the speech client, the pause file; canary; compare never fast;
  the speech client's fast route (detect → fast English model, si/ta auto + language, 503 → auto for 5 min,
  an old service without detect). `server/test/risime/agent/call_transcribe_fast_test.exs`,
  `call_transcribe_overlap_test.exs`, `speech_http_test.exs`.
- **Android:** `VadChunkerTest` (cuts, overlap, lead-in, keep-alive, mute, resume, the `chunk` parse) and
  `CallTranscribeManagerTest` (a fast session cuts at 4 s with overlap; the developer flag off and an old
  server's session keep 12 s).
- **Speech service:** `scripts/asr-smoke` live-reserve fixture.
- **Latency:** `scripts/call-latency-bench --md` (table in `docs/status/ci.md`).
