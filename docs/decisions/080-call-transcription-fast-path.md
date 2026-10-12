# 080 — Call transcription fast path: phone-side chunks, consent from everyone, local per-language models

**Status:** accepted 2026-10-12. Requirements come from Harsha; the details are root's. This decision becomes
PROTOCOL §36 (v1.39). It sits next to decision 070 (§27.7–§27.8, the LiveKit listener) and does not
replace it.

## Decision
- **Phone-side chunks, not a LiveKit bot.**
  - **How it works:** each phone records only its own microphone. It cuts the audio into chunks of 10–15 s
    (Ogg/Opus) and sends them over TLS to `POST /api/v1/calls/{call_id}/transcribe/chunks`. The speaker is
    the authenticated uploader.
  - **Why:**
    - 1:1 Official calls are peer-to-peer §16/§19 calls (v1.33), so there is no room for a bot to join.
      The `risi-listen` Rust/LiveKit path (decision 070) is still unbuilt and needs a crypto NIF for frame
      keys.
    - Phone chunks need no SFU, no frame-key export and no diarisation, and they work through any NAT.
    - Server and Android can ship this in days.
  - **Cost:** the path is not end-to-end encrypted to the members, because the server hears the audio. The
    UI says so plainly ("Audio is sent to Risi for transcription", decision 048), and nothing runs without
    everyone's consent.
- **Consent.**
  - A participant taps [Transcribe]. Every other participant gets Allow / Decline, and the request expires
    after 30 s.
  - Transcription runs only after **all** have allowed. Anyone can stop it for everyone.
  - A red "Risi transcribing" bar shows on every phone while it runs. It fails toward showing.
  - Each start, decline and stop is a Risi system line in the chat, naming who and when.
  - **Private calls:** there is no button and no code path. The caller marks Official calls inside MLS
    (`call_offer.tab`). A phone that holds the call as Private declines any request for it automatically.
    The server refuses any session that is not an Official `grp:` with Official `on`.
- **No storage.**
  - Chunks live in memory, or in the speech container's size-limited tmpfs, until they are transcribed.
    They are then deleted, and also on any error.
  - No audio is ever written to disk or to a log.
  - The transcript follows the §27.8 sealed buffer (24 h, then deleted after extraction).
  - The lines posted in the chat are Official memory under §35, deletable like any memory.
  - "Stop and delete transcript" removes Risi's posts of that session and makes no summary.
- **Models (all local on spark2, loopback only):**
  - each chunk's language is detected with `faster-whisper-large-v3`;
  - Sinhala goes to `whisper-small-sinhala`;
  - Tamil goes to `tamil-asr-qwen3`;
  - English, and unsure cases, go to `faster-whisper-large-v3`.
  - The aliases are served next to the existing `risime-whisper` service on `127.0.0.1:8200` (decision 071),
    through the same OpenAI-compatible `/v1/audio/transcriptions` with a `model` field. Fetching the new
    weights follows hard rule 10 (night window, rate limit, logged).
- **Developer compare mode.**
  - Only users listed in `RISI_DEV_USERS` (`User.risi_dev`) can turn it on with
    `PUT /api/v1/me/risi/dev {"transcribe_compare": true}`.
  - In compare mode every model runs on every chunk. Risi posts the results side by side (model, text,
    time taken) instead of the normal lines.
  - Each result is a `transcribe_compare` learning-log row. Its text is sealed and expires after 30 days.
    This is the one exception to "no transcript text in the learning log".
  - The peer's consent prompt says that comparison is on.
- **After the call:** the existing §27.2 extraction produces the per-person `discussion_summary`, the
  Official `discussion_card` and the §29.8 calendar `confirm` cards. Owners confirm their own items. Each
  reader's copy is written in the language that reader spoke most (the new optional `lang`).

## Memory budget
- `risi-l1` (vLLM) holds `--gpu-memory-utilization 0.34`, about 41 GB of 121.6 GB, and about 24 GB must stay
  free for the pilot and builds (decision 061).
- The speech models need an estimated 6–9 GB in total:
  - large-v3 in float16: about 3–4 GB;
  - whisper-small: about 1 GB;
  - tamil-asr-qwen3: about 2–4 GB, depending on its size.
- Load them lazily and keep them resident only while sessions run, or set an idle unload of about 10 min.
  Re-measure on spark2 before the switch goes `on`.
- Concurrency is capped by the server (`transcribe_unavailable` when full): at most 2 queued chunks per
  participant, and a configurable limit on live sessions (start at 4).
- In compare mode one chunk costs all three models, so it is for developers only.

## Kill switch and rollback
- `RISI_CALL_TRANSCRIBE` (default `off`) maps to `/auth/config` `call_transcribe`.
  - Turning it off hides the button on every phone, stops live sessions (`switch_off`) and refuses uploads
    with `503`. No new app is needed.
- **Server rollback:** `scripts/rollback`. The session table is additive.
- **App rollback:** the new kinds degrade to `body`, and the capability simply goes missing, so the button
  never shows.
- The §27.7 listener path is unaffected, and the two never run in the same call.
