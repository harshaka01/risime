# Phone TTS (Android on-device text-to-speech): status and Harsha's phone checklist

2026-10-14 (Harsha's TTS candidates, decision 089 Voices page). A **phone-side** candidate: the admin console lists
"Phone TTS" as *on the phone only, not a server voice*. It is measured on the phone, not on spark2.

## What the screen does
Settings → About → tap the version 7 times → **Developer** (`risi_dev` accounts only) → **Phone voice (TTS) test**
(`PhoneTtsSection`, under the phone recogniser test).
- Starts `android.speech.tts.TextToSpeech` with the phone's default engine and shows the engine package (and any
  other installed engines).
- For **Sinhala** (`si-LK`, `si`), **Tamil** (`ta-IN`, `ta-LK`, `ta`) and **English** (`en-US`, `en-GB`, `en`) it
  lists every voice the engine reports, with quality and latency, and marks each one **on-device**,
  **network only (rejected)** or **voice data not installed**. It says which on-device voice the test would use, or
  "No on-device voice for <language>".
- **Speak:** a prefilled sentence per language (editable, ≤ 300 characters) spoken with that on-device voice
  (`setVoice(...)`, `KEY_FEATURE_EMBEDDED_SYNTHESIS=true`, `KEY_FEATURE_NETWORK_SYNTHESIS=false`). It shows the
  **time to first audio** (from `speak()` to `UtteranceProgressListener.onStart`).
- The sentence is never stored, logged or sent anywhere. The result lines carry only the language, the voice name and
  the time.

## The on-device-only rule
This is the same rule as the phone recogniser (`docs/status/phone-recogniser.md`): **never network synthesis**. A
voice with `isNetworkConnectionRequired()` is rejected, and so is a voice whose features contain
`KEY_FEATURE_NOT_INSTALLED`. When a language has no on-device voice, the test refuses and does **not** fall back to
the engine's default or to a network voice. The voice choice is a pure function, `PhoneTts.pickOnDeviceVoice`
(unit tests: `PhoneTtsTest`).

## What is known per language (before the on-phone check)
| Language | Expectation | Confidence |
|---|---|---|
| English (en-US / en-GB) | On-device voices ship with Google Speech Services (and with Samsung TTS). | high |
| Tamil (ta-IN) | Google Speech Services has downloadable ta-IN voices. They are on-device once the voice data is installed; before that they show as "not installed" or network-only. ta-LK is usually not a separate voice. | medium-high |
| Sinhala (si-LK) | **Uncertain.** Google lists Sinhala for some speech features, but it is not known whether Google Speech Services or Samsung TTS ships an **on-device** si-LK voice on Harsha's phone, rather than a network-only voice or none at all. Only the on-phone check can tell. | low |

The emulator and redroid have whatever engine the image ships (often none, or Pico without si/ta), so they prove
nothing here.

## Checklist for Harsha's phone
1. Install the nightly. Settings → About → tap the version 7 times → **Developer** → scroll to **Phone voice (TTS) test**.
2. Note the **Engine** line (e.g. `com.google.android.tts`, `com.samsung.SMT`).
3. For each of Sinhala, Tamil and English, note the language line: the on-device voice name, or "No on-device voice".
   Also note any voice marked "network only (rejected)" or "voice data not installed".
4. If a language has no on-device voice, install its voice data, then reopen the screen:
   Android Settings → System → Languages & input → **Text-to-speech output** (Samsung: General management →
   Text-to-speech) → the engine's ⚙ → **Install voice data** → pick the language → download (Wi-Fi).
5. For each language with a voice, pick the chip, tap **Speak** 3 times, and note the first-audio times and how it
   sounds (natural / robotic, correct pronunciation, Singlish words in a Sinhala sentence).
6. Privacy check: turn on airplane mode and speak again. It must still work (on-device).
7. Paste the result lines here (they contain no sentence text).

## Results (to fill from Harsha's phone)
| Phone / Android | Engine | Sinhala on-device voice | Tamil on-device voice | English on-device voice | First audio (si / ta / en) | Quality notes |
|---|---|---|---|---|---|---|
| | | | | | | |
