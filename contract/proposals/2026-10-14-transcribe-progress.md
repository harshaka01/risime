# Proposal: "Transcribing (Sinhala)… [Stop]" for voice notes (2026-10-14, Sinhala-slow hotfix)

**Status:** APPLIED as **v1.49 §37.12** (root, 2026-10-14; renumbered, §37.11 is spoken @Risi). Was: proposal for root/Harsha, to be applied as **v1.48** (§37.11, additive). Nothing changes on the wire
until root edits PROTOCOL.md. The server implements it **behind the runtime flag `RISI_VOICE_PROGRESS`**
(`off` | `canary` | `on`, default `off`; `canary` = `RISI_DEV_USERS` only), and Android implements it as an APK
hotfix. Old apps ignore the unknown signal kind (§2.3) and never call the two endpoints.

## Why
Harsha 2026-10-14 07:41 SLT: a Sinhala voice note's transcription "hangs for minutes" on the pilot
(docs/status/incident-2026-10-14-sinhala-slow.md). Today the sender sees nothing in an Official chat until the
§37.6 `voice_transcript` (or "Couldn't transcribe") arrives, and "Risi is typing…" in the Risi chat. There is no
way to stop a slow transcription or to try again. Harsha wants the sender's phone to show
**"Transcribing (Sinhala)… [Stop]"**; Stop cancels the transcription and keeps the note, and offers **"Try again"**.

## Proposal

### Signal `voice_transcribing` (§2.3 `signal`; ephemeral, never stored, never replayed)
Sent **only to the note's sender** (every live socket of the sender's user; a Risi-chat note goes only to
`risi_tools` sockets, as every Risi-chat event, §25.2). Example `signal_voice_transcribing.json`:
```json
{"kind": "voice_transcribing",
 "data": {"conversation_id": "grp:6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
          "message_id": "0e4f3a20-a95b-11f1-8000-0242ac120002",
          "state": "working", "language": "si", "seq": 1,
          "can_cancel": true, "can_retry": false,
          "server_ts": "2026-10-14T02:00:01.000Z"}}
```
| field | rule |
|---|---|
| `state` | `working` (Risi is transcribing the note) \| `done` (finished: the §37.6 `voice_transcript` follows or has arrived, whatever its status) \| `cancelled` (stopped by the sender) \| `failed` (no transcript: the speech service and every fallback failed; the §37.6 `failed` card is also posted) |
| `language` | the expected language (`si` \| `ta` \| `en`) from the sender's and the chat's language hint (decision 071 d), or `null`. It's a hint, not a detection: the app shows "Transcribing (Sinhala)…" for `si`, "Transcribing (Tamil)…" for `ta`, "Transcribing…" otherwise |
| `seq` | grows by 1 per signal of one note; a client drops a signal with a lower `seq` than one already applied |
| `can_cancel` | true while `working` and the sender may stop it |
| `can_retry` | true on `cancelled` / `failed` while the server still holds the note's key **in memory** (at most 10 min after the stop or failure; never on disk, §36.4) |

### Stop and Try again (REST, auth; the note's sender only)
- `POST /api/v1/me/risi/voice/cancel` `{"conversation_id", "message_id"}` (`me_risi_voice_cancel.json`)
  - `200 {"state": "cancelled", "can_retry": bool}`: the transcription is stopped; **nothing is posted** for the note
    (no `voice_transcript`, no §38 items); the note itself is untouched. A `cancelled` signal also goes out.
  - `404 {"error": "not_running"}`: nothing is running for that note (already done, or not the caller's note).
- `POST /api/v1/me/risi/voice/retry` `{"conversation_id", "message_id"}` (same body)
  - `202 {"state": "working"}`: transcription starts again (signals as above).
  - `409 {"error": "running"}`: it is still running.
  - `410 {"error": "expired"}`: the server no longer holds the note (over 10 min, a restart, or the note was deleted):
    the app shows "Can't try again. Send the note again." and hides Try again.
  - `404 {"error": "not_found"}`: not the caller's note or not an Official/Risi-chat note.
- Both answer `404` while `RISI_VOICE_PROGRESS` is off for the caller.
- **Never Private:** both endpoints refuse any conversation that is not an Official `grp:` or the caller's Risi chat
  (Risi never had the note).

### Client (Android, normative where it says "must")
- Under the sender's **own** voice-note bubble (Official tab and the Risi chat only; never Private):
  - `working`: one line "Transcribing (Sinhala)… " + **[Stop]** (when `can_cancel`).
  - `cancelled`: "Transcription stopped" + **[Try again]** (when `can_retry`).
  - `failed`: "Couldn't transcribe" + **[Try again]** (when `can_retry`).
  - `done`: the line disappears (the §37.6 transcript block shows as before).
- Stop → `cancel`; Try again → `retry` (on `410`: the "Can't try again" line, no button).
- The state is in memory only (per process): it is never persisted and never backed up. A `working` line with no
  signal for `max(60 s, 4 × duration)` disappears.
- Old servers never send the signal: nothing changes.

## Server notes (non-normative)
- The server keeps the running transcription's task per (conversation, note) in memory; Stop kills it (its loopback
  speech request is dropped). The validated note (blob id, key, sizes) stays in memory for Try again at most 10 min,
  then is dropped; a restart drops it.
- Codes and timings only in logs and `risi_agent_runs` (`agent=voice`, `mode=stt`, `status=cancelled`); never text.

## Gate
server: signal shape and `seq`; cancel stops the task and posts nothing; retry re-runs; 410 after expiry; Private
refused; the flag off → 404 and no signal. android: the line per state; Stop/Try again call the endpoints; old
server (no signal) unchanged; Private never shows the line. APK hotfix gates apply (no protocol break: additive).
