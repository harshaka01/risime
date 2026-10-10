#!/usr/bin/env python3
"""ASR test subsets for scripts/risi-eval asr (docs/RISI-MODELS.md §ASR). Public, ungated, no token.

  python3 -I eval/risi/asr_data.py <data dir> [--n 50]
  * Tamil:   google/fleurs ta_in test (CC BY 4.0): test.tsv + audio/test.tar.gz, first N rows.
  * Sinhala: FLEURS has no si_lk; SPEAK-ASR/youtube-sinhala-asr test parquet (405 rows, YouTube,
             code-mixed flags), first N rows. Needs pyarrow: pip install --target <data>/.pylib pyarrow
  * Harsha's own clips (eval/risi/speech/{si,ta}/NN.wav + sentences) are added when they exist.
Writes <data>/manifest.jsonl: {id, lang, audio, text, duration_s, source}.
"""
import csv
import io
import json
import os
import sys
import tarfile
import urllib.request
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
data = sys.argv[1]
N = int(sys.argv[sys.argv.index("--n") + 1]) if "--n" in sys.argv else 50
sys.path.insert(0, os.path.join(data, ".pylib"))
HF = "https://huggingface.co/datasets"


def fetch(url, path):
    if not os.path.exists(path):
        print(f"download {url}")
        tmp = path + ".part"
        with urllib.request.urlopen(url, timeout=600) as r, open(tmp, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        os.replace(tmp, path)
    return path


def wav_dur(p):
    try:
        with wave.open(p) as w:
            return w.getnframes() / w.getframerate()
    except Exception:  # noqa: BLE001
        return None


out = []
# ---- Tamil (FLEURS)
ta = os.path.join(data, "fleurs-ta"); os.makedirs(ta, exist_ok=True)
tsv = fetch(f"{HF}/google/fleurs/resolve/main/data/ta_in/test.tsv", os.path.join(ta, "test.tsv"))
rows = list(csv.reader(open(tsv, encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE))
seen, pick = set(), []
for r in rows:  # id, file, raw, normalized, phonemes, n_samples, gender
    if r[0] in seen:
        continue
    seen.add(r[0]); pick.append(r)
    if len(pick) >= N:
        break
want = {r[1] for r in pick}
if not all(os.path.exists(os.path.join(ta, f)) for f in want):
    tgz = fetch(f"{HF}/google/fleurs/resolve/main/data/ta_in/audio/test.tar.gz", os.path.join(ta, "test.tar.gz"))
    with tarfile.open(tgz) as t:
        for m in t:
            b = os.path.basename(m.name)
            if m.isfile() and b in want:
                with open(os.path.join(ta, b), "wb") as f:
                    f.write(t.extractfile(m).read())
for r in pick:
    p = os.path.join(ta, r[1])
    out.append({"id": f"fleurs-ta-{r[0]}", "lang": "ta", "audio": p, "text": r[2],
                "duration_s": int(r[5]) / 16000 if r[5].isdigit() else wav_dur(p),
                "source": "google/fleurs ta_in test (CC BY 4.0)"})

# ---- Sinhala (SPEAK-ASR youtube-sinhala-asr)
si = os.path.join(data, "yt-si"); os.makedirs(si, exist_ok=True)
pq = fetch(f"{HF}/SPEAK-ASR/youtube-sinhala-asr/resolve/main/data/test-00000-of-00001.parquet",
           os.path.join(si, "test.parquet"))
import pyarrow.parquet as papq  # noqa: E402

tab = papq.read_table(pq)
cols = tab.column_names
print("youtube-sinhala columns:", cols)
recs = tab.slice(0, N).to_pylist()
tcol = next(c for c in ("sentence", "transcription", "text", "transcript") if c in cols)
for i, r in enumerate(recs):
    a = r["audio"]
    raw = a.get("bytes") if isinstance(a, dict) else a
    ext = os.path.splitext((a.get("path") or "x.wav") if isinstance(a, dict) else "x.wav")[1] or ".wav"
    p = os.path.join(si, f"{i:03d}{ext}")
    if not os.path.exists(p):
        open(p, "wb").write(raw)
    out.append({"id": f"yt-si-{i:03d}", "lang": "si", "audio": p, "text": r[tcol],
                "duration_s": r.get("duration") or wav_dur(p),
                "code_mixed": r.get("is_code_mixed"), "noise": r.get("has_noise"),
                "source": "SPEAK-ASR/youtube-sinhala-asr test (no licence on the repo)"})

# ---- Harsha's recordings, when present
for lang in ("si", "ta"):
    sent = os.path.join(HERE, "speech", f"{lang}.txt")
    if not os.path.exists(sent):
        continue
    for k, line in enumerate(open(sent, encoding="utf-8"), 1):
        p = os.path.join(HERE, "speech", lang, f"{k:02d}.wav")
        if line.strip() and os.path.exists(p):
            out.append({"id": f"harsha-{lang}-{k:02d}", "lang": lang, "audio": p, "text": line.strip(),
                        "duration_s": wav_dur(p), "source": "own recording"})

with open(os.path.join(data, "manifest.jsonl"), "w", encoding="utf-8") as f:
    for m in out:
        f.write(json.dumps(m, ensure_ascii=False) + "\n")
print(f"manifest: {len(out)} clips ({sum(m['lang'] == 'ta' for m in out)} ta, {sum(m['lang'] == 'si' for m in out)} si)")
