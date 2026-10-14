# Call Risi (decision 090) — status

**State (2026-10-14):** built behind flags, off by default; measured end to end with the harness; not yet
tried on a phone. Contract: `contract/proposals/2026-10-14-call-risi.md` (v1.48 candidate, not applied to
PROTOCOL.md; root/Harsha to review).

## Turn it on for Harsha
1. **Services on spark2** (once):
   - `docker compose --env-file .env -f infra/docker-compose.tts.yml up -d --build` (risime-tts, Kokoro on
     the GPU, 127.0.0.1:8230; ~2 GB host RAM + ~0.5 GB unified GPU memory);
   - `scripts/voice-setup` (venv `~/risime-voice/venv`, worker `~/risime-voice/risi_voice_worker.py`).
2. **LiveKit reachability from spark2 itself** (needed before the pilot can use it; see "Gaps" 1).
3. **Server switch:** `RISI_CALL_RISI=canary` (Harsha is in `RISI_DEV_USERS`), live in 5 s:
   `scripts/risi-rpc 'RisiMe.Agents.Flags.put("RISI_CALL_RISI", "canary")'`.
   The release must contain commits d7f7294c + 6cc89f37 (server) and 62222874 (android).
4. **Phone:** Settings → About → tap the version 7 times → Developer → "Call Risi (voice call with Risi)".
   The Call Risi button appears in the Risi chat's top bar.
- Kill switch: `Flags.put("RISI_CALL_RISI", "off")` (running calls end within 10 s) or `RISI_AGENTS=legacy`.

## What runs
phone mic (WebRTC NS/AEC/AGC on) → LiveKit (no frame E2EE for this room, said on screen) → `risi-voice`
worker (Erlang Port of `RisiMe.Agent.CallRisi.Session`): WebRTC APM echo cancellation against Risi's own
output + high-pass → RNNoise → Silero VAD, end of turn 350 ms → `RisiMe.Agent.Speech` (admin route via
SpeechRoutes, language `en`) → Router rules → chat: risi-l1 streamed, cut at the first clause/sentence;
tool route: filler at once, then the §25 Turn (its card lands in the Risi chat with `call_id`) → Kokoro
(risime-tts, voice from `TTS.voice_for("en")`, Auto = `kokoro:af_heart`) → Risi's mic track.
Barge-in: 300 ms of caller speech while Risi talks clears Risi's audio queue and kills the turn. The
transcript is posted as `risi_call_transcript` when the call ends. One `risi_agent_runs` row per turn
(agent `voice_call`, timings only) feeds the daily scoreboard.

## Latency (scripts/call-risi-harness)
| Run (end of turn 350 ms) | Caller heard p50 | p95 | Server p50 | p95 |
|---|---|---|---|---|
| 10 turns, no echo | 1415 ms | 1576 ms | 1324 ms | 1444 ms |
| latest 10 turns, no echo | 1411 ms | 1677 ms | 1349 ms | 1641 ms |
| echo −6 dB | 1388 ms | 1747 ms | – | – |

Per stage p50 (p95): end of turn 360 (371), STT large-v3 `en` 416 (494), first token 198 (209), first piece after
the utterance 778 (958), Kokoro first audio 218 (338). Tool route filler ≈ 0.9–1.0 s. End of turn 450 ms → caller
p50 1715 ms. Kokoro alone: GPU 330/445 ms, CPU 660/887 ms. Targets 1.5 s p50 / 3 s p95: met in the harness.

## Gaps
1. **The pilot LiveKit and the worker on the same host.** The pilot's LiveKit advertises only
   203.189.69.76 (NAT 1:1 of 10.20.20.15), and the edge does not hairpin it, so a process on spark2 can't
   reach its media. The harness uses a loopback LiveKit. `--pilot-like` (loopback candidate) is not run yet; root's plan is `enable_loopback_candidate: true` on the pilot LiveKit at a deploy.
2. **E2EE:** v1 has no frame encryption for Risi calls (Risi's MLS core has no `call_frame_keys`, crypto
   C10). Said honestly on the call screen and in the start reply.
3. **Echo:** one −6 dB run was clean, another had ~9 self-barge-ins (Risi's words heard as the caller's); the echo guard (700 ms tail, barge-in > 0.35 × Risi's own level) is untested. A real phone's echo cancellation should cover most of it: needs a device test.
4. English only (STT language fixed to `en`, Kokoro English voices). si/ta: another TTS engine via the
   admin console's per-language voice choice.
5. Tool turns in the harness use a stand-in §25 Turn (timed filler path, no real calendar). Real tool
   answers take the Turn's own time (pilot p50 3.3 s), covered by the filler.
6. Speeding up: an admin choice of `faster-whisper-large-v3-turbo` for `en` measured 290 ms vs 410 ms;
   streaming STT and a learned end-of-turn model are v2.
7. Not tried on a device yet (Android build 62222874 is unit-tested only).
