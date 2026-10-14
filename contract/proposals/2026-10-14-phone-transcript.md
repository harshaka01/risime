# Proposal: the sender phone's own transcript inside a voice note (2026-10-14, root worker for Part 12 quick fix 2)

**Status:** APPLIED as **v1.47 §37.10** (root, 2026-10-14). Was: proposal for root/Harsha, to be applied as **v1.47** (§37.2 amendment). Nothing on the wire changes until
root edits PROTOCOL.md. The Android side is implemented against this text **behind a developer flag** (off for
everyone; Harsha's `risi_dev` account can turn it on), so root can apply the contract and flip it.

## Why
Harsha wants a "Phone (on-device)" speech candidate: Android's on-device recogniser
(`SpeechRecognizer.createOnDeviceSpeechRecognizer`, API 31+; Google's Android System Intelligence / Gboard engine
on most phones) transcribes the sender's **own** voice note on the phone, before encryption. Recipients then read
it without any server model, and Harsha can compare it with our server models on the same notes.

§37 has no field for this: §37.2 defines only `transcribe` (the opt-out), `reply_to`, `forwarded`, `request_id`;
the §37.6 `voice_transcript` is a **Risi post** (a separate `text` envelope from Risi's leaf, Official only) and
can't be reused: it's authored by Risi, carries `model`/`made_by`, and doesn't exist in Private.

## Proposal (additive, optional)
`voice` envelope (§37.2) gains one optional field **`phone_transcript`**:

| field | type | rule |
|---|---|---|
| `phone_transcript` | object, optional | absent = no phone transcript (every note before v1.47, and every note while the flag is off) |
| `phone_transcript.text` | string | 1–4000 characters after trimming, ≤ 12 000 UTF-8 bytes; the recogniser's final text (segments joined with one space) |
| `phone_transcript.lang` | string | the BCP-47 locale tag the recogniser ran with, 2–35 chars, e.g. `"si-LK"`, `"ta-LK"`, `"ta-IN"`, `"en-US"`, `"en-IN"`. Singlish (Sinhala with English mixed) is recognised as `"si-LK"`: no on-device pack exists for code-mixed speech |
| `phone_transcript.engine` | string | 1–64 chars; v1.47 Android senders write **`"android-on-device"`**; receivers accept any value (e.g. a future `"apple-on-device"`) and don't show it |

Example `voice_payload_phone_transcript.json`:
```json
{"v":1,"type":"voice","blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"mime":"audio/ogg; codecs=opus","duration_ms":12400,"waveform":"BAYKEihIbpa0yNK+oHhaRjxQeKrc//DIlm5QPCgeGBQePGSMqr7ItJZ4ZFpujKCWeFo8KBwUDgoIBgUEBAMCAg==","phone_transcript":{"text":"හෙට උදේ දහයට meeting එක තියෙනවා","lang":"si-LK","engine":"android-on-device"}}
```

Example `voice_payload_phone_transcript_bad.json` (the field is malformed: no `lang`; **the note is kept**, only
`phone_transcript` is ignored):
```json
{"v":1,"type":"voice","blob":{"blob_id":"5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c","size":49168,"sha256":"q7FnzD0yS2bX5uJ8Kc3mT9vL1aP4eR6wY0hN2iO8gUs="},"enc":{"alg":"A256GCM-S64K","key":"Hc2Tj9wQ1mZ8rV4yK7pL3sX6aN0bF5gD2eU9iO1tR8c=","plain_size":49152},"mime":"audio/ogg; codecs=opus","duration_ms":12400,"waveform":"BAYKEihIbpa0yNK+oHhaRjxQeKrc//DIlm5QPCgeGBQePGSMqr7ItJZ4ZFpujKCWeFo8KBwUDgoIBgUEBAMCAg==","phone_transcript":{"text":"hello","engine":"android-on-device"}}
```

### Sender rules (normative)
- **On-device only.** Only an on-device recogniser may produce it. A phone must **never** use an online recogniser
  (Android: never `SpeechRecognizer.createSpeechRecognizer`, never `ACTION_RECOGNIZE_SPEECH` activities); if the
  chosen language has no installed on-device pack, the note goes without `phone_transcript` and the UI says so. No
  audio leaves the phone except the §37.2 E2EE blob.
- Only the device that **recorded** the note writes it, from its own recording, before encryption.
- **Not written:** on a note with `transcribe: false` (the sender opted out of transcription in Official, so no
  text of that note goes to Risi's leaf either); on a **forward** (§37.3: dropped like `transcribe`/`request_id`).
- Allowed in Private, Official **and the user's Risi chat** (amended 2026-10-14, Harsha: the server Sinhala model
  hallucinates on real speech, so Risi compares the phone's text and may fall back to it; see the server's §37.10
  fallback amendment). It is part of the MLS-encrypted content (never plaintext metadata).
- Best effort: the recogniser may take at most a few seconds after the recording stops (Android: 8 s); after that
  the note is sent without it. Never delays a note beyond that, never blocks it.

### Receiver rules
- §0 old apps ignore the unknown field; the note plays as before. **No capability**, no readiness change.
- Validation is lenient: a malformed `phone_transcript` (wrong types, empty or too long `text`, missing `lang` or
  `engine`) is **ignored and the note is kept** (unlike the §37.2 strict fields).
- Rendering: under the bubble, collapsed to one line **"Transcript (sender's phone) ▾"**, expandable (the same
  block as §37.6). In Official, a Risi `voice_transcript` (§37.6) is shown first, then this one. Shown in Private too.
  Never claims to be Risi's.
- Copy/forward/quote texts are unchanged (§37.3 previews stay "🎤 Voice note 0:12").
- Backups (§22.5) and history shares (§17.7) carry the envelope as `payload`, so it travels with them.

### Server / Risi
- The server sees only a larger ciphertext (it already learns the envelope size). Honest note for decision 048:
  nothing else is learned.
- **Risi's device in Official and the Risi chat can decrypt the field** (Risi is a visible member there). Amended
  2026-10-14: Risi may **compare** it with its own §37.6 transcript and **fall back** to it (e.g. Sinhala), under
  the separate §37.10 fallback amendment being written by the server/risi side; until that lands, Risi ignores it.
  Either way it is never written to the learning log, server logs or pushes as text; a §37.10 `voice_transcript`
  built from it must say so (e.g. `model: "phone"`), never claim a server model heard it. Never in Private (Risi
  isn't a member there).

### Size
Envelope bound stays 22 KiB (§14.4): 12 000 bytes of text + ≈ 600 bytes of the rest fit.

## Gate
- android: encode/validate round trip; a malformed field ignored with the note kept; the field absent → the
  envelope bytes are identical to v1.46 (parity); forward drops it; the online recogniser is never constructed.
- server: both examples parse as valid `voice` envelopes; Risi ignores the field (no post, no log).
