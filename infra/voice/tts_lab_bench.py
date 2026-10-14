"""Time to first audio, real-time factor and memory per TTS engine and language (2026-10-14).
Usage (host, stdlib only): python3 -I infra/voice/tts_lab_bench.py [--reps N] [--engines a,b] [--md]
Kokoro is measured on risime-tts (127.0.0.1:8230, streaming: first chunk = first audio); the
candidates on risime-tts-lab (127.0.0.1:8231: first sentence's audio; Orpheus also the streaming
estimate, 4 SNAC frames). Memory = the host's MemAvailable drop across the engine's load (unified
memory, approximate: other jobs move it too). Fixed sentences only; nothing is stored but the numbers.
Reads TTS_API_KEY from the environment if the services need it."""
import argparse
import json
import os
import time
import urllib.error
import urllib.request

KOKORO = os.environ.get("RISI_TTS_URL", "http://127.0.0.1:8230")
LAB = os.environ.get("RISI_TTS_LAB_URL", "http://127.0.0.1:8231")
KEY = os.environ.get("TTS_API_KEY", "")
TEXTS = {
    "en": ["Sure.", "Tomorrow you have two meetings, one at ten and one at three.",
           "Hmm, let me check your calendar."],
    "ta": ["சரி.", "நாளை உங்களுக்கு இரண்டு கூட்டங்கள் உள்ளன, ஒன்று பத்து மணிக்கு, மற்றொன்று மூன்று மணிக்கு.",
           "கொஞ்சம் பொறுங்கள், உங்கள் நாட்காட்டியைப் பார்க்கிறேன்."],
}
# One voice per engine and language.
VOICES = {
    ("kokoro", "en"): "kokoro:af_heart",
    ("chatterbox", "en"): "chatterbox:default",
    ("orpheus", "en"): "orpheus:tara",
    ("indic-parler", "en"): "indic-parler:en:mary",
    ("indic-parler", "ta"): "indic-parler:ta:jaya",
    ("indicf5", "ta"): "indicf5:ta:stock-1",
}


def req(url, body=None, timeout=300):
    h = {"Content-Type": "application/json"}
    if KEY:
        h["Authorization"] = "Bearer " + KEY
    return urllib.request.Request(url, data=json.dumps(body).encode() if body else None, headers=h)


def mem_mb():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024


def kokoro(text, voice):
    t0 = time.monotonic()
    first, n = None, 0
    with urllib.request.urlopen(req(KOKORO + "/v1/tts", {"text": text, "voice": voice})) as r:
        while True:
            b = r.read1(4800)
            if not b:
                break
            first = first if first is not None else time.monotonic() - t0
            n += len(b)
    total = time.monotonic() - t0
    return {"load_ms": 0, "first_ms": int(first * 1000), "synth_ms": int(total * 1000),
            "audio_ms": int(n / 2 / 24000 * 1000)}


def lab(text, voice):
    with urllib.request.urlopen(req(LAB + "/v1/tts", {"text": text, "voice": voice}), timeout=300) as r:
        h = r.headers
        r.read()
        out = {k: int(h[v]) for k, v in (("load_ms", "X-Load-Ms"), ("first_ms", "X-First-Audio-Ms"),
                                         ("synth_ms", "X-Synth-Ms"), ("audio_ms", "X-Audio-Ms"))}
        if h.get("X-Stream-First-Audio-Ms"):
            out["stream_first_ms"] = int(h["X-Stream-First-Audio-Ms"])
        return out


def q(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))] if xs else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--reps", type=int, default=2)
    ap.add_argument("--engines", default="")
    ap.add_argument("--md", action="store_true")
    a = ap.parse_args()
    want = set(a.engines.split(",")) if a.engines else None
    rows = []
    for (eng, lang), voice in VOICES.items():
        if want and eng not in want:
            continue
        say = kokoro if eng == "kokoro" else lab
        before = mem_mb()
        try:
            if eng != "kokoro":
                urllib.request.urlopen(req(LAB + "/v1/unload", {}), timeout=30).read()
                before = mem_mb()
            runs = [say(t, voice) for _ in range(a.reps) for t in TEXTS[lang]]
        except urllib.error.HTTPError as e:
            rows.append({"engine": eng, "lang": lang, "voice": voice,
                         "error": json.loads(e.read() or b"{}").get("error", e.code)})
            continue
        except OSError as e:
            rows.append({"engine": eng, "lang": lang, "voice": voice, "error": type(e).__name__})
            continue
        after = mem_mb()
        firsts = [r["first_ms"] for r in runs]
        row = {"engine": eng, "lang": lang, "voice": voice, "n": len(runs),
               "load_ms": max(r["load_ms"] for r in runs),
               "first_p50_ms": q(firsts, 0.5), "first_p95_ms": q(firsts, 0.95),
               "rtf": round(sum(r["synth_ms"] for r in runs) / max(1, sum(r["audio_ms"] for r in runs)), 3),
               "mem_drop_mb": before - after if eng != "kokoro" else None}
        streams = [r["stream_first_ms"] for r in runs if "stream_first_ms" in r]
        if streams:
            row["stream_first_p50_ms"] = q(streams, 0.5)
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    if a.md:
        print("\n| Engine | Lang | Voice | Load (s) | First audio p50 / p95 (ms) | RTF | MemAvailable drop (MB) |")
        print("|---|---|---|---|---|---|---|")
        for r in rows:
            if "error" in r:
                print(f"| {r['engine']} | {r['lang']} | {r['voice']} | {r['error']} | | | |")
            else:
                s = f" (stream ~{r['stream_first_p50_ms']})" if "stream_first_p50_ms" in r else ""
                print(f"| {r['engine']} | {r['lang']} | {r['voice']} | {r['load_ms'] / 1000:.1f} | "
                      f"{r['first_p50_ms']} / {r['first_p95_ms']}{s} | {r['rtf']} | {r['mem_drop_mb'] or '-'} |")


if __name__ == "__main__":
    main()
