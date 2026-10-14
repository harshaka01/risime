"""Self-test of risime-tts-lab's serving rules, inside the image, no network, no real model:
  docker run --rm --network none --entrypoint python3 risime/tts-lab:<tag> -I /app/tts_lab_selftest.py
Starts the server on a private port with the tone engine (TTS_LAB_SELFTEST=1) and checks: on-demand
load (X-Load-Ms > 0, then 0 while resident), the WAV and its headers, one synthesis at a time (429),
idle unload, the MemAvailable floor (503 no_memory, nothing started), the live-speech pause (503
paused), missing weights (409 downloading), a broken engine (503 load_failed) and IndicF5's review
gate (409 review_pending)."""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 18231
fails = []


def server(**env):
    e = {**os.environ, "TTS_LAB_SELFTEST": "1", "TTS_LAB_PORT": str(PORT), "TTS_LAB_HOST": "127.0.0.1",
         "TTS_DEVICE": "cpu", "TTS_API_KEY": "", **{k: str(v) for k, v in env.items()}}
    p = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "tts_lab_server.py")], env=e)
    for _ in range(100):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=1)
            return p
        except OSError:
            time.sleep(0.1)
    raise SystemExit("server did not start")


def call(path, body=None):
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}",
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def check(name, cond):
    print(("ok   " if cond else "FAIL ") + name)
    if not cond:
        fails.append(name)


def err(b):
    try:
        return json.loads(b).get("error")
    except ValueError:
        return None


models = tempfile.mkdtemp()
guard = os.path.join(tempfile.mkdtemp(), "speech-eval.pause")
# A "chatterbox" with all its files present but not loadable (empty files): load_failed.
os.makedirs(os.path.join(models, "chatterbox"))
for f in ("ve.safetensors", "t3_cfg.safetensors", "s3gen.safetensors", "tokenizer.json", "conds.pt"):
    open(os.path.join(models, "chatterbox", f), "wb").close()
os.makedirs(os.path.join(models, "indicf5", "checkpoints"))
for f in ("model.safetensors", "model.py", "config.json", "checkpoints/vocab.txt"):
    open(os.path.join(models, "indicf5", f), "wb").close()

p = server(TTS_LAB_MODELS=models, TTS_LAB_PAUSE_FILE=guard, TTS_LAB_IDLE_UNLOAD_S=3,
           TTS_LAB_MIN_AVAILABLE_MB=0, TTS_LAB_EST_CHATTERBOX_MB=0)
try:
    s, _, b = call("/v1/engines")
    st = {e["id"]: e["status"] for e in json.loads(b)["engines"]}
    check("statuses", st == {"chatterbox": "ready", "indic-parler": "downloading",
                             "orpheus": "downloading", "indicf5": "review_pending", "selftest": "ready"})
    s, h, b = call("/v1/tts", {"text": "One. Two.", "voice": "selftest:tone"})
    check("first request loads (200, X-Load-Ms > 0)", s == 200 and int(h["X-Load-Ms"]) > 0)
    check("WAV, 2 sentences = 0.8 s", b[:4] == b"RIFF" and h["X-Audio-Ms"] == "800")
    check("first audio < total", 0 < int(h["X-First-Audio-Ms"]) <= int(h["X-Synth-Ms"]))
    s, h, b = call("/v1/tts", {"text": "Again.", "voice": "selftest:tone"})
    check("resident: X-Load-Ms = 0", s == 200 and h["X-Load-Ms"] == "0")
    check("health shows it loaded", json.loads(call("/health")[2])["loaded"] == "selftest")

    out = {}
    t = threading.Thread(target=lambda: out.update(r=call("/v1/tts", {"text": "A. B. C. D. E. F.",
                                                                      "voice": "selftest:tone"})))
    t.start()
    time.sleep(0.1)
    s, _, b = call("/v1/tts", {"text": "Now.", "voice": "selftest:tone"})
    t.join()
    check("one synthesis at a time (429 busy)", s == 429 and err(b) == "busy" and out["r"][0] == 200)

    time.sleep(9)
    check("idle unload", json.loads(call("/health")[2])["loaded"] is None)

    s, _, b = call("/v1/tts", {"text": "Hi.", "voice": "orpheus:tara"})
    check("missing weights: 409 downloading", s == 409 and err(b) == "downloading")
    s, _, b = call("/v1/tts", {"text": "Hi.", "voice": "indicf5:ta:stock-1"})
    check("IndicF5 not cleared: 409 review_pending", s == 409 and err(b) == "review_pending")
    s, _, b = call("/v1/tts", {"text": "Hi.", "voice": "chatterbox:default"})
    check("broken weights: 503 load_failed, nothing left loaded",
          s == 503 and err(b) == "load_failed" and json.loads(call("/health")[2])["loaded"] is None)
    s, _, b = call("/v1/tts", {"text": "x" * 301, "voice": "selftest:tone"})
    check("over 300 characters: 400", s == 400)

    with open(guard, "w") as f:
        f.write(f"until={int(time.time()) + 60} reason=test\n")
    s, _, b = call("/v1/tts", {"text": "Hi.", "voice": "selftest:tone"})
    check("live speech first: 503 paused", s == 503 and err(b) == "paused")
    os.remove(guard)
finally:
    p.terminate()
    p.wait()

p = server(TTS_LAB_MODELS=models, TTS_LAB_PAUSE_FILE=guard, TTS_LAB_MIN_AVAILABLE_MB=0,
           TTS_LAB_SELFTEST_MB=10_000_000)
try:
    s, _, b = call("/v1/tts", {"text": "Hi.", "voice": "selftest:tone"})
    check("below the MemAvailable floor: 503 no_memory, nothing loaded",
          s == 503 and err(b) == "no_memory" and json.loads(call("/health")[2])["loaded"] is None)
finally:
    p.terminate()
    p.wait()

print("SELFTEST", "FAILED: " + ", ".join(fails) if fails else "PASSED")
sys.exit(1 if fails else 0)
