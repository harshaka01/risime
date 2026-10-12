"""OpenAI-compatible speech-to-text for Risi call transcription (decision 071 + 2026-10-12 amendment).

Several local models behind one minimal FastAPI app; each loads lazily and the least recently used
one is unloaded when the memory budget would be exceeded. The detector model (faster-whisper
large-v3) is loaded at start and never unloaded.

  GET  /health                     no auth; 200 once the detector model is loaded
  GET  /v1/models                  Bearer auth; served models, loaded state, memory budget
  POST /v1/audio/transcriptions    Bearer auth; multipart like OpenAI's endpoint:
       file (required), model, language, prompt, response_format (json|text|verbose_json),
       temperature, vad_filter (extension, default true)
       model=auto routes by detected language: si -> whisper-small-sinhala,
       ta -> tamil-asr-qwen3, en (and anything else) -> faster-whisper-large-v3
  POST /v1/audio/compare           Bearer auth; file, language, models (comma list, optional):
       runs every candidate sequentially (one model resident at a time beyond the pool) and
       returns model, text, language, processing_s for each

Privacy: audio is decoded in memory (never written to disk), no transcript text or prompt is
logged, the access log is off. Runs offline with local model directories.
"""

import collections
import ctypes
import gc
import hmac
import io
import logging
import os
import threading
import time

import numpy as np
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse
from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio
from faster_whisper.vad import VadOptions, get_speech_timestamps

API_KEY = os.environ.get("WHISPER_API_KEY", "")
MODELS_ROOT = os.environ.get("WHISPER_MODELS_ROOT", "/models")
DEFAULT = os.environ.get("WHISPER_MODEL_ALIAS", "faster-whisper-large-v3")
DEFAULT_DIR = os.environ.get("WHISPER_MODEL_PATH", f"{MODELS_ROOT}/faster-whisper-large-v3")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "float16")
NUM_WORKERS = int(os.environ.get("WHISPER_NUM_WORKERS", "2"))
BEAM_SIZE = int(os.environ.get("WHISPER_BEAM_SIZE", "5"))
QWEN_BEAMS = int(os.environ.get("QWEN_ASR_BEAMS", "1"))
MAX_UPLOAD = int(os.environ.get("WHISPER_MAX_UPLOAD_BYTES", str(25 * 1024 * 1024)))
# Sum of the loaded models' resident estimates (MB) may not exceed this.
BUDGET_MB = int(os.environ.get("WHISPER_MEM_BUDGET_MB", "11000"))
# Host MemAvailable must stay above this after a load (protects risi-l1 and the pilot).
MIN_AVAIL_MB = int(os.environ.get("WHISPER_MIN_AVAILABLE_MB", "3072"))
ROUTE = {"si": "whisper-small-sinhala", "ta": "tamil-asr-qwen3", "en": DEFAULT}
ROUTE_LANGS = tuple(ROUTE)
# Below this (renormalised over si/ta/en) the chunk goes to the default model, which detects itself.
MIN_ROUTE_P = float(os.environ.get("WHISPER_ROUTE_MIN_P", "0.5"))

# name -> spec. est_mb: measured resident memory (GPU unified + host MemAvailable drop, with
# CT2_CUDA_ALLOCATOR=cuda_malloc_async), see decision 071. idle_s: unload after this long unused
# (None = keep); the default/detector model is never unloaded.
SPECS = {
    DEFAULT: dict(kind="ct2", path=DEFAULT_DIR, compute=COMPUTE_TYPE, workers=NUM_WORKERS,
                  est_mb=int(os.environ.get("WHISPER_EST_MB_DEFAULT", "3000")), lang=None,
                  role="default", idle_s=None),
    "whisper-small-sinhala": dict(kind="ct2", path=f"{MODELS_ROOT}/faster-whisper-small-sinhala",
                                  compute="float16", workers=1, est_mb=1000, lang="si",
                                  role="route", idle_s=None),
    "tamil-asr-qwen3": dict(kind="qwen3", path=f"{MODELS_ROOT}/tamil-asr-qwen3", est_mb=6700,
                            lang="ta", role="route",
                            idle_s=int(os.environ.get("WHISPER_IDLE_UNLOAD_S", "900"))),
    "whisper-large-v3-sinhala": dict(kind="ct2",
                                     path=f"{MODELS_ROOT}/faster-whisper-large-v3-sinhala",
                                     compute="int8_float16", workers=1, est_mb=2200, lang="si",
                                     role="compare", idle_s=120),
    "faster-whisper-large-v3-turbo": dict(kind="ct2",
                                          path=f"{MODELS_ROOT}/faster-whisper-large-v3-turbo",
                                          compute="float16", workers=1, est_mb=2400, lang=None,
                                          role="compare", idle_s=120),
}
SPECS = {n: s for n, s in SPECS.items()
         if os.path.isfile(os.path.join(s["path"], "model.bin" if s["kind"] == "ct2"
                                        else "model.safetensors"))}
if DEFAULT not in SPECS:
    raise SystemExit(f"default model missing: {DEFAULT_DIR}")
ACCEPTED = set(SPECS) | {"whisper-1", "auto"}

if not API_KEY:
    raise SystemExit("WHISPER_API_KEY is not set")

log = logging.getLogger("risime-whisper")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logging.getLogger("faster_whisper").setLevel(logging.WARNING)
logging.getLogger("transformers").setLevel(logging.ERROR)

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
# At most NUM_WORKERS transcriptions run at once; more requests wait here.
slots = threading.BoundedSemaphore(NUM_WORKERS)
compare_lock = threading.Lock()


def mem_available_mb():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    return 0


try:
    _libc = ctypes.CDLL("libc.so.6")
except OSError:
    _libc = None


def trim():
    """Hand freed host heap back to the OS: a load stages and converts weights in host RAM
    (CTranslate2 int8 conversion, torch), and glibc keeps those pages otherwise."""
    gc.collect()
    if _libc is not None:
        _libc.malloc_trim(0)


class NoMemory(Exception):
    pass


class Pool:
    """Lazily loaded models, LRU-evicted against BUDGET_MB and MIN_AVAIL_MB."""

    def __init__(self):
        self.lock = threading.Condition()
        self.loaded = collections.OrderedDict()  # name -> model (most recent last)
        self.in_use = collections.Counter()
        self.loading = set()
        self.measured = {}  # name -> MemAvailable drop at load (MB), informative
        self.last_used = {}

    def _used(self):
        return sum(SPECS[n]["est_mb"] for n in list(self.loaded) + list(self.loading))

    def _evict_one(self, keep):
        for n in self.loaded:
            if n != DEFAULT and n != keep and self.in_use[n] == 0:
                m = self.loaded.pop(n)
                self._close(n, m)
                return True
        return False

    @staticmethod
    def _close(name, m):
        t0 = time.monotonic()
        if SPECS[name]["kind"] == "qwen3":
            m.close()
        else:
            m.model.unload_model()
        del m
        trim()
        log.info("unloaded %s in %.1fs (available %d MB)", name, time.monotonic() - t0,
                 mem_available_mb())

    def acquire(self, name):
        with self.lock:
            while name in self.loading:
                self.lock.wait()
            if name in self.loaded:
                self.loaded.move_to_end(name)
                self.in_use[name] += 1
                self.last_used[name] = time.monotonic()
                return self.loaded[name]
            need = SPECS[name]["est_mb"]
            # The detector/default model is the baseline: it always loads.
            while name != DEFAULT and (self._used() + need > BUDGET_MB or mem_available_mb() - need < MIN_AVAIL_MB):
                if not self._evict_one(keep=name):
                    raise NoMemory(f"{name} needs ~{need} MB: budget {BUDGET_MB} MB, "
                                   f"{self._used()} MB loaded, {mem_available_mb()} MB available, "
                                   f"floor {MIN_AVAIL_MB} MB")
            self.loading.add(name)
        try:
            before = mem_available_mb()
            m = load(name)
            drop = before - mem_available_mb()
        finally:
            with self.lock:
                self.loading.discard(name)
                self.lock.notify_all()
        with self.lock:
            self.loaded[name] = m
            self.measured[name] = drop
            self.in_use[name] += 1
            self.last_used[name] = time.monotonic()
            return m

    def unload_idle(self):
        """Unload models idle past their idle_s, and, while host MemAvailable is under the floor
        (other workloads grew after our load), every unused non-default model, LRU first."""
        with self.lock:
            now = time.monotonic()
            for n in list(self.loaded):
                idle = SPECS[n]["idle_s"]
                if (n != DEFAULT and idle is not None and self.in_use[n] == 0
                        and now - self.last_used.get(n, now) > idle):
                    self._close(n, self.loaded.pop(n))
            while mem_available_mb() < MIN_AVAIL_MB:
                avail = mem_available_mb()
                if not self._evict_one(keep=None):
                    break
                log.warning("memory pressure: MemAvailable %d MB < floor %d MB, unloaded LRU",
                            avail, MIN_AVAIL_MB)

    def release(self, name, unload=False):
        with self.lock:
            self.in_use[name] -= 1
            self.last_used[name] = time.monotonic()
            if unload and name != DEFAULT and self.in_use[name] == 0 and name in self.loaded:
                self._close(name, self.loaded.pop(name))

    def status(self):
        with self.lock:
            return list(self.loaded), dict(self.measured)


pool = Pool()


def load(name):
    s = SPECS[name]
    t0 = time.monotonic()
    if s["kind"] == "ct2":
        m = WhisperModel(s["path"], device="cuda", compute_type=s["compute"],
                         num_workers=s["workers"], local_files_only=True)
    else:
        from qwen3_asr import Qwen3ASR  # torch/transformers only when needed
        m = Qwen3ASR(s["path"], num_beams=QWEN_BEAMS)
    trim()
    log.info("loaded %s (%s, %s) in %.1fs", name, s["path"], s.get("compute", "bf16"),
             time.monotonic() - t0)
    return m


def auth(request: Request):
    h = request.headers.get("authorization", "")
    token = h[7:] if h.lower().startswith("bearer ") else ""
    if not token or not hmac.compare_digest(token.encode(), API_KEY.encode()):
        raise HTTPException(401, detail={"error": {"message": "invalid API key",
                                                   "type": "invalid_request_error"}})


def err(code, msg):
    return JSONResponse({"error": {"message": msg, "type": "invalid_request_error"}},
                        status_code=code)


def read_audio(file):
    """Upload -> float32 16 kHz mono, in memory only. Returns (audio, None) or (None, response)."""
    data = file.file.read(MAX_UPLOAD + 1)
    try:
        if len(data) > MAX_UPLOAD:
            return None, err(413, "audio too large")
        if not data:
            return None, err(400, "empty file")
        try:
            return decode_audio(io.BytesIO(data), sampling_rate=16000), None
        except Exception:
            return None, err(400, "could not decode audio")
    finally:
        del data
        file.file.close()  # SpooledTemporaryFile: in memory below 1 MB; closed either way


def has_speech(audio):
    return bool(get_speech_timestamps(audio, VadOptions(min_silence_duration_ms=500)))


def detect(audio):
    """Language of the chunk, restricted to si/ta/en. Returns a dict (detector = DEFAULT)."""
    t0 = time.monotonic()
    m = pool.acquire(DEFAULT)
    try:
        with slots:
            top, top_p, probs = m.detect_language(audio, vad_filter=True,
                                                  language_detection_segments=1)
    finally:
        pool.release(DEFAULT)
    probs = dict(probs)
    raw = {lang: probs.get(lang, 0.0) for lang in ROUTE_LANGS}
    total = sum(raw.values())
    norm = {k: (v / total if total > 0 else 0.0) for k, v in raw.items()}
    best = max(norm, key=norm.get)
    return {"language": best, "probability": round(norm[best], 4),
            "probs": {k: round(v, 4) for k, v in norm.items()},
            "raw_probs": {k: round(v, 4) for k, v in raw.items()},
            "whisper_top": top, "whisper_top_p": round(top_p, 4),
            "detect_s": round(time.monotonic() - t0, 3)}


def run(name, audio, language=None, prompt=None, temperature=0.0, vad_filter=True,
        unload_after=False):
    """Transcribe with one model. Returns a result dict (no text is logged)."""
    s = SPECS[name]
    lang = s["lang"] or language
    t_load = time.monotonic()
    m = pool.acquire(name)
    load_s = time.monotonic() - t_load
    try:
        t0 = time.monotonic()
        with slots:
            if s["kind"] == "ct2":
                segments, info = m.transcribe(
                    audio, language=lang or None, initial_prompt=prompt or None,
                    temperature=temperature if temperature > 0 else [0.0, 0.2, 0.4, 0.6, 0.8, 1.0],
                    beam_size=BEAM_SIZE, vad_filter=vad_filter,
                    condition_on_previous_text=False)
                segs = list(segments)
                text = "".join(x.text for x in segs).strip()
                out = {"language": info.language,
                       "language_probability": round(info.language_probability, 4),
                       "duration_after_vad": round(info.duration_after_vad, 3),
                       "segments": [{"id": i, "start": round(x.start, 3), "end": round(x.end, 3),
                                     "text": x.text, "avg_logprob": round(x.avg_logprob, 4),
                                     "no_speech_prob": round(x.no_speech_prob, 4),
                                     "compression_ratio": round(x.compression_ratio, 4)}
                                    for i, x in enumerate(segs)]}
            else:
                text, _ = m.transcribe(audio, prompt=prompt)
                out = {"language": s["lang"], "segments": []}
        elapsed = time.monotonic() - t0
    finally:
        pool.release(name, unload=unload_after)
    out.update(text=text, model=name, processing_s=round(elapsed, 3), load_s=round(load_s, 3))
    return out


@app.get("/health")
def health():
    loaded, _ = pool.status()
    if DEFAULT not in loaded:
        return JSONResponse({"status": "loading"}, status_code=503)
    return {"status": "ok", "model": DEFAULT, "loaded": loaded}


@app.get("/v1/models")
def models(request: Request):
    auth(request)
    loaded, measured = pool.status()
    data = [{"id": n, "object": "model", "owned_by": "risime", "role": s["role"],
             "language": s["lang"], "loaded": n in loaded, "est_mb": s["est_mb"],
             **({"measured_load_mb": measured[n]} if n in measured else {})}
            for n, s in SPECS.items()]
    data.append({"id": "auto", "object": "model", "owned_by": "risime", "role": "router",
                 "routes": {k: v for k, v in ROUTE.items() if v in SPECS}})
    return {"object": "list", "data": data,
            "memory": {"budget_mb": BUDGET_MB, "min_available_mb": MIN_AVAIL_MB,
                       "available_mb": mem_available_mb()}}


@app.post("/v1/audio/transcriptions")
def transcriptions(request: Request,
                   file: UploadFile = File(...),
                   model_name: str = Form(DEFAULT, alias="model"),
                   language: str | None = Form(None),
                   prompt: str | None = Form(None),
                   response_format: str = Form("json"),
                   temperature: float = Form(0.0),
                   vad_filter: bool = Form(True)):
    auth(request)
    loaded, _ = pool.status()
    if DEFAULT not in loaded:
        return err(503, "model loading")
    if model_name not in ACCEPTED:
        return err(404, f"model not served; use one of {sorted(ACCEPTED)}")
    if response_format not in ("json", "text", "verbose_json"):
        return err(400, "response_format must be json, text or verbose_json")
    audio, bad = read_audio(file)
    if bad:
        return bad
    duration = len(audio) / 16000
    t_all = time.monotonic()
    routing = None
    name = DEFAULT if model_name == "whisper-1" else model_name
    try:
        if name == "auto":
            if vad_filter and not has_speech(audio):
                routing = {"reason": "no_speech"}
                res = {"text": "", "model": DEFAULT, "language": None, "segments": [],
                       "processing_s": 0.0, "load_s": 0.0}
            else:
                if language in ROUTE:
                    routing = {"reason": "language_hint", "language": language}
                else:
                    routing = {"reason": "detected", **detect(audio)}
                    if routing["probability"] < MIN_ROUTE_P:
                        routing["reason"] = "low_confidence"
                lang = routing.get("language")
                target = ROUTE.get(lang, DEFAULT) if routing["reason"] != "low_confidence" \
                    else DEFAULT
                if target not in SPECS:
                    target, routing["fallback"] = DEFAULT, "model_missing"
                try:
                    res = run(target, audio, language=lang if target == DEFAULT and
                              routing["reason"] != "low_confidence" else None,
                              prompt=prompt, temperature=temperature, vad_filter=vad_filter)
                except NoMemory:
                    routing["fallback"] = "memory"
                    res = run(DEFAULT, audio, language=lang, prompt=prompt,
                              temperature=temperature, vad_filter=vad_filter)
        else:
            try:
                res = run(name, audio, language=language, prompt=prompt,
                          temperature=temperature, vad_filter=vad_filter)
            except NoMemory as e:
                return err(503, f"not enough memory to load {name}: {e}")
    finally:
        del audio
    total = time.monotonic() - t_all
    # Metrics only, never text (decision 066: learn and delete).
    log.info("transcribed %.1fs audio in %.2fs model=%s req=%s lang=%s route=%s", duration, total,
             res["model"], model_name, res.get("language"),
             routing and routing.get("reason"))
    if response_format == "text":
        return PlainTextResponse(res["text"])
    if response_format == "json":
        body = {"text": res["text"]}
        if model_name == "auto":
            body.update(model=res["model"], language=res.get("language"))
        return body
    body = {"task": "transcribe", "language": res.get("language"),
            "language_probability": res.get("language_probability"),
            "duration": round(duration, 3), "duration_after_vad": res.get("duration_after_vad"),
            "text": res["text"], "model": res["model"], "processing_s": round(total, 3),
            "model_processing_s": res["processing_s"], "load_s": res["load_s"],
            "segments": res["segments"]}
    if routing is not None:
        body["routing"] = routing
    return body


@app.post("/v1/audio/compare")
def compare(request: Request,
            file: UploadFile = File(...),
            language: str | None = Form(None),
            models: str | None = Form(None),
            prompt: str | None = Form(None)):
    """Every candidate on the same chunk, one after another. Models outside the resident pool
    (compare-only ones) are unloaded right after use, so this never holds more than one extra."""
    auth(request)
    loaded, _ = pool.status()
    if DEFAULT not in loaded:
        return err(503, "model loading")
    names = [n.strip() for n in models.split(",")] if models else list(SPECS)
    unknown = [n for n in names if n not in SPECS]
    if unknown:
        return err(404, f"unknown models {unknown}; served: {sorted(SPECS)}")
    audio, bad = read_audio(file)
    if bad:
        return bad
    duration = len(audio) / 16000
    t_all = time.monotonic()
    try:
        with compare_lock:
            det = detect(audio)
            routed = ROUTE.get(det["language"], DEFAULT) if det["probability"] >= MIN_ROUTE_P \
                else DEFAULT
            results = []
            for n in names:
                try:
                    r = run(n, audio, language=language, prompt=prompt,
                            unload_after=SPECS[n]["role"] == "compare")
                    results.append({"model": n, "text": r["text"], "language": r.get("language"),
                                    "processing_s": r["processing_s"], "load_s": r["load_s"],
                                    "routed": n == routed})
                except NoMemory as e:
                    results.append({"model": n, "error": f"not enough memory: {e}"})
                except Exception as e:  # one broken candidate must not hide the others
                    log.exception("compare: %s failed", n)
                    results.append({"model": n, "error": type(e).__name__})
    finally:
        del audio
    log.info("compared %.1fs audio on %d models in %.2fs", duration, len(names),
             time.monotonic() - t_all)
    return {"duration": round(duration, 3), "detected": det, "routed_model": routed,
            "results": results, "processing_s": round(time.monotonic() - t_all, 3)}


def idle_reaper():
    while True:
        time.sleep(10)
        try:
            pool.unload_idle()
        except Exception:
            log.exception("idle unload failed")


if __name__ == "__main__":
    pool.acquire(DEFAULT)
    pool.release(DEFAULT)
    threading.Thread(target=idle_reaper, daemon=True).start()
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8000")),
                access_log=False, log_level="info")
