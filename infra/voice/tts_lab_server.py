"""risime-tts-lab: the TTS candidates for the admin console's Voices page (2026-10-14).

Console tests only. Call Risi keeps Kokoro in risime-tts (127.0.0.1:8230), always resident; this
service never touches it. Binds 127.0.0.1 only (rule 6; inside the container 0.0.0.0, published on
127.0.0.1:8231). Offline: every model is read from /models (~/risime-eval-models, read-only).
The text to speak is never logged or stored; audio lives in memory.

  GET  /health       -> {"ok": true, "loaded": engine | null}
  GET  /v1/engines   -> {"engines": [{id, status, voices, languages, missing, est_mb, loaded}],
                         "mem_available_mb", "floor_mb", "paused"}
       status: ready (weights here, loads on demand) | loaded | downloading (weights missing) |
               review_pending (IndicF5 until its code review clears the pinned revision)
  POST /v1/tts {"text", "voice": "<engine>:<voice>"} -> one WAV (s16 mono, the engine's rate)
       headers X-First-Audio-Ms (first sentence, after the load), X-Load-Ms (this request's load,
       0 if resident), X-Audio-Ms, X-Total-Ms[, X-Stream-First-Audio-Ms (Orpheus: 4 SNAC frames)]
       errors (JSON {"error"}): 400 bad_request, 409 downloading | review_pending,
       429 busy, 503 no_memory | paused | load_failed, 500 failed
  POST /v1/unload    -> unloads the loaded engine

Memory (GB10 unified memory, shared with risi-l1, whisper and Kokoro): one engine at a time, each in
its own process (tts_lab_worker.py), so an unload frees everything. A load is refused when host
MemAvailable minus the engine's estimate would fall below TTS_LAB_MIN_AVAILABLE_MB (default 4096),
and a load or synthesis is stopped if MemAvailable falls below the floor while it runs. Idle engines
are unloaded after TTS_LAB_IDLE_UNLOAD_S (default 300).

Live first: while the shared speech-guard pause file says paused (written after a slow live voice
note; docs/status/speech-eval-guard.md) the loaded engine is unloaded and work is refused (503
paused). The app server refuses console tests while a Call Risi call runs.

Auth: when TTS_API_KEY is set, every request but /health needs `Authorization: Bearer <key>`.
"""

import asyncio
import hmac
import json
import os
import struct
import subprocess
import sys
import time
import base64

from aiohttp import web

HOST = os.environ.get("TTS_LAB_HOST", "127.0.0.1")
PORT = int(os.environ.get("TTS_LAB_PORT", "8231"))
API_KEY = os.environ.get("TTS_API_KEY", "")
MODELS = os.environ.get("TTS_LAB_MODELS", "/models")
FLOOR_MB = int(os.environ.get("TTS_LAB_MIN_AVAILABLE_MB", "4096"))
IDLE_S = int(os.environ.get("TTS_LAB_IDLE_UNLOAD_S", "300"))
LOAD_TIMEOUT_S = int(os.environ.get("TTS_LAB_LOAD_TIMEOUT_S", "600"))
SYNTH_TIMEOUT_S = int(os.environ.get("TTS_LAB_SYNTH_TIMEOUT_S", "170"))
PAUSE_FILE = os.environ.get("TTS_LAB_PAUSE_FILE", "/guard/speech-eval.pause")
WORKER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tts_lab_worker.py")
# IndicF5 runs only when this equals the revision cleared in docs/status/tts-indicf5-code-review.md.
INDICF5_CLEARED = os.environ.get("TTS_LAB_INDICF5_CLEARED", "")
MAX_TEXT = 300

PARLER = {"ta": ["Jaya", "Kavitha"], "en": ["Mary", "Thoma"]}
ORPHEUS = ["tara", "leah", "jess", "leo", "dan", "mia", "zac", "zoe"]

# Each engine: the files that must be present, the estimated unified memory of a loaded engine (MB:
# weights in the dtype used + CUDA context + activations), its languages and its stock voices.
ENGINES = {
    "chatterbox": {
        "files": ["chatterbox/ve.safetensors", "chatterbox/t3_cfg.safetensors",
                  "chatterbox/s3gen.safetensors", "chatterbox/tokenizer.json", "chatterbox/conds.pt"],
        "est_mb": 4500, "languages": ["en"],
        "voices": [{"id": "chatterbox:default", "name": "Chatterbox default (en, built-in)",
                    "languages": ["en"]}],
    },
    "indic-parler": {
        "files": ["indic-parler-tts/model.safetensors", "indic-parler-tts/config.json",
                  "indic-parler-tts/tokenizer.json", "flan-t5-large-tokenizer/tokenizer.json"],
        "est_mb": 5000, "languages": ["ta", "en"],
        "voices": [{"id": f"indic-parler:{lang}:{n.lower()}", "name": f"Indic Parler {n} ({lang})",
                    "languages": [lang]} for lang, ns in PARLER.items() for n in ns],
    },
    "orpheus": {
        "files": ["orpheus-3b-0.1-ft/model.safetensors.index.json"] +
                 [f"orpheus-3b-0.1-ft/model-0000{i}-of-00004.safetensors" for i in range(1, 5)] +
                 ["orpheus-3b-0.1-ft/tokenizer.json", "snac_24khz/pytorch_model.bin",
                  "snac_24khz/config.json"],
        "est_mb": 9000, "languages": ["en"],
        "voices": [{"id": f"orpheus:{v}", "name": f"Orpheus {v.title()} (en)", "languages": ["en"]}
                   for v in ORPHEUS],
    },
    "indicf5": {
        "files": ["indicf5/model.safetensors", "indicf5/model.py", "indicf5/config.json",
                  "indicf5/checkpoints/vocab.txt"],
        "est_mb": 3000, "languages": ["ta"],
        "voices": [{"id": "indicf5:ta:stock-1", "name": "IndicF5 stock prompt 1 (ta; a dataset speaker)",
                    "languages": ["ta"]}],
    },
}


# Estimates can be tuned from the measured numbers without a rebuild: TTS_LAB_EST_<ENGINE>_MB.
for _eid, _e in ENGINES.items():
    _v = os.environ.get("TTS_LAB_EST_" + _eid.upper().replace("-", "_") + "_MB")
    if _v:
        _e["est_mb"] = int(_v)

# tts_lab_selftest.py only: a tone engine that exercises load, unload, the floor and the headers.
if os.environ.get("TTS_LAB_SELFTEST") == "1":
    ENGINES["selftest"] = {"files": [], "est_mb": int(os.environ.get("TTS_LAB_SELFTEST_MB", "0")),
                           "languages": ["en"],
                           "voices": [{"id": "selftest:tone", "name": "Tone", "languages": ["en"]}]}


def mem_available_mb():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    return 0


def paused():
    try:
        with open(PAUSE_FILE) as f:
            body = f.read()
    except OSError:
        return False
    for tok in body.split():
        if tok.startswith("until="):
            try:
                return int(tok[6:]) > time.time()
            except ValueError:
                return True
    return True


def missing(eid):
    return [p for p in ENGINES[eid]["files"] if not os.path.isfile(os.path.join(MODELS, p))]


class Pool:
    """One engine process at a time."""

    def __init__(self):
        self.lock = asyncio.Lock()
        self.name, self.proc, self.used_at, self.sr = None, None, 0.0, 0

    def status(self, eid):
        if self.name == eid and self.proc is not None:
            return "loaded"
        if missing(eid):
            return "downloading"
        if eid == "indicf5" and not INDICF5_CLEARED:
            return "review_pending"
        return "ready"

    async def stop(self):
        p, self.name, self.proc = self.proc, None, None
        if p is not None:
            print(f"tts-lab: unloading {self.name_of(p)}", flush=True)
            try:
                p.stdin.close()
            except Exception:  # noqa: BLE001
                pass
            try:
                await asyncio.wait_for(p.wait(), 10)
            except asyncio.TimeoutError:
                p.kill()
                await p.wait()

    @staticmethod
    def name_of(p):
        return getattr(p, "_engine", "?")

    async def _watch(self, coro):
        """Runs `coro`; stops the engine and raises MemoryError if MemAvailable < floor meanwhile."""
        task = asyncio.ensure_future(coro)
        while not task.done():
            done, _ = await asyncio.wait({task}, timeout=0.5)
            if done:
                break
            if mem_available_mb() < FLOOR_MB:
                task.cancel()
                if self.proc is not None:
                    self.proc.kill()
                await self.stop()
                raise MemoryError("MemAvailable below the floor while running")
        return task.result()

    async def load(self, eid):
        """-> load_ms (0 when already resident)."""
        if self.name == eid and self.proc is not None and self.proc.returncode is None:
            return 0
        await self.stop()
        avail = mem_available_mb()
        if avail - ENGINES[eid]["est_mb"] < FLOOR_MB:
            raise MemoryError(f"{eid} needs ~{ENGINES[eid]['est_mb']} MB, MemAvailable {avail} MB, "
                              f"floor {FLOOR_MB} MB")
        t0 = time.monotonic()
        p = await asyncio.create_subprocess_exec(
            sys.executable, "-I", WORKER, eid, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            limit=64 * 1024 * 1024)
        p._engine = eid
        self.name, self.proc = eid, p
        print(f"tts-lab: loading {eid} (MemAvailable {avail} MB)", flush=True)
        line = await self._watch(asyncio.wait_for(p.stdout.readline(), LOAD_TIMEOUT_S))
        msg = json.loads(line or b"{}")
        if not msg.get("ready"):
            await self.stop()
            raise RuntimeError("load_failed")
        self.sr, self.used_at = int(msg["sr"]), time.monotonic()
        ms = int((time.monotonic() - t0) * 1000)
        print(f"tts-lab: loaded {eid} in {ms} ms on {msg.get('device')} "
              f"(MemAvailable {avail} -> {mem_available_mb()} MB)", flush=True)
        return ms

    async def speak(self, text, voice):
        p = self.proc
        p.stdin.write((json.dumps({"text": text, "voice": voice}) + "\n").encode())
        await p.stdin.drain()
        line = await self._watch(asyncio.wait_for(p.stdout.readline(), SYNTH_TIMEOUT_S))
        self.used_at = time.monotonic()
        return json.loads(line or b"{}")


pool = Pool()


def authorised(request):
    if not API_KEY:
        return True
    return hmac.compare_digest(request.headers.get("Authorization", ""), "Bearer " + API_KEY)


def err(status, code):
    return web.json_response({"error": code}, status=status)


def wav(pcm, sr):
    hdr = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack(
        "<IHHIIHH", 16, 1, 1, sr, sr * 2, 2, 16) + b"data" + struct.pack("<I", len(pcm))
    return hdr + pcm


async def health(_request):
    return web.json_response({"ok": True, "loaded": pool.name})


async def engines(request):
    if not authorised(request):
        return err(401, "unauthorised")
    out = []
    for eid, e in ENGINES.items():
        out.append({"id": eid, "status": pool.status(eid), "voices": e["voices"],
                    "languages": e["languages"], "missing": missing(eid), "est_mb": e["est_mb"],
                    "loaded": pool.name == eid})
    return web.json_response({"engines": out, "mem_available_mb": mem_available_mb(),
                              "floor_mb": FLOOR_MB, "paused": paused()})


async def tts(request):
    if not authorised(request):
        return err(401, "unauthorised")
    try:
        body = await request.json()
    except (json.JSONDecodeError, UnicodeDecodeError):
        return err(400, "bad_request")
    text = (body.get("text") or "").strip()
    voice = body.get("voice") or ""
    eid = voice.split(":", 1)[0]
    if not text or len(text) > MAX_TEXT or eid not in ENGINES or \
            voice not in {v["id"] for v in ENGINES[eid]["voices"]}:
        return err(400, "bad_request")
    st = pool.status(eid)
    if st in ("downloading", "review_pending"):
        return err(409, st)
    if paused():
        return err(503, "paused")
    if pool.lock.locked():
        return err(429, "busy")
    async with pool.lock:
        t0 = time.monotonic()
        try:
            load_ms = await pool.load(eid)
            r = await pool.speak(text, voice)
        except MemoryError as e:
            print(f"tts-lab: refused: {e}", flush=True)
            return err(503, "no_memory")
        except (RuntimeError, asyncio.TimeoutError, json.JSONDecodeError, BrokenPipeError,
                ConnectionResetError) as e:
            print(f"tts-lab: {eid} failed: {type(e).__name__}", flush=True)
            await pool.stop()
            return err(503, "load_failed")
        if not r.get("ok"):
            return err(500, r.get("error") or "failed")
        pcm = base64.b64decode(r["pcm"])
        sr = int(r["sr"])
        headers = {"X-First-Audio-Ms": str(r.get("first_ms", 0)), "X-Load-Ms": str(load_ms),
                   "X-Audio-Ms": str(len(pcm) * 1000 // (2 * sr)),
                   "X-Total-Ms": str(int((time.monotonic() - t0) * 1000)),
                   "X-Synth-Ms": str(r.get("total_ms", 0))}
        if "stream_first_ms" in r:
            headers["X-Stream-First-Audio-Ms"] = str(r["stream_first_ms"])
        return web.Response(body=wav(pcm, sr), content_type="audio/wav", headers=headers)


async def unload(request):
    if not authorised(request):
        return err(401, "unauthorised")
    async with pool.lock:
        await pool.stop()
    return web.json_response({"ok": True})


async def janitor(_app):
    async def loop():
        while True:
            await asyncio.sleep(5)
            if pool.proc is None or pool.lock.locked():
                continue
            idle = time.monotonic() - pool.used_at > IDLE_S
            if idle or paused() or mem_available_mb() < FLOOR_MB:
                async with pool.lock:
                    await pool.stop()
    task = asyncio.ensure_future(loop())
    yield
    task.cancel()
    await pool.stop()


def main():
    app = web.Application(client_max_size=64 * 1024)
    app.router.add_get("/health", health)
    app.router.add_get("/v1/engines", engines)
    app.router.add_post("/v1/tts", tts)
    app.router.add_post("/v1/unload", unload)
    app.cleanup_ctx.append(janitor)
    web.run_app(app, host=HOST, port=PORT, access_log=None, print=None)


if __name__ == "__main__":
    main()
