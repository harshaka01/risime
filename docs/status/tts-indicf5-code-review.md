# IndicF5 custom-code review (TTS candidates, 2026-10-14)

**Verdict: NOT CLEARED YET. IndicF5 must not run.** Of the 64 non-LFS files at the pinned revision, 50 are
reviewed and clean for inference. The three files that decide the verdict (`model.py`, `config.json` and
`f5_tts/infer/utils_infer.py`) are only on Hugging Face (`model.py`, `config.json`) or differ there from GitHub
(`utils_infer.py`). The repo is gated, so these files arrive with root's night download
(`scripts/dl-approved-2026-10-14-tts2`, queued behind Chatterbox's night-only t3 weights). The risime-tts-lab
service reports IndicF5 as `review_pending` (console: "waiting for its code review") until
`TTS_LAB_INDICF5_CLEARED` is set to the revision below, and nothing sets it.

## What is pinned
- Repo `ai4bharat/IndicF5`, licence MIT, tag `custom_code` (it is meant for `trust_remote_code=True`).
- **Revision `fb652a96e508091045890dae5bb99fe511facbcf`** (HF `sha`, 2026-10-14 03:05 UTC). The review covers
  exactly the git blob ids of this revision (HF tree API, public metadata). Each file is matched by its git
  blob SHA-1, `sha1("blob <len>\0" + bytes)`, so a later change on HF can't slip in under the same name.
- 94 entries: 64 non-LFS files (code, configs, docs, 13 `.pyc`), and 30 LFS entries (`model.safetensors`
  1,402,789,408 bytes, oid `686852688f…`; audio prompts and samples).

## How it was reviewed (no code run)
- The HF files are gated (401 without a token). The token is root's and was not used here. AI4Bharat's public
  GitHub repo (`AI4Bharat/IndicF5`, tarball of `main`, 2026-10-14) was downloaded into an isolated scratch
  directory, **never executed or imported**, and every file was matched against the HF blob ids.
- **50 files are byte-identical** to the pinned revision. They include all of `f5_tts/model/**`
  (`cfm.py`, `modules.py`, `utils.py`, `dataset.py`, `trainer.py`, `backbones/dit.py`, `mmdit.py`, `unett.py`),
  `f5_tts/api.py`, the eval, train and script files, `f5_tts/socket_server.py`, the configs and `checkpoints/vocab.txt` (in
  `f5_tts/infer/examples`).
- **Missing on GitHub (HF-only):** `model.py` (the `custom_code` entry point), `config.json` (its `auto_map`),
  `checkpoints/vocab.txt`, and the 13 `__pycache__/*.pyc` files.
- **Different on GitHub:** `f5_tts/infer/utils_infer.py`, `README.md`.
- Searched for: network calls (`requests`, `urllib`, `socket`, `http`, `hf_hub_download`, `snapshot_download`,
  `cached_path`, `from_pretrained`, `load_dataset`), subprocess / `os.system` / `popen`, `eval(`/`exec(`,
  `__import__`/`importlib`, pickle / `torch.load` / `joblib` / `marshal`, file writes (`open(..., "w")`,
  `.save(`, `sf.write`, `torchaudio.save`, `tempfile`), deletes (`os.remove`, `rmtree`), telemetry (`wandb`, gradio).

## Findings (the 50 matching files)
| File(s) | Finding | Risk for our use |
|---|---|---|
| `f5_tts/model/cfm.py`, `backbones/dit.py`, `modules.py`, `utils.py` (the inference model) | Pure torch. No network, subprocess, eval/exec or file writes. `utils.py` reads the vocab file (read-only `open`). Imports `jieba` and `pypinyin` (Chinese text tools, local). | **Clean.** |
| `f5_tts/model/__init__.py` | Imports `trainer`, which imports `wandb`, `accelerate`, `ema_pytorch` and `datasets` at import time. | Low (nothing runs on import, wandb only acts on `wandb.init`), but these packages are **not installed** in risime-tts-lab. The adapter has to stub `f5_tts.model.trainer` before importing, or import the inference modules without the package `__init__`. |
| `f5_tts/model/trainer.py` | `wandb` logging, `accelerator.save` / `torchaudio.save` writes, `torch.load(..., weights_only=True)`. | Training only; **never called** by inference. |
| `f5_tts/model/dataset.py` | `datasets.load_dataset(...)` (Hub) and `load_from_disk`. | Training only; never called. |
| `f5_tts/api.py` (`F5TTS` class) | `cached_path("hf://SWivid/F5-TTS/...")`: **downloads** the upstream checkpoints; `sf.write` to a caller-given path. | **Must not be used.** Our adapter will not import `api.py`. |
| `f5_tts/socket_server.py` | A TCP socket server. | **Must not be used**, never imported. |
| `f5_tts/infer/infer_cli*.py`, `infer_gradio*.py`, `speech_edit.py`, `infer_batch_parallel.py` | CLI / Gradio front ends (Gradio opens a web server; the CLIs write output files). | Never imported. |
| `f5_tts/eval/**`, `f5_tts/train/**`, `f5_tts/scripts/**` | Evaluation and data preparation: Hub datasets, file writes. | Never imported. |
| `f5_tts/infer/__pycache__/*.pyc`, `f5_tts/model/**/__pycache__/*.pyc` (13 files) | **Compiled bytecode shipped in the repo.** It can't be reviewed as source and doesn't have to match it: a hash-based `.pyc` with `check_source=0` is loaded even when the `.py` next to it differs. | **High if left in place.** The adapter must run from a clean copy of the reviewed `.py` files only (no `__pycache__`), with `PYTHONPYCACHEPREFIX` set to an empty tmpfs directory so no shipped `.pyc` can ever be picked up. |

### Pre-review of the GitHub `utils_infer.py` (not the pinned file: indicative only)
- `load_vocoder`: `hf_hub_download` / `snapshot_download` (Vocos `charactr/vocos-mel-24khz`, BigVGAN) unless a
  local path is given; `torch.load(..., weights_only=True)`.
- `load_checkpoint`: safetensors, or `torch.load(..., weights_only=True)`.
- `preprocess_ref_audio_text`: writes the trimmed reference audio to `tempfile.NamedTemporaryFile(delete=False)`
  (a file in `/tmp` that is never deleted: on the lab's tmpfs it vanishes on restart; the adapter must pass
  already-trimmed audio, or delete the file).
- No subprocess, eval/exec or sockets.
- Whether the HF version keeps these calls (and whether `model.py` calls `load_vocoder` from the Hub) is the
  first thing to check tonight. Offline (`HF_HUB_OFFLINE=1`) turns a Hub call into an error, not a download,
  but the review must still record it.

## To clear it (tonight, after the files arrive)
1. Check `sha1("blob …")` of every downloaded file against the pinned tree (`model.py` `c8431d49…`,
   `config.json` `bb1ebb0a…`, `utils_infer.py` `74fb5f7f…`, `checkpoints/vocab.txt` `65db6313…`).
2. Review `model.py`, `config.json` (`auto_map`) and the HF `utils_infer.py` for the patterns above. Also check:
   does `model.safetensors` hold the vocoder (if not, Vocos weights are another small download, MIT), and
   which keys does it contain (safetensors only: no pickle).
3. Write the adapter (`IndicF5` in `infra/voice/tts_lab_worker.py`). It loads **without
   `trust_remote_code`**: from a clean copy of the reviewed `.py` files only (no `__pycache__`, stubbed trainer,
   `PYTHONPYCACHEPREFIX` empty), `model.safetensors` via safetensors, no Hub, under `python3 -I` with
   `HF_HUB_OFFLINE=1` like every lab engine. Its dependencies (`x-transformers`, `torchdiffeq`, `vocos`, `jieba`,
   `pypinyin`, `ema-pytorch` only if unavoidable) are pinned in `/opt/eng/indicf5`.
4. Record the verdict and commit hash here, then set `TTS_LAB_INDICF5_CLEARED=fb652a96e508091045890dae5bb99fe511facbcf`
   in `.env` and recreate risime-tts-lab.

## Voice rule (independent of the code verdict)
IndicF5 has **no built-in voice**: every sentence copies the voice of a reference recording plus its
transcript. Its shipped prompts (`prompts/MAR_F_HAPPY_00001.wav`, `PAN_F_HAPPY_00001.wav`) are real dataset
speakers. In the console they are labelled "stock prompt (a dataset speaker)". **They are not acceptable as
Risi's production voice** (Harsha 2026-10-14: original or properly licensed, never a clone of a real person).
Production would need a licensed, consented reference voice (for example a contracted voice actor), or a synthetic
reference (for example an Indic Parler-TTS described voice). The console flags IndicF5 "production: no".
