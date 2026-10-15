# Downloads log

Rule (CLAUDE.md golden rule 10, Harsha 2026-10-10): a download over 2 GB needs Harsha's OK first, runs only
22:00–06:00 Sri Lanka time (16:30–00:30 UTC), is rate-limited (default 10 MB/s), and is logged here.

| When (UTC) | What | Size | Source | Approved by | Result |
|---|---|---|---|---|---|
| 2026-10-09/10 | Risi model-eval candidates (LLMs + ASR) in ~/risime-eval-models | ~160 GB | Hugging Face | Harsha (model eval request) | complete; kept (all models and quants); verification below |
| 2026-10-12 16:18–16:20 | ASR eval: FLEURS `en_us` test audio + tsv (20 clips kept in ~/risime-eval-data/fleurs-en) | 290 MB | huggingface.co/datasets/google/fleurs | ≤ 2 GB, allowed (Harsha's ASR-eval request 2026-10-12) | complete |
| 2026-10-12 16:23–16:31 | ASR eval: OpenSLR SLR30 Sinhala `si_lk.tar.gz` + lines (36 clips kept in ~/risime-eval-data/slr30-si) | 700 MB | openslr.org/30 | ≤ 2 GB, allowed (same) | complete |
| 2026-10-12 16:29–16:30 | ASR eval: `facebook/omnilingual-asr-corpus` `sin_Sinh` test parquet (held-out Sinhala, spontaneous) | 179 MB | Hugging Face | ≤ 2 GB, allowed (same) | complete |
| 2026-10-12 16:30–16:35 | `Nerdstorm/Qwen3-ASR-0.6B-Sinhala-8bit` (MLX) → ~/risime-eval-models/qwen3-asr-0.6b-sinhala-8bit | 1.01 GB | Hugging Face | ≤ 2 GB, allowed (same) | complete |
| 2026-10-12 16:34–16:49 | `SPEAK-ASR/faster-whisper-medium-si-exp10-fp16` (CT2) | 1.53 GB | Hugging Face | ≤ 2 GB, allowed (same) | complete (first attempt cut by an HTTP/2 reset at 1.09 GB, resumed) |
| 2026-10-12 16:49–16:52 | `mohankumars26/whisper-tamil-medium-ct2` (CT2 int8 of vasista22/whisper-tamil-medium) | 0.78 GB | Hugging Face | ≤ 2 GB, allowed (same) | complete |
| 2026-10-12 16:52–16:56 | `vasista22/whisper-tamil-small` (`pytorch_model.bin` only, no flax) | 0.97 GB | Hugging Face | ≤ 2 GB, allowed (same) | complete |
| 2026-10-12 16:56–17:00 | `janiduchamika/whisper-small-sinhala-general-185k` (top-level files, no checkpoint/optimizer) | 0.97 GB | Hugging Face | ≤ 2 GB, allowed (same) | complete |
| 2026-10-12 17:00–17:04 | `OpenVoiceOS/omnilingual-asr-ctc-1b-onnx` (`model.int8.onnx` + tokens only, not the 3.9 GB fp32) | 1.03 GB | Hugging Face | ≤ 2 GB, allowed (same) | complete |

All 2026-10-12 items: `curl --limit-rate 10M`, one at a time, log in `~/risime-eval-data/dl/downloads-2026-10-12.log`; total ~6.7 GB in 9 separate downloads, each ≤ 2 GB. The > 2 GB candidates were **not** downloaded; they are listed for Harsha's OK in docs/RISI-MODELS.md ("ASR eval 2026-10-12", download list). Also installed: `pyarrow` (pip, ~40 MB) in a throwaway venv in the session scratchpad, to read the parquet.
| 2026-10-13 02:53– | (1) `TriLinSL/nemotron-3.5-asr-streaming-si-ta-en-210h` `best_model/model.nemo` → ~/risime-eval-models/nemotron-si-ta-en | 2.49 GB | Hugging Face | Harsha 2026-10-13 (items 1, 2, 9); **daytime exception, 50 MB/s cap, approved by Harsha 2026-10-13**. Measured line 2.3–3.9 MB/s (HF, GitHub, Ubuntu samples), so run at 2.5 MB/s (≈ 65–70 % of peak); pauses during release gates | **done** 03:09 UTC, 2,490,736,640 bytes (scripts/dl-approved-2026-10-13; log ~/risime-eval-data/dl/downloads-2026-10-13.log) |
| 2026-10-13 03:09– | (2) `facebook/omniASR-LLM-1B` `omniASR-LLM-1B.pt` → ~/risime-eval-models/omniASR-LLM-1B | 9.10 GB | Hugging Face | same | **done** 06:41 UTC, 9,103,136,284 bytes |
| 2026-10-13 06:41– | (9) `Qwen/Qwen3-4B-Instruct-2507-FP8` (all files) → ~/risime-models/qwen3-4b-instruct-2507-fp8 | 5.21 GB | Hugging Face | same | **done** 07:42 UTC, all repo files |
| 2026-10-13 20:00 UTC– (night window) | `facebook/omniASR-LLM-7B` `omniASR-LLM-7B.pt` → ~/risime-eval-models/omniASR-LLM-7B, then `omniASR-CTC-7B` (26.02 GB), then `omniASR-LLM-3B` (17.51 GB) and `omniASR-CTC-3B` (12.32 GB) (3B approved too, 01:52 SLT) | 31.21 GB (87.06 GB with all four) | Hugging Face (Apache-2.0) | **Harsha 2026-10-14 01:26 SLT (Part 12, quick fix 1)**. Night window only, curl limit 10 MB/s (the line gives ~2.8 MB/s); pauses during release gates; resumes next night if the window closes | in progress (scripts/dl-approved-2026-10-14; log ~/risime-eval-data/dl/downloads-2026-10-14.log) |
| 2026-10-13 20:24 UTC– (night window) | `hexgrad/Kokoro-82M` (all files) → ~/risime-eval-models/kokoro-82m, then `ResembleAI/chatterbox` safetensors + tokenizers + conds (no `.pt` duplicates, no old `t3_23lang`) → ~/risime-eval-models/chatterbox | 0.36 GB + ~8.6 GB | Hugging Face (Kokoro Apache-2.0; Chatterbox MIT, built-in PerTh watermark) | **Harsha 2026-10-14 01:52 SLT** (TTS list). 1 MB/s beside the omniASR queue; pauses during release gates | in progress (scripts/dl-approved-2026-10-14-tts; log ~/risime-eval-data/dl/downloads-2026-10-14-tts.log) |
| 2026-10-14 03:02 UTC– (files > 2 GB at night only) | `canopylabs/orpheus-3b-0.1-ft` safetensors only (~15.1 GB; not the 26 GB optimizer or 15 GB FSDP files), `ai4bharat/indic-parler-tts` (3.76 GB), `ai4bharat/IndicF5` (1.41 GB) | ~20.3 GB | Hugging Face, **gated** (accept terms + token) | Harsha 2026-10-14 01:52 SLT | Harsha gave a read token 2026-10-14 (in .env, never printed); queued in scripts/dl-approved-2026-10-14-tts2 with the Chatterbox retries (5 files failed on 13 Oct). Waits for a non-empty HF_TOKEN. Orpheus is tagged Apache-2.0 but is fine-tuned from Llama 3.2 3B, so the Llama 3.2 Community Licence applies too (production check) |
| n/a | Meta MMS-TTS Sinhala | – | `facebook/mms-tts-sin` does not exist on Hugging Face; MMS-TTS Tamil is CC-BY-NC-4.0 (no production use) | – | not downloaded |
| 2026-10-14 03:52–03:53 UTC | TTS lab support files (not in the tts2 list): `hubertsiuzdak/snac_24khz` (config + `pytorch_model.bin`, the Orpheus audio decoder, loaded `weights_only=True`) @ d73ad176 → ~/risime-eval-models/snac_24khz; `google/flan-t5-large` **tokenizer files only** (spiece.model, tokenizer.json, tokenizer_config.json, special_tokens_map.json; Indic Parler's description tokenizer) @ 0613663d → ~/risime-eval-models/flan-t5-large-tokenizer | 79.5 MB + 3.2 MB | Hugging Face, not gated (SNAC MIT, flan-t5 Apache-2.0) | ≤ 2 GB, allowed (Harsha's TTS request 2026-10-14) | complete; no token used |
| 2026-10-15 16:30 UTC– (night window, to 00:30 UTC) | `ResembleAI/chatterbox-turbo` (MIT, not gated): t3_turbo_v1, s3gen, s3gen_meanflow, ve safetensors + tokenizer/yaml + conds.pt → ~/risime-eval-models/chatterbox-turbo | 4.04 GB | Hugging Face | Harsha 2026-10-15 20:26 SLT | scheduled (scripts/dl-approved-2026-10-15-tts3, size + sha256 checked). The sub-200 ms figure on the model card is Resemble's paid service, not measured on the local model |
| 2026-10-15 16:30 UTC– (night window) | `nari-labs/Dia2-1B` (Apache-2.0, not gated; 1B chosen over 2B at 7.7 GB): model.safetensors + config/tokenizer → ~/risime-eval-models/dia2-1b; plus its codec `kyutai/mimi` (CC-BY-4.0, attribution needed; named in dia2_assets.json) → ~/risime-eval-models/mimi | 4.31 GB + 0.38 GB | Hugging Face | Harsha 2026-10-15 20:26 SLT | scheduled (same script) |
| 2026-10-15 16:30 UTC– (night window) | retry `canopylabs/orpheus-3b-0.1-ft` safetensors + config/tokenizer only → ~/risime-eval-models/orpheus-3b-0.1-ft | 15.16 GB | Hugging Face, **gated** | Harsha 2026-10-14 01:52 SLT, retry 2026-10-15 20:26 SLT | scheduled, runs last; **blocked until the token is authorised**: the API returns 403 (not in the authorised list), which is why every file failed on 14 Oct. The script skips it with a log line if still 403 |
| 2026-10-14 from 16:30 UTC (night window) | `Qwen/Qwen3-ASR-1.7B` (all files) → ~/risime-eval-models/qwen3-asr-1.7b; OpenSLR 52 Large Sinhala ASR (16 zips + utt_spk_text.tsv) → ~/risime-eval-data/openslr52 | 4.70 GB + 14.7 GB | Hugging Face (Apache-2.0); openslr.org (CC BY-SA 4.0: serving only, never in a distributed model) | **Harsha 2026-10-14 06:09 SLT** (decision 7: fine-tune "starting from public data now (OpenSLR, …)"; plan docs/status/asr-sinhala-finetune-plan-2026-10-14.md) | queued: waits for the window, 10 MB/s, pauses for release gates (scripts/dl-approved-2026-10-14-ft; log ~/risime-eval-data/dl/downloads-2026-10-14-ft.log) |

## Verification of ~/risime-eval-models
(shard count, sha256 and size of each file against the Hugging Face metadata; filled in by the model-eval run)

Checked 2026-10-10 at about 08:40 UTC by the model-eval run, with
`python3 -I eval/risi/verify_models.py`. It used the HF API tree listing (metadata only, no file
downloads). Every file of each repo's main revision is compared on:
- size;
- the hash: sha256 (`lfs.oid`) for LFS files, the git blob sha1 for small files.

Weight shards are counted from `model.safetensors.index.json`.

**All 10 folders verified OK; nothing needs re-downloading.** faster-whisper-large-v3/-turbo and
Qwen3.6-35B-A3B-FP8 in ~/risime-models are the live models' own copies and weren't part of this
check.

| model dir | HF repo | files checked | excluded by pattern | weight shards | size GB | verified | problems |
|---|---|---|---|---|---|---|---|
| gemma4-12b-fp8 | RedHatAI/gemma-4-12B-it-FP8-Dynamic | 10 | 0 | 1 | 15.07 | ok | - |
| gemma4-12b-qat | google/gemma-4-12B-it-qat-w4a16-ct | 10 | 0 | 1 | 10.30 | ok | - |
| gemma4-26b-a4b-awq | cyankiwi/gemma-4-26B-A4B-it-AWQ-4bit | 13 | 0 | 4 | 17.23 | ok | - |
| gemma4-26b-a4b-fp8 | RedHatAI/gemma-4-26B-A4B-it-FP8-dynamic | 17 | 0 | 1 | 28.67 | ok | - |
| gemma4-31b-fp8 | RedHatAI/gemma-4-31B-it-FP8-block | 18 | 0 | 2 | 33.30 | ok | - |
| qwen38-27b-fp8 | Qwen/Qwen3.8-27B-FP8 | 81 | 0 | 66 | 30.89 | ok | - |
| qwen38-27b-nvfp4 | RadixArk/Qwen3.8-27B-NVFP4 | 22 | 0 | 3 | 21.95 | ok | - |
| tamil-asr-qwen3 | lemuralabs/tamil-asr-qwen3 | 15 | 0 | 1 | 4.09 | ok | - |
| whisper-large-v3-sinhala | kasunw/whisper-large-v3-sinhala | 15 | 0 | 2 | 6.18 | ok | - |
| whisper-small-sinhala | SinhaSpeech/whisper-small-sinhala-v6-e6-run11-best | 16 | 0 | 1 | 0.97 | ok | - |
| 2026-10-15 15:39 UTC (21:09 SLT) – | **Harsha exception 21:08**: Chatterbox-Turbo, Dia2-1B + Mimi, Orpheus retry, started now instead of 22:00 (scripts/dl-approved-2026-10-15-tts3 with DL_NOW=1) | ~8.7 GB (+15.2 GB Orpheus) | Hugging Face | Harsha 2026-10-15 21:08 SLT (exception to the 22:00 window), **6 MB/s** | in progress. Orpheus still 403: "Access to model canopylabs/orpheus-3b-0.1-ft is restricted and you are not in the authorized list" (token "spark2" of harshaka001, canReadGatedRepos true); sent to Harsha |
