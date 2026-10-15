# CI / release timings

| Run | Start (UTC) | End (UTC) | Release (start → APK published) | Notes |
|---|---|---|---|---|
| v0.3.0-nightly.8 (RELEASE_CORE, before) | 14 Oct 16:12 | 17:20 | 68 min | gates run at release time on spark2; target ≤ 10 min |
| v0.3.0-nightly.9 (RELEASE_CORE) | 14 Oct 23:36 | 00:49 | 73 min | same core gates; UI robot report-only after |

## Live call transcript latency (P0-1b, 2026-10-15, `scripts/call-latency-bench --md`)
Real-time 3-way conversation (one speaker at a time, the test clips of `scripts/call-transcribe-test`, low noise on
every microphone) through the phone's chunker and the **live pilot speech service** (loopback, 2026-10-15 ~01:45 UTC,
large-v3 + omniASR-CTC resident; the turbo model could load only twice, MemAvailable ~1.2 GB under the 3 GB floor).
`speech→line` = for every 100 ms of speech, the time until the line carrying it would show on the peers:
chunk cut + Ogg/Opus encode + speech service + 300 ms post/push (pilot 2026-10-14: 50–360 ms). Network upload not
included (pilot server-side request time 15–290 ms). `before` = today's server (12-s chunks, turn merging);
`before-chunk` = today's chunks posted as soon as transcribed (the pilot's alternating case, "~8 s");
`fast` = `RISI_LIVE_FAST` (VAD 1–4 s chunks, 0.5 s overlap, per-chunk lines, live priority).

| mode | lang | p50 speech→line | p95 | end-of-utterance p50 / p95 | speech service p50 / p95 | chunk p50 | chunks/min/speaker | WER/CER |
|---|---|---|---|---|---|---|---|---|
| before | en | 15.7 s | 24.3 s | 15.0 / 20.8 s | 1.30 / 1.83 s | 12.0 s | 4.6 | 0.053 |
| before-chunk | en | 8.6 s | 13.2 s | 8.8 / 10.0 s | 1.39 / 1.82 s | 12.0 s | 4.6 | 0.053 |
| **fast** | en | **2.8 s** | 4.6 s | **1.6 / 2.7 s** | 0.58 / 0.96 s | 2.0 s | 7.6 | 0.070 |
| before | si | 11.2 s | 23.9 s | 9.8 / 21.4 s | 1.66 / 1.76 s | 12.0 s | 5.0 | 0.034 |
| before-chunk | si | 9.0 s | 12.6 s | 9.0 / 9.7 s | 1.68 / 1.72 s | 12.0 s | 5.0 | 0.034 |
| **fast** | si | **2.7 s** | 4.2 s | 3.2 / 5.3 s | 0.57 / 3.85 s | 1.8 s | 5.8 | **0.214** |
| before | ta | 19.3 s | 25.4 s | 18.1 / 21.1 s | 1.75 / 2.84 s | 12.0 s | 4.5 | 0.141 |
| before-chunk | ta | 9.7 s | 13.7 s | 9.6 / 11.3 s | 1.61 / 2.28 s | 12.0 s | 4.5 | 0.141 |
| **fast** | ta | **3.1 s** | 4.8 s | **1.8 / 1.9 s** | 0.78 / 1.40 s | 1.5 s | 8.4 | **0.271** |

Reading: the 2-s target is met **from the end of a phrase** for en and ta (p50 1.6 / 1.8 s); per word (a word waits
for its phrase's chunk to close) p50 is ~2.7–3.1 s. **Accuracy cost:** short chunks raise si CER 3.4 % → 21 % and
ta CER 14 % → 27 % on these read-speech clips (en WER 5.3 % → 7.0 %); keep `RISI_LIVE_FAST` on canary until si/ta
are tuned (longer minimum for si/ta, or a re-transcribed final line). Small synthetic/read test set (3 en/ta, 1 si
clip); real conversation clips are the next round.

**After §36.12 (v1.50, 2026-10-15 ~02:00 UTC): Sinhala/Tamil chunks of one speaker joined (≤ 8 s) before
transcription; English stays per chunk.** Same bench, live pilot service:

| mode | lang | p50 speech→line | p95 | end-of-utterance p50 / p95 | speech service p50 / p95 | WER/CER |
|---|---|---|---|---|---|---|
| fast (joined) | si | **4.2 s** | 5.8 s | 2.4 / 2.4 s | 0.56 / 0.62 s | **0.026** (12-s: 0.034) |
| fast (joined) | ta | **4.7 s** | 8.5 s | 2.9 / 3.0 s | 1.01 / 1.72 s | **0.095** (12-s: 0.141) |
| fast | en | 2.7 s | 4.5 s | 1.7 / 2.5 s | 0.70 / 0.88 s | 0.070 (12-s: 0.053) |

`beam_size 1` for English was not tried: the speech service has no per-request beam size (one env for all
requests), so it needs a service change and an image rebuild.
