# Downloads log

Rule (CLAUDE.md golden rule 10, Harsha 2026-10-10): a download over 2 GB needs Harsha's OK first, runs only
22:00–06:00 Sri Lanka time (16:30–00:30 UTC), is rate-limited (default 10 MB/s), and is logged here.

| When (UTC) | What | Size | Source | Approved by | Result |
|---|---|---|---|---|---|
| 2026-10-09/10 | Risi model-eval candidates (LLMs + ASR) in ~/risime-eval-models | ~160 GB | Hugging Face | Harsha (model eval request) | complete; kept (all models and quants); verification below |

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
