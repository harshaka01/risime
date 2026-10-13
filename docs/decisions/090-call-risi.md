# 090 — "Call Risi": a voice call that Risi answers (v1, English first, Kokoro TTS)

**Status:** accepted 2026-10-14 (root, Harsha item 3 of 2026-10-14), with contract proposal
`contract/proposals/2026-10-14-call-risi.md` (v1.48 candidate). Behind the server flag
**`RISI_CALL_RISI`** (`off` | `canary` | `on`, default `off`; `canary` = `RISI_DEV_USERS`, Harsha first)
and the Android developer flag **`call_risi`** (Settings → About → seven taps → Developer; `risi_dev`
accounts only, off by default). With either off, nothing changes for any call (parity tests).

## Context
Harsha wants to *talk* to Risi: a "Call Risi" button in the Risi chat (and on the Risi contact) that starts
a normal-looking voice call, Risi answers, the call screen says clearly that Risi hears the conversation,
the transcript lands in the Risi chat. Targets: first audio **≤ 1.5 s p50, ≤ 3 s p95 after the user stops
talking**, barge-in (the user talks, Risi stops), natural fillers while a tool runs (never a fake result),
noise removal before STT, Risi never hears itself.

What we already have:
- **Calls:** LiveKit SFU v1.13.9 on spark2 (host networking, signalling on `127.0.0.1:7880`, media on
  `10.20.20.15:49500–49999`), coturn, the Android LiveKit SDK 2.29.0 path of §20 with the full call screen,
  Telecom, foreground service and history lines (§20.4). The **Risi chat is already a `grp:`** (§25.2)
  with exactly one human and Risi's device as MLS members, so a §20 room for it needs no new conversation.
- **Speech:** the whisper service on `127.0.0.1:8200` (faster-whisper large-v3 + per-language models),
  reached only through `RisiMe.Agent.Speech` and `RisiMe.Agent.SpeechRoutes` (the admin console's
  per-language choice, decision 089).
- **Brain:** `RisiMe.Agents.Router` (rules in µs), `RisiMe.Agent.Turn` (the tool loop that answers in the
  Risi chat), risi-l1 on vLLM `127.0.0.1:8100` (no streaming used yet).
- **Not built:** §27.8's `risi-listen` Rust participant was never built (decision 080 replaced it with
  phone-side chunks). So no server process can join a LiveKit room today, and Risi's MLS core has **no
  `call_frame_keys` export** (§20.6) on the server side.

## Options
| | A. `livekit-agents` (Python) worker | B. Custom pipeline, phone ↔ server WebSocket | **C. Hybrid: Risi joins the room as a participant through a thin media worker (LiveKit Python `rtc` SDK); our Elixir Voice agent drives the turn** |
|---|---|---|---|
| Reuses call stack | yes (room, coturn, call screens) | **no**: a second audio path on the phone (own capture, own AEC, own reconnects, own Telecom) | **yes**: room, tokens, coturn, the §20 call screen, Telecom, history line |
| Latency | good (streaming STT/LLM/TTS plugins) | best in theory (no SFU hop, ~5 ms on LAN) | good: the SFU hop on loopback is < 5 ms; all models local |
| ARM64 | `livekit` wheel yes; plugins mixed; its turn-detector downloads a model from the Hub at run time | n/a | `livekit==1.1.20` ships a `manylinux_2_28_aarch64` wheel (Rust `livekit-ffi` inside); pyrnnoise and silero-vad ship aarch64/noarch wheels; Kokoro runs on torch 2.8 CPU aarch64 |
| Licences | framework Apache-2.0, but the turn-detector model is under the *LiveKit Model License* (not OSI) | ours | all permissive: livekit rtc Apache-2.0, RNNoise BSD-3 (pyrnnoise Apache-2.0), Silero VAD MIT, Kokoro-82M Apache-2.0 (misaki MIT), torch BSD-3 |
| Who decides | the framework's agent loop and its LLM plugins (the decision lives in Python, outside our Router, Turn, learning log and flags) | ours | **ours**: Elixir owns turn-taking, routing, fillers, tools, honesty guards, learning log; Python only moves audio (infrastructure) |
| E2EE | same as C | same as C | see below |

**Decision: C.** It reuses everything the phone already has for calls, keeps every decision inside the agent
tree (decision 085: "modules and services are tools agents use; they never make the decisions"), uses only
permissive, ARM64-ready parts, and has no Hub download at run time. `livekit-agents` was rejected because it
would move the brain into a second framework with its own LLM/TTS plugins and an non-OSI turn model; B was
rejected because it throws away the call stack (Telecom, reconnects, the WebRTC audio processing the phone
already runs in calls) for a few milliseconds that the SFU on loopback doesn't cost.

### The pipeline (v1)
```
phone mic ─(WebRTC NS + AEC + AGC on the phone; Opus/DTLS-SRTP)─▶ LiveKit SFU (spark2)
  ─▶ risi-voice worker (Python, Erlang Port of RisiMe.Agent.CallRisi.Session, 48 kHz PCM in memory)
       1. WebRTC APM server-side (echo cancellation against Risi's own outgoing audio + high-pass),
       2. RNNoise (48 kHz, 10 ms frames, streaming: runs while the user talks, adds no turn latency),
       3. Silero VAD (16 kHz, 32 ms frames) → end of turn = 450 ms of silence after ≥ 250 ms of speech
          (configurable; the Voice agent can lengthen it per user later),
     ─▶ utterance (16 kHz mono WAV, in memory) to Elixir
  ─▶ RisiMe.Agent.Speech (the admin's per-language route via SpeechRoutes; fallback = today's route)
  ─▶ Voice agent: Router.rules (µs) → "chat" route: risi-l1 streamed (stream: true), sentence cut;
                                     → a tool route: filler line at once, then RisiMe.Agent.Turn (the same
                                       tool loop as the Risi chat; its answer card lands in the chat) and
                                       the answer text is spoken
  ─▶ "say" sentence by sentence to the worker ─▶ Kokoro-82M TTS (risime-tts, 127.0.0.1:8230, 24 kHz)
  ─▶ the worker resamples to 48 kHz and publishes Risi's microphone track ─▶ SFU ─▶ phone speaker
```
- **Barge-in:** while Risi speaks, the worker keeps VAD running on the echo-cancelled input; ≥ 300 ms of
  user speech clears the playback queue at once (`AudioSource.clear_queue`) and tells the agent, which
  cancels the stream/turn and listens. What Risi had not said yet is not in the transcript.
- **Risi never hears itself:** (1) Risi never subscribes to its own track (a participant can't); (2) the
  phone's WebRTC AEC removes the speaker playback from the mic (LiveKit default on, kept on); (3) the
  server-side APM gets Risi's outgoing frames as the reverse stream and cancels residual echo; (4) during
  Risi's own speech the barge-in threshold is higher. The harness's "echo" test (a caller that plays Risi's
  audio back into its mic at −6 dB) asserts no self-barge-in and no transcript of Risi's own words.
- **Fillers** are chosen by the Voice agent in the user's language from a small fixed set per route
  ("Hmm, let me check your calendar…", "One moment, I'll look that up…"), spoken only when a tool is
  actually called. They never state or imply a result.
- **Latency budget** (after the user stops talking): end-of-turn 450 ms + STT ~250–450 ms + first sentence
  ~250–500 ms + first TTS chunk ~150–300 ms + SFU/jitter ~80 ms ≈ 1.2–1.8 s. Measured numbers: below.

### E2EE — said honestly
- **A Risi call is a call with Risi, not an Official or Private call between humans.** Risi is the other
  party, so Risi necessarily decrypts and hears this call's media. Nothing about human ↔ human calls changes:
  Private calls still never reach Risi; §27.7 Official listening is unchanged.
- **v1 runs without §20.6 frame encryption.** The media is encrypted on every hop (DTLS-SRTP phone ↔ SFU,
  then loopback to the worker on the same host), but it is not frame-encrypted end to end, because the
  server-side MLS core has no `call_frame_keys` export yet (adding it is a crypto change with the full
  release gates: tracked as "C10 for Risi calls"). Frame encryption to Risi would protect the audio only
  against the SFU process, which runs on the same machine as Risi. The call screen therefore says
  **"Risi hears this call · Not end-to-end encrypted: audio is processed by Risi on RisiMe's server"**
  (decision 048: never claim encryption), and the reply of `start` says `"e2ee": false`.
- **No raw audio is ever stored:** the worker holds at most the current utterance (≤ 30 s) in memory;
  TTS audio is streamed and dropped; nothing is written to disk or logs. The transcript goes into the Risi
  chat (Risi's MLS post, encrypted to the user's devices) and the sealed 24-h buffer like every Risi-chat
  message; the learning log keeps timings, counts, model ids and hashes only (never text, never audio).

### Rooms and tokens
- The phone calls `POST /api/v1/calls/rooms` `start` with `"risi_call": true` for its Risi chat. The server
  checks `RISI_CALL_RISI` for the caller, the Risi chat (`chat_kind: "risi"`, the caller its owner), the
  device's `call_risi` capability, health (whisper, TTS, worker), and `RISI_CALL_RISI_MAX` (default 2)
  running Risi calls; then creates the room (metadata gains `"k": "risi"`) and starts the Voice session,
  which mints Risi's token **inside the server** (`canPublish: true`, `canPublishSources: ["microphone"]`,
  `canSubscribe: true`, `canPublishData: false`), never through REST.
- No ringing: the phone doesn't send `call_offer`; it sends the durable `group_call` `started`/`ended`
  lines as §20.4 (with `"with": "risi"`), so the history line is "Call with Risi · 3:12".
- Risi leaves when the user leaves, at 30 min (v1 cap), on a flag change to `off`, or on a crash (the
  session is supervised; a crash ends the call with "Risi had a problem and left the call").

## Agent design (decision 085)
- **Owner: the Voice agent** (Part 9; `RisiMe.Agents.Planned.Voice` → a real `RisiMe.Agents.Agent`
  `voice_call` spec). Media (LiveKit, RNNoise, APM, VAD framing, Kokoro synthesis) is **infrastructure**,
  used as tools: exactness and real-time work where a learned policy has no place.
- **Goal:** a spoken conversation that feels like a phone call: quick first audio, natural turn-taking,
  correct answers, honest about tools.
- **Tools:** `speech.transcribe` (routed), `router.classify`, `llm.stream` (risi-l1), `turn.run` (the Risi
  tool loop: calendar, notes, memory, reminders; writes still go through confirm cards in the chat),
  `tts.speak`, `media.stop_speaking`.
- **Memory:** the call's own turns (in memory, ≤ 12 turns of context); the Risi chat's sealed buffer via
  `Turn` as today. Nothing new persisted.
- **Signals (learning, L1 now):** per-turn timings (end-of-turn wait, STT, first token, first sentence,
  first audio), barge-ins (count, how far into Risi's sentence), "say that again"/repeats, hang-ups
  during Risi's speech, the end-of-call rating (👍/👎 on the transcript card), route and tool status.
- **Eval / golden set:** `eval/voice-call/` scripted calls (WAVs of Harsha's real voice when he records
  them; synthetic Kokoro voices only as a labelled fallback): first-audio latency, barge-in stop time,
  echo test (no self-barge-in), route correctness, filler-only-with-a-tool, no invented result.
- **Intelligence Index "Voice calls":**
  `Eval = 0.4 × LatencyScore + 0.3 × InterruptionScore + 0.3 × AnswerScore` where
  `LatencyScore = 100 × share of turns with first audio ≤ 1.5 s` (minus 50 % of the share over 3 s),
  `InterruptionScore = 100 × share of barge-ins where playback stopped ≤ 300 ms and the next answer
  addressed the new utterance`, `AnswerScore` = route + answer correctness on the golden calls (a fake
  result scores 0 for that case). `Live = 100 × 👍 ÷ (👍 + 👎)` on call transcripts over 7 days (fewer than
  20: "Eval only (n live)"). Index = 0.7 × Eval + 0.3 × Live (decision 085).
- **Learning loop:** nightly the Coach reads the per-turn timings and barge-in signals: tune the end-of-turn
  silence per user (L1), filler wording (L1), the prompt (L2, eval-gated), later a small turn-detection
  model trained on our own consented turns (L3). Promotion rule: a change that lowers the Index rolls back.
- **Why a hybrid (AOA + SOA):** turn-taking, routing and wording are judgement and must learn; media is
  exactness. Putting the turn loop in a media framework (option A) would have hidden the decisions from the
  learning log and the flags.
- **Flags:** `RISI_CALL_RISI` off → canary (Harsha) → on; `RISI_AGENTS=legacy` turns it off with every agent.

## Consequences
- New: `RisiMe.Agent.CallRisi` (+ `Session`, `Worker` port, `Stream`, `Fillers`), `infra/voice/` (the media
  worker and the Kokoro server, a host venv `~/risime-voice/venv`, systemd user unit for `risime-tts`),
  `scripts/call-risi-harness` (a headless LiveKit caller that plays a WAV and measures first audio).
- Python grows beyond "model serving only" (CLAUDE.md stack): the worker is a media bridge. Recorded here
  (rule 8). It is a separate OS process under the agent tree, so a crash never takes the app server down.
- **Gaps for v2:** frame E2EE to Risi (C10), si/ta TTS (per-language admin choice), a streaming STT so the
  transcript is ready at end of turn, a learned end-of-turn model, video, calling Risi from a group call.

## Measured (spark2, 2026-10-14)
See "Latency" in `docs/status/call-risi.md` (filled by `scripts/call-risi-harness`).
