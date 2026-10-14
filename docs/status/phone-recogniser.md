# Phone (on-device) speech candidate: status and Harsha's phone checklist

Part 12 quick fix 2 (2026-10-14). Developer flag **`phone_stt`**, off for everyone; contract proposal
`contract/proposals/2026-10-14-phone-transcript.md` (v1.47 candidate, field `voice.phone_transcript`).

## What it is
- Android's **on-device** recogniser (`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 13+ here because
  the app feeds its own audio through `EXTRA_AUDIO_SOURCE`). On Pixels and most Google phones it is the Android
  System Intelligence engine (the same one Gboard voice typing uses offline); Samsung may ship its own.
- **On-device only.** The app never builds the online recogniser (a unit test scans the sources). A language with no
  installed on-device pack is reported as "not available on-device" and the note goes without a phone transcript.
  No audio goes to Google.
- While the flag is on, the recorder's frames are also streamed (16 kHz) to the on-device recogniser. When you stop,
  the app waits at most 2–8 s for the final text, then sends the note with the text **inside the encrypted voice
  note**. Recipients see "Transcript (sender's phone) ▾" under the bubble (Private and Official). Not in the Risi chat,
  not on a note with `transcribe: false`, dropped on forward.
- Until root applies the proposal as v1.47, Risi on the server ignores the field (and older apps ignore it, §0).

## Agent design
Owner: the **transcription agent** (decision 085 scoreboard, "speech" Index). The phone engine is one more
**candidate** next to the server models; it is not promoted to anything. Signal: Harsha's compare runs on the same
notes (golden set = his real voice notes, human-checked). Learning level L1 (choose an engine per language by measured
CER/WER). Hybrid: the recogniser is a deterministic platform tool; the agent decides only from measured results.

## Redroid / spark2
Redroid has no on-device recognition service: the precheck reports "no on-device recogniser on this phone" and the
send path is unchanged (no session opens). Nothing in the gates depends on a recogniser.

## Checklist for Harsha's phone
1. Install the nightly. Settings → About → tap the version 7 times → **Developer** (your account is `risi_dev`).
2. Turn on **"Phone transcript on my voice notes (on-device)"**. Pick a language (default Sinhala / Singlish, si-LK).
3. Tap **Check on-device languages** and note each line. Report for: `si-LK`, `si`, `ta-LK`, `ta-IN`, `ta`, `en-LK`,
   `en-IN`, `en-US`, `en-GB` (installed / downloading / pack available / not available on-device). Also copy the
   "Installed on-device" list. For any "pack available", tap **Download**, wait a minute (Wi-Fi), check again.
4. The microphone permission must be granted (record one voice note first).
5. **Phone recogniser test:** Choose a voice note → **Run all languages**. For each note record: duration, each
   language's text and time (× real time). Do at least: 3 Sinhala, 3 Singlish (Sinhala with English words), 2 Tamil,
   2 English notes, ideally the same notes the server compare view already has. **Copy results** and paste them to me.
   If a run says "no result in time" or "failed", try again with **Real-time pacing** on and say which worked.
6. Send test: with the flag on, send a voice note in a Private chat with a second phone (or Kamal's). Check that:
   - the note arrives and plays as usual; the other phone shows "Transcript (sender's phone) ▾" with the text;
   - in the Risi chat no phone transcript is attached;
   - with the language set to one that's not on-device you get "Phone transcript: … isn't available on-device …" and
     the note still goes;
   - the extra wait after you release the mic (2–8 s) is acceptable or not.
7. Optional privacy check: turn on airplane mode, run the test on a note again: it must still work (on-device).
8. Turn the flag off: notes are exactly as before (no transcript line on the other phone).
9. **Phone voice (TTS):** on the same Developer screen, run the "Phone voice (TTS) test" and fill in
   `docs/status/phone-tts.md` (on-device si / ta / en voices, time to first audio).

## Results (to fill from Harsha's phone)
| Phone / Android | Engine | si-LK | ta-LK | ta-IN | en-US | en-IN | notes |
|---|---|---|---|---|---|---|---|
| | | | | | | | |
