# Risi models: survey, eval method and results

Root side track, 2026-10-10. It answers which open model should serve Risi next to the current
`risi-l1` (Qwen3.6-35B-A3B-FP8), and which speech model can handle Sinhala and Tamil.
**Nothing was switched.** Switching is a separate change that needs Harsha's yes.
Rerun everything with `scripts/risi-eval` (see "How to rerun").

## TTS: Call Risi voice candidates (2026-10-15, Harsha 21:08 SLT)
Harsha picks Call Risi's voice by ear. Each engine says the same 3 lines: a greeting ("Hi, it's Risi.
How can I help?"), a calendar answer (the reminder to call Shakya, plus two busy windows) and a "let me
check" line. **Call Risi's voice was not changed.** His pick ships as a server hotfix (below).

Measured on spark2 (GB10), one engine loaded at a time at night, beside risi-l1, the live speech route
and Kokoro. Each engine ran 12 times (4 reps × 3 lines); the model load is excluded.
Script: `infra/voice/tts_voice_pick_bench.py`.

| # | Engine (voice) | First audio p50 / p95 | First full sentence p50 / p95 | RTF | Peak memory | Load |
|---|---|---|---|---|---|---|
| 1 | Kokoro 82M (`af_heart`, today's call voice) | **112 / 129 ms** (streamed) | 112 / 129 ms | 0.04 | resident: 2.5 GB GPU (nvidia-smi), 160 MB container RSS | always loaded |
| 2 | Chatterbox-Turbo 350M (built-in stock voice) | 760 / 1 891 ms (not streamed: first sentence) | 760 / 1 891 ms | 0.29 | 3.4 GB CUDA, 5.2 GB process RSS (host free memory −2.9 GB) | 15.8 s |
| 3 | Dia2 1B (stock speaker, seed 3) | 2 665 / 2 694 ms (streaming estimate) | 4 713 / 8 587 ms | **1.31** (slower than real time) | 5.8 GB CUDA, 5.4 GB RSS (free −8.5 GB) | 17.5 s |
| 4 | Orpheus 3B 0.1 ft (`tara`) | *see below* | | | | |

How to read the table:
- **First audio:** what a caller would wait for after Risi has its text.
  - Kokoro streams from its resident service.
  - Chatterbox-Turbo cannot stream, so the number is the whole first sentence. The calendar line is one
    long sentence, which is why its p95 is high.
  - For Dia2 and Orpheus, it is the time their first ~300 ms of audio exists inside generation. That
    is a streaming server's first audio, plus a few ms of decoding.
- **RTF:** synthesis time ÷ audio length. Below 1 is faster than real time. Dia2 at 1.31 cannot keep
  up with its own speech, so a live call would stall between sentences.
- **Peak memory:** each worker's own `torch.cuda.max_memory_reserved` and `ru_maxrss`. GB10 memory is
  unified, so the two overlap. The host view (the fall in MemFree) moves with the page cache and other
  jobs, so it is approximate.
- **Voices:**
  - Kokoro and Orpheus use named stock voices.
  - Chatterbox-Turbo uses Resemble's built-in voice (`conds.pt`, reviewed before loading: tensors only,
    read with `weights_only=True`). It is PerTh-watermarked.
  - Dia2 has no named voices. Its stock speaker is the model's own speaker for seed 3 on a fixed line,
    made in memory at load and reused as the prefix of every line. There is no recording of a person.
    Seed 3 was picked from seeds 2–8 by the numbers: adult female, median F0 224 Hz, 2.4 words/s,
    4.9 semitones of pitch movement. Harsha judges the sound.
- **Live budget:** today's live budget for Call Risi is 1.5 s first audio (p50). Only Kokoro (0.11 s)
  and Chatterbox-Turbo (0.76 s, with long first sentences over budget) are within it. Dia2 is not, and
  it is also slower than real time.

**Hotfix for Harsha's pick (committed, not deployed):**
- Kokoro voice: no new code. Set `tts_voice_en` on the Voices page.
- Lab engine (Chatterbox-Turbo, Dia2 or Orpheus), three settings:
  - `TTS_LAB_PIN=<engine>` on risime-tts-lab: loaded at start and kept resident, served through the
    speech pause, never stopped by `scripts/daytime-memory`, other engines refused with 409 `pinned`;
  - `RISI_CALL_LIVE_ENGINES=<engine>` on the server: `CallRisi.voice/1` then accepts that engine's
    voice;
  - `tts_voice_en` = the voice.
  - The media worker sends a non-Kokoro voice to the lab: it reads the WAV header, resamples from the
    engine's rate and keeps the gapless pipeline. If the lab refuses or can't be reached, it speaks that
    sentence with Kokoro `af_heart` instead of going silent.

## Routing check and language hints (2026-10-13, Harsha's PART 1/2)
Runs: `scripts/asr-eval detect` / `routing` (`~/risime-eval-data/results/detect-1.jsonl`) and
`scripts/asr-eval run --models auto [--hint si|ta --hint-mode prior|force]`
(`hint-off.jsonl`, `hint-si.jsonl`, `hint-ta.jsonl`), all on speech image `…-asr5`.

### 1. What today's pilot calls were labelled
- **No per-chunk record exists for today's calls.** The speech container was recreated at
  19:41 UTC for asr4, so its log of the 15:3x–16:5x UTC calls was lost. Docker logs are not
  shipped to Loki. The server keeps no audio and logs no language per chunk.
- **What survives is the session-level learning-log row** (`transcribe`, `meta.audio_s` per
  detected language, no text). It covers the 4 Official-call sessions of 2026-10-12:

| Session (UTC) | Audio | Labelled | Models |
|---|---|---|---|
| 15:32–15:34 | 144 s | en 144 s | large-v3 |
| 15:38–15:39 | 120 s | **si 84 s, en 36 s** | small-sinhala + large-v3 |
| 16:00–16:03 | 360 s | en 360 s | large-v3 |
| 16:52–16:54 | 264 s | en 264 s | large-v3 |

- Only one session had any chunk labelled Sinhala; 2 of its 7 speech chunks (each up to 12 s)
  went to large-v3 as English. If Harsha and Shenika spoke Sinhala in the 16:00 or 16:52
  session, **every chunk was labelled en** and transcribed by large-v3 as English, which looks
  exactly like "English good, Sinhala poor". The si chunks of 15:38 still went to the old route
  (whisper-small-sinhala); the Qwen route only went live at 19:50.
- **From now on every chunk is logged** by the speech service: `transcribed … model= route=
  detected= p= probs=si:…,ta:…,en:… top=<large-v3's own top language>/<p> hint= mode=
  fallback= retried_with=`, and the same for compare. It never logs text or audio.
  `docker logs risime-whisper-whisper-1 | grep transcribed` shows them. The next real call
  answers this question directly.

**Detection on held-out clips** (per ≤ 15 s chunk, no hint, the production rule: si/ta/en
renormalised, p < 0.5 → large-v3):

| Set | Chunks | → si | → ta | → en | low p | large-v3's own top language |
|---|---|---|---|---|---|---|
| omni-si (spontaneous) | 312 | 98% | 0 | 2% (6) | 0 | si 303, en 3, te 2 |
| yt5-si (conversational) | 47 | 98% | 0 | 0 | 2% | si 44, **ml 3** |
| yt-si | 52 | 92% | 2% | 2% | 4% | si 45, ml 2, kn 2 |
| slr30-si (read) | 37 | 95% | 3% | 0 | 0 | si 33, my/ka/ta |
| fleurs-ta | 65 | 2% | 94% | 2% | 2% | ta 59 |
| fleurs-en | 26 | 0 | 0 | 92% (2 no speech) | 0 | en 24 |
| si+en synthetic | 6 | 67% | 0 | 33% | 0 | |
| ta+en synthetic | 6 | 0 | 0 | **100%** | 0 | |

- On clean Sinhala, large-v3's detection is right 92–98% of the time. It is not the main
  failure on these sets, even though it transcribes Sinhala badly.
- Detection is fragile, though. The smoke clip's own top language is Bengali (0.35), and it
  only routes to si because si/ta/en are renormalised. Phone audio (Opus, room noise, two
  voices) may well fall into "en" more often. The per-chunk log will show that.
- Mixed chunks go to whichever language dominates. Tamil+English went to English every time.

### 2. Language hints: hint vs auto-detect
`language_hint` (comma list, the languages expected on the call) is a new field on
`/v1/audio/transcriptions`, `/compare` and `/detect`. `WHISPER_HINT_MODE` (or `hint_mode` per
request) controls what it does:
- `off`: ignore the hint (the old behaviour).
- `prior`: detection is restricted to the hinted languages + en, and a hinted si/ta wins unless
  en reaches `WHISPER_HINT_EN_MIN_P` (0.9) after renormalising over those.
- `force`: a hinted si/ta always runs its model (Harsha's "run the Sinhala model whenever a
  Sinhala speaker is on the call").

A hint of only `en` changes nothing, so typing Singlish never blocks Sinhala detection.

**Sinhala hint** (CER / WER; same clips, same service):

| Set | auto (no hint) | hint si, **prior** | hint si, force |
|---|---|---|---|
| omni-si spontaneous (60) | 13.8% / 33.6% | **13.6% / 33.4%** | 13.3% / 33.1% |
| yt5-si conversational (47) | 23.6% / 43.4% | **21.8% / 42.5%** | 21.8% / 42.5% |
| slr30-si read (36) | 10.2% / 38.2% | **7.9% / 37.1%** | 7.9% / 37.1% |
| fleurs-en: English spoken in a "Sinhala" call (20) | 2.5% / 3.9% | **2.5% / 3.9%** | **99.1% / 99.1%** (Sinhala-script gibberish) |
| si+en synthetic (6) | 57.0% / 56.2% | 75.9% / 77.1% | 71.5% / 74.3% |

**Tamil hint** (tamil-asr-qwen3 could not load all evening because of the memory floor, so
whisper-tamil-medium answered: `fallback`):

| Set | auto | hint ta, **prior** | hint ta, force |
|---|---|---|---|
| fleurs-ta (50) | 18.0% / 34.9% | **15.6% / 32.1%** | 15.7% / 31.9% |
| fleurs-en (20) | 2.5% / 3.9% | **2.5% / 3.9%** | 100.7% / 100% |
| ta+en synthetic (6) | 50.9% / 41.7% (0% Tamil script: Tamil translated or dropped) | 53.9% / 61.6% (60% Tamil script) | 55.5% / 74.8% |

**Decision: `prior` wins and is the default (`WHISPER_HINT_MODE=prior`, deployed).**
- It fixes the Sinhala/Tamil chunks that detection sends elsewhere: −2.3 CER points on read
  Sinhala, −1.8 on conversational Sinhala, −2.4 on Tamil.
- It never costs English (fleurs-en is unchanged).
- `force` gains nothing over `prior` on Sinhala and destroys English: a Sinhala speaker's English
  sentence comes back as Sinhala-script syllables. So "always the Sinhala model" is not
  recommended. The hint should mean "Sinhala is likely here", not "Sinhala only".
- The synthetic si+en clips are worse with a hint (6 clips: a whole English sentence glued to a
  Sinhala one, which Qwen-si mangles). Real Singlish (English words inside Sinhala sentences,
  yt5-si) improves. Recordings will decide this.
- Without a hint, `prior` is identical to `off`, so production is unchanged until the app server
  sends hints.

**Server change needed for hints to reach the service** (server role; nothing done outside
infra here):
1. `RisiMe.Agent.Speech.Http.post_transcription/3` and `compare/2` add the multipart field
   `language_hint` from `opts[:language_hint]` (a list → `"si,en"`), omitted when empty.
2. `CallTranscribe.Call` computes the hint once per session and passes
   `Speech.transcribe(audio, language_hint: hint)` (and to `compare`). The hint is the union of:
   - each participant's `RisiMe.Agent.Lang.user_lang/1` (the script of their Official messages);
   - the conversation's language (`Lang.detect` over its recent Official messages);
   - the participants' app language, once the phones report it.

   Only si/ta matter; en is ignored by the service.
3. **App language is not on the server today.** The contract has no field for the phone's UI
   language. Sending it, e.g. an optional `app_lang` in device registration or the
   `call:transcribe` push, is a contract proposal for root/Harsha.

   Until then `user_lang` is the stand-in. It says "en" for people who type Singlish in Latin
   letters, so those calls still rely on detection.

### 3. kasunw/whisper-large-v3-sinhala (si forced + Sinhala prompt, CT2 int8)
Finished on the held-out sets: omni-si **20.1% / 49.4%**, yt5-si **35.6% / 60.2%**, slr30-si
**6.5% / 38.9%** (100% Sinhala script, nothing romanised, RTF 0.2–0.27).
- On spontaneous speech it ties whisper-small-sinhala (20.4%) and is far behind Qwen3-ASR-0.6B-Sinhala
  (13.3%).
- On conversational speech it is behind Qwen (21.2%) and omniASR-CTC-1B (30.2%).
- It drops English words (en kept 0%).

It is not a top-2 model, so it stays compare-only by name, outside the default compare set.

### 4. Compare sets (no models list; the routed model first)
- **si:** `qwen3-asr-0.6b-sinhala` (production), `omnilingual-ctc-1b` (the runner-up on the
  held-out conversational set, 30.2%, and best on read and on its in-domain spontaneous set),
  `whisper-small-sinhala` (resident anyway, cheap, the retry model). Unchanged; the top 2 were
  already there.
- **ta:** `tamil-asr-qwen3` (production), `whisper-tamil-medium` (the Tamil fine-tune, 15.5%),
  `omnilingual-ctc-1b`, `faster-whisper-large-v3`.
  - Fixed in asr5: compare now forces the decided language on every candidate. Before, only si
    chunks were forced, so large-v3 auto-detected on Tamil chunks; it now runs with
    `language=ta` next to the Tamil models.
- **en:** large-v3, turbo.

### 5. In-house recordings
None yet (`~/risime-eval-data/real/*/` holds only `sentences.txt`); the request in
docs/status/asr-eval.md stands.

## ASR eval 2026-10-12 (held-out sets; supersedes the 2026-10-10 Sinhala numbers)
Harsha's real call (2026-10-12): English good, Sinhala poor. The 2026-10-10 "14.5% WER" of
whisper-small-sinhala came from the SPEAK-ASR YouTube test, which may overlap its training data,
so this round scores every model **only on clips its training data doesn't contain**
(`in_training_of` in the manifest; `scripts/asr-eval`, `scripts/asr-eval-data.py`,
docs/status/asr-eval.md). Clips go through the speech service itself (same loaders, prompt and
language forcing as production), in ≤ 15 s chunks like call audio. WER/CER are corpus totals after
NFC, lower-case, punctuation and ZWJ removal. **Sinhala WER is inflated by word spacing**
(පිට කොටුවයි vs පිටකොටුවයි), so CER is the better Sinhala signal.
**No pilot clips:** the server keeps no call audio (decision 066). **No in-house recordings yet:**
the request is in docs/status/asr-eval.md; they become the deciding set when they arrive.

### Test sets (audio in ~/risime-eval-data, outside Git)
| Set | Clips | What | Held out for |
|---|---|---|---|
| `omni-si` | 60 (64 min) | `facebook/omnilingual-asr-corpus` sin_Sinh **test**: spontaneous answers to prompts, 1 speaker, CC BY 4.0 | every fine-tune; **in-domain** for omnilingual-ctc-1b (Meta trained on the corpus' train split) |
| `yt5-si` | 47 | SPEAK-ASR YouTube test, only the 5 channels Nerdstorm held out (Carnage, Newsfirst, Arogya, Anoma's Kitchen, Hera's Diary); conversational, half code-mixed | all except whisper-small-sinhala (its pool has 2% YouTube of unknown origin: excluded) |
| `yt-si` | 50 | SPEAK-ASR YouTube test (the 2026-10-10 set) | not for whisper-small-sinhala, Qwen3-ASR-0.6B-Sinhala (trained on 25 of its channels) |
| `slr30-si` | 36 (12 speakers) | OpenSLR SLR30, read sentences, CC BY-SA 4.0 | all evaluated models |
| `fleurs-ta` | 50 | FLEURS ta_in **test**, read, CC BY 4.0 | all (lemuralabs / vasista22 used FLEURS train/dev only) |
| `fleurs-en` | 20 | FLEURS en_us test | all |
| `synth-cs` | 6 si+en, 6 ta+en | **synthetic**: a si/ta clip + 0.3 s + an English clip, levelled to the same loudness (no real code-switched recordings exist yet) | all |

### Sinhala (CER / WER; – = in training data, excluded)
| Model (runtime, size) | omni-si spontaneous | yt5-si conversational | slr30-si read | yt-si | RTF | Licence |
|---|---|---|---|---|---|---|
| **Nerdstorm/Qwen3-ASR-0.6B-Sinhala** rev 2 (MLX 8-bit → bf16, transformers; 1.56 GB) | **13.3% / 33.1%** | **21.2% / 42.0%** (card: 20.5% CER on the same channels) | 8.9% / 38.2% | – | 0.20 | CC BY-SA 4.0 |
| facebook omniASR-CTC-1B (OpenVoiceOS ONNX int8, CPU; 1.03 GB) | 9.5% / 37.8% (in-domain) | 30.2% / 56.8% | 7.4% / 44.2% | 30.6% / 55.3% | 0.05 (CPU) | Apache-2.0 |
| SinhaSpeech/whisper-small-sinhala run11 (**routed until now**; CT2 fp16; 0.97 GB) | 20.4% / 37.4% | – (6.3% if counted: likely overlap) | **5.2% / 35.3%** | – (9.1%) | 0.03–0.08 | Apache-2.0 |
| SPEAK-ASR/faster-whisper-medium-si-exp10 (CT2 fp16; 1.53 GB) | 23.1% / 47.9% | 36.0% / 59.0% | 6.4% / 35.3% | 38.8% / 60.0% | 0.12 | **none stated** |
| janiduchamika/whisper-small-sinhala-general-185k (CT2 fp16; 0.97 GB) | 23.7% / 52.7% | – (not run) | 6.3% / 35.3% | 39.7% / 68.0% | 0.07 | Apache-2.0 |
| kasunw/whisper-large-v3-sinhala (CT2 int8; on disk since 10-10; finished 2026-10-13) | 20.1% / 49.4% | 35.6% / 60.2% | 6.5% / 38.9% | 35.8% / 54.8% (11 clips) | 0.27 | Apache-2.0 |
| faster-whisper large-v3 (`language=si` + prompt) | – | – | 88.3% / 107.4% | – | 2.2 | MIT |

**Reading:** on read speech the old route (small) is best, but calls are spontaneous. On the two
clean conversational sets Qwen3-ASR-0.6B-Sinhala is clearly ahead of every Whisper fine-tune
(omni-si CER 13.3% vs 20.4% for the routed small model; yt5-si 21.2% vs 30–36% for the others).
whisper-small-sinhala's 6–9% on the YouTube sets next to 20% on omni-si fits training overlap,
as Harsha suspected. **Nothing reaches ~20% WER** on spontaneous speech (best 33%) →
docs/status/asr-finetune-plan.md.

### Tamil (FLEURS ta_in test, read speech)
| Model | CER / WER | RTF | Licence |
|---|---|---|---|
| lemuralabs/tamil-asr-qwen3 (**routed**, 4.1 GB bf16, ~6.7 GB resident) | 15.2% / 32.6% | 0.36 | Apache-2.0 |
| vasista22/whisper-tamil-medium (CT2 int8 of mohankumars26; 0.78 GB) | 15.5% / 31.9% | 0.4 | Apache-2.0 |
| vasista22/whisper-tamil-small (CT2 fp16; 0.97 GB) | 15.8% / 31.8% | 0.6 | Apache-2.0 |
| omniASR-CTC-1B (ONNX int8, CPU) | 15.2% / 43.4% | 0.06 | Apache-2.0 |
| faster-whisper large-v3 (`language=ta`) | 21.6% / 57.4% | 0.6 | MIT |

**Reading:** tamil-asr-qwen3 and the 0.78 GB whisper-tamil-medium tie on FLEURS (15.2% vs
15.5% CER); both beat large-v3 (21.6%). The ta route stays (no eval reason to change it), and
whisper-tamil-medium becomes its **memory fallback** (tamil-asr-qwen3 could not even load in
the first smoke run tonight: MemAvailable floor) and a compare candidate. FLEURS is read speech:
Tamil recordings will decide between the two.

English (FLEURS en_us, 20 clips): large-v3 2.5% CER / 3.9% WER (unchanged, stays routed);
omniASR-CTC-1B 3.4% / 9.9% (no punctuation or case).

### Code-switching (synthetic si+en / ta+en, plus the real code-mixed yt5-si clips)
`en kept` = English words of the reference that come back in Latin letters.
- **whisper-small-sinhala (and the 185k model):** drops the English sentence entirely (en kept
  0%) or writes it as phonetic Sinhala ("මෙන්න small අතු රස්පියුන්බර්…" for "The smaller the
  Rossby number…"). Single English words inside Sinhala sentences mostly survive (yt-si).
- **Qwen3-ASR-0.6B-Sinhala:** keeps English words in English letters inside Sinhala sentences
  (yt5-si: en kept 53%, script 89% Sinhala as the references are). Synthetic si+en: 3 of 6 clips
  come back with the English sentence correctly in English; the other 3 turn it into Sinhala-script
  syllables that **loop** ("න්‍ර්මිය න්‍ර්මිය …") until the token cap (CER 71.5% on the set). The
  loop is a known Qwen3-ASR failure on out-of-language audio; the service caps tokens by length.
- **tamil-asr-qwen3:** writes English as **Tamil-script transliteration** ("ரீவ் கரண்ட்ஸ் …
  வேவ்ஸ் பிரேக்கிங்" for "Rip currents … waves breaking"), en kept 0%; one clip drops it.
- **omniASR-CTC-1B:** switches script with the speech: Sinhala in Sinhala, English in Latin (en
  kept 52% on synthetic si+en), but occasionally emits Tamil or Telugu letters inside Sinhala words
  (no language conditioning).
- **SPEAK-ASR medium:** transliterates part of the English (en kept 44%).
- **Tamil fine-tunes (small/medium):** drop the English sentence (en kept 0%).
- **`model=auto` (production routing):** the detector decides once per chunk. In the synthetic
  ta+en chunks it picked **en** every time, and large-v3 then dropped the Tamil or **translated it
  into English** ("As soon as these expectations came true, Britain and Germany…"). In si+en
  chunks it picked si 4×, en 2×: whatever language wins, the other half is lost. Real Singlish and
  Tanglish are mostly English words inside Sinhala/Tamil sentences, where the Sinhala route
  (Qwen) handles them; whole-sentence switches inside one chunk remain a gap (follow-up: shorter
  chunks or VAD-segment-level detection).

### Production change (decision 071 amendment c)
- `model=auto` routes **si → qwen3-asr-0.6b-sinhala** (forced "language Sinhala", the Sinhala
  prompt as context). whisper-small-sinhala stays loaded as its script retry and memory fallback
  (`WHISPER_KEEP`, `WHISPER_SI_RETRY_ORDER=whisper-small-sinhala,qwen3-asr-0.6b-sinhala`); a
  NoMemory on the routed model now falls back to the small model before large-v3.
- ta and en routes unchanged (tamil-asr-qwen3, large-v3). New: a NoMemory on tamil-asr-qwen3
  falls back to whisper-tamil-medium (`WHISPER_TA_FALLBACK`) before large-v3.
- Memory: large-v3 3.0 + small 1.0 + Qwen-si 3.5 + tamil-asr-qwen3 6.7 GB > the 11 GB budget, so
  a Tamil chunk evicts the Sinhala Qwen (reload 0.6–0.9 s from page cache) and vice versa; fine
  for the pilot, revisit if si and ta calls overlap often.
- Deployed 2026-10-12 ~19:50 UTC; `scripts/asr-smoke` PASS (si → Qwen CER 0% on the test clip,
  warm 2.1 s; ta → tamil-asr-qwen3; en → large-v3; compare per language; retry fixtures; no
  residual files).
- Compare (no models list) runs per detected language: si → Qwen, small, omniASR-CTC-1B;
  ta → tamil-asr-qwen3, whisper-tamil-medium, omniASR-CTC-1B, large-v3; en → large-v3, turbo.
- Rollback of the routing alone: `WHISPER_ROUTE_SI=whisper-small-sinhala` in `.env`; of the
  image: `image:` back to `…-asr3`.

### Over 2 GB: download list for Harsha's OK (nothing here was downloaded)
| Repo | Files (bytes) | Licence | Why |
|---|---|---|---|
| `TriLinSL/nemotron-3.5-asr-streaming-si-ta-en-210h` | `best_model/model.nemo` 2,490,736,640 | OpenMDW-1.1 | one streaming model for si + ta + en with code-switching; reported si 8.96% / ta 8.00% CER on its own held-out speakers; needs NeMo in the image |
| `facebook/omniASR-LLM-1B` | `omniASR-LLM-1B.pt` 9,103,136,284 | Apache-2.0 | the language-conditioned Omnilingual model (fixes the CTC's stray scripts); fairseq2 runtime |
| `facebook/omniASR-LLM-7B` | `omniASR-LLM-7B.pt` 31,205,087,103 | Apache-2.0 | the only published Sinhala CER (7.2%); reference ceiling, too big next to risi-l1 |
| `facebook/omniASR-CTC-1B` (or `aadel4/omniASR-CTC-1B-v2`, transformers port) | 3,900,517,028 (3,902,806,784) | Apache-2.0 | fp32 / GPU version of the CTC tested here in int8 on CPU |
| `openai/whisper-medium` + `SPEAK-ASR/whisper-si-exp-10-medium-all` (LoRA 119,186,168) | `model.safetensors` 3,055,544,304 | Apache-2.0 | SPEAK-ASR's medium trained on OpenSLR + YouTube (the exp10 tested here is OpenSLR only) |
| `vasista22/whisper-tamil-large-v2` | `pytorch_model.bin` 6,173,647,530 | Apache-2.0 | only if a Tamil Whisper must beat the 0.78 GB medium |
| OpenSLR 52 (fine-tune data) | 16 zips, ~14 GB | CC BY-SA 4.0 | replay data for the fine-tune plan |
Not needed: kasunw/whisper-large-v3-sinhala and lemuralabs/tamil-asr-qwen3 are already on disk.
No Sinhala exists in FLEURS, Common Voice (21 untranscribed clips), Qwen3-ASR base, SeamlessM4T,
Canary or MMS (`facebook/mms-1b-all` has no Sinhala adapter and is CC BY-NC).

## 0. Results (2026-10-10)

### Headline findings
1. **Most of Risi's failures come from the schema, not the model. Fixing it is a server change,
   not a model switch.**
   - The cause: the server sends the `risi_next_action` JSON schema Jason-encoded. That sorts the
     keys, so `args`/`answer` come before `tool` in every alternative.
   - vLLM's guided decoding writes properties in schema order. So the model has to start writing
     `{"a…` before it has named a tool, and almost always ends up in `final`.
   - The effect on live risi-l1, unchanged otherwise:
     - correct tool calls: **8%**;
     - false "I've set the alarm" claims with no tool call or card: **19**.
   - With `tool` first (`risi_eval.py --tool-first`), the same model gets **68%** correct and
     **0** false claims, at the same speed.
   - The fix belongs to the server role: emit `tool` first, e.g. with `Jason.OrderedObject` in
     `RisiMe.Agent.Tools.schema/2`. Every candidate was also run both ways.
2. **Speech:** whisper large-v3, which is live, is unusable for Sinhala (108% WER) and weak for
   Tamil (58% WER).
   - **SinhaSpeech/whisper-small-sinhala** gets **14.5% WER / 5.7% CER** on YouTube Sinhala
     (**superseded 2026-10-12**: likely training overlap; see "ASR eval 2026-10-12" above).
   - **lemuralabs/tamil-asr-qwen3**: see the ASR table.
3. **Memory decides what can be tested at all.**
   - With risi-l1 (47.4 GB), whisper (6.9 GB), Cassandra (6.8 GB) and the gates' Gradle daemons
     resident, the kernel's MemAvailable stayed at **22–38 GB** all day.
   - A candidate costs its `--gpu-memory-utilization` share **plus ~7 GB** (CUDA context, torch
     compile workers, the profile pass; measured with `eval/risi/memlog.py`, files in
     `results/loadtest/`).
   - With the 10 GB headroom rule, only models with a share of 20 GB or less ever fitted beside
     risi-l1 today.

### LLMs: the eval set (140 items) against Risi's real prompts
Each run ends in one of two states:
- **Done**: scored, and judged blind A/B by Claude subagents (see the Method section).
- **Blocked**: no memory window opened. A run is blocked when MemAvailable never reached the
  share + 7 GB overhead + 10 GB headroom while no gate was running.

| Model | Status | Peak memory GB | tok/s | TTFT s | JSON valid | Tool calls ok (as served / tool first) | False action claims (as served / tool first) | Summary + extract + ask ok | Other hallucinations | si / ta script ok | Blind A/B vs risi-l1, all 140 (W-T-L, mean judge score 1–5) | Blind A/B, tool-first turns (80) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **risi-l1** Qwen3.6-35B-A3B-FP8 (live) | done (read-only) | 47.4 resident | **50.9** | 0.23 | 100% | 8% / **68%** | 19 / **0** | 93% | 3 | 78% / 67% | baseline | baseline |
| gemma-4-12B-it QAT w4a16 | done | 20.2 | 24.9 | 0.12 | 99% | 67% / 68% | 0 / 0 | 95% | 3 | 83% / 90% | **80-32-28** (4.06 vs 2.88). Turns 55-8-17; summary/extract/ask 25-24-11 | 18-35-**27** (3.58 vs 3.77): risi-l1 better |
| gemma-4-12B-it FP8 | partial: 78/140, then killed by the 6 GB memory floor; a retry was killed while loading | 27.3 | 16.1 | 0.10 | (54%, errors) | (35%) / – | 4 / – | 97% of answered | 2 | 86% / 86% | not judged | – |
| gemma-4-26B-A4B AWQ 4-bit | **blocked** (needs 37 GB) | – | – | – | – | – | – | – | – | – | – | – |
| Qwen3.8-27B NVFP4 | **blocked** (needs 42 GB) | – | – | – | – | – | – | – | – | – | – | – |
| gemma-4-26B-A4B FP8 | **blocked** (needs 49 GB) | – | – | – | – | – | – | – | – | – | – | – |
| Qwen3.8-27B FP8 | **blocked** (needs 51 GB) | – | – | – | – | – | – | – | – | – | – | – |
| gemma-4-31B FP8 | **blocked** (needs 54 GB) | – | – | – | – | – | – | – | – | – | – | – |

How to read the table:
- **MemAvailable seen all day** (2026-10-10, 02:00–14:20 UTC) was 18–38 GB without a candidate.
  Release gates (nightly.48 ×3, several `ui-entry-test` runs) took most of the day.
- **Peak memory** is the drop in MemAvailable while the candidate was loaded. nvidia-smi
  per-process figures are in `results/gpu-mem.tsv`.
- **Speed** is single-stream with guided JSON. risi-l1 runs with CUDA graphs at 0.34 share; the
  12B models are dense, so they are slower than the 3B-active MoE.
- **Judge:** blind A/B by Claude (Opus 5.5) subagents with `eval/risi/JUDGE.md`. A/B was shuffled
  per item and the key was never shown to the judges. Verdicts and keys are in
  `results/pairs/`.

**What the judges saw:**
- **risi-l1 as served:**
  - It answers `final` and says "I've set it" for alarms, reminders and messages, so nothing
    happens.
  - It writes **English summaries for Sinhala and Tamil chats**, e.g. si-SU1, ta-SU1/2/3/6.
  - On F03 it mis-converts the UTC busy block.
- **gemma-12B-QAT:**
  - It calls tools even with the sorted-key schema, keeps Sinhala and Tamil in native script
    more often, and ignored the SU5 injection, which risi-l1 echoed in Sinhala.
  - But it often leaves times unresolved ("Friday 3pm"), loops on a few Tamil items (repeated
    list lines), stumbles on Singlish ("Badada" resolved to today), and is half as fast.
- **Shared failures:**
  - neither asks a question on "remind me at 6" (both guess 6 pm);
  - both miss the weekday repeat on T08;
  - both say "free all afternoon" despite the 14:00–15:00 block on some F03 items.

### ASR (50 Sinhala clips from SPEAK-ASR/youtube-sinhala-asr test, 50 Tamil clips from FLEURS ta_in test)
All transcriptions used a language hint. Text was NFC-normalised, lower-cased, and stripped of
punctuation and ZWJ.

| ASR model | Sinhala WER / CER | Tamil WER / CER | Real-time factor (si / ta) | Peak memory GB | Runtime |
|---|---|---|---|---|---|
| faster-whisper large-v3 (**live**, :8200) | 107.9% / 92.1% | 57.9% / 23.4% | 3.24 / 0.83 | 6.9 resident | CTranslate2 FP16 |
| faster-whisper large-v3-turbo | 124.5% / 97.2% (19 clips in the wrong script) | 81.1% / 37.8% | 0.49 / 0.12 | 3.1 | CTranslate2 FP16 |
| **SinhaSpeech/whisper-small-sinhala** | **14.5% / 5.7%** | – | 0.05 | 9.4 (vLLM share 6) | vLLM Whisper |
| kasunw/whisper-large-v3-sinhala | 62.3% / 37.6% | – | 0.23 | 11.3 | vLLM Whisper |
| **lemuralabs/tamil-asr-qwen3** | – | **35.8% / 19.2%**, partial: 17/50 clips before a release gate started (live whisper on the same 17: 64.9% / 30.6%) | 0.09 | 8.6 | vLLM Qwen3-ASR |

Caveats:
- **whisper-small-sinhala:** its training pool (SinhaSpeech/sinhala-asr-data) includes YouTube
  speech, so some overlap with the SPEAK-ASR YouTube test can't be ruled out. Confirm it on
  Harsha's 20 recordings (`eval/risi/speech/`).
- **Tamil:** FLEURS is read speech. Phone and call audio will be harder.

### Recommendation
**LLM: keep risi-l1 (Qwen3.6-35B-A3B-FP8), and ship the schema-order fix first (server role).**
- With `tool` first it gets 68% correct tool calls, 0 false claims and 51 tok/s.
- It beats the only fully tested candidate, gemma-4-12B QAT, in the blind tool-turn comparison
  (27-35-18), at twice the speed.
- Its real weakness is **language**: 22–33% of its Sinhala/Tamil answers and summaries come back
  in English. The QAT 12B does better there (83/90% native script; wins summary/extract/ask
  25-24-11). Two ways to fix it:
  - a prompt fix: "write in the chat's script: Sinhala → සිංහල, Tamil → தமிழ்";
  - later, per-task routing of `summarise`/`discussion_summarise` for si/ta chats to a Gemma-4
    model, but only after that model wins the same gate.
- The newer and bigger candidates are downloaded and verified, but **untested: they don't fit
  beside risi-l1 under the 10 GB headroom rule** today. They are Qwen3.8-27B (FP8/NVFP4) and
  Gemma-4 26B-A4B (FP8/AWQ) / 31B. No recommendation for or against them yet.

**Sinhala ASR: SinhaSpeech/whisper-small-sinhala.**
- 14.5% WER / 5.7% CER, against 108% for the live large-v3.
- Small (0.24B) and fast (RTF 0.05).
- Confirm it on Harsha's recordings first (possible data overlap).

**Tamil ASR: lemuralabs/tamil-asr-qwen3.**
- About half the live whisper's WER on the same clips (35.8% vs 64.9%; 17 clips, full 50-clip run
  pending).
- Keep large-v3 as the Tamil fallback until the full run confirms it.

### Do they fit together beside RisiMe on the GB10?
Unified memory is 121.6 GB. Measured 2026-10-10:
- nvidia-smi per process;
- `docker stats`;
- `systemctl --user show risime -p MemoryCurrent`.

| Component | GB |
|---|---|
| risi-l1 vLLM (0.34 share + CUDA) | 47.4 |
| Cassandra | 6.8 |
| Postgres | 0.3 |
| Monitoring stack (Prometheus, Grafana, Loki, Alloy, Alertmanager, exporters, Uptime Kuma, blackbox) | ~0.7 |
| RisiMe pilot (`risime.service`) | 0.1 |
| LiveKit + coturn | <0.1 |
| dockerd, kernel slab/page tables, OS | ~4 |
| **Base without speech** | **~59** |
| whisper-small-sinhala (vLLM, as measured) | 9.4 (likely ~2 as CTranslate2, unmeasured) |
| tamil-asr-qwen3 (vLLM, as measured) | 8.6 |
| **Proposed resident set** (the two new ASR models replace large-v3's 6.9) | **~77** |
| Release gates: 3 Redroid phones + Gradle/Kotlin daemons, measured as the MemAvailable drop during gates | 20–30 |

**Verdict:**
- **The proposed set fits.** It is about 77 GB resident, leaving about 44 GB, which still covers
  a release gate (20–30 GB) with ≥10 GB to spare.
- **Adding a second LLM does not fit while gates run.** gemma-12B-QAT at 20 GB would make it
  ~97 GB, so 97 + 25 is more than 121.6. The same goes for any of the 27–31B models.
- **A bigger LLM would have to replace risi-l1, not sit beside it.** That needs a switch window
  and Harsha's yes.

### Needs Harsha
1. **Approve the schema-order fix** (the server role implements it): `tool` first in the
   `risi_next_action` schema. It's the biggest quality gain found here: 8% → 68% correct tool
   calls, 19 → 0 false "done" claims on the live model.
2. **A test window for the 27–31B candidates.** They need 42–54 GB MemAvailable, i.e. no
   release/UI gates and the Gradle daemons stopped. Optionally, also stop the live whisper,
   which nothing uses yet (+6.9 GB). Never stop risi-l1 without a yes. Then run
   `RISI_EVAL_WAIT=1 scripts/risi-eval chain gemma4-26b-a4b-awq qwen38-27b-nvfp4 gemma4-26b-a4b-fp8 qwen38-27b-fp8 gemma4-31b-fp8`
   and `LANGS=ta scripts/risi-eval asr tamil-asr-qwen3` (the full Tamil run).
3. **Recordings:** 20 Sinhala + 20 Tamil clips per `eval/risi/speech/README.md`.
4. **Review the Sinhala/Tamil wording** (items marked `review` in `eval/risi/*.jsonl`):
   - si-T04 (හවස 3), si-T17 (අවන්හල), si-F02, si-SU2;
   - ta-T05, ta-T13, ta-F02, ta-SU6;
   - speech si line 15, ta line 11.
5. **Gated or skipped:**
   - ai4bharat/indic-conformer-600m (gated);
   - CohereLabs tiny-aya (gated, non-commercial);
   - Common Voice (left HF);
   - FLEURS has no Sinhala;
   - Nerdstorm/Qwen3-ASR-0.6B-Sinhala (MLX/OpenVINO only; needs an FP16 export to test).

### Safety record (2026-10-10)
- **risi-l1 (risime-llm-vllm-1) was never touched:** running since 10-08, 0 restarts. It only
  served read-only eval requests (sequential, one at a time).
- **Every live probe passed** (risi-l1 `/health` + a completion + pilot `/health`) before, every
  60 s during, and after each candidate.
- **The memory floor worked:** it killed gemma-4-12B-FP8 twice, at MemAvailable 5.9 GB and
  5.6 GB, before risi-l1 was affected. The measured overhead then went into the budget.
- **Every `scripts/risi-canary` run passed** (`_eval` instance; at least 4 runs, 04:22–10:50 UTC).
  - It ran before and after the candidates.
  - The run *during* each candidate was skipped every time: with a candidate loaded,
    MemAvailable was below the 18 GB the canary's Gradle + server need. The 60-s live probe
    covered those windows. This is a deviation from the "during" rule, recorded here.
- **Gates:** candidates waited for every `nightly-release` / `ui-entry-test`, and each one
  yielded (was killed) the moment a gate started. That's why the Tamil ASR run is partial.
- **The pilot `/health` watch** (every 30 s, pausing while it takes > 1 s) has been active since
  08:45 UTC. It never triggered.
- **Weights are all kept** in `~/risime-eval-models` (~158 GB), verified against HF
  (`docs/status/downloads.md`). No downloads after Harsha's stop.

### How to rerun
```
scripts/risi-eval dump && scripts/risi-eval build   # Risi's real prompts -> eval set
scripts/risi-eval baseline                          # live risi-l1, read-only
scripts/risi-eval candidate <name>                  # eval/risi/candidates.json; RISI_EVAL_WAIT=1 to wait for gates/memory
scripts/risi-eval asr live | asr <name>             # ASR (LANGS=si|ta)
python3 -I eval/risi/risi_eval.py pairs risi-l1 <name>   # blind pairs, then judge with eval/risi/JUDGE.md
python3 -I eval/risi/risi_eval.py unblind risi-l1 <name>
scripts/risi-eval report                            # eval/risi/results/REPORT.md
```

## 1. Hugging Face survey (checked 2026-10-10 against the public HF API, no token)
Notes on the numbers:
- **Disk** is the sum of the weight files in GB.
- **Serve** is weights plus KV for 8k context × 4 sequences plus about 3 GB of activations. It's
  the `mem_gb` used for launch decisions.
- **vLLM** means `config.json` `architectures` is in the registry of our image
  (`nvcr.io/nvidia/vllm:26.09-py3` = vLLM 0.29.0, transformers 5.16.1).
- Every repo listed is public and ungated unless marked.

### LLMs
| Repo | Date | Licence | Params (active) | Disk GB | Serve GB | vLLM arch | Worth testing |
|---|---|---|---|---|---|---|---|
| Qwen/Qwen3.6-35B-A3B-FP8 (**live risi-l1**) | 2026-04 | apache-2.0 | 36B (3B) MoE | 37.5 | 47.4 measured (0.34 share) | Qwen3_5MoeForConditionalGeneration ✓ | baseline |
| **Qwen/Qwen3.8-27B-FP8** | 2026-08-13 | apache-2.0 | 27.8B dense | 30.9 | ~38 | Qwen3_5ForConditionalGeneration ✓ | **yes**: the newest official Qwen text model that fits |
| RadixArk/Qwen3.8-27B-NVFP4 | 2026-08-14 | apache-2.0 | 27.8B | 21.9 | ~29 | same ✓ (NVFP4 on GB10 unverified) | fallback if memory is tight |
| Qwen/Qwen3.8-Flash-Next-FP8 | 2026-08-24 | qwen-community | 180B (6B) | 185.5 | — | Qwen4ExpForConditionalGeneration ✓ | no: doesn't fit |
| Qwen/Qwen3.8-2.4T-A95B-FP8 | 2026-08-08 | other | 2.45T (95B) | 2496 | — | ✓ | no |
| "Qwen3.7", "Qwen4" | — | — | — | — | — | — | **don't exist** as official weights. The `Qwen4Exp` arch is only Flash-Next; other Qwen4 repos are test fixtures |
| **RedHatAI/gemma-4-26B-A4B-it-FP8-dynamic** | 2026-04-06 | apache-2.0 | 25.8B (3.8B) MoE | 28.6 | ~34 | Gemma4ForConditionalGeneration ✓ | **yes**: fast MoE, "140+ languages" pre-training |
| **RedHatAI/gemma-4-31B-it-FP8-block** | 2026-04-03 | apache-2.0 | 31.3B dense | 33.3 | ~42 | ✓ | **yes**: the strongest Gemma that fits at rest |
| **RedHatAI/gemma-4-12B-it-FP8-Dynamic** | 2026-06-08 | apache-2.0 | 13B dense (+audio) | 15.0 | ~20 | Gemma4UnifiedForConditionalGeneration ✓ | **yes**: small, and has audio input |
| google/gemma-4-31B-it-qat-w4a16-ct | 2026-06-04 | apache-2.0 | 31.3B | 23.3 | ~29 | ✓ | alternative for a 22 GB budget |
| Qwen/Qwen3-Omni-30B-A3B-Instruct (+ FP8/AWQ community builds) | 2025-09 | other | 35B (3B) | 70.5 (FP8 37.4) | ~40 | Qwen3OmniMoeForConditionalGeneration ✓ | no: its 19 speech-input languages don't include si or ta. No newer Qwen Omni exists |
| nvidia/Nemotron-3-Nano-Omni-30B-A3B-Reasoning-FP8 | 2026-04-24 | other | 33B | 35.2 | ~37 | ✓ | low: si/ta unverified |
| sarvamai/sarvam-30b-fp8 | 2026-03-26 | apache-2.0 | 32B (2.4B) | 38.6 | ~40 | SarvamMoEForCausalLM ✓ (card wants a fork) | low: 22 Indian languages incl. Tamil, **no Sinhala** |
| polyglots/SinLlama_v01 | 2025-08 | Llama 3 licence | LoRA on Llama-3-8B (gated base) | 7.5 adapter | — | LoRA | no: a **base** model (not instruct) with Sinhala classification evals only; merged copies (SAWithanage/…-Merged, 16 GB) are the same |
| openai/gpt-oss-120b / -20b | 2025-08 | apache-2.0 | 117B (5.1B) / 21B (3.6B) | 65.3 / 13.8 | ~68 / ~15 | GptOssForCausalLM ✓ | 120b doesn't fit beside risi-l1; 20b has no si/ta claim |
| CohereLabs/tiny-aya-* | 2026-02 | cc-by-nc-4.0 | 3.4B | 6.7 | — | ? | **gated** + non-commercial: skipped |

There is no recent, instruct-tuned, Sinhala-specific LLM in the 15–55 GB range on HF. The
realistic options are general multilingual models.

### Speech (ASR)
| Repo | Date | Licence | Size | Serving | Reported |
|---|---|---|---|---|---|
| openai/whisper-large-v3 (**live** as faster-whisper on :8200) | 2023-11 | apache-2.0 | 1.5B, 3.1 GB fp16 | CTranslate2 image / vLLM Whisper ✓ | see results |
| openai/whisper-large-v3-turbo (local CT2 copy) | 2024-10 | mit | 0.8B | same | — |
| **SinhaSpeech/whisper-small-sinhala-v6-e6-run11-best** | 2026-09-30 | apache-2.0 | 0.24B, 0.97 GB | vLLM Whisper ✓ | 16.4 WER / 4.4 CER on its own test |
| kasunw/whisper-large-v3-sinhala | 2024-12 / 2026-09 | apache-2.0 | 1.5B, 6.2 GB fp32 | vLLM Whisper ✓ | none (trained on OpenSLR 52) |
| Nerdstorm/Qwen3-ASR-0.6B-Sinhala-8bit | 2026-09-26 | cc-by-sa-4.0 | 1.0 GB MLX | **MLX/OpenVINO only**: needs an FP16 export | 6.9 CER (SLR52), 20.5 CER (YouTube) |
| **lemuralabs/tamil-asr-qwen3** | 2026-07 | apache-2.0 | 2B, 4.1 GB | Qwen3ASRForConditionalGeneration ✓ | FLEURS-ta 25.3 WER / 8.0 CER |
| vasista22/whisper-tamil-large-v2 | 2023-01 | apache-2.0 | 6.2 GB | Whisper ✓ | FLEURS-ta 7.5 WER (different normaliser) |
| Qwen/Qwen3-ASR-1.7B | 2026-01 | apache-2.0 | 4.7 GB | ✓ | si/ta **not** among its 30 languages |
| ai4bharat/indic-conformer-600m-multilingual | 2025-03 | mit | — | NeMo/ONNX | **gated**: skipped; Tamil only |
| facebook/mms-1b-all, seamless-m4t-v2 | 2023 | cc-by-nc-4.0 | — | not vLLM | non-commercial; MMS has no Sinhala adapter |

**Test sets:**
- **FLEURS has no Sinhala** (`si_lk` is 404). Tamil is `google/fleurs` `ta_in` test (CC BY 4.0).
- **Common Voice has left HF** (Mozilla Data Collective since 2025-10).
- **Sinhala:** `SPEAK-ASR/youtube-sinhala-asr` test (405 rows, YouTube speech, code-mixed flags;
  the repo states no licence: OK for internal eval, not for redistribution).

<!-- METHOD -->
## 2. Method

### Eval set (`eval/risi/`)
| File | Items | What |
|---|---|---|
| `si.jsonl`, `ta.jsonl`, `en.jsonl` | 40 each | The same 40 scenarios in native Sinhala, Tamil and English (names localised) |
| `mixed.jsonl` | 20 | Singlish and Tanglish: romanised, and native script mixed with English |

Each line holds:
- `id`, `lang`, `category`;
- `messages`: the real system prompt and the user message, rendered the way the server renders
  it;
- `schema`: a path into `prompts.json`;
- `expect`: an expected tool and args, or a rubric;
- `review`: for wording Harsha should check.

**The prompts are Risi's own.** `eval/risi/prompts.json` is dumped from the compiled server
(`scripts/risi-eval dump`, `RisiMe.Agent.Turn.system/2`, `Tools.schema/2`, `Prompts.*`):
- the §25 `risi_next_action` turn with the real tool registry (capabilities, set_reminder,
  calendar_check/add, risi_calendar_check/add, set_alarm, schedule_message, cancel_scheduled)
  and its JSON schema;
- the `ask` answer prompt;
- the chat summary;
- the commitment extraction.

Requests use the server's settings: guided JSON (`response_format: json_schema`, strict),
temperature 0.2, top_p 0.8, thinking off. The fixed clock is Monday 2026-10-12 09:15,
Asia/Colombo.

| Category (per language) | n | Checks |
|---|---|---|
| Tool calls (T01–T15): alarm, "wake me at 6 and message Kumu good morning", reminders (me / the chat / relative "in 20 minutes"), calendar check/add (Risi and phone calendars), weekday alarm, scheduled and daily messages, cancel | 15 | Tool name and args. Times are resolved like the server's `TimePhrase` (ISO or an English phrase; a bare "6" is ambiguous) and compared with the expected local time. A `final` whose `draft` fills the action card also counts (§25.1) |
| Honesty (T16 search, T17 booking, F04 unreadable calendar) | 3 | No false claim; says what it can't do. F04: `read_ok: false` must never become "you're free" |
| Missing info (T12 "remind me at 6") | 1 | One short question, no guessed reminder |
| Capabilities / small talk (T11, C1) | 2 | Tool `capabilities` or a correct list; short reply |
| Follow-ups (F01 second step after the alarm card, F02 "no, make it 7" over a pending draft, F03 a calendar result with a busy block) | 3 | Next tool or args; correct use of the tool result |
| Summaries (SU1–SU6): release chat, team lunch, invoice error, greetings only, a prompt injection, website launch | 6 | Key facts present (day, owner, amount); no decisions or action items invented for small talk; injection not followed |
| Commitment extraction (E1–E6) | 6 | Owner + due date exactly; no commitment from questions, wishes, things already done or old lines |
| Questions about the chat / unknowns / injection (A1–A6) | 6 | Right fact + refs; "Wi-Fi password", "who won the cricket" → honest, nothing invented; no system-prompt leak |

### Metrics
- **JSON valid**: parses and validates against the schema.
- **Tool-call ok**: on items that expect a tool, the right tool with the right args (or an
  equivalent draft).
- **Task ok**: the summary, extraction and ask checks.
- **Hallucinations**: counted automatically.
  - a false action claim ("I've set an alarm" without a tool call or card);
  - invented refs/sources;
  - extra (invented) commitments;
  - decisions or action items invented for small talk;
  - forbidden content (an invented score or amount, "booked", a followed injection).

  The blind judge also flags hallucinations.
- **Script correctness** for si/ta answers is computed over the letters of the answer.
  - ok: at least 50% in the target script.
  - Latin: at least 80% Latin, i.e. an English or romanised reply.
  - wrong script: at least 5% of another Indic or foreign script, e.g. Tamil in a Sinhala answer.
  - mixed: anything else.
- **Speed**: decode tokens/s = (completion tokens − 1) / (total − TTFT), single stream, median;
  TTFT median; streaming.
- **Peak memory**: MemAvailable just before the launch, minus its minimum while the candidate was
  loaded and running, sampled every 2 s. For unified memory this is the real cost to the box.
- **Quality**: a blind side-by-side against risi-l1 on every item. A and B are shuffled with a
  fixed seed (`risi_eval.py pairs`), and the key is kept away from the judge. The rubric:
  correctness against the expected outcome, faithfulness (no invention), language and script
  match, helpfulness and brevity, 1–5 per side, and a winner. **The judge is Claude (Opus 5.5)
  subagents, not a human,** recorded as such.

### Safety during the runs (Harsha's rules)
- One candidate at a time: `risi-eval-<name>` on 127.0.0.1:8101, same image as risi-l1.
- Stopped and removed right after its test (a trap).
- Never while `nightly-release` or `ui-entry-test` runs; a candidate also yields at once if one
  starts.
- Launched only if MemAvailable ≥ serve GB + 10 GB. `--gpu-memory-utilization` = serve GB /
  MemTotal, `--max-model-len 8192`, `--max-num-seqs 4`. Killed if MemAvailable < 6 GB.
- Live risi-l1 is only read: sequential requests.
- `scripts/risi-eval probe` (risi-l1 `/health` + a 3-token completion + pilot `/health`) runs
  before, every 60 s during, and after every candidate. A failure kills the candidate.
- `scripts/risi-canary` (its own instance `_eval`, ports 4390/8490; it never touches :4000 or
  :8100) runs before the chain, once while each candidate is loaded, and after.
