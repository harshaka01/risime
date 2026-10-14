# Sinhala + Singlish ASR fine-tune plan: Qwen3-ASR-1.7B and omniASR-LLM (root, 2026-10-14, plan only)

Harsha (2026-10-14): "Qwen3-ASR-1.7B has no Sinhala. Write a plan to fine-tune it (and omniASR-LLM) on
Sinhala + Singlish, starting from public data now (OpenSLR, the omnilingual corpus train split, FLEURS si)
on the spark2 night GPU. Our own pilot audio joins once I decide on voice training data. Include the GPU
time and the expected gain."

This page extends `docs/status/asr-finetune-plan.md` (2026-10-12, base: Qwen3-ASR-0.6B-Sinhala) and uses
the numbers in `docs/status/asr-omni-eval-2026-10-14.md`. **Nothing has been downloaded or trained for this
page.** All sizes below come from Hugging Face / OpenSLR metadata (API listings, datasets-server
statistics), checked 2026-10-14.

## TL;DR
- **FLEURS has no Sinhala.** `google/fleurs` has 102 configs and none is Sinhala (`sl_si` is Slovenian).
  The public Sinhala data is OpenSLR 52 (read), OpenSLR 30 (read, already our eval set) and the
  omnilingual corpus `sin_Sinh` (spontaneous, but only **8.4 h from 5 speakers** in train).
- **None of the licensed public data has English in Latin script.** The omnilingual transcripts write
  English words in Sinhala script; this is why omniASR writes "ඔටෝමේෂන්ස්" for "automations". In our
  60 omni-si references, 0 contain a Latin word. Singlish is fixed by data, not by the model: we need
  Latin-English transcripts (SPEAK-ASR YouTube with written permission, Latinised omnilingual transcripts
  (an experiment), and later pilot audio).
- **Memory, not FLOPs, is the limit.** On the GB10, risi-l1 holds 47.5 GB and MemAvailable swings from 2 to
  18 GB (4.8 GB while this was written). A **full fine-tune of a ~2.3B model (about 38 GB of training
  state) does not fit next to risi-l1.** On spark2 we can only do **LoRA** (about 10–14 GB peak),
  paused by a memory watchdog. A full fine-tune would need an external GPU (a decision for Harsha;
  public data only, never pilot audio).
- **GPU time (an estimate; no benchmark was run, below):** about 3–5 GPU minutes per hour of audio per
  epoch with LoRA on a ~2.3B model. The whole programme comes to **~30–45 GPU hours**, or about **7–10
  nights** at 4–6 usable GPU hours a night.
- **Expected gain (honest):** Qwen3-ASR-1.7B trained only on licensed public data (OpenSLR 52 +
  omnilingual) will probably **not** beat today's production Sinhala model on broadcast speech or Singlish,
  because that model's edge comes from conversational, code-switched data that we may not reuse without
  permission. With SPEAK-ASR's permission, or with pilot audio, **1.7B is the best bet for a real step**:
  about **yt5-si CER 21% → 16–19%** and **Singlish CER 27.5% → 20–24%**, with wide error bars. omniASR-LLM-1B
  can mainly be fixed on **English-in-Latin and script drift**. Its Sinhala is already trained on the
  same omnilingual data, so that data adds little.
- **Downloads needing Harsha's OK (> 2 GB):** `Qwen/Qwen3-ASR-1.7B` (4.70 GB) and OpenSLR 52 (16 zips,
  14.7 GB). Everything else is either on disk or under 2 GB.

## 1. Data

| Set | Exact source | Size (metadata) | Licence | How we use it |
|---|---|---|---|---|
| **OpenSLR 52**, Large Sinhala ASR (read, crowd-sourced) | https://www.openslr.org/52/ : `asr_sinhala_{0-9,a-f}.zip` + `utt_spk_text.tsv` | 16 zips of 901–938 MB = **14.7 GB**; **~185k utterances**, 478 speakers; about 200 h (an estimate from the count at ~4 s each; the page gives no hours) | **CC BY-SA 4.0** | **Stage A for Qwen3-ASR-1.7B** (teaches it Sinhala; that is how Nerdstorm's 0.6B rev 1 was made). For omniASR, a 10–20k-utterance replay share only. Text-dedupe against slr30-si refs (below) |
| **Omnilingual ASR corpus** `facebook/omnilingual-asr-corpus`, config `sin_Sinh` | `data/sin_Sinh/train-0000{0,1}-of-00002.parquet`, `dev-…`, `test-…` | train **426 rows, 8.37 h, 5 speakers** (876 MB); dev 90 rows, 1.69 h, 1 speaker (231 MB); test 111 rows, 1.92 h, 1 speaker (179 MB, already on disk as omni-si) | **CC BY 4.0** | **Stage B: the spontaneous data.** Strip `<hesitation>` tags. Rows are ~70 s each, so split them into ≤ 30-s segments (forced alignment, or the line breaks in `raw_text`, which are sentence-level). The dev split is for checkpoint selection; the test split is never used for training. **It is already in omniASR's training data**, so it adds little to omniASR |
| **FLEURS si** | – | **does not exist** (`google/fleurs` configs checked: 102 languages, no `si_lk`) | – | none. FLEURS stays only as ta/en eval sets |
| OpenSLR 30 (read, TTS speakers) | already on disk as `slr30-si` | 36 eval clips | CC BY-SA 4.0 | **eval only** |
| SPEAK-ASR/youtube-sinhala-asr | HF dataset, 4,037 rows, 0.9 GB parquet; train ~7.4 h | under 2 GB | **none stated** | **The only public real Singlish with English in Latin script.** Train on it **only with SPEAK-ASR's written permission** (ask now; it costs nothing). Without permission it stays eval-only (yt-si, yt5-si, singlish-real) |
| TriLingDATA/si-ta-en-asr | HF dataset, 398,974 rows | **78.5 GB** parquet | "other" (unclear) | **not recommended**: its Sinhala part is mostly OpenSLR, and the licence is unclear |
| **Pilot audio (later)** | consented clips, after Harsha's voice-training-data decision | grows | in-house, explicit consent | the real domain (calls, voice notes, Opus 16 kHz). Never leaves spark2 and never goes to an external GPU. Eval-first: the first ≥ 20 clips per language become the golden set (decision 085) before anything trains |

### OpenSLR 52 and CC BY-SA: what it means for us
- The **share-alike** condition applies when we *share* "Adapted Material". Whether model weights trained
  on a dataset are Adapted Material is legally unsettled. The conservative reading (Nerdstorm took it,
  and we should too) is that a model trained on SLR52 is CC BY-SA 4.0.
- **Running it on our own server is fine.** Serving transcripts is not sharing the weights, and the licence
  has no network clause like AGPL's. The transcripts we produce are not the model.
- **What it restricts:**
  - If we ever *distribute* the weights, for example an on-device ASR model inside the APK (the
    on-device agent direction) or publishing them, the weights must go out under CC BY-SA with
    attribution, and anyone could extract and reuse them. We could not put a proprietary licence on
    that model.
  - A model fine-tuned on *pilot audio* on top of an SLR52-derived base inherits the same terms when
    shared. We should never distribute a pilot-trained model anyway, because it can memorise speech.
- **Nothing changes today:** the production Sinhala model (Nerdstorm Qwen3-ASR-0.6B-Sinhala) is already
  CC BY-SA. For an on-device or redistributable model, take the **omniASR route** (Apache-2.0 base;
  it already knows Sinhala, so it doesn't need SLR52) or train Qwen3-ASR on CC BY / in-house data only.
  This is not legal advice; if we ever plan to distribute weights, get it checked.

### Data rules for every training set
- **Call-codec augmentation:** each training clip goes through Opus 16 kHz, 24 kbit/s
  `-application voip` (the call path) with probability 0.5, plus room noise at 10–20 dB SNR and 0.9–1.1
  speed perturbation (from the 2026-10-12 plan).
- **Transcript convention (Singlish):** Sinhala in Sinhala script; **English words in Latin script** as
  spoken ("AI එකට", "meeting එක"); digits as digits when the speaker reads a number. This is the
  convention of our references (singlish-real) and of SPEAK-ASR.
- **Latinising the omnilingual transcripts (experiment L):**
  - risi-l1 proposes, for each Sinhala-script token, whether it is an English loanword and its Latin
    form ("ඔටෝමේෂන්ස්" → "automations").
  - A dictionary of accepted pairs is built, and a human checks a 100-segment sample before any training
    use. Plain Sinhala words stay untouched.
  - This is Index-relevant (Singlish "en kept" today: production 53%, omniASR 0%), but it is a guess at
    how the speaker meant the word, so it is measured on singlish-real, never assumed.
- **Dedupe:** drop any training utterance whose normalised text equals, or shares an 8-gram with, any
  eval reference (all.jsonl, singlish-real). SLR52 and SLR30 both read Google's prompt sentences, so
  overlap is likely and must be removed.

## 2. Base models

| Model | Params (checked) | Download | Licence | Sinhala today | Note |
|---|---|---|---|---|---|
| **Qwen/Qwen3-ASR-1.7B** | **2.35B** total (safetensors metadata; the "1.7B" is the LLM part), bf16 | 2 shards, **4.70 GB** (needs OK) | Apache-2.0 | **none** (30 languages + 22 Chinese dialects; no si/ta) | Strongest open ASR in its size class. Its byte-level BPE has few Sinhala merges, so Sinhala text costs more tokens per character, which means slower decoding and longer training sequences. Measure the tokens per second of audio in E0 |
| Qwen/Qwen3-ASR-0.6B (reference) | 0.94B | 1.88 GB (no OK needed) | Apache-2.0 | none | Nerdstorm's base. Our production model is its Sinhala fine-tune (on disk, 1.56 GB) |
| **facebook/omniASR-LLM-1B** | **2.28B** (wav2vec2 1B encoder + LLM decoder) | `omniASR-LLM-1B.pt` 9.1 GB, **already on disk** (`~/risime-eval-models/`) | Apache-2.0 | yes: omni-si CER 8.9% (in-domain), yt5-si 42.7%, Singlish 49.4% (en kept 0%) | fairseq2 model. Our aarch64 build: `infra/omniasr` |
| facebook/omniASR-LLM-3B | ~3–4B | 17.5 GB | Apache-2.0 | yes | not downloaded; not planned |
| **facebook/omniASR-LLM-7B** | **7.80B** | 31.2 GB, **already on disk** | Apache-2.0 | yes: omni-si 7.5%, yt5-si 36.2%, Singlish 49.0% (en kept 0%) | Inference runs only with the encoder streamed. Training is **not possible on spark2 next to risi-l1**: bf16 weights alone are 15.6 GB. Out of scope unless risi-l1's memory changes or Harsha approves an external GPU |

## 3. Method

### Full fine-tune vs LoRA
| | Full fine-tune | LoRA (chosen for spark2) |
|---|---|---|
| Training state, ~2.3B params | bf16 weights + fp32 master + AdamW + grads ≈ 16 B/param ≈ **37–38 GB**, plus activations | frozen bf16 weights 4.4–4.7 GB + adapters (r = 64–128, ~50–150M params, ~1–2 GB with optimiser) + activations with gradient checkpointing (batch ~8 × ≤ 30 s: ~4–6 GB) ≈ **10–14 GB peak** |
| Fits next to risi-l1? | **No** (MemAvailable 2–18 GB) | yes, in the quiet part of the night window, with the watchdog |
| Quality | best, especially when teaching a **new language** (Nerdstorm's 0.6B rev 1 was a full fine-tune on SLR52) | close to full for adapting a language the model knows (omniASR). **Less proven for teaching a new language** (Qwen3-ASR-1.7B + Sinhala): we compensate with high rank on every linear layer of the LLM, plus training the audio encoder's top 4 layers and the projector in full (about 0.1B params) |
| Option | an external 80 GB GPU for ~10–20 h would run the Qwen3-ASR-1.7B stage-A full fine-tune on **public data only** (SLR52/omnilingual). It needs Harsha's decision (cost, data leaving the house); pilot audio never goes there | – |

### Frameworks that work (or should) on aarch64 / GB10 (sm_121)
- **Qwen3-ASR:**
  - The official `finetuning/qwen3_asr_sft.py` (QwenLM/Qwen3-ASR) uses `qwen-asr`, `datasets` and
    PyTorch/torchrun. Data is JSONL with `{"audio": wav, "text": "language Sinhala<asr_text>…"}`.
    The example hyperparameters are batch 32 × grad-accum 4, LR 2e-5, 1 epoch, a full fine-tune.
  - Our image goes on the **NGC vLLM base already on spark2** (torch 2.14, CUDA 13.4, arm64), like
    risime-whisper, the same way we run `Qwen3ASRForConditionalGeneration` today. Add `peft` for LoRA,
    which is pure Python.
  - **Do not build flash-attn** (the README recommends it). On sm_121 use torch SDPA, as risi-l1 and
    the speech service do. Install with `--no-deps` so pip keeps NVIDIA's torch, the lesson from
    `infra/omniasr/Dockerfile`.
  - The prompt label "Sinhala" is new to the model, but the prompt is free text, so no code change is
    needed.
- **omniASR-LLM:**
  - The official recipes (omnilingual-asr's training section; fairseq2 workflows for CTC and LLM) need
    fairseq2's data pipeline. In our source-built fairseq2n v0.6.0, `read_sequence` and the C++
    SentencePiece decoder **segfault** (`asr-omni-eval-2026-10-14.md`), so the official recipe
    **won't run as is**.
  - Plan: a plain-torch training loop around the fairseq2 model. We already build `Seq2SeqBatch` in
    torch and decode with the `sentencepiece` package for inference, and use the model's own loss.
    Add LoRA through `peft`, or hand-written adapters if peft can't wrap fairseq2's modules.
  - Effort is about 1–2 days, and it is the main risk of the omniASR track. Before trusting it, check it
    against inference: the zero-step loss must match a reference computed by the same model.
- **Common to both:**
  - `scripts/asr-omni-stream`-style memory supervision: pause under 6 GB MemAvailable (training) and
    resume from the last checkpoint. Checkpoint every ~15 minutes.
  - Training only in the night window **16:30–00:30 UTC (22:00–06:00 SLT)**. It also pauses while a
    release gate runs (`nightly-release`, ui-entry-test, redroid, Gradle) and while users are on calls or
    using voice: if the speech service's queue is non-empty, or risi-l1's p95 rises above budget, it
    pauses.
  - `scripts/risi-canary` runs before and after each night. **risi-l1 is never touched.**

### Singlish handling (exactly where omniASR failed)
1. **Data:** Latin-English transcripts only. SPEAK-ASR with permission; Latinised omnilingual
   transcripts (experiment L); pilot audio later. omniASR's script problem was learned from its data, so
   more of the same Sinhala-script-English data makes it worse.
2. **Mixing ratio** in stage B: ≥ 30% of segments contain at least one Latin word, by oversampling.
3. **Language prompt:** Qwen3-ASR uses `language Sinhala`, with no separate "Singlish" label (the model
   has to handle mixing inside one utterance). omniASR keeps `sin_Sinh` conditioning; the fine-tune
   teaches that `sin_Sinh` output may contain Latin.
4. **Guard metrics** per run: "en kept" on singlish-real (≥ production's 53%), script share (≥ 98%
   Sinhala or Latin, i.e. no Korean, Greek or Romanian drift, which omniASR-LLM-1B showed), and a
   repetition-loop check (no 4-gram repeated more than 3 times).
5. **Synthetic spliced si+en (synth-cs)** stays eval-only and labelled synthetic. It is not real
   code-switching and must not drive decisions.

## 4. Eval and held-out rules
Scoring is unchanged: `scripts/asr-eval.py score` (NFC, lower case, punctuation and ZWJ/ZWNJ removed,
corpus WER + CER, 15-s chunks). CER is the main Sinhala column because of word spacing. Per decision 085
these are **public fallback sets, labelled as such**, until ≥ 20 real clips per language exist.

| Set | Clips | Role | Leakage rule |
|---|---|---|---|
| `omni-si` (omnilingual **test**) | 60 | spontaneous | Never train on test. For any model trained on omnilingual **train** (all our candidates, and omniASR itself) it is **in-domain**: report it in a separate "in-domain" column, which can't promote on its own. Its speaker is not in train (train: 5 speakers; test: 1) |
| `slr30-si` | 36 | read | Different corpus from SLR52, but the prompt sentences may overlap, so **dedupe SLR52 by text** before training |
| `yt-si` | 50 | broadcast | **In the training data of the production model (Qwen3-ASR-0.6B-Sinhala) and of whisper-small-sinhala.** If we train on SPEAK-ASR (with permission), yt-si is in-domain for us too, so it never counts for promotion |
| **`yt5-si`** | 47 | broadcast, held out | The 5 channels nobody trains on. **If we use SPEAK-ASR train, those 5 channels are removed from it by channel, not by clip.** This is our main held-out broadcast set |
| **`singlish-real`** (new) | 51 (25 yt + 26 yt5) | real Singlish, Latin English | **Only the 26 yt5 clips count** for any model that saw SPEAK-ASR or is compared with the production model. 7.4 min of audio is direction, not decimals |
| `synth-cs` | 6 si+en | synthetic | labelled; never decisive |
| `fleurs-ta`, `fleurs-en` | 50, 20 | non-regression | only if a candidate would also serve ta/en. The Sinhala route change must not touch ta/en |
| **Real recordings** (Harsha / consented pilot clips) | 0 audio so far | **the Index set** once ≥ 20/language | **test only, forever.** Pilot training audio, once allowed, is split **by speaker and by date** (no eval speaker in train; train on clips before D, eval after D) |

Text-level guard: the 8-gram dedupe (section 1) runs against every eval reference before each training run.
The manifest records `in_training_of` for each new model, as today.

## 5. GPU time
**No benchmark was run.** MemAvailable was 4.8 GB when this was written (the floor for a tiny benchmark
is 12 GB) while nightly.7's gates ran. **The numbers below are estimates.** Run benchmark E0b at the
start of the first quiet night: 50 LoRA steps on omniASR-LLM-1B (on disk) at batch 8 × 15 s, measuring
s/step and peak memory. Then replace this table.

How it was estimated:
- Training FLOPs ≈ 6 × params × positions.
- omniASR-LLM-1B, per hour of audio: encoder ~1B × 180k frames (50 Hz) plus decoder ~1.2B × ~60k
  positions, so about **1.5 PFLOP per hour of audio per epoch**. Qwen3-ASR-1.7B is about 1.0–1.5 PFLOP
  (audio tokens at 12.5 Hz, but a 1.7B LLM and longer Sinhala token sequences).
- At an effective **15–25 TFLOP/s** bf16 on the GB10 while it is shared with risi-l1, that is 1–1.5
  minutes ideal. With gradient checkpointing (+33%), padding, small batches and pauses (×2–3), it comes
  to **~3–5 GPU minutes per hour of audio per epoch**.
- Cross-check against measured inference: the LLM-1B forward pass runs at RTF 0.16–0.27 *including*
  beam search. Teacher-forced training is a single forward pass plus a backward pass of about 2× that.

| Run | Audio per epoch | Epochs | GPU hours (est.) |
|---|---|---|---|
| Data prep (segmentation, Opus augmentation, dedupe, Latinisation proposals by risi-l1) | – | – | 2–3 h, mostly CPU; the risi-l1 calls are batched at night |
| E1 omniASR-LLM-1B LoRA, stage B (omnilingual train 8.4 h, Latinised + augmented ×2) | ~17 h | 3 | **~3–4 h** |
| E2 Qwen3-ASR-1.7B LoRA, stage A (SLR52, deduped ~180k utts ≈ 200 h) | ~200 h | 1 | **~10–17 h** (2–3 nights) |
| E2 stage B (omnilingual 8.4 h + SPEAK-ASR 7.4 h if permitted, augmented ×2, 15% SLR52 replay) | ~35 h | 3 | **~5–9 h** |
| E3 control: Qwen3-ASR-0.6B-Sinhala LoRA/full, same stage B (full fits: 0.94B ≈ 15 GB + activations) | ~35 h | 3 | **~3–5 h** |
| Eval per checkpoint (all sets, 275 clips, 1.6 h audio at RTF ~0.25) | – | – | ~0.5 h each, ~8 checkpoints ≈ **4 h** |
| **Total** | | | **~30–45 GPU hours ≈ 7–10 nights** at 4–6 usable GPU hours a night |

The night window is 8 h, but release gates, call traffic and memory pauses leave about 4–6 hours of
training. **If MemAvailable stays at tonight's 2–6 GB, training will mostly be paused.** Freeing memory
(stopping Gradle daemons and the TTS service at night, as on 2026-10-13) is a precondition, not a detail.

## 6. Expected gain (honest; wide uncertainty)
Published evidence:
- **Size helps most on low-resource languages.** The Qwen3-ASR model card gives Fleurs†† (30
  languages) WER **21.80 (0.6B) → 12.60 (1.7B)**, News-Multilingual 17.39 → 12.80, and CommonVoice
  12.75 → 9.18. These are languages both models were trained on, not a new language added later.
- **Adding Sinhala to Qwen3-ASR has a published path** (Nerdstorm, model card numbers from
  `docs/RISI-MODELS.md`):
  - rev 1: 0.6B + OpenSLR 52, 1 epoch. SLR52 CER 6.9%, but **conversational YouTube CER ~33%**.
  - rev 2: + 7.4 h of conversational YouTube. Conversational CER **20.5%**. Our yt5-si: 21.2% CER /
    42.0% WER.
  - So read data teaches the language, and the conversational step comes from ~7 h of
    conversational, code-switched data.
- **omniASR:** the repo states fine-tuning recipes exist for CTC and LLM models, but publishes **no
  fine-tune gains**. The 7B model is reported at CER < 10 for 78% of its languages (zero-shot style). Our
  own eval: omniASR is strong on in-domain spontaneous Sinhala (CER 8.9% / 7.5%) and weak on broadcast
  and Singlish.
- Other public Sinhala fine-tunes (Whisper-medium ~29% WER, Whisper-small fine-tunes 16–53% WER on their
  own test sets) use incomparable sets.

Our expectations (CER on held-out sets; production today: omni-si 13.3%, yt5-si 21.2%, Singlish-yt5 27.5%
with en kept 53%):

| Candidate | Data | omni-si (in-domain) | yt5-si | Singlish yt5 (en kept) | Confidence |
|---|---|---|---|---|---|
| E2 Qwen3-ASR-1.7B, **public licensed only** (SLR52 + omnilingual) | no Latin English | ~9–12% | **~22–30% (likely worse than production)** | ~30–40% (en kept < 30%) | medium: rev 1's 33% shows read data alone doesn't reach broadcast |
| E2 Qwen3-ASR-1.7B **+ SPEAK-ASR (permission)** | ≈ Nerdstorm's data on a 2.5× larger model, LoRA | ~9–11% | **~16–19%** | **~20–24% (55–70%)** | low–medium: the size gain is assumed to carry over; LoRA for a new language may lose part of it |
| E1 omniASR-LLM-1B LoRA (Latinised omnilingual) | data it already saw + Latin English | ~8–9% (no real change) | ~35–42% | ~35–45% (**0% → 20–50%**) | low: the main win is script handling and fewer drift cases, not Sinhala acoustics |
| E3 control: 0.6B-Sinhala + our stage B | | ~10–12% | ~19–21% | ~24–27% | medium (the 2026-10-12 plan's estimate) |
| Any of the above **+ 20–50 h consented pilot audio** | the real domain | – | – | – | this is what most likely gets Sinhala to **~20% WER on real calls**. Public data alone probably won't |

**Bottom line:** public data alone buys the 1.7B a Sinhala that is about as good as today's model, at
about twice its inference cost (4.7 GB vs 1.56 GB; RTF to be measured). The step change needs
conversational, Latin-English data: SPEAK-ASR permission now, and pilot audio after Harsha's decision.
Ask SPEAK-ASR now; it is the cheapest lever on this page.

## 7. Downloads (Harsha's OK needed for > 2 GB; night window, ≤ 10 MB/s, logged in `docs/status/downloads.md`)
| What | Source | Size | Needs OK? |
|---|---|---|---|
| **Qwen3-ASR-1.7B** | `Qwen/Qwen3-ASR-1.7B` (2 safetensors shards) | **4.70 GB** | **yes** |
| **OpenSLR 52** | openslr.org/52: 16 zips + tsv | **14.7 GB** (~25 min at 10 MB/s) | **yes** |
| omnilingual `sin_Sinh` train + dev | HF parquet | 876 MB + 231 MB | no (each < 2 GB; logged) |
| Qwen3-ASR-0.6B (reference/control base, optional) | `Qwen/Qwen3-ASR-0.6B` | 1.88 GB | no |
| SPEAK-ASR youtube-sinhala-asr (train) | HF | 0.9 GB | no, but **only after written permission** |
| Python packages for the training image (`qwen-asr`, `peft`, `datasets`) | PyPI | < 0.5 GB | no |
| omniASR-LLM-1B / LLM-7B | – | on disk | – |
| omniASR-LLM-3B (17.5 GB), TriLingDATA (78.5 GB) | – | – | **not requested** |

## 8. Order of experiments
1. **E0 (now; no OK needed):**
   - Freeze the baseline: production si, omniASR-LLM-1B, the 0.6B-Sinhala control, all sets, recorded on
     the scoreboard as "fallback (public)".
   - Download the omnilingual train + dev (< 2 GB each).
   - Build the stage-B manifests: segmentation, dedupe, Opus augmentation.
   - Write to SPEAK-ASR asking for training permission.
   - **E0b:** the 50-step LoRA benchmark on omniASR-LLM-1B, to replace section 5's estimates.
2. **L:** the Latinisation of the omnilingual transcripts (risi-l1 proposes, a human checks 100 segments).
   If the human accept rate is < 90%, drop it and rely on SPEAK-ASR / pilot audio.
3. **E1:** omniASR-LLM-1B LoRA (on-disk model, < 2 GB of data). Proves the plain-torch fairseq2
   training loop and tests whether the script problem is data-fixable. Cheapest experiment.
4. **E2 (after Harsha's OK for the 4.7 GB + 14.7 GB downloads):** Qwen3-ASR-1.7B LoRA, stage A (SLR52),
   then stage B. Evaluate after each stage.
5. **E3:** the control. The same stage B on the 0.6B-Sinhala (the 2026-10-12 plan). A 1.7B that doesn't
   beat the fine-tuned 0.6B by the promotion margin isn't worth twice the inference memory.
6. **E4 (after Harsha's voice-training-data decision):** pilot audio. First build the golden set
   (≥ 20 clips per language, human-checked, test only), then train on the rest (speaker- and date-split).
   This is when the Index becomes "real speech", not fallback.
7. **Not planned:** omniASR-LLM-7B training (memory), LLM-3B, a full fine-tune on spark2.

## 9. Promotion rule (decision 084 promotion rule, scored on the decision 085 Index)
- **Index (Transcription si)** = 0.7 × Eval + 0.3 × Live, with Eval = 100 − WER.
  - Until ≥ 20 real Sinhala clips exist, Eval comes from the **held-out public sets** (yt5-si + slr30-si
    + singlish-real yt5 part; omni-si only in a separate in-domain column) and is shown as "fallback
    (public)".
  - Live = transcript lines kept unedited ÷ shown, over the last 7 days; "Eval only (n live)" if fewer
    than 20 signals.
- **A candidate is promoted only if all of these hold:**
  1. the si Eval Index beats production by **≥ 3 points** (the singlish-real set is small, so
     smaller margins are noise);
  2. **no held-out set gets worse** by more than 1.0 CER point absolute;
  3. Singlish "en kept" ≥ production (53%);
  4. script share ≥ 98%, with no repetition loops;
  5. RTF ≤ 0.30 on the shared GPU, and resident memory fits the speech service's budget next to risi-l1;
  6. the licence suits the intended use (server-only for CC BY-SA).
- **Path:** shadow (compare mode, developer only) → canary (Harsha first, `si` routed to the candidate
  for his account) → on. This is the decision 071 amendment pattern.
- **Rollback:** automatic if the live si Index drops below the pre-promotion 7-day value by more than
  3 points over the next 7 days, or on any honesty or script regression. The Coach agent (085) owns the
  nightly loop, and it can't grade itself: the golden sets are human-checked.
- **ta/en:** untouched by this plan. Their routes and Indexes must not move (non-regression check only).
