# Risi speech test clips (Harsha to record)

We need 20 Sinhala and 20 Tamil clips of real Risi requests, read the way people speak to a
phone. ASR scoring (`scripts/risi-eval asr <model>`) picks them up automatically once they exist.
Until then, ASR is scored on public test sets: FLEURS `ta_in` for Tamil and SPEAK-ASR
`youtube-sinhala-asr` for Sinhala, because FLEURS has no Sinhala.

## What to record
- **Sentences:** `si.txt` and `ta.txt`, one sentence per line, in order. Line 1 → `si/01.wav`,
  line 2 → `si/02.wav`, … and the same for Tamil in `ta/`.
- **How:** read each sentence naturally, the way you would ask Risi.
  - English words that are written in English (client, report, invoice, meeting) stay English.
  - Say numbers as words (හයට, ஆறு மணிக்கு).
  - If you would naturally say a line differently, say it your way and edit the line in the
    `.txt` file to match. The file is the reference transcript.
- **Format:** WAV, mono, 16 kHz, 16-bit; 2–8 s each. Phone recordings are fine. Convert them with
  `ffmpeg -i in.m4a -ac 1 -ar 16000 si/01.wav`.
- **Speakers:** ideally 2–3 different voices per language (record who in `speakers.txt`, no
  names needed: "A male, B female").
- **Privacy:** these sentences are synthetic (fake names, no real numbers). Don't record real
  chats, and don't commit recordings of people who haven't agreed to it.

## Sentences
**Sinhala (`si.txt`):** wake-up alarm, message Kumu, bank reminder, Wednesday availability,
Thursday meeting with Nimal, a promise ("quotation by Friday"), a report request, the launch date,
"summarise today", last week's decisions, my promises, team lunch, budget amount, a daily good-night
message, a 20-minute reminder, a meeting moved, a duplicated invoice charge, "I'll check with
finance", cancel a scheduled message, thanks.

**Tamil (`ta.txt`):** the same 20 intents in natural Tamil.

**Items for Harsha's review:** the Sinhala and Tamil wording is mine, Claude's. Please correct
anything that sounds unnatural, especially Sinhala line 15 (විනාඩි විස්සකින්…) and Tamil line 11
(வாக்குறுதிகளின் பட்டியல் for "my promises").

## Scoring
`scripts/risi-eval asr-data` writes `~/risime-eval-data/manifest.jsonl`. It includes these clips
when they exist, plus the public subsets. `scripts/risi-eval asr <model>` reports WER and CER per
language, after NFC normalisation, lower-casing, punctuation removal and ZWJ removal.
