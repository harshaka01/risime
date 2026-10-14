# omniASR eval and compare candidate (root, 2026-10-14, Part 12 quick fix 1)

Harsha asked (2026-10-14 01:26 SLT) for Meta Omnilingual ASR (omniASR) to be evaluated on our speech
sets and added to the developer compare mode. This page has the results, the runtime on the GB10
(aarch64, sm_121), memory next to risi-l1, and the caveats. Nothing here changes the production
route.

## Status (2026-10-13 23:35 UTC)
- **omniASR-LLM-1B: done** on every set (275 clips).
- **omniASR-LLM-7B: done** on every set (275 clips; downloaded 22:39 UTC, 31,205,087,103 bytes).
  It **cannot be resident next to risi-l1** (15.6 GB in bf16 with 6-13 GB MemAvailable
  tonight, minus the 4 GB floor); it runs with the encoder streamed layer by layer (below).
- **omniASR-CTC-7B: downloading** (started 22:39 UTC, ~2 h at the line's ~3.4 MB/s; the night window
  closes 00:30 UTC, so it finishes on a later night). **LLM-3B / CTC-3B: not downloaded tonight.**
- Compare mode: omniASR-LLM-1B is the default compare candidate (off by default, see "Compare
  mode"). **omniASR-LLM-7B is a compare-only candidate** (Harsha 2026-10-14): the service runs it
  with the encoder streamed per request (it can't be resident: 15.6 GB); it is a tick on the
  console's Speech page Test card, "omniASR 7B (compare only, slow)", not pre-ticked and not in the
  default list. Never a production route.

## Licence
**Apache-2.0** for code and weights: `LICENSE` of facebookresearch/omnilingual-asr ("Licensed under
the Apache License, Version 2.0"), the omnilingual-asr 0.2.0 PyPI metadata, and `license: apache-2.0`
on every Hugging Face card we use (`facebook/omniASR-LLM-1B`, `-LLM-3B`, `-LLM-7B`, `-CTC-7B`). The
cards name `facebook/omnilingual-asr-corpus` (CC BY 4.0) as training data. fairseq2 (built from
source here) is MIT. No restriction on internal use or on a later fine-tune.

## Sets
All audio stays on spark2 (`~/risime-eval-data`); no commercial service and no network in the eval
containers. Scoring is `scripts/asr-eval.py score` unchanged (NFC, lower case, punctuation and
ZWJ/ZWNJ removed; corpus WER and CER), with the same 15-s chunking as every earlier row, so the
numbers sit next to docs/RISI-MODELS.md. Each model gets the clip's base language (si/ta/en; omniASR
LLM: `sin_Sinh` / `tam_Taml` / `eng_Latn` conditioning). Clips in a model's training data are
excluded from its score (`in_training_of`).

| Set | Clips | Kind | Notes |
|---|---|---|---|
| si: `omni-si` | 60 | spontaneous, held-out | test split of facebook/omnilingual-asr-corpus `sin_Sinh`. **In-domain for omniASR** (its train split is in omniASR's training data): read omniASR's omni-si numbers as a best case |
| si: `slr30-si` | 36 | read | OpenSLR 30 |
| si: `yt-si`, `yt5-si` | 50 + 47 | broadcast (YouTube) | yt-si is in Qwen3-ASR-0.6B-Sinhala's training data (excluded from its score) |
| **Singlish (new): `singlish-real`** | **51** | **real** broadcast speech, Sinhala + English in one sentence | `~/risime-eval-data/manifests/singlish-real.jsonl`: every yt-si / yt5-si clip whose reference has English words in Latin script (7.4 min, 235 English words; 25 from yt-si, 26 from yt5-si). Same ids as the source rows. For the production si model only the 26 yt5-si clips count |
| si+en spliced: `synth-cs` | 6 | **synthetic** (a Sinhala clip + an English clip concatenated) | labelled synthetic; not real code-switching |
| ta: `fleurs-ta` | 50 | read | FLEURS ta_in test |
| **ta+en** | **0 real** | – | **gap:** no real Tamil+English recordings exist (the 6 `synth-cs` ta+en clips are spliced). `~/risime-eval-data/real/ta+en/` has the 6 sentences but no audio yet (docs/status/asr-eval.md request) |
| en: `fleurs-en` | 20 | read | FLEURS en_us test |

The in-house recordings requested on 2026-10-12 (`real/{si,si+en,ta,ta+en,en}`) still have no audio,
so per ARCHITECTURE-PRINCIPLES §3 these are fallback (public) sets, labelled as such.

## Results (corpus WER / CER; RTF = model compute / audio seconds)
Production route per language (decision 071 c/d): si → **Qwen3-ASR-0.6B-Sinhala**, ta →
**tamil-asr-qwen3**, en → **faster-whisper-large-v3** (each forced to the clip's language, as the
route does after detection). `auto` = the service with detection only (no hint). omniASR-CTC-1B is
the ONNX int8 CPU run of 2026-10-12 (results/omni.jsonl). omniASR-LLM-1B: bf16 on the GPU,
results/omni-llm.jsonl.

| Set (clips) | Production model | auto (no hint) | omniASR-CTC-1B | **omniASR-LLM-1B** | omniASR-LLM-7B |
|---|---|---|---|---|---|
| si `omni-si` spontaneous (60) | 33.1% / 13.3% | 33.6% / 13.8% | 37.8% / 9.5% | **30.0% / 8.9%** | **27.0% / 7.5%** |
| si `slr30-si` read (36) | 38.2% / 8.9% | 38.2% / 10.2% | 44.2% / 7.4% | 52.7% / 16.6% | 58.7% / 14.9% |
| si `yt-si` broadcast (50) | excluded (trained on it) | – | 55.3% / 30.6% | 63.1% / 42.9% | 58.0% / 34.3% |
| si `yt5-si` broadcast (47) | **42.0% / 21.2%** | 43.4% / 23.6% | 56.8% / 30.2% | 67.0% / 42.7% | 63.8% / 36.2% |
| **Singlish real, yt5 part (26)** | **44.9% / 27.5%** (en kept 53%) | 47.1% / 31.0% | 57.8% / 39.5% (5%) | 67.2% / 49.4% (0%) | 73.1% / 49.0% (0%) |
| Singlish real, yt part (25) | excluded | – | 59.3% / 40.1% (12%) | 69.1% / 52.5% (0%) | 63.8% / 45.9% (0%) |
| si+en **spliced** (6, synthetic) | 74.3% / 71.5% | 56.2% / 57.0% | 53.5% / 22.2% | 163% / 138% | 85.4% / 68.4% |
| ta `fleurs-ta` read (50) | 32.6% / 15.2% | 34.9% / 18.0% | 43.4% / 15.2% | **33.8% / 14.1%** | **33.3% / 13.8%** |
| ta+en **spliced** (6, synthetic; no real clips) | 80.1% / 59.3% | 41.7% / 50.9% (en via large-v3) | – | 65.6% / 50.1% | 78.8% / 55.1% |
| en `fleurs-en` read (20) | **3.9% / 2.5%** | 3.9% / 2.5% | 9.9% / 3.4% | 10.3% / 5.9% | 7.2% / 4.6% |

"en kept" (Singlish rows) = English words of the reference that the hypothesis keeps in Latin
script. LLM-1B keeps none: it writes them in Sinhala script.

**Speed** (RTF; lower is faster): production si 0.20-0.23, ta 0.36, en 0.08; omniASR-CTC-1B 0.05
(CPU int8); **omniASR-LLM-1B 0.16-0.27 (0.24 over all 275 clips)**, about the same as the
production Sinhala model; ~1.5 s per 6-s clip on the shared GPU. LLM-7B: 0.22 compute over all 275 clips (0.15-0.28 per set), measured with the encoder streamed; the run took 1380 s wall for 5918 s of audio (groups of 64 chunks), so streaming the weights added only ~5%.

**Reading (LLM-1B):**
- **Best result on spontaneous Sinhala** (omni-si CER 8.9% vs 13.3% production), but omni-si is
  in-domain for omniASR (same corpus as its training data), so this is its best case.
- **Worse on read and broadcast Sinhala** (slr30 CER 16.6% vs 8.9%; yt5 42.7% vs 21.2%) and on
  **real Singlish** (49.4% vs 27.5%): English words come out in Sinhala script, and some broadcast
  clips leave the language entirely (below).
- **Tamil: on par with or slightly ahead of tamil-asr-qwen3** (14.1% vs 15.2% CER) at ~2/3 of its
  run time; with 4.4 GB resident vs ~6.7 GB.
- English: behind large-v3 (5.9% vs 2.5% CER).
**Reading (LLM-7B vs 1B):** better on spontaneous and broadcast Sinhala (omni-si CER 7.5%, yt-si
34.3%, yt5 36.2%; it no longer drifts into other scripts, script % ~100) and on English (4.6%);
Tamil about equal (13.8%). Still clearly behind the production Sinhala model on read and broadcast
speech and on real Singlish (49.0% vs 27.5% CER), and it also writes English in Sinhala script
(en kept 0%). It does not justify a 15.6 GB resident model next to risi-l1.
- **Recommendation:** no route change. LLM-1B is worth keeping as a compare candidate (spontaneous
  Sinhala, Tamil) and as a fine-tune base candidate next to Qwen3-ASR-0.6B-Sinhala
  (docs/status/asr-finetune-plan.md); it is not ready for Singlish.

## Runtime on the GB10 (what it took)
- **No aarch64 fairseq2n wheel**, and fairseq2n pins `torch==2.9.1`; the GB10 runs NVIDIA's torch
  2.14 (NGC). `infra/omniasr/Dockerfile` builds fairseq2n v0.6.0 from source on the NGC vLLM base
  (already on spark2, shared layers): C++20 (torch 2.14 headers), two fmt format strings and one
  `std::ssize` ambiguity patched; torchaudio runs in its pure-PyTorch mode (its C++ extension is
  built for upstream torch).
- fairseq2n's **data pipeline** (`read_sequence` on Python objects) and its **C++ SentencePiece
  decoder** segfault in this build. The service therefore builds the batch in plain torch (Meta's
  own `postprocess_waveform`, `Seq2SeqBatch`, then the pipeline's `_apply_model`: same model, beam
  search, normalisation) and decodes with the `sentencepiece` Python package (the char tokenizer is a
  raw SentencePiece model). cuDNN is off (no conv1d engine on sm_121, as in decision 071).
- `infra/omniasr/stream_eval.py`: the encoder streamed **layer by layer** from the memory-mapped
  checkpoint (decoder, frontend and projection resident; all chunks pass layer *i* before layer *i+1*
  is loaded). **Validated: 12/12 chunks identical** to the resident 1B. Weights are read one tensor
  at a time by `infra/omniasr/ckpt_reader.py` (restricted unpickler, pages dropped after each copy;
  bit-exact vs `torch.load`), because `torch.load(mmap=True)` kept ~6 GB of mapped pages counted
  against MemAvailable. `scripts/asr-omni-stream`
  runs it with the memory floor (stops under 4096 MB MemAvailable, resumes, waits for release gates).

## Compare mode (developer only)
- **Service:** `infra/docker-compose.omniasr.yml` → `risime-omniasr` on **127.0.0.1:8210** (rule 6),
  same API as risime-whisper (`/v1/audio/transcriptions`, `/v1/audio/compare`, Bearer
  `WHISPER_API_KEY`), offline, read-only, audio in memory only, no transcript logged. One model
  resident; lazy load; refused (503 / result `error`) if MemAvailable would go under 4096 MB; idle
  unload after 180 s.
- **App server:** `RisiMe.Agent.Speech.Http.compare/2` appends the omniASR results after the speech
  service's own when `RISI_SPEECH_COMPARE_EXTRA_URL=http://127.0.0.1:8210` is set (models:
  `RISI_SPEECH_COMPARE_EXTRA_MODELS`, default `omniasr-llm-1b`). Same v1.40 §36.11 result shape
  (`model` is a free alias; `script`/`romanised` for a Sinhala chunk), `chosen` and
  `language_detected` stay the speech service's, a failing omniASR service only marks its rows
  `failed`/`timeout`. **Off by default; not enabled on the pilot** (see "To enable"). `transcribe/2`
  (the production route) is untouched; no wire change.
- **To enable (root, after a nightly/fast lane carries the commit):** `docker compose --env-file .env
  -f infra/docker-compose.omniasr.yml up -d`, add `RISI_SPEECH_COMPARE_EXTRA_URL=http://127.0.0.1:8210`
  to `.env`, restart `risime.service`. Cost: a compare session loads LLM-1B (~4.4 GB of unified memory
  for up to 180 s after the last chunk), which can push risime-whisper's own pool into its memory
  fallback while it is loaded. So enable it for a test session, not permanently, until memory allows.
- **omniASR-LLM-7B, compare only (slow):** served with the encoder streamed per request (the
  stream_eval method, validated identical to the resident model on the 1B): ~2.8 GB resident, the
  128 encoder layers read from the checkpoint for every request (pages dropped after use), floor
  checked at load and per layer. Asked only when ticked on the console ("omniASR 7B (compare only,
  slow)") or named in `opts[:extra_models]`; 180 s timeout (`RISI_SPEECH_COMPARE_EXTRA_TIMEOUT_MS`).
  Time per clip: to be measured after the nightly.7 gates (estimate from the eval run: ~15-20 s for
  a 5-10 s clip).
- **Console:** the Speech page's Test card lists the omniASR models as compare-only ticks; the
  Models table marks them "compare only"; they are never offered as a language's route
  (`SpeechRoutes.put` refuses them).

## Memory and load (GB10 unified memory; risi-l1 = 47.5 GB GPU, untouched)
| Model | bf16 weights | Resident: CUDA / host MemAvailable drop | Load | Next to risi-l1? |
|---|---|---|---|---|
| omniASR-LLM-1B (2.28B params) | 4.4 GB | 4.39 GB CUDA, ~1.2 GB process RSS; **-4.4 GB MemAvailable** (measured 18.6 → 14.2 GB) | **1.0-4.9 s** (mmap'd checkpoint, page cache warm/cold) | **yes**, when ≥ ~9 GB is available (floor 4 GB) |
| LLM-1B, streamed encoder (eval) | – | 2.6-3.0 GB CUDA peak, ~4.6 GB MemAvailable incl. the torch/CUDA runtime | 1-3 s | yes |
| omniASR-LLM-7B (7.80B) | 15.6 GB | not attempted resident: 15.6 GB + runtime > what is available (6-13 GB minus the floor). int8 would still be ~8 GB + runtime | – | **no** (resident); **yes streamed**: resident part 2.8 GB CUDA (decoder, frontend, projection), peak 3.3 GB CUDA, -2.8 GB MemAvailable at load (9.3 -> 6.5 GB), load 2.2 s, min MemAvailable 5.8 GB during the run |
| omniASR-CTC-7B (6.5B) | 13 GB | not downloaded yet | – | resident no; streamed expected like LLM-7B |
| Production speech service (for scale) | – | idle 2.0 GB GPU; all routed models ~11 GB host | – | – |

Tonight's MemAvailable swung between 1.7 and 13 GB from other work (Cassandra 7.5 GB, two
Gradle daemons ~8 GB until root stopped them, another session's TTS service ~4 GB), so every run
went through `scripts/asr-omni-stream`, which stops under the 4 GB floor and resumes.

## Caveats
- **omni-si favours omniASR** (same corpus as its training data, different split).
- **LLM-1B leaves the language** on some broadcast clips: Korean, Greek or Romanian text and
  repetition loops on Sinhala audio despite `sin_Sinh` conditioning (e.g. yt5-si-059, yt5-si-021).
  It also **writes English words in Sinhala script** ("ඔටෝමේෂන්ස්" for "automations"), so "en kept"
  is 0% on Singlish, and it spells numbers as words ("දෙදහස් විසි පහ" for 2025); both count as
  errors against our references.
- Sinhala WER is inflated by word spacing (පිට කොටුවයි vs පිටකොටුවයි), so CER is the fairer column.
- Speeds of the streamed runs are GPU compute per chunk (what a resident model would take), not the
  streaming wall time. The GPU is shared with risi-l1 and others, so times vary.
- The singlish-real set is 7.4 minutes of broadcast audio: small; it shows direction, not decimals.
