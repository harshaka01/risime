"""Time to first audio of risime-tts (decision 090). Usage: python3 -I infra/voice/tts_ttfa.py [url] [reps]
Prints p50/p95 of the first chunk and the real-time factor. Speaks fixed sentences only."""
import json
import sys
import time
import urllib.request

URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8230"
REPS = int(sys.argv[2]) if len(sys.argv) > 2 else 4
TEXTS = ["Sure.", "Tomorrow you have two meetings, one at ten and one at three.",
         "Hmm, let me check your calendar.", "I can help with that. What time works best for you?",
         "Good morning! You have nothing else on today, so it's a quiet one."]


def q(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))]


firsts, totals, audio = [], [], []
for _ in range(REPS):
    for t in TEXTS:
        body = json.dumps({"text": t, "voice": "kokoro:af_heart"}).encode()
        req = urllib.request.Request(URL + "/v1/tts", data=body, headers={"Content-Type": "application/json"})
        t0 = time.monotonic()
        first, n = None, 0
        with urllib.request.urlopen(req) as r:
            while True:
                b = r.read1(4800)
                if not b:
                    break
                if first is None:
                    first = time.monotonic() - t0
                n += len(b)
        firsts.append(first * 1000)
        totals.append((time.monotonic() - t0) * 1000)
        audio.append(n / 2 / 24000 * 1000)
print("n=%d first_audio p50=%.0f ms p95=%.0f ms; total p50=%.0f ms; RTF=%.3f" % (
    len(firsts), q(firsts, .5), q(firsts, .95), q(totals, .5), sum(totals) / sum(audio)))
