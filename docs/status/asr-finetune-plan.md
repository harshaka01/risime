# Sinhala ASR fine-tune plan (root, 2026-10-12, plan only, nothing trained)

**Why:** no candidate reaches ~20% WER on held-out Sinhala. The best result on spontaneous
held-out speech is Qwen3-ASR-0.6B-Sinhala at 33% WER / 13% CER (omnilingual `sin_Sinh` test,
see docs/RISI-MODELS.md, "ASR eval 2026-10-12"). Read speech is easier (5–9% CER), but calls are
spontaneous, so it doesn't count. Part of the Sinhala WER is word spacing (පිට කොටුවයි vs
පිටකොටුවයි is one substitution plus one insertion), which is why CER is tracked next to it.
Real recordings (docs/status/asr-eval.md) will replace these estimates once they exist.

## Base model
**Nerdstorm/Qwen3-ASR-0.6B-Sinhala (rev 2), dequantised to bf16** (already converted:
`~/risime-models/qwen3-asr-0.6b-sinhala`, 1.56 GB).
- It is the best held-out spontaneous result, writes English words in English letters
  (code-switching), and its whole recipe is public (Nerdstorm/LiveTranscribe-Sinhala:
  trainer, data prep, a runbook).
- It is small: 0.9B parameters, so a **full fine-tune fits** on the GB10 next to risi-l1.
- Licence: **CC BY-SA 4.0** (because of OpenSLR 52). Fine for our own service. A derived model
  we published would have to be CC BY-SA too. Its YouTube data was used "with its authors'
  permission"; we would not reuse that data without asking SPEAK-ASR ourselves.
- **Fallback base:** Meta omniASR-CTC-1B (Apache-2.0, ONNX int8 works on CPU today), or
  `Qwen/Qwen3-ASR-0.6B` (Apache-2.0) if the CC BY-SA terms are a problem; that one starts without
  Sinhala, so it needs the full OpenSLR 52 pass first (Nerdstorm's rev 1: one epoch).

## Data
| Set | Hours | Licence | Use | Download |
|---|---|---|---|---|
| OpenSLR 52 (Large Sinhala ASR, read) | 185k utterances (478 speakers) | CC BY-SA 4.0 | already in the base; a small replay share only | 16 zips, ~14 GB: **needs Harsha's OK** (> 2 GB) |
| `facebook/omnilingual-asr-corpus` `sin_Sinh` train + dev | ~9 h + ~2.5 h (876 MB + 231 MB; the test split is 1.9 h in 179 MB) | CC BY 4.0 | **main new data**: spontaneous, prompted, natural pace | ≤ 2 GB each, allowed |
| Our consented recordings (docs/status/asr-eval.md) | ~0.2 h now | in-house | **test only** (never train on the eval set) | – |
| Consented call audio (later, opt-in) | grows | in-house, explicit consent | the real domain; needs a product decision (the server keeps no audio today, decision 066) | – |
| SPEAK-ASR/youtube-sinhala-asr train | 7.4 h | **none stated** | only with SPEAK-ASR's written permission | 713 MB |
| `TriLingDATA/si-ta-en-asr` (OpenSLR si + CV22 ta + LibriTTS-R) | 210 h | "other" | si/ta/en code-switching; read the licence first | ~10 GB+: needs OK |
| FLEURS / Common Voice Sinhala | – | – | **don't exist** (FLEURS has no si_lk; CV si is 21 untranscribed clips) | – |

Augmentation for call audio: re-encode every training clip through **Opus 16 kHz, 24 kbit/s,
`-application voip`** (exactly what the call path sends), plus room noise at 10–20 dB SNR and
random 0.8–1.2 speed. Keep the code-switched English in English letters, as Nerdstorm's
transcripts do.

Held-out for the eval after training: our recordings, `yt5-si` (5 YouTube channels nobody trains
on), SLR30 (read), and a few omnilingual **test** clips only if the train split was used (then
they are in-domain: report separately).

## Compute on spark2 (GB10, unified memory)
- **Full fine-tune, bf16 with fp32 master weights, AdamW:** about 0.9B × 14 bytes ≈ 13 GB for
  weights + optimiser, plus 6–10 GB activations at 16–32 utterances per step with gradient
  checkpointing: **~20–25 GB peak**. LoRA (r=64 on the decoder + top encoder layers) cuts this to
  **~6–8 GB** at a small quality cost.
- **Time:** the omnilingual train split (~10 h audio, ~10k utterances) × 3 epochs ≈ 30 h of audio:
  ~1–2 GPU hours on the GB10 (Nerdstorm's comparable 3-epoch conversation pass ran on a laptop).
  A replay pass over 10–20k OpenSLR 52 utterances adds ~1 h.
- **risi-l1 impact:** risi-l1 keeps its 47.5 GB GPU share; training competes for the same GPU
  SMs (its tok/s drops while a step runs) and for the remaining unified memory. Today's
  MemAvailable swung between 2 and 18 GB with Gradle/redroid gates. So:
  - run **only in a quiet window** (no `nightly-release`, `ui-entry-test` or redroid gate; Gradle
    daemons stopped): the same rule as the 2026-10-10 LLM candidates;
  - a memory watchdog (as `eval/risi/memlog.py`) stops training when MemAvailable < 6 GB;
  - LoRA first (fits beside a gate), full fine-tune only in a long quiet window;
  - never touch the risi-l1 container; its canary (`scripts/risi-canary`) before and after.

## Expected gain (honest)
- Nerdstorm's rev 1 → rev 2 (7.4 h of conversation added) took conversation CER from 33% to
  20% on its held-out channels. Adding ~10 h of spontaneous speech plus call-codec augmentation
  should give a similar step on call-like audio: **omnilingual-style CER ~13% → ~9–10%, WER
  ~33% → ~25%**. Reaching **~20% WER** most likely needs **20–50 h** of in-domain conversational
  speech (consented calls) and a spacing-tolerant reference convention.
- English mixed into Sinhala should stay in English letters (both data sets write it so).
- No change for Tamil or English: they keep their own models.

## Steps (when Harsha says go)
1. Download the omnilingual `sin_Sinh` train + dev splits (≤ 2 GB each, logged).
2. Port Nerdstorm's trainer to torch + transformers (`Qwen3ASRForConditionalGeneration` is in
   the speech image; their MLX trainer is the reference for the exact features and prompts).
3. LoRA run in a quiet window → `scripts/asr-eval` on yt5-si, slr30-si, real recordings.
4. If it beats the routed model on the held-out sets: put it in the compare set, then route `si`
   to it (decision 071 amendment), like amendment c.
