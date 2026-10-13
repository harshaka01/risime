"""risime-tts: Kokoro-82M text-to-speech on loopback (decision 090, Call Risi).

Binds 127.0.0.1 only (rule 6). Offline: the model and voices are read from KOKORO_DIR
(~/risime-eval-models/kokoro-82m), never from the Hub. Run with `python3 -I` (the model files are
downloaded data). The text to speak is never logged or written anywhere; audio lives in memory.

  GET  /health                 -> {"ok": true, "model": "kokoro-82m", "voices": n}
  GET  /v1/voices              -> {"voices": [{"id": "kokoro:af_heart", "name": "Heart (US, f)", "languages": ["en"]}]}
  POST /v1/tts {"text", "voice", "speed"?}
       -> 200, chunked raw PCM s16le mono 24 kHz, one chunk per sentence as soon as it is ready.
          Headers: X-Sample-Rate: 24000, Content-Type: audio/L16;rate=24000;channels=1
          ?format=wav -> one WAV (not streamed) for the admin console's Test.

Auth: when TTS_API_KEY is set, every request but /health needs `Authorization: Bearer <key>`.
"""

import asyncio
import io
import json
import os
import re
import struct
import threading
import time

os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
os.environ.setdefault("DO_NOT_TRACK", "1")

import numpy as np  # noqa: E402
import torch  # noqa: E402
from aiohttp import web  # noqa: E402

KOKORO_DIR = os.environ.get("KOKORO_DIR", os.path.expanduser("~/risime-eval-models/kokoro-82m"))
# 127.0.0.1 on the host; the container listens on 0.0.0.0 inside and is published on 127.0.0.1 only.
HOST = os.environ.get("TTS_HOST", "127.0.0.1")
PORT = int(os.environ.get("TTS_PORT", "8230"))
API_KEY = os.environ.get("TTS_API_KEY", "")
THREADS = int(os.environ.get("TTS_THREADS", "6"))
DEFAULT_VOICE = os.environ.get("TTS_DEFAULT_VOICE", "af_heart")
SR = 24000
DEVICE = os.environ.get("TTS_DEVICE") or ("cuda" if torch.cuda.is_available() else "cpu")
MAX_TEXT = 2000

# Kokoro voice prefixes: a = American English, b = British English (the only languages served
# here in v1; si/ta come with another engine through the admin console's per-language choice).
LANG_OF_PREFIX = {"a": ("a", "US"), "b": ("b", "UK")}
GENDER = {"f": "f", "m": "m"}

torch.set_num_threads(THREADS)
# cuDNN 9 has no engine for Kokoro's conv_transpose1d on the GB10 (sm_121): "GET was unable to find
# an engine". torch's own CUDA kernels are used instead (the same issue as decision 071's CTranslate2).
if os.environ.get("TTS_CUDNN", "0") != "1":
    torch.backends.cudnn.enabled = False
_lock = threading.Lock()
_model = None
_pipelines = {}
_voices = {}


def _load():
    global _model
    from kokoro import KModel, KPipeline

    _model = KModel(
        repo_id="hexgrad/Kokoro-82M",
        config=os.path.join(KOKORO_DIR, "config.json"),
        model=os.path.join(KOKORO_DIR, "kokoro-v1_0.pth"),
        # cuFFT fails on the GB10 ("unknown error 50"): Kokoro's conv-based STFT needs no FFT.
        disable_complex=(DEVICE == "cuda"),
    ).eval()
    if DEVICE == "cuda":
        _model = _model.to("cuda")
    for code in ("a", "b"):
        _pipelines[code] = KPipeline(lang_code=code, repo_id="hexgrad/Kokoro-82M", model=_model)
    vdir = os.path.join(KOKORO_DIR, "voices")
    for f in sorted(os.listdir(vdir)):
        name = f[:-3]
        if f.endswith(".pt") and name[:1] in LANG_OF_PREFIX:
            _voices[name] = os.path.join(vdir, f)
    # Warm-up: the first call compiles kernels and loads G2P; never on a user's sentence.
    for _ in _synth("Hello.", DEFAULT_VOICE, 1.0):
        pass


def voice_list():
    out = []
    for name in _voices:
        _, region = LANG_OF_PREFIX[name[0]]
        g = GENDER.get(name[1], "")
        pretty = name.split("_", 1)[1].replace("_", " ").title()
        out.append({"id": "kokoro:" + name, "name": f"{pretty} ({region}, {g})", "languages": ["en"]})
    return out


def _synth(text, voice, speed):
    """Yields int16 numpy arrays, one per sentence (KPipeline splits the text)."""
    pipe = _pipelines[voice[0]]
    for result in pipe(text, voice=_voices[voice], speed=speed, split_pattern=r"\n+"):
        audio = result.audio
        if audio is None:
            continue
        a = audio.detach().float().cpu().numpy() if hasattr(audio, "detach") else np.asarray(audio)
        yield (np.clip(a, -1.0, 1.0) * 32767.0).astype(np.int16)


SENTENCE = re.compile(r"(?<=[.!?…])\s+")


CLAUSE = re.compile(r"(?<=[,;:])\s+")


def _sentences(text):
    """Sentences; a long first sentence is cut at its first clause so the first audio comes sooner."""
    parts = [p.strip() for p in SENTENCE.split(text) if p.strip()] or [text.strip()]
    first = parts[0]
    if len(first.split()) > 8:
        head, *tail = CLAUSE.split(first, maxsplit=1)
        if tail and len(head.split()) >= 3:
            parts = [head, tail[0]] + parts[1:]
    return parts


def _authorised(request):
    if not API_KEY:
        return True
    return request.headers.get("Authorization", "") == "Bearer " + API_KEY


async def health(_request):
    return web.json_response({"ok": _model is not None, "model": "kokoro-82m", "voices": len(_voices),
                              "device": DEVICE})


async def voices(request):
    if not _authorised(request):
        return web.json_response({"error": "unauthorised"}, status=401)
    return web.json_response({"voices": voice_list()})


def _wav(pcm):
    data = pcm.tobytes()
    hdr = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack(
        "<IHHIIHH", 16, 1, 1, SR, SR * 2, 2, 16) + b"data" + struct.pack("<I", len(data))
    return hdr + data


async def tts(request):
    if not _authorised(request):
        return web.json_response({"error": "unauthorised"}, status=401)
    try:
        body = await request.json()
    except (json.JSONDecodeError, UnicodeDecodeError):
        return web.json_response({"error": "bad_request"}, status=400)
    text = (body.get("text") or "").strip()
    voice = (body.get("voice") or DEFAULT_VOICE).removeprefix("kokoro:")
    speed = float(body.get("speed") or 1.0)
    if not text or len(text) > MAX_TEXT or voice not in _voices or not (0.5 <= speed <= 2.0):
        return web.json_response({"error": "bad_request"}, status=400)

    loop = asyncio.get_running_loop()
    t0 = time.monotonic()

    if request.query.get("format") == "wav":
        def whole():
            with _lock:
                return np.concatenate([c for s in _sentences(text) for c in _synth(s, voice, speed)])
        pcm = await loop.run_in_executor(None, whole)
        return web.Response(body=_wav(pcm), content_type="audio/wav",
                            headers={"X-First-Audio-Ms": str(int((time.monotonic() - t0) * 1000))})

    resp = web.StreamResponse(headers={"Content-Type": "audio/L16;rate=24000;channels=1",
                                       "X-Sample-Rate": str(SR)})
    resp.enable_chunked_encoding()
    await resp.prepare(request)
    queue = asyncio.Queue()
    gone = threading.Event()

    def produce():
        try:
            with _lock:
                for s in _sentences(text):
                    if gone.is_set():
                        break
                    for chunk in _synth(s, voice, speed):
                        loop.call_soon_threadsafe(queue.put_nowait, chunk)
        finally:
            loop.call_soon_threadsafe(queue.put_nowait, None)

    fut = loop.run_in_executor(None, produce)
    try:
        while True:
            chunk = await queue.get()
            if chunk is None:
                break
            await resp.write(chunk.tobytes())
    except (ConnectionResetError, asyncio.CancelledError):
        # Barge-in: the worker hung up; stop after the sentence being synthesised.
        gone.set()
    await fut
    try:
        await resp.write_eof()
    except ConnectionResetError:
        pass
    return resp


def main():
    _load()
    app = web.Application(client_max_size=64 * 1024)
    app.router.add_get("/health", health)
    app.router.add_get("/v1/voices", voices)
    app.router.add_post("/v1/tts", tts)
    web.run_app(app, host=HOST, port=PORT, access_log=None, print=None)


if __name__ == "__main__":
    main()
