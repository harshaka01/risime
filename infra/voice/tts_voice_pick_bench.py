"""Harsha's call-voice pick (2026-10-15 21:08 SLT): measure each engine and make the 3 samples.

Usage (host, stdlib only):
  python3 -I infra/voice/tts_voice_pick_bench.py [--engines kokoro,chatterbox-turbo,dia2,orpheus]
      [--reps 4] [--out ~/risime-voice-samples] [--env-file .env]

Per engine, one at a time:
  * memory: host MemFree ("free"; spark2's MemAvailable reads low by design). A lab engine is loaded
    only when MemFree minus its estimate leaves >= 10 GB (the lab refuses otherwise too); while it runs,
    MemFree is sampled every 0.25 s and the engine is unloaded at once if it falls under 6 GB.
    Peak = the worker's own peaks (torch CUDA memory reserved, unified on GB10, + host RSS), and the
    host view: MemFree before the load minus the lowest MemFree during load + runs (approximate:
    other jobs and the page cache move it too). Kokoro is resident (risime-tts): no load, no peak.
  * first audio over reps x 3 lines (>= 12 runs; the load request is excluded): Kokoro streams (first
    chunk); the lab returns the first sentence's audio (X-First-Audio-Ms) and, for Orpheus and Dia2,
    a streaming estimate (X-Stream-First-Audio-Ms: the time their first ~300 ms of audio exists).
  * RTF = synthesis time / audio length.
  * samples: the 3 lines once each, saved as <engine>-<line>.wav in --out (outside Git), and
    stats.json there (read by RisiMe.Agent.TTS.VoicePick). The lab engine is unloaded afterwards.
Nothing but the numbers and the 12 sample files is stored; no text is logged.
"""
import argparse
import json
import os
import statistics
import threading
import time
import urllib.error
import urllib.request

KOKORO = os.environ.get("RISI_TTS_URL", "http://127.0.0.1:8230")
LAB = os.environ.get("RISI_TTS_LAB_URL", "http://127.0.0.1:8231")
LINES = [
    ("greeting", "Hi, it's Risi. How can I help?"),
    ("calendar", "Tomorrow you have a reminder to call Shakya at 10, and you're busy from 10:30 to "
                 "11:30 and from 3 to 4."),
    ("check", "Sure, give me a second, let me pull up your calendar."),
]
VOICES = {"kokoro": "kokoro:af_heart", "chatterbox-turbo": "chatterbox-turbo:default",
          "dia2": "dia2:stock", "orpheus": "orpheus:tara"}
EST_MB = {"chatterbox-turbo": 4500, "dia2": 6000, "orpheus": 9000}
HEADROOM_MB, ABORT_MB = 10240, 6144
KEY = ""


def mem_free_mb():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemFree:"):
                return int(line.split()[1]) // 1024
    return 0


def req(url, body=None):
    h = {"Content-Type": "application/json"}
    if KEY:
        h["Authorization"] = "Bearer " + KEY
    return urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None,
                                  headers=h)


def unload():
    try:
        urllib.request.urlopen(req(LAB + "/v1/unload", {}), timeout=30).read()
    except OSError:
        pass


class Watch(threading.Thread):
    """Lowest MemFree while running; unloads the lab engine if it falls under ABORT_MB."""

    def __init__(self, lab):
        super().__init__(daemon=True)
        self.lab, self.low, self.stop, self.aborted = lab, mem_free_mb(), False, False

    def run(self):
        while not self.stop:
            m = mem_free_mb()
            self.low = min(self.low, m)
            if self.lab and m < ABORT_MB and not self.aborted:
                self.aborted = True
                print(f"ABORT: MemFree {m} MB < {ABORT_MB} MB, unloading", flush=True)
                unload()
            time.sleep(0.25)


def kokoro(text, voice, wav=False):
    t0 = time.monotonic()
    url = KOKORO + "/v1/tts" + ("?format=wav" if wav else "")
    first, buf = None, bytearray()
    with urllib.request.urlopen(req(url, {"text": text, "voice": voice}), timeout=120) as r:
        while True:
            b = r.read1(4800)
            if not b:
                break
            first = first if first is not None else time.monotonic() - t0
            buf += b
    total = time.monotonic() - t0
    pcm = bytes(buf[44:]) if wav else bytes(buf)
    return ({"load_ms": 0, "first_ms": int(first * 1000), "synth_ms": int(total * 1000),
             "audio_ms": int(len(pcm) / 2 / 24000 * 1000)}, bytes(buf) if wav else None)


def lab(text, voice, wav=False):
    with urllib.request.urlopen(req(LAB + "/v1/tts", {"text": text, "voice": voice}), timeout=900) as r:
        h, body = r.headers, r.read()
    out = {k: int(h[v]) for k, v in (("load_ms", "X-Load-Ms"), ("first_ms", "X-First-Audio-Ms"),
                                     ("synth_ms", "X-Synth-Ms"), ("audio_ms", "X-Audio-Ms"))}
    if h.get("X-Stream-First-Audio-Ms"):
        out["stream_first_ms"] = int(h["X-Stream-First-Audio-Ms"])
    for k, v in (("cuda_peak_mb", "X-Cuda-Peak-Mb"), ("rss_peak_mb", "X-Rss-Peak-Mb")):
        if h.get(v):
            out[k] = int(h[v])
    return out, body


def pct(xs, p):
    xs = sorted(xs)
    if not xs:
        return None
    k = (len(xs) - 1) * p
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return int(round(xs[lo] + (xs[hi] - xs[lo]) * (k - lo)))


def wav_ok(b):
    return len(b) > 44 and b[:4] == b"RIFF" and b[8:12] == b"WAVE"


def run_engine(eng, reps, out):
    voice = VOICES[eng]
    is_lab = eng != "kokoro"
    say = lab if is_lab else kokoro
    if is_lab:
        unload()
        time.sleep(2)
        free = mem_free_mb()
        if free - EST_MB[eng] < HEADROOM_MB:
            return {"engine": eng, "voice": voice,
                    "error": f"not loaded: MemFree {free} MB - ~{EST_MB[eng]} MB < {HEADROOM_MB} MB"}
    before = mem_free_mb()
    w = Watch(is_lab)
    w.start()
    row = {"engine": eng, "voice": voice}
    try:
        # The load (lab) or a warm-up (Kokoro): excluded from the stats.
        r0, _ = say(LINES[0][1], voice)
        row["load_ms"] = r0["load_ms"]
        runs = []
        for _ in range(reps):
            for _, text in LINES:
                r, _b = say(text, voice)
                runs.append(r)
                if w.aborted:
                    raise MemoryError("MemFree under the abort floor")
        samples = {}
        for k, text in LINES:
            _r, b = say(text, voice, wav=True) if not is_lab else say(text, voice)
            if b is None or not wav_ok(b):
                raise RuntimeError(f"no WAV for {k}")
            samples[k] = b
        firsts = [r.get("stream_first_ms", r["first_ms"]) for r in runs]
        sent = [r["first_ms"] for r in runs]
        row.update({
            "n": len(runs),
            "p50_ms": pct(firsts, 0.5), "p95_ms": pct(firsts, 0.95),
            "first_sentence_p50_ms": pct(sent, 0.5), "first_sentence_p95_ms": pct(sent, 0.95),
            "streaming": eng == "kokoro" or any("stream_first_ms" in r for r in runs),
            "rtf": round(sum(r["synth_ms"] for r in runs) / max(1, sum(r["audio_ms"] for r in runs)), 3),
            "audio_s_mean": round(statistics.mean(r["audio_ms"] for r in runs) / 1000, 2),
            "cuda_peak_mb": max((r.get("cuda_peak_mb", 0) for r in runs), default=0) or None,
            "rss_peak_mb": max((r.get("rss_peak_mb", 0) for r in runs), default=0) or None,
        })
        os.makedirs(out, exist_ok=True)
        for k, b in samples.items():
            p = os.path.join(out, f"{eng}-{k}.wav")
            with open(p + ".tmp", "wb") as f:
                f.write(b)
            os.replace(p + ".tmp", p)
    except urllib.error.HTTPError as e:
        row["error"] = json.loads(e.read() or b"{}").get("error", e.code)
    except (OSError, MemoryError, RuntimeError) as e:
        row["error"] = f"{type(e).__name__}: {e}"
    finally:
        w.stop = True
        w.join()
        if is_lab:
            unload()
    row["peak_mb"] = (before - w.low) if is_lab else None
    row["mem_free_before_mb"], row["mem_free_low_mb"] = before, w.low
    return row


def main():
    global KEY
    ap = argparse.ArgumentParser()
    ap.add_argument("--engines", default="kokoro,chatterbox-turbo,dia2,orpheus")
    ap.add_argument("--reps", type=int, default=4)
    ap.add_argument("--out", default=os.path.expanduser("~/risime-voice-samples"))
    ap.add_argument("--env-file", default="")
    a = ap.parse_args()
    KEY = os.environ.get("TTS_API_KEY", "")
    if a.env_file and not KEY:
        with open(a.env_file) as f:
            for line in f:
                if line.startswith("TTS_API_KEY="):
                    KEY = line.split("=", 1)[1].strip().strip("'\"")
    stats_p = os.path.join(a.out, "stats.json")
    try:
        with open(stats_p) as f:
            stats = json.load(f)
    except (OSError, ValueError):
        stats = {}
    for eng in a.engines.split(","):
        row = run_engine(eng, a.reps, a.out)
        row["at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        print(json.dumps(row), flush=True)
        if "error" not in row:
            stats[eng] = row
            os.makedirs(a.out, exist_ok=True)
            with open(stats_p + ".tmp", "w") as f:
                json.dump(stats, f, indent=1)
            os.replace(stats_p + ".tmp", stats_p)


if __name__ == "__main__":
    main()
