"""Meta Omnilingual ASR (omniASR, Apache-2.0) behind the speech service's API, for the ASR eval and
the developer compare mode only (2026-10-14, Part 12). Never on the production route.

  GET  /health                     no auth; 200 while the process is up (models load lazily)
  GET  /v1/models                  Bearer auth; served models, which one is loaded, memory floor
  POST /v1/audio/transcriptions    Bearer auth; multipart: file, model, language (si|ta|en, the
       LLM models are conditioned on it: sin_Sinh / tam_Taml / eng_Latn; without it they run
       unconditioned), response_format (json|text|verbose_json). verbose_json adds language,
       model, duration, model_processing_s, load_s and, for si, the Sinhala script check.
  POST /v1/audio/compare           Bearer auth; file, language, models (comma list, optional;
       default OMNI_COMPARE): each model in turn -> {results: [{model, text, language,
       processing_s, load_s[, script, romanised]} | {model, error}]}, the same per-result shape
       as the speech service's /v1/audio/compare (contract v1.40 §36.11).

Memory (GB10 unified memory, shared with risi-l1): one model resident at a time. A load is
refused (503 / error "not enough memory") if host MemAvailable minus the model's estimate would
drop below OMNI_MIN_AVAILABLE_MB (default 4096). The checkpoint is memory-mapped and the model is
built directly in bf16 on the GPU, so the fp32 file is never copied into anonymous memory. Idle
models are unloaded after OMNI_IDLE_UNLOAD_S.

Privacy: audio is decoded in memory and never written, no transcript text is logged, the access
log is off, the hub is offline (local checkpoints and tokenizers only).
"""

import ctypes
import gc
import hmac
import io
import logging
import os
import threading
import time
import unicodedata

import numpy as np
import soundfile as sf
import torch
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse

API_KEY = os.environ.get("OMNI_API_KEY") or os.environ.get("WHISPER_API_KEY", "")
MODELS_ROOT = os.environ.get("OMNI_MODELS_ROOT", "/models")
TOKENIZERS = os.environ.get("OMNI_TOKENIZERS", f"{MODELS_ROOT}/omniASR-tokenizers")
MIN_AVAILABLE_MB = int(os.environ.get("OMNI_MIN_AVAILABLE_MB", "4096"))
IDLE_UNLOAD_S = int(os.environ.get("OMNI_IDLE_UNLOAD_S", "600"))
# Beyond the bf16 weights: CUDA context, activations, the decoder's KV cache (measured ~0.5 GB for
# the 1B on 15-s chunks, docs/status/asr-omni-eval-2026-10-14.md).
LOAD_OVERHEAD_MB = int(os.environ.get("OMNI_LOAD_OVERHEAD_MB", "800"))
DTYPE = {"bf16": torch.bfloat16, "fp16": torch.float16}[os.environ.get("OMNI_DTYPE", "bf16")]
MAX_UPLOAD = 20 * 1024 * 1024
MAX_SECONDS = 38.0  # the reference pipeline caps one input at 40 s
LANG = {"si": "sin_Sinh", "ta": "tam_Taml", "en": "eng_Latn"}
# cuDNN 9 in the base image has no conv1d engine for the wav2vec2 feature extractor on the GB10
# (sm_121, bf16): "GET was unable to find an engine". torch's own convolution is used instead
# (the same cuDNN gap as decision 071's CTranslate2 build; the frontend is a small part of the cost).
torch.backends.cudnn.enabled = os.environ.get("OMNI_CUDNN", "0") == "1"

# alias -> (directory/file, fairseq2 family, arch, tokenizer file, params in billions).
# v1 checkpoints as published on Hugging Face (facebook/omniASR-*), arch names from
# omnilingual_asr/cards/models/rc_models_v1.yaml.
CATALOGUE = {
    "omniasr-llm-1b": ("omniASR-LLM-1B/omniASR-LLM-1B.pt", "wav2vec2_llama", "1b",
                       "omniASR_tokenizer.model", 2.28),
    "omniasr-llm-3b": ("omniASR-LLM-3B/omniASR-LLM-3B.pt", "wav2vec2_llama", "3b",
                       "omniASR_tokenizer.model", 4.4),
    "omniasr-llm-7b": ("omniASR-LLM-7B/omniASR-LLM-7B.pt", "wav2vec2_llama", "7b_v1_variant7",
                       "omniASR_tokenizer_v7.model", 7.8),
    "omniasr-ctc-3b": ("omniASR-CTC-3B/omniASR-CTC-3B.pt", "wav2vec2_asr", "3b",
                       "omniASR_tokenizer.model", 3.1),
    "omniasr-ctc-7b": ("omniASR-CTC-7B/omniASR-CTC-7B.pt", "wav2vec2_asr", "7b",
                       "omniASR_tokenizer.model", 6.5),
}
COMPARE = [n.strip() for n in os.environ.get("OMNI_COMPARE", "omniasr-llm-1b").split(",")
           if n.strip()]

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("omniasr")
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


def served():
    """Aliases whose checkpoint and tokenizer exist and are complete (a .pt still downloading
    fails the zip check)."""
    out = {}
    for alias, (rel, fam, arch, tok, params) in CATALOGUE.items():
        path, tok_path = os.path.join(MODELS_ROOT, rel), os.path.join(TOKENIZERS, tok)
        if os.path.isfile(path) and os.path.isfile(tok_path) and complete(path):
            out[alias] = dict(path=path, family=fam, arch=arch, tokenizer=tok_path,
                              est_mb=int(params * 1e9 * 2 / 2**20) + LOAD_OVERHEAD_MB)
    return out


def complete(path):
    """A torch zip checkpoint ends with the zip end-of-central-directory record."""
    try:
        with open(path, "rb") as f:
            f.seek(-22, os.SEEK_END)
            return f.read(4) == b"PK\x05\x06"
    except OSError:
        return False


def mem_available_mb():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    return 0


def rss_mb():
    with open("/proc/self/status") as f:
        for line in f:
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) // 1024
    return 0


try:
    _libc = ctypes.CDLL("libc.so.6")
except OSError:
    _libc = None


def trim():
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()
    if _libc is not None:
        _libc.malloc_trim(0)


class NoMemory(Exception):
    pass


_card_dir = None


def cards():
    """Local fairseq2 asset cards (checkpoint and tokenizer paths on disk; nothing downloaded)."""
    global _card_dir
    if _card_dir is None:
        _card_dir = "/tmp/fairseq2-assets"
        os.makedirs(_card_dir, exist_ok=True)
        docs = []
        for alias, s in served().items():
            docs.append(f"name: {alias}-tokenizer\ntokenizer_family: char_tokenizer\n"
                        f"tokenizer: file://{s['tokenizer']}\n")
            docs.append(f"name: {alias}\nmodel_family: {s['family']}\nmodel_arch: {s['arch']}\n"
                        f"checkpoint: file://{s['path']}\ntokenizer_ref: {alias}-tokenizer\n")
        with open(os.path.join(_card_dir, "risime.yaml"), "w") as f:
            f.write("\n---\n\n".join(docs))
        os.environ["FAIRSEQ2_ASSET_DIR"] = _card_dir
    return _card_dir


class Pool:
    """One omniASR model resident at a time."""

    def __init__(self):
        self.lock = threading.Lock()
        self.name, self.pipe, self.used_at, self.load_s = None, None, 0.0, 0.0
        self.peak_mb = 0

    def get(self, name):
        """-> (pipeline, load_s of this call). Caller holds self.lock."""
        if self.name == name:
            self.used_at = time.monotonic()
            return self.pipe, 0.0
        self._unload()
        spec = served().get(name)
        if spec is None:
            raise KeyError(name)
        avail = mem_available_mb()
        if avail - spec["est_mb"] < MIN_AVAILABLE_MB:
            raise NoMemory(f"{name} needs ~{spec['est_mb']} MB, MemAvailable {avail} MB, "
                           f"floor {MIN_AVAILABLE_MB} MB")
        cards()
        from fairseq2 import init_fairseq2
        from fairseq2.data.tokenizers.hub import load_tokenizer
        from fairseq2.models.hub import load_model
        global _fs2
        if not _fs2:
            init_fairseq2()
            _fs2 = True
        t0 = time.monotonic()
        dev = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model = load_model(name, device=dev, dtype=DTYPE, mmap=True, progress=False)
        tok = load_tokenizer(name)
        pipe = make_pipeline(model, tok, spec["tokenizer"], dev)
        trim()
        self.name, self.pipe, self.used_at = name, pipe, time.monotonic()
        self.load_s = time.monotonic() - t0
        dtypes = sorted({str(p.dtype) for p in model.parameters()})
        cuda_mb = (torch.cuda.memory_allocated() // 2**20) if torch.cuda.is_available() else 0
        log.info("loaded %s in %.1fs (params %s, CUDA allocated %d MB, process RSS %d MB, "
                 "MemAvailable %d -> %d MB)", name, self.load_s, dtypes, cuda_mb, rss_mb(),
                 avail, mem_available_mb())
        return pipe, self.load_s

    def _unload(self):
        if self.pipe is not None:
            log.info("unloading %s", self.name)
            self.name, self.pipe = None, None
            trim()

    def unload_idle(self):
        with self.lock:
            if self.pipe is not None and time.monotonic() - self.used_at > IDLE_UNLOAD_S:
                self._unload()


_fs2 = False
pool = Pool()


def auth(request: Request):
    h = request.headers.get("authorization", "")
    token = h[7:] if h.lower().startswith("bearer ") else ""
    if not API_KEY or not token or not hmac.compare_digest(token.encode(), API_KEY.encode()):
        raise HTTPException(401, detail={"error": {"message": "invalid API key",
                                                   "type": "invalid_request_error"}})


def err(code, msg):
    return JSONResponse({"error": {"message": msg, "type": "invalid_request_error"}},
                        status_code=code)


def read_audio(file):
    """Upload -> float32 mono 16 kHz numpy, in memory only. -> (audio, None) | (None, resp)."""
    data = file.file.read(MAX_UPLOAD + 1)
    try:
        if len(data) > MAX_UPLOAD:
            return None, err(413, "audio too large")
        if not data:
            return None, err(400, "empty file")
        try:
            a, sr = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
        except Exception:
            return None, err(400, "could not decode audio")
        a = a.mean(axis=1)
        if sr != 16000:
            import torchaudio.functional as F
            a = F.resample(torch.from_numpy(a), sr, 16000).numpy()
        return a, None
    finally:
        del data
        file.file.close()


def script_check(text):
    """The speech service's Sinhala script check (infra/whisper/server.py), same fields."""
    sinhala = latin = letters = 0
    for c in text or "":
        if "඀" <= c <= "෿":
            sinhala += 1
        elif c.isalpha():
            letters += 1
            if c.isascii() or unicodedata.name(c, "").startswith("LATIN"):
                latin += 1
    romanised = sinhala == 0 and letters > 0
    return ({"expected": "sinh", "sinhala_chars": sinhala, "latin_chars": latin,
             "ok": not romanised}, romanised)


class PySentencePieceDecoder:
    """fairseq2's char_tokenizer is a raw SentencePiece model; its fairseq2n (C++) decoder
    segfaults in this aarch64 build, so the same model is decoded with the `sentencepiece`
    Python package: ids -> text, control tokens (and ids past the vocabulary, e.g. the
    language/special markers the LLM allocates at vocab_size + n) skipped, as
    create_decoder(skip_special_tokens=True) does."""

    def __init__(self, path):
        import sentencepiece as spm
        self.sp = spm.SentencePieceProcessor(model_file=path)
        self.n = self.sp.get_piece_size()

    def __call__(self, token_indices):
        ids = [int(i) for i in token_indices.tolist()]
        return self.sp.decode([i for i in ids if 0 <= i < self.n and not self.sp.is_control(i)])


def make_pipeline(model, tok, tok_path, dev):
    from omnilingual_asr.models.inference.pipeline import ASRInferencePipeline
    pipe = ASRInferencePipeline(None, model=model, tokenizer=tok, device=dev, dtype=DTYPE)
    pipe.token_decoder = PySentencePieceDecoder(tok_path)
    return pipe


@torch.inference_mode()
def infer(pipe, audio, lang):
    """One utterance through Meta's model, beam search and token decoder, exactly as
    ASRInferencePipeline.transcribe does for batch_size=1, but with the batch built in plain torch:
    fairseq2n's data pipeline (read_sequence on Python objects) segfaults in this aarch64 build
    (fairseq2n compiled as C++20 against NVIDIA torch 2.14), so it is not used for audio.
    Same preprocessing: mono, the reference's utterance normalisation, the model dtype."""
    from fairseq2.datasets.batch import Seq2SeqBatch
    from omnilingual_asr.datasets.utils.audio import postprocess_waveform

    wav = postprocess_waveform(torch.from_numpy(np.ascontiguousarray(audio)),
                               normalize_audio=True, dtype=pipe.dtype, spec_aug_p=None,
                               spec_aug_freq_mask_param=0, spec_aug_time_mask_param=0)
    batch = Seq2SeqBatch(
        source_seqs=wav[None, :].to(pipe.device, pipe.dtype),
        source_seq_lens=[wav.shape[0]],
        target_seqs=torch.tensor([[0]], dtype=torch.int64, device=pipe.device),
        target_seq_lens=[1],
        example={"lang": [lang]} if lang else {},
    )
    return pipe._apply_model(batch)[0]


def run(name, audio, language):
    """-> dict(text, processing_s, load_s, peak_mb). Splits audio longer than MAX_SECONDS."""
    with pool.lock:
        pipe, load_s = pool.get(name)
        if torch.cuda.is_available():
            torch.cuda.reset_peak_memory_stats()
        t0 = time.monotonic()
        step = int(30 * 16000) if len(audio) > MAX_SECONDS * 16000 else len(audio)
        pieces = [audio[i:i + step] for i in range(0, len(audio), step)] or [audio]
        lang = LANG.get(language)
        texts = [infer(pipe, p, lang) for p in pieces]
        if torch.cuda.is_available():
            torch.cuda.synchronize()
        dt = time.monotonic() - t0
        peak = (torch.cuda.max_memory_allocated() // 2**20) if torch.cuda.is_available() else 0
        pool.peak_mb = max(pool.peak_mb, peak)
        pool.used_at = time.monotonic()
    return {"text": " ".join(t.strip() for t in texts).strip(), "processing_s": round(dt, 3),
            "load_s": round(load_s, 1), "peak_mb": int(peak)}


@app.get("/health")
def health():
    return {"status": "ok", "loaded": pool.name}


@app.get("/v1/models")
def models(request: Request):
    auth(request)
    s = served()
    return {"object": "list", "loaded": pool.name, "last_load_s": round(pool.load_s, 1),
            "peak_cuda_mb": pool.peak_mb, "min_available_mb": MIN_AVAILABLE_MB,
            "mem_available_mb": mem_available_mb(), "compare": [n for n in COMPARE if n in s],
            "data": [{"id": n, "object": "model", "owned_by": "local", "est_mb": v["est_mb"],
                      "arch": v["arch"]} for n, v in sorted(s.items())]}


@app.post("/v1/audio/transcriptions")
def transcriptions(request: Request,
                   file: UploadFile = File(...),
                   model: str = Form("omniasr-llm-1b"),
                   language: str | None = Form(None),
                   response_format: str = Form("json")):
    auth(request)
    if model not in served():
        return err(404, f"unknown model {model}; served: {sorted(served())}")
    if language is not None and language not in LANG:
        language = None
    audio, bad = read_audio(file)
    if bad:
        return bad
    duration = len(audio) / 16000
    try:
        r = run(model, audio, language)
    except NoMemory as e:
        return err(503, f"not enough memory: {e}")
    finally:
        del audio
    log.info("transcribed %.1fs audio model=%s lang=%s in %.2fs (load %.1fs, peak %d MB)",
             duration, model, language, r["processing_s"], r["load_s"], r["peak_mb"])
    if response_format == "text":
        return PlainTextResponse(r["text"])
    if response_format != "verbose_json":
        return {"text": r["text"]}
    out = {"text": r["text"], "language": language, "model": model, "duration": round(duration, 3),
           "model_processing_s": r["processing_s"], "processing_s": r["processing_s"],
           "load_s": r["load_s"], "peak_cuda_mb": r["peak_mb"]}
    if language == "si":
        out["script"], out["romanised"] = script_check(r["text"])
    return out


@app.post("/v1/audio/compare")
def compare(request: Request,
            file: UploadFile = File(...),
            language: str | None = Form(None),
            models: str | None = Form(None)):
    auth(request)
    s = served()
    names = [n.strip() for n in models.split(",")] if models else [n for n in COMPARE if n in s]
    unknown = [n for n in names if n not in s]
    if unknown:
        return err(404, f"unknown models {unknown}; served: {sorted(s)}")
    if language is not None and language not in LANG:
        language = None
    audio, bad = read_audio(file)
    if bad:
        return bad
    duration, t_all, results = len(audio) / 16000, time.monotonic(), []
    try:
        for n in names:
            try:
                r = run(n, audio, language)
                item = {"model": n, "text": r["text"], "language": language,
                        "processing_s": r["processing_s"], "load_s": r["load_s"],
                        "routed": False}
                if language == "si":
                    item["script"], item["romanised"] = script_check(r["text"])
                results.append(item)
            except NoMemory as e:
                results.append({"model": n, "error": f"not enough memory: {e}"})
            except Exception as e:  # one broken candidate must not hide the others
                log.exception("compare: %s failed", n)
                results.append({"model": n, "error": type(e).__name__})
    finally:
        del audio
    log.info("compared %.1fs audio on %d models in %.2fs lang=%s", duration, len(names),
             time.monotonic() - t_all, language)
    return {"duration": round(duration, 3), "language": language, "results": results,
            "processing_s": round(time.monotonic() - t_all, 3)}


def idle_reaper():
    while True:
        time.sleep(15)
        try:
            pool.unload_idle()
        except Exception:
            log.exception("idle unload failed")


if __name__ == "__main__":
    threading.Thread(target=idle_reaper, daemon=True).start()
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8000")),
                access_log=False, log_level="info")
