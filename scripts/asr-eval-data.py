"""Build the ASR eval manifest (decision 071 amendment c) from clips under ~/risime-eval-data.

Nothing here downloads; the public subsets were fetched once (docs/status/downloads.md) and the
in-house recordings are dropped into ~/risime-eval-data/real/ by hand (docs/status/asr-eval.md).
Audio never enters git; only this script and the provenance table below do.

  python3 -I scripts/asr-eval-data.py [--data ~/risime-eval-data] [--out manifests/all.jsonl]

Sets:
  yt-si      SPEAK-ASR/youtube-sinhala-asr test (50 clips, legacy manifest.jsonl)
  slr30-si   OpenSLR SLR30 Sinhala (Google TTS corpus, read speech, multi-speaker) subset
  fleurs-ta  google/fleurs ta_in test (50 clips, legacy manifest.jsonl)
  fleurs-en  google/fleurs en_us test (20 clips)
  real/<lang>/NN-<speaker>.<wav|m4a|ogg|...> + real/<lang>/sentences.txt (line NN = reference),
             converted to real/<lang>/16k/*.wav (mono 16 kHz) on first build, for lang in
             si, ta, en, si+en, ta+en  (in-house consented recordings)
  synthetic code-switch (lang si+en / ta+en, synthetic: true): a si (ta) clip followed by an en
             clip, written to synth-cs/ -- used only while no real code-switched recordings exist.

in_training_of comes from PROVENANCE: which candidate models' training data contains each source
(model cards / training datasets, checked 2026-10-12; see docs/RISI-MODELS.md). "possible" overlap
counts as contained (the clip is excluded from that model's score).
"""

import argparse
import array
import json
import os
import struct
import subprocess
import sys

# source set -> models whose training data contains (or may contain) it.
PROVENANCE = {
    # SinhaSpeech/sinhala-asr-data pools YouTube speech; overlap with the SPEAK-ASR YouTube test
    # can't be ruled out (docs/RISI-MODELS.md caveat, Harsha 2026-10-12: treat as unreliable).
    "yt-si": ["whisper-small-sinhala"],
    "slr30-si": [],
    "fleurs-ta": [],
    "fleurs-en": [],
    "real": [],
    "synth-cs": [],
}

LICENCE = {
    "yt-si": "none stated on the repo (internal eval only, not redistributed)",
    "slr30-si": "CC BY-SA 4.0",
    "fleurs-ta": "CC BY 4.0",
    "fleurs-en": "CC BY 4.0",
    "real": "in-house, consented, internal testing only",
}


def read_wav(path):
    """-> (samples as array('h') int16, rate). Mono PCM16 or float32 only."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError(f"not a WAV file: {path}")
    pos, fmt, rate, ch, bits, pcm = 12, None, None, None, None, None
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = data[pos + 8:pos + 8 + size]
        if cid == b"fmt ":
            fmt, ch, rate = struct.unpack("<HHI", body[:8])
            bits = struct.unpack("<H", body[14:16])[0]
        elif cid == b"data":
            pcm = body
        pos += 8 + size + (size & 1)
    if ch != 1:
        raise ValueError(f"{path}: {ch} channels, want mono")
    if fmt == 1 and bits == 16:
        a = array.array("h")
        a.frombytes(pcm)
    elif fmt == 3 and bits == 32:
        fl = array.array("f")
        fl.frombytes(pcm)
        a = array.array("h", (max(-32768, min(32767, int(x * 32767))) for x in fl))
    else:
        raise ValueError(f"{path}: format {fmt}/{bits} bits unsupported")
    return a, rate


def write_wav(path, samples, rate=16000):
    raw = samples.tobytes()
    hdr = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(raw), b"WAVE", b"fmt ", 16, 1, 1,
                      rate, rate * 2, 2, 16, b"data", len(raw))
    with open(path, "wb") as f:
        f.write(hdr + raw)


def duration(path):
    a, rate = read_wav(path)
    return round(len(a) / rate, 2)


AUDIO_EXT = (".wav", ".m4a", ".mp3", ".ogg", ".opus", ".aac", ".amr", ".3gp", ".flac", ".webm")


def convert_real(d):
    """Recordings in any phone format (NN-<speaker>.<ext>) -> d/16k/NN-<speaker>.wav (mono 16 kHz
    PCM16) with the speech image's ffmpeg (the host has none); only missing ones are converted.
    Returns the 16k directory."""
    out = os.path.join(d, "16k")
    os.makedirs(out, exist_ok=True)
    todo = [n for n in sorted(os.listdir(d))
            if n.lower().endswith(AUDIO_EXT) and n[:2].isdigit()
            and not os.path.exists(os.path.join(out, os.path.splitext(n)[0] + ".wav"))]
    if todo:
        image = os.environ.get("ASR_EVAL_IMAGE") or subprocess.run(
            ["docker", "inspect", "-f", "{{.Config.Image}}", "risime-whisper-whisper-1"],
            capture_output=True, text=True, check=True).stdout.strip()
        loop = ('for f in "$@"; do ffmpeg -nostdin -loglevel error -y -i "/in/$f" -ac 1 -ar 16000 '
                '-c:a pcm_s16le "/in/16k/${f%.*}.wav" || exit 1; done')
        subprocess.run(["docker", "run", "--rm", "--network", "none", "--memory", "1g",
                        "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{d}:/in",
                        "--entrypoint", "bash", image, "-c", loop, "convert", *todo], check=True)
        print(f"converted {len(todo)} recordings in {d}", file=sys.stderr)
    return out


def legacy(data, prefix):
    out = []
    with open(os.path.join(data, "manifest.jsonl")) as f:
        for line in f:
            c = json.loads(line)
            if c["id"].startswith(prefix):
                out.append(c)
    return out


def build(data):
    clips = []
    for c in legacy(data, "yt-si-"):
        c.update(set="yt-si", kind="broadcast", licence=LICENCE["yt-si"],
                 in_training_of=PROVENANCE["yt-si"])
        clips.append(c)
    slr = os.path.join(data, "slr30-si", "selected.json")
    if os.path.exists(slr):
        with open(slr) as f:
            for utt, text in json.load(f):
                p = os.path.join(data, "slr30-si", utt + ".wav")
                clips.append({"id": f"slr30-si-{utt}", "set": "slr30-si", "lang": "si", "audio": p,
                              "text": text, "duration_s": duration(p), "kind": "read",
                              "source": "OpenSLR SLR30 (Google, Sinhala TTS corpus)",
                              "licence": LICENCE["slr30-si"],
                              "in_training_of": PROVENANCE["slr30-si"]})
    for c in legacy(data, "fleurs-ta-"):
        c.update(set="fleurs-ta", kind="read", licence=LICENCE["fleurs-ta"],
                 in_training_of=PROVENANCE["fleurs-ta"])
        clips.append(c)
    fen = os.path.join(data, "dl", "fleurs-en", "selected.json")
    if os.path.exists(fen):
        with open(fen) as f:
            for sid, wav, text in json.load(f):
                p = os.path.join(data, "fleurs-en", wav)
                clips.append({"id": f"fleurs-en-{sid}", "set": "fleurs-en", "lang": "en",
                              "audio": p, "text": text, "duration_s": duration(p), "kind": "read",
                              "source": "google/fleurs en_us test", "licence": LICENCE["fleurs-en"],
                              "in_training_of": PROVENANCE["fleurs-en"]})
    real = os.path.join(data, "real")
    have_real_cs = set()
    for lang in ("si", "ta", "en", "si+en", "ta+en"):
        d = os.path.join(real, lang)
        sent = os.path.join(d, "sentences.txt")
        if not os.path.exists(sent):
            continue
        with open(sent) as f:
            lines = [ln.strip() for ln in f if ln.strip() and not ln.startswith("#")]
        d = convert_real(d)
        for name in sorted(os.listdir(d)):
            if not name.endswith(".wav"):
                continue
            stem = name[:-4]
            num, _, speaker = stem.partition("-")
            if not num.isdigit() or not 1 <= int(num) <= len(lines):
                print(f"skip {lang}/{name}: no sentence line {num}", file=sys.stderr)
                continue
            p = os.path.join(d, name)
            clips.append({"id": f"real-{lang}-{stem}", "set": "real", "lang": lang, "audio": p,
                          "text": lines[int(num) - 1], "duration_s": duration(p), "kind": "real",
                          "speaker": speaker or "?", "source": "in-house recording",
                          "licence": LICENCE["real"], "in_training_of": PROVENANCE["real"]})
            if "+" in lang:
                have_real_cs.add(lang)
    clips += synth_cs(data, clips, skip=have_real_cs)
    return clips


def synth_cs(data, clips, skip, n=6):
    """Concatenate a si/ta clip and an en clip (0.3 s gap) -> code-switch at a clip boundary.
    Only for the languages that have no real code-switched recordings yet."""
    out_dir = os.path.join(data, "synth-cs")
    os.makedirs(out_dir, exist_ok=True)
    en = [c for c in clips if c["lang"] == "en" and c["set"] == "fleurs-en"
          and (c.get("duration_s") or 99) <= 8]
    res = []
    for lang, srcs in (("si", ("slr30-si", "yt-si")), ("ta", ("fleurs-ta",))):
        if f"{lang}+en" in skip or not en:
            continue
        base = []
        for src in srcs:  # the first set that has short clips
            base = [c for c in clips if c["lang"] == lang and c["set"] == src
                    and not c.get("code_mixed") and (c.get("duration_s") or 99) <= 8][:n]
            if base:
                break
        for i, c in enumerate(base):
            e = en[i % len(en)]
            a, ra = read_wav(c["audio"])
            b, rb = read_wav(e["audio"])
            if ra != 16000 or rb != 16000:
                continue
            gap = array.array("h", bytes(2 * 4800))
            out = os.path.join(out_dir, f"{lang}-en-{i:02d}.wav")
            if not os.path.exists(out):
                write_wav(out, a + gap + b)
            res.append({"id": f"synth-{lang}+en-{i:02d}", "set": "synth-cs", "lang": f"{lang}+en",
                        "audio": out, "text": c["text"] + " " + e["text"],
                        "duration_s": round((len(a) + len(gap) + len(b)) / 16000, 2),
                        "kind": "synthetic-concat", "synthetic": True,
                        "source": f"concat of {c['id']} + {e['id']}",
                        "licence": f"{c['licence']} + {e['licence']}",
                        "in_training_of": sorted(set(c["in_training_of"]) |
                                                 set(e["in_training_of"]))})
    return res


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--data", default=os.path.expanduser("~/risime-eval-data"))
    p.add_argument("--out", default="manifests/all.jsonl")
    a = p.parse_args()
    clips = build(a.data)
    out = os.path.join(a.data, a.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        for c in clips:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    by = {}
    for c in clips:
        by[c["set"]] = by.get(c["set"], 0) + 1
    print(f"{out}: {len(clips)} clips {by}")


if __name__ == "__main__":
    main()
