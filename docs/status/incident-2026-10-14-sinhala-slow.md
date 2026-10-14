# Incident 2026-10-14: Sinhala voice-note transcription "hangs for minutes"

Reported by Harsha at 07:41 SLT (02:11 UTC) on the pilot (v0.3.0-nightly.7). Root hotfix worker.
All numbers come from the live speech service's log (`risime-whisper-whisper-1`, asr5), the learning log
(`risi_llm_calls_by_day`, task `transcribe`), Prometheus `node_memory_MemAvailable_bytes` and a loopback
measurement. They are codes and timings only, never text.

## What happened (UTC)
| time | event |
|---|---|
| 01:57:47 | Sinhala note 6.5 s: `qwen3-asr-0.6b-sinhala`, 5.0 s (cold load 2.0 s). Normal. |
| 01:59:18 | Sinhala note 3.5 s: qwen3, 2.4 s. Normal. |
| **01:59:23** | `memory pressure: MemAvailable 2821 MB < floor 3072 MB, unloaded LRU`: the service evicts its Sinhala models, both the routed `qwen3-asr-0.6b-sinhala` and the "kept" `whisper-small-sinhala`. |
| 02:00:26 / 02:00:48 | the same 5.5-s Sinhala note, twice: `fallback=memory`, answered by **faster-whisper-large-v3 in 47.6 s and 39.5 s** |
| 02:02:01 / 02:02:27 | the next 6.0-s Sinhala note, twice: large-v3, **59.0 s and 55.7 s** |
| 02:00:38, 02:02:01 | learning log: both notes `transcribe` **status=failed, latency 60 026 / 60 031 ms** (model none) |
| 02:02:40 onward | English notes: large-v3, 0.6–1.3 s (unaffected) |

## Cause
1. **Host memory pressure, not GPU compute.** MemAvailable stayed under the speech service's 3 072 MB load
   floor almost continuously from 23:45 UTC (Prometheus, 5-min steps: 1.0–3.6 GB; 2.3 GB at 02:20). On spark2,
   `vm.watermark_scale_factor` is **1020** (default 10), so the kernel keeps ~25 GB as its high watermark and
   `MemAvailable` reads ~13 GB lower than the free pages (MemFree 16 GB at the same time). Consumers at
   02:14: risi-l1 vLLM 47.5 GB, Cassandra 7.8 GB, a Gradle daemon 2.7 GB, two release-gate redroid containers,
   Kokoro TTS 1.8 GB, the omniASR eval container, and other agents' builds and tests.
2. **The speech service's memory fallback for Sinhala is large-v3**, and large-v3 decodes Sinhala very slowly:
   beam 5 with the 6-step temperature fallback gives an **RTF of 7–10** (40–59 s for 5.5–6 s). With MemAvailable
   under the floor, every Sinhala load (qwen3 3.5 GB, small-sinhala 1.0 GB, the CTC model 2.5 GB estimate) is
   refused with NoMemory, so large-v3 was the only path left.
3. **The server waited a fixed 30 s, then retried once** (`Speech.Http` timeout 30 s, `Voice` retry), so the
   sender saw nothing for 60 s and then "Couldn't transcribe". The abandoned requests kept running on the
   service's two GPU slots (4 × ~50 s), which delayed anything queued behind them.

**Not the cause:** GPU contention with risi-l1 (English notes stayed at about 1 s); call-transcription jobs ahead
of the notes (there were no calls 01:50–02:20); CPU fallback; chunking (each note is one chunk).

## Measured on loopback (5.2-s Sinhala read clip, `infra/whisper/testdata/si-slr30-*.ogg`)
| path | wall |
|---|---|
| live asr5, MemAvailable 2.3 GB (`auto`, memory fallback to large-v3) | **7.5 s** (read speech; the real spontaneous notes took 40–59 s) |
| live asr5, `omnilingual-ctc-1b` by name | **503** (NoMemory: needs ~2.5 GB over the floor) |
| **asr6** test container (127.0.0.1:8202, load floor forced so every Sinhala load is refused): `auto` → CPU fallback | **0.46 s** (`fallback_model=omnilingual-ctc-1b`) |
| asr6, `omnilingual-ctc-1b` direct | 0.38–0.40 s |
| asr6 priority gate (1 GPU slot, 8 `normal` requests queued, then 1 `live`) | live done in 0.71 s, 4th of 9, ahead of 5 earlier requests |

## Fixes
**Server (fast lane, commits 2e74500f, f9150221, plus the console commit):**
- `Speech.transcribe_note/2`: the routed model with `priority=live` and a **budget of 3 × the note's duration,
  at least 15 s**. A fast error is retried once within the budget. Then the fallbacks: the sender phone's §37.10
  transcript (flag `RISI_VOICE_PHONE_FALLBACK`, **off** until root applies
  `contract/proposals/2026-10-14-phone-transcript-fallback.md`), then **omniASR-CTC-1B on the CPU**
  (`Speech.Http.fallback_transcribe/2`), else `failed`.
- One `risi_agent_runs` row per note (agent `voice`, mode `stt`, model, status, `fell_back`, reason such as
  `timeout_ctc` or `timeout_none`, latency, budget). These feed the Voice agent's daily scoreboard (incidents)
  and the console.
- `SpeechGuard`: a slow note (over `max(8 s, 1.5 × duration)`), or one that fell back or failed, writes the eval
  pause file for 10 min (docs/status/speech-eval-guard.md).
- "Transcribing (Sinhala)… [Stop]" / Try again: `VoiceJobs`, the `voice_transcribing` signal and
  `POST /api/v1/me/risi/voice/{cancel,retry}`, behind flag `RISI_VOICE_PROGRESS` (**off** until root applies
  `contract/proposals/2026-10-14-transcribe-progress.md`). The Android part ships as an APK hotfix.
- Admin console, Speech page: a "Speed today" card with voice notes, timeouts and fallbacks, per-model p50/p95
  and failed counts.

**Speech service asr6 (infra, prepared and tested, NOT rolled):** GPU slots served live first (`priority`
live/normal/batch); omniASR-CTC-1B resident on the CPU, outside the GPU budget, floor and eviction; Sinhala memory
fallback goes to CTC before large-v3; resident models are evicted only below a 1.5 GB hard floor; `/health` reports
queue and live latency. Roll command: in docs/status/speech-eval-guard.md ("Rolling asr6").

**With only the server fix and asr5:** under the same pressure, a 6-s Sinhala note now ends at the 18-s budget.
The CTC fallback on asr5 answers 503 under pressure, so without the phone fallback the note is `failed` after
about 18 s instead of 60 s. **With asr6** it gets a CTC transcript in under 1 s.

## Follow-ups (Harsha)
- Roll asr6 (command in docs/status/speech-eval-guard.md).
- Turn on `RISI_VOICE_PHONE_FALLBACK` and `RISI_VOICE_PROGRESS` (canary first) once root applies the two proposals
  and the APK hotfix is out.
- Optional, needs sudo: `vm.watermark_scale_factor=1020` makes MemAvailable read about 13 GB low, so every memory
  guard (the speech service's 3 GB floor, scripts/heavy's 5 GB) trips early. Check with NVIDIA's DGX Spark
  guidance before changing it (`sudo sysctl vm.watermark_scale_factor=<n>`).
