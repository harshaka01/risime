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
import re
import struct
import subprocess
import sys

# source set -> models whose training data contains (or may contain) it.
PROVENANCE = {
    # SinhaSpeech/sinhala-asr-data pools YouTube speech; overlap with the SPEAK-ASR YouTube test
    # can't be ruled out (docs/RISI-MODELS.md caveat, Harsha 2026-10-12: treat as unreliable).
    # Nerdstorm rev 2 trained on 25 channels of SPEAK-ASR/youtube-sinhala-asr (re-split), and
    # SPEAK-ASR's medium-all LoRA on its preprocessed YouTube train split: both excluded.
    "yt-si": ["whisper-small-sinhala", "qwen3-asr-0.6b-sinhala", "whisper-medium-si-all"],
    # The 5 channels Nerdstorm held out: clean for it, omni, kasunw and SPEAK-ASR medium (OpenSLR
    # only). SinhaSpeech's pool has 2% YouTube of unknown origin: possible overlap, excluded.
    "yt5-si": ["whisper-small-sinhala"],
    # SLR30 (Google TTS corpus): only hlasith/whisper-sinhala-small (not evaluated) lists it.
    "slr30-si": [],
    # Omnilingual corpus: Meta's omniASR models trained on its train split; the test split is
    # their designated held-out set (different recordings, possibly the same speakers), so it is
    # scored but marked in-domain for omnilingual-ctc-1b. No other candidate lists it.
    "omni-si": [],
    # FLEURS ta_in test: lemuralabs used FLEURS-Tamil *train*; vasista22 FLEURS train+dev. Clean.
    "fleurs-ta": [],
    "fleurs-en": [],
    "real": [],
    "synth-cs": [],
}

LICENCE = {
    "yt-si": "none stated on the repo (internal eval only, not redistributed)",
    "slr30-si": "CC BY-SA 4.0",
    "omni-si": "CC BY 4.0",
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


def level(samples, target_rms=3000.0):
    """Scale to a speech RMS of ~-21 dBFS (peaks clipped to int16)."""
    n = len(samples) or 1
    rms = (sum(x * x for x in samples) / n) ** 0.5 or 1.0
    g = target_rms / rms
    return array.array("h", (max(-32768, min(32767, int(x * g))) for x in samples))


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
    ffmpeg_16k(d, todo, "16k")
    return out


def ffmpeg_16k(d, names, sub):
    """d/<name> -> d/<sub>/<stem>.wav, mono 16 kHz PCM16, in one throwaway container of the speech
    image (offline, 1 GB)."""
    if not names:
        return
    image = os.environ.get("ASR_EVAL_IMAGE") or subprocess.run(
        ["docker", "inspect", "-f", "{{.Config.Image}}", "risime-whisper-whisper-1"],
        capture_output=True, text=True, check=True).stdout.strip()
    loop = (f'mkdir -p "/in/{sub}"; for f in "$@"; do b="$(basename "${{f%.*}}")"; '
            f'ffmpeg -nostdin -loglevel error -y -i "/in/$f" '
            f'-ac 1 -ar 16000 -c:a pcm_s16le "/in/{sub}/$b.wav" || exit 1; done')
    subprocess.run(["docker", "run", "--rm", "--network", "none", "--memory", "1g",
                    "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{d}:/in",
                    "--entrypoint", "bash", image, "-c", loop, "convert", *names], check=True)
    print(f"converted {len(names)} files in {d}", file=sys.stderr)


TAG = re.compile(r"<[^>]*>")


def clean_omni(text):
    """Omnilingual corpus transcript -> reference: drop <hesitation>/<noise>/<laugh>/<> tags and
    hyphenated false starts (a word ending in '-'), newlines to spaces."""
    words = [w for w in TAG.sub(" ", text).split() if not w.endswith("-")]
    return " ".join(words)


def prep_slr30(data, n=60, seed=71):
    """dl/slr30/si_lk.tar.gz (OpenSLR SLR30) -> slr30-si/<utt>.wav (16 kHz) for n random
    utterances, at most 3 per speaker, + slr30-si/selected.json [[utt, text]]."""
    import random
    import tarfile
    src = os.path.join(data, "dl", "slr30")
    lines = {}
    with open(os.path.join(src, "si_lk.lines.txt"), encoding="utf-8") as f:
        for ln in f:
            m = re.match(r'\(\s*(\S+)\s+"(.*)"\s*\)\s*$', ln.strip())
            if m:
                lines[m.group(1)] = m.group(2).strip()
    utts = sorted(lines)
    random.Random(seed).shuffle(utts)
    per, sel = {}, []
    for u in utts:
        spk = u.split("_")[1]
        if per.get(spk, 0) < 3:
            per[spk] = per.get(spk, 0) + 1
            sel.append(u)
        if len(sel) == n:
            break
    out = os.path.join(data, "slr30-si")
    raw = os.path.join(out, "raw")
    os.makedirs(raw, exist_ok=True)
    want = {u + ".wav" for u in sel}
    with tarfile.open(os.path.join(src, "si_lk.tar.gz")) as t:
        for m in t:
            base = os.path.basename(m.name)
            if m.isfile() and base in want and not os.path.exists(os.path.join(raw, base)):
                with t.extractfile(m) as fsrc, open(os.path.join(raw, base), "wb") as fdst:
                    fdst.write(fsrc.read())
    ffmpeg_16k(out, [os.path.join("raw", u + ".wav") for u in sel
                     if not os.path.exists(os.path.join(out, u + ".wav"))], ".")
    with open(os.path.join(out, "selected.json"), "w") as f:
        json.dump([[u, lines[u]] for u in sel], f, ensure_ascii=False)
    print(f"slr30-si: {len(sel)} utterances from {len(per)} speakers")


# The 5 SPEAK-ASR YouTube channels that Nerdstorm's Qwen3-ASR-0.6B-Sinhala rev 2 held out
# (its card: split by channel, none of their clips in training).
YT5 = ("Carnage", "Newsfirst Sri Lanka", "Arogya", "Anoma's Kitchen", "Hera's Diary")


def prep_yt5(data):
    """dl/yt-si/test.parquet (SPEAK-ASR/youtube-sinhala-asr test) -> yt5-si/<n>.wav + selected.json
    for every clip of the 5 held-out channels. Needs pyarrow."""
    import pyarrow.parquet as pq
    rows = pq.read_table(os.path.join(data, "dl", "yt-si", "test.parquet")).to_pylist()
    out = os.path.join(data, "yt5-si")
    raw = os.path.join(out, "raw")
    os.makedirs(raw, exist_ok=True)
    sel, names = [], []
    for i, r in enumerate(rows):
        up = (r["uploader"] or "").strip()
        if not any(up.startswith(c) for c in YT5):
            continue
        rid = f"{i:03d}"
        ext = os.path.splitext(r["audio"].get("path") or "")[1] or ".wav"
        fp = os.path.join(raw, rid + ext)
        if not os.path.exists(fp):
            with open(fp, "wb") as f:
                f.write(r["audio"]["bytes"])
        if not os.path.exists(os.path.join(out, rid + ".wav")):
            names.append(os.path.join("raw", rid + ext))
        sel.append([rid, r["text"], up, bool(r["is_code_mixed"]), bool(r["has_noise"])])
    ffmpeg_16k(out, names, ".")
    with open(os.path.join(out, "selected.json"), "w") as f:
        json.dump(sel, f, ensure_ascii=False)
    print(f"yt5-si: {len(sel)} clips")


def prep_omni(data, n=60, seed=71):
    """dl/omni-si/.../sin_Sinh test parquet (facebook/omnilingual-asr-corpus) -> omni-si/<id>.wav +
    omni-si/selected.json [[id, reference, speaker, prompt_id]]. Needs pyarrow (run with a venv
    python: the host python has none)."""
    import random
    import pyarrow.parquet as pq
    p = os.path.join(data, "dl", "omni-si", "data", "sin_Sinh", "test-00000-of-00001.parquet")
    rows = pq.read_table(p).to_pylist()
    random.Random(seed).shuffle(rows)
    rows = rows[:n]
    out = os.path.join(data, "omni-si")
    raw = os.path.join(out, "raw")
    os.makedirs(raw, exist_ok=True)
    sel, names = [], []
    for r in rows:
        rid = f"{r['speaker_id']}_{r['prompt_id']}_{r['segment_id']}"
        ext = os.path.splitext(r["audio"]["path"])[1] or ".flac"
        fp = os.path.join(raw, rid + ext)
        if not os.path.exists(fp):
            with open(fp, "wb") as f:
                f.write(r["audio"]["bytes"])
        if not os.path.exists(os.path.join(out, rid + ".wav")):
            names.append(os.path.join("raw", rid + ext))
        sel.append([rid, clean_omni(r["raw_text"]), r["speaker_id"], r["prompt_id"]])
    ffmpeg_16k(out, names, ".")
    with open(os.path.join(out, "selected.json"), "w") as f:
        json.dump(sel, f, ensure_ascii=False)
    print(f"omni-si: {len(sel)} clips, {len({s[2] for s in sel})} speakers")


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
    yt5 = os.path.join(data, "yt5-si", "selected.json")
    if os.path.exists(yt5):
        with open(yt5) as f:
            for rid, text, up, mixed, noise in json.load(f):
                p = os.path.join(data, "yt5-si", rid + ".wav")
                clips.append({"id": f"yt5-si-{rid}", "set": "yt5-si", "lang": "si", "audio": p,
                              "text": text, "duration_s": duration(p), "kind": "broadcast",
                              "channel": up, "code_mixed": mixed, "noise": noise,
                              "source": "SPEAK-ASR/youtube-sinhala-asr test, 5 held-out channels",
                              "licence": LICENCE["yt-si"], "in_training_of": PROVENANCE["yt5-si"]})
    omni = os.path.join(data, "omni-si", "selected.json")
    if os.path.exists(omni):
        with open(omni) as f:
            for rid, text, spk, prompt_id in json.load(f):
                p = os.path.join(data, "omni-si", rid + ".wav")
                clips.append({"id": f"omni-si-{rid}", "set": "omni-si", "lang": "si", "audio": p,
                              "text": text, "duration_s": duration(p), "kind": "spontaneous",
                              "speaker": spk,
                              "source": "facebook/omnilingual-asr-corpus sin_Sinh test",
                              "licence": LICENCE["omni-si"],
                              "in_training_of": PROVENANCE["omni-si"]})
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
    # real/<lang>/ = one sentence per clip; real/notes-<lang>/ = 20-40 s work voice notes read from a
    # script (docs/status/asr-recordings-plan.md), set "real-notes", same NN-<speaker> naming.
    folders = [(lang, lang, "real") for lang in ("si", "ta", "en", "si+en", "ta+en")]
    folders += [("notes-" + lang, lang, "real-notes") for lang in ("si", "ta", "en", "si+en")]
    for folder, lang, set_name in folders:
        d = os.path.join(real, folder)
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
            clips.append({"id": f"real-{folder}-{stem}", "set": set_name, "lang": lang, "audio": p,
                          "text": lines[int(num) - 1], "duration_s": duration(p), "kind": "real",
                          "speaker": speaker or "?", "source": "in-house recording",
                          "licence": LICENCE["real"], "in_training_of": PROVENANCE["real"]})
            if "+" in lang and set_name == "real":
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
            out = os.path.join(out_dir, f"{c['id']}+{e['id']}.v2.wav")
            if not os.path.exists(out):
                # Same loudness for both halves: FLEURS clips peak as low as -40 dBFS, and the
                # service's VAD dropped a quiet English half as silence (v1 of these clips).
                write_wav(out, level(a) + gap + level(b))
            res.append({"id": f"synth2-{lang}+en-{i:02d}", "set": "synth-cs", "lang": f"{lang}+en",
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
    p.add_argument("--prep", choices=["slr30", "omni", "yt5"],
                   help="extract a downloaded public set first (omni needs pyarrow)")
    a = p.parse_args()
    if a.prep == "slr30":
        prep_slr30(a.data)
    elif a.prep == "omni":
        prep_omni(a.data)
    elif a.prep == "yt5":
        prep_yt5(a.data)
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
