"""OpenAI-compatible speech-to-text for Risi call transcription (decision 071).

faster-whisper (CTranslate2, CUDA) behind a minimal FastAPI app:
  GET  /health                     no auth; 200 once the model is loaded
  GET  /v1/models                  Bearer auth; lists the served alias
  POST /v1/audio/transcriptions    Bearer auth; multipart like OpenAI's endpoint:
       file (required), model, language, prompt, response_format (json|text|verbose_json),
       temperature, vad_filter (extension, default true)

Privacy: audio is decoded in memory (never written to disk), no transcript text or prompt is
logged, the access log is off. Runs offline with a local model directory.
"""

import hmac
import io
import logging
import os
import threading
import time

import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse, PlainTextResponse
from faster_whisper import WhisperModel
from faster_whisper.audio import decode_audio

MODEL_DIR = os.environ.get("WHISPER_MODEL_PATH", "/models/faster-whisper-large-v3")
ALIAS = os.environ.get("WHISPER_MODEL_ALIAS", "faster-whisper-large-v3")
API_KEY = os.environ.get("WHISPER_API_KEY", "")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "float16")
NUM_WORKERS = int(os.environ.get("WHISPER_NUM_WORKERS", "2"))
BEAM_SIZE = int(os.environ.get("WHISPER_BEAM_SIZE", "5"))
MAX_UPLOAD = int(os.environ.get("WHISPER_MAX_UPLOAD_BYTES", str(25 * 1024 * 1024)))
# Names a client may ask for: the alias, and OpenAI's generic name.
ACCEPTED_MODELS = {ALIAS, "whisper-1"}

if not API_KEY:
    raise SystemExit("WHISPER_API_KEY is not set")

log = logging.getLogger("risime-whisper")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logging.getLogger("faster_whisper").setLevel(logging.WARNING)

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
model = None
# CTranslate2 runs NUM_WORKERS transcriptions in parallel; more requests wait here.
slots = threading.BoundedSemaphore(NUM_WORKERS)


def load():
    global model
    t0 = time.monotonic()
    model = WhisperModel(MODEL_DIR, device="cuda", compute_type=COMPUTE_TYPE,
                         num_workers=NUM_WORKERS, local_files_only=True)
    log.info("loaded %s (%s, %s) in %.1fs", ALIAS, MODEL_DIR, COMPUTE_TYPE, time.monotonic() - t0)


def auth(request: Request):
    h = request.headers.get("authorization", "")
    token = h[7:] if h.lower().startswith("bearer ") else ""
    if not token or not hmac.compare_digest(token.encode(), API_KEY.encode()):
        raise HTTPException(401, detail={"error": {"message": "invalid API key",
                                                   "type": "invalid_request_error"}})


@app.get("/health")
def health():
    if model is None:
        return JSONResponse({"status": "loading"}, status_code=503)
    return {"status": "ok", "model": ALIAS}


@app.get("/v1/models")
def models(request: Request):
    auth(request)
    return {"object": "list",
            "data": [{"id": ALIAS, "object": "model", "owned_by": "risime"}]}


def err(code, msg):
    return JSONResponse({"error": {"message": msg, "type": "invalid_request_error"}},
                        status_code=code)


@app.post("/v1/audio/transcriptions")
def transcriptions(request: Request,
                   file: UploadFile = File(...),
                   model_name: str = Form(ALIAS, alias="model"),
                   language: str | None = Form(None),
                   prompt: str | None = Form(None),
                   response_format: str = Form("json"),
                   temperature: float = Form(0.0),
                   vad_filter: bool = Form(True)):
    auth(request)
    if model is None:
        return err(503, "model loading")
    if model_name not in ACCEPTED_MODELS:
        return err(404, f"model not served; use {ALIAS}")
    if response_format not in ("json", "text", "verbose_json"):
        return err(400, "response_format must be json, text or verbose_json")
    data = file.file.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        return err(413, "audio too large")
    if not data:
        return err(400, "empty file")
    try:
        audio = decode_audio(io.BytesIO(data), sampling_rate=16000)
    except Exception:
        return err(400, "could not decode audio")
    finally:
        del data
    t0 = time.monotonic()
    with slots:
        segments, info = model.transcribe(
            audio, language=language or None, initial_prompt=prompt or None,
            temperature=temperature if temperature > 0 else [0.0, 0.2, 0.4, 0.6, 0.8, 1.0],
            beam_size=BEAM_SIZE, vad_filter=vad_filter,
            condition_on_previous_text=False)
        segs = list(segments)
    elapsed = time.monotonic() - t0
    text = "".join(s.text for s in segs).strip()
    # Metrics only, never text (decision 066: learn and delete).
    log.info("transcribed %.1fs audio in %.2fs lang=%s segments=%d",
             info.duration, elapsed, info.language, len(segs))
    if response_format == "text":
        return PlainTextResponse(text)
    if response_format == "json":
        return {"text": text}
    return {
        "task": "transcribe", "language": info.language,
        "language_probability": round(info.language_probability, 4),
        "duration": round(info.duration, 3),
        "duration_after_vad": round(info.duration_after_vad, 3),
        "text": text, "model": ALIAS, "processing_s": round(elapsed, 3),
        "segments": [{"id": i, "start": round(s.start, 3), "end": round(s.end, 3),
                      "text": s.text, "avg_logprob": round(s.avg_logprob, 4),
                      "no_speech_prob": round(s.no_speech_prob, 4),
                      "compression_ratio": round(s.compression_ratio, 4)}
                     for i, s in enumerate(segs)],
    }


if __name__ == "__main__":
    load()
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("PORT", "8000")),
                access_log=False, log_level="info")
