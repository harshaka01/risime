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
