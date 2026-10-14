# Speech eval guard: live transcription first (2026-10-14)

Incident 2026-10-14 (docs/status/incident-2026-10-14-sinhala-slow.md). Live voice notes and calls come before
evals and training. Eval and training tooling checks, before and during work, that the live speech service is
healthy and idle enough and that host memory is above a floor. It pauses while live latency is high.

## The pieces
| piece | what it does |
|---|---|
| **pause file** `~/risime-logs/speech-guard/speech-eval.pause` | `until=<unix s> reason=<code> by=…`: paused until then. A file without `until=` is a manual pause that lasts until `resume`. The directory exists only for this file, so the omniASR container mounts it read-only. |
| **app server** (`RisiMe.Agent.SpeechGuard`) | after a live voice note that was slow (over `max(8 s, 1.5 × duration)`), fell back or failed: writes or extends `until=now+10 min` (`RISI_SPEECH_PAUSE_S`). It never overwrites a manual pause. Codes only. |
| **`scripts/speech-guard`** | `check` (exit 0 = go) · `wait [--max S]` · `watch --container NAME` · `pause [reason]` · `resume` · `status`. "Go" needs: no active pause; the live service's `/health` ok and, on asr6, no live request waiting, live p95 over the last 10 min ≤ 8 s (`SPEECH_GUARD_LIVE_P95_S`) and worst live RTF ≤ 1.5; MemAvailable ≥ 5 120 MB (`SPEECH_GUARD_MIN_MB`). |
| **speech service asr6** (`infra/whisper`) | GPU slots served live → normal (call chunks) → batch (compare and evals); `/health` adds `queue` and `live` for the guard |
| **`scripts/asr-eval.py`** | before every request: its own memory guard, then `speech-guard wait`; sends `priority=batch` (`SPEECH_GUARD=off` skips the guard, for an offline box only) |
| **`scripts/asr-omni-stream`** (and the `~/risime-eval-data/tools/asr-omni-stream` wrapper) | each attempt starts only after `speech-guard wait` (MemAvailable ≥ NEED_MB). While it runs, `speech-guard watch` **docker-pauses its own container** while live speech isn't "go", unpauses it when it clears, and stops it below `GUARD_RUN_MIN_MB` (5 120); the run resumes on the next attempt. Refuses to start without the guard. |
| **omniASR eval service** (`infra/omniasr`) | `/v1/audio/transcriptions` and `/compare` answer **503 "paused: live speech first"** while the pause file is active (mounted at `/guard`); its own 4 GB load floor is unchanged |
| **future training** | must run `scripts/speech-guard wait` before starting and `speech-guard watch --container <its container>` during the run (or call `check` between steps) |

## Use
```
scripts/speech-guard status          # why it says wait, live p95, queue, MemAvailable
scripts/speech-guard pause demo      # stop all evals by hand (until resume)
scripts/speech-guard resume
```

## Rolling asr6 (infra; Harsha or root, not done by the hotfix worker)
The image is built on spark2 (`risime/whisper:ct2-4.8.2-fw-1.2.1-asr6`, only `server.py` changed from asr5). It was
tested on a throwaway container on 127.0.0.1:8202 (CPU fallback 0.46 s for a 5.2-s Sinhala clip under a forced memory
floor; the priority gate served live ahead of queued normal requests). It loads about 1 GB more host RAM (the
resident CTC model). To roll it (about 60 s without the speech service, so pick a quiet minute; there is no lock):
```
cd ~/development/risime && git pull && \
docker compose --env-file .env -f infra/docker-compose.whisper.yml up -d && \
until curl -sf http://127.0.0.1:8200/health | grep -q cpu_fallback; do sleep 5; done; scripts/asr-smoke
```
Rollback: `WHISPER_IMAGE` isn't templated, so check out the previous compose line (`asr5`) and run the same `up -d`.
The omniASR eval service picks up its `/guard` mount on its next `docker compose … -f infra/docker-compose.omniasr.yml up -d`.
