"""ASR eval for the speech service (decision 071, amendment c): WER, CER and script % per model.

Runs clips from a manifest through the running service (127.0.0.1:8200, the production code path:
same loaders, same Sinhala prompt) with an explicitly named model and the clip's language forced,
or through model=auto (routing included), and scores them. Audio never enters git: manifests point
at files under ~/risime-eval-data.

Manifest (JSONL, one clip per line):
  id             unique id
  audio          absolute path (outside git)
  text           reference transcript (native script; English words in Latin as spoken)
  lang           si | ta | en | si+en | ta+en   (si+en = code-switched, base language first)
  source         where the clip comes from
  licence        licence of the clip
  in_training_of [model names]  models whose training data contains this clip (or its source
                 split): their score on it is excluded; "*" = every candidate
  kind           read | broadcast | real | synthetic-concat   (optional)
  synthetic      true for clips made by concatenating other clips (optional)

  run    --manifest M --models a,b,auto [--langs si,ta] [--sets omni-si] [--limit N] --out R
         [--chunk-s 15]  clips longer than this are sent in chunks (cut at the quietest 20 ms
                         in the last 3 s), like the call path; the texts are joined
         [--no-force-lang]  (model=auto never gets a language)
         Results are appended (resumable: a (model, clip) pair already in --out is skipped).
         A 503 (no memory to load the model) waits and retries for up to --wait-s seconds.
  score  --manifest M --results R [--langs ...] [--markdown] [--by kind]
  check  --manifest M     (paths exist, fields present, ids unique)

Scoring: NFC, lower-case, punctuation and ZWJ/ZWNJ removed, whitespace collapsed (same as
eval/risi). WER over words, CER over characters without spaces, both as corpus totals
(sum of edits / sum of reference lengths). Script %: letters in the expected script (Sinhala
U+0D80-U+0DFF, Tamil U+0B80-U+0BFF, Latin for en) over all letters of the hypothesis (combining
signs count with their block). For code-switched clips, the reference's own script % is shown
next to it. "romanised" = a si/ta hypothesis with letters but none in the expected script.
"en kept" (code-switching) = English words of a si/ta reference (Latin script) that the
hypothesis also has in Latin script, over all such reference words.
"""

import argparse
import array
import collections
import json
import os
import struct
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid

SCRIPT = {"si": ("඀", "෿"), "ta": ("஀", "௿")}


def base_lang(lang):
    return lang.split("+")[0]


def normalise(s):
    s = unicodedata.normalize("NFC", s or "").lower()
    s = s.replace("‍", "").replace("‌", "")
    out = []
    for c in s:
        cat = unicodedata.category(c)
        out.append(" " if cat.startswith("P") or cat.startswith("S") else c)
    return " ".join("".join(out).split())


def edits(a, b):
    """Levenshtein distance between sequences a and b."""
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def in_block(c, lang):
    if lang == "en":
        return c.isascii() or unicodedata.name(c, "").startswith("LATIN")
    lo, hi = SCRIPT[lang]
    return lo <= c <= hi


def script_stats(text, lang):
    """(expected-script letters, all letters, latin letters)."""
    exp = letters = latin = 0
    for c in text or "":
        cat = unicodedata.category(c)
        if not (cat.startswith("L") or cat.startswith("M")):
            continue
        letters += 1
        if c.isascii() or unicodedata.name(c, "").startswith("LATIN"):
            latin += 1
        if in_block(c, lang):
            exp += 1
    return exp, letters, latin


def load_manifest(path):
    clips = []
    with open(path) as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if line and not line.startswith("#"):
                c = json.loads(line)
                c.setdefault("in_training_of", [])
                clips.append(c)
    return clips


def post(url, key, path, fields, timeout=600):
    """multipart POST; fields["file"] = (filename, bytes)."""
    boundary = uuid.uuid4().hex
    io_parts = []
    for k, v in fields.items():
        if k == "file":
            name, data = v
            io_parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                            f'filename="{name}"\r\n'
                            f"Content-Type: application/octet-stream\r\n\r\n".encode() + data
                            + b"\r\n")
        elif v is not None:
            io_parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n'
                            f"{v}\r\n".encode())
    body = b"".join(io_parts) + f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(url + path, data=body, method="POST", headers={
        "Authorization": f"Bearer {key}",
        "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def read_pcm16(path):
    """WAV (mono PCM16 or float32) -> (array('h'), rate)."""
    with open(path, "rb") as f:
        data = f.read()
    pos, fmt, rate, bits, pcm = 12, None, None, None, b""
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = data[pos + 8:pos + 8 + size]
        if cid == b"fmt ":
            fmt, ch, rate = struct.unpack("<HHI", body[:8])
            bits = struct.unpack("<H", body[14:16])[0]
            if ch != 1:
                raise ValueError(f"{path}: not mono")
        elif cid == b"data":
            pcm = body
        pos += 8 + size + (size & 1)
    if fmt == 1 and bits == 16:
        a = array.array("h")
        a.frombytes(pcm)
    elif fmt == 3 and bits == 32:
        fl = array.array("f")
        fl.frombytes(pcm)
        a = array.array("h", (max(-32768, min(32767, int(x * 32767))) for x in fl))
    else:
        raise ValueError(f"{path}: WAV format {fmt}/{bits} unsupported")
    return a, rate


def wav_bytes(samples, rate):
    raw = samples.tobytes()
    return struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(raw), b"WAVE", b"fmt ", 16, 1, 1,
                       rate, rate * 2, 2, 16, b"data", len(raw)) + raw


def chunks(path, chunk_s):
    """Split like the call path does (fixed-length chunks), but cut at the quietest 20 ms frame
    in the last 3 s of each window so fewer words are cut in half. -> [(name, wav bytes)]."""
    a, rate = read_pcm16(path)
    size, frame = int(chunk_s * rate), rate // 50
    if len(a) <= size:
        return [(os.path.basename(path), wav_bytes(a, rate))]
    out, start = [], 0
    while start < len(a):
        end = min(start + size, len(a))
        if end < len(a):
            best, best_e = end, None
            for f0 in range(end - 3 * rate, end - frame, frame):
                e = sum(x * x for x in a[f0:f0 + frame:4])
                if best_e is None or e < best_e:
                    best, best_e = f0 + frame // 2, e
            end = best
        out.append((f"chunk{len(out)}.wav", wav_bytes(a[start:end], rate)))
        start = end
    return out


def transcribe(a, key, model, file, lang):
    """One request with the 503 (no memory) wait. -> response dict or {"error": ...}."""
    fields = {"file": file, "model": model, "response_format": "verbose_json", "language": lang}
    deadline = time.monotonic() + a.wait_s
    while True:
        try:
            return post(a.url, key, "/v1/audio/transcriptions", fields)
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="replace")[:300]
            if e.code == 503 and time.monotonic() < deadline:
                print(f"  503 for {model} (memory), waiting 30 s", file=sys.stderr)
                time.sleep(30)
                continue
            return {"error": f"HTTP {e.code}: {msg}"}
        except Exception as e:  # timeouts etc.
            return {"error": f"{type(e).__name__}: {e}"}


def cmd_run(a):
    clips = [c for c in load_manifest(a.manifest)
             if (not a.langs or c["lang"] in a.langs or base_lang(c["lang"]) in a.langs)
             and (not a.sets or c.get("set") in a.sets)]
    if a.limit:
        clips = clips[:a.limit]
    done = set()
    if os.path.exists(a.out):
        with open(a.out) as f:
            for line in f:
                r = json.loads(line)
                if "error" not in r:
                    done.add((r["model"], r["id"]))
    key = os.environ["WHISPER_API_KEY"]
    with open(a.out, "a") as out:
        for model in a.models:
            n = 0
            for c in clips:
                if (model, c["id"]) in done:
                    continue
                lang = None if (model == "auto" or a.no_force_lang) else base_lang(c["lang"])
                t0 = time.monotonic()
                parts = []
                for piece in chunks(c["audio"], a.chunk_s):
                    res = transcribe(a, key, model, piece, lang)
                    parts.append(res)
                    if "error" in res:
                        break
                rec = {"model": model, "id": c["id"], "lang": c["lang"],
                       "forced_language": lang, "wall_s": round(time.monotonic() - t0, 3),
                       "duration_s": c.get("duration_s"), "chunks": len(parts)}
                bad = [p for p in parts if "error" in p]
                if bad:
                    rec["error"] = bad[0]["error"]
                    print(f"  {model} {c['id']}: {rec['error']}", file=sys.stderr)
                else:
                    first = parts[0]
                    rec.update(text=" ".join(p.get("text", "").strip() for p in parts).strip(),
                               served_by=",".join(sorted({p.get("model") or "" for p in parts})),
                               language=first.get("language"),
                               processing_s=round(sum(p.get("model_processing_s") or 0
                                                      for p in parts), 3),
                               retried_with=next((p["retried_with"] for p in parts
                                                  if p.get("retried_with")), None),
                               route=(first.get("routing") or {}).get("reason"),
                               fallback=next(((p.get("routing") or {}).get("fallback")
                                              for p in parts
                                              if (p.get("routing") or {}).get("fallback")), None))
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out.flush()
                n += 1
            print(f"{model}: {n} clips run", file=sys.stderr)


def score(clips, results, langs=None, by=None, include_trained=False):
    """-> {(model, group): stats}"""
    meta = {c["id"]: c for c in clips}
    agg = collections.defaultdict(lambda: collections.Counter())
    for r in results:
        c = meta.get(r["id"])
        if c is None or (langs and c["lang"] not in langs and base_lang(c["lang"]) not in langs):
            continue
        group = c["lang"] if by is None else f'{c["lang"]}/{c.get(by, "-")}'
        st = agg[(r["model"], group)]
        st["clips"] += 1
        excl = c["in_training_of"]
        if "*" in excl or r["model"] in excl or (r["model"] == "auto" and r.get("served_by") in excl):
            st["excluded"] += 1
            if not include_trained:
                continue
        if "error" in r:
            st["errors"] += 1
            continue
        ref, hyp = normalise(c["text"]), normalise(r["text"])
        rw, hw = ref.split(), hyp.split()
        st["n"] += 1
        st["w_err"] += edits(rw, hw)
        st["w_ref"] += len(rw)
        rc, hc = ref.replace(" ", ""), hyp.replace(" ", "")
        st["c_err"] += edits(list(rc), list(hc))
        st["c_ref"] += len(rc)
        bl = base_lang(c["lang"])
        e, l, lat = script_stats(r["text"], bl)
        st["s_exp"] += e
        st["s_all"] += l
        st["s_latin"] += lat
        re_, rl, _ = script_stats(c["text"], bl)
        st["ref_exp"] += re_
        st["ref_all"] += rl
        if bl in SCRIPT and l > 0 and e == 0:
            st["romanised"] += 1
        # Code-switching: English (Latin) words of the reference found in Latin in the hypothesis.
        if bl in SCRIPT:
            ref_en = [w for w in rw if w.isascii() and any(ch.isalpha() for ch in w)]
            hyp_en = collections.Counter(w for w in hw if w.isascii())
            st["en_ref"] += len(ref_en)
            for w in ref_en:
                if hyp_en[w] > 0:
                    hyp_en[w] -= 1
                    st["en_kept"] += 1
        if not hyp:
            st["empty"] += 1
        if r.get("retried_with"):
            st["retried"] += 1
        if r.get("fallback"):
            st["fallback"] += 1
        st["proc_s"] += r.get("processing_s") or 0
        st["audio_s"] += c.get("duration_s") or 0
        if r["model"] == "auto":
            st[f"served:{r.get('served_by')}"] += 1
    return agg


def pct(a, b):
    return f"{100 * a / b:.1f}%" if b else "–"


def cmd_score(a):
    clips = load_manifest(a.manifest)
    results = []
    for path in a.results:
        with open(path) as f:
            results += [json.loads(line) for line in f if line.strip()]
    # The last successful result per (model, clip) wins (reruns override; errors never do).
    last = {}
    for r in results:
        k = (r["model"], r["id"])
        if "error" not in r or k not in last or "error" in last[k]:  # a success is never
            last[k] = r                                                # replaced by an error
    agg = score(clips, list(last.values()), a.langs, a.by, a.include_trained)
    rows = []
    for (model, group), st in sorted(agg.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        served = ", ".join(f"{k[7:]} {v}" for k, v in st.items() if k.startswith("served:"))
        rows.append([group, model, str(st["n"]), str(st["excluded"]), str(st["errors"]),
                     pct(st["w_err"], st["w_ref"]), pct(st["c_err"], st["c_ref"]),
                     pct(st["s_exp"], st["s_all"]) + (f' (ref {pct(st["ref_exp"], st["ref_all"])})'
                                                      if "+" in group else ""),
                     str(st["romanised"]), pct(st["en_kept"], st["en_ref"]), str(st["empty"]),
                     f'{st["proc_s"] / st["audio_s"]:.2f}' if st["audio_s"] else "–",
                     (f'retried {st["retried"]} ' if st["retried"] else "")
                     + (f'fallback {st["fallback"]} ' if st["fallback"] else "") + served])
    head = ["set", "model", "n", "excl", "err", "WER", "CER", "script %", "romanised", "en kept", "empty",
            "RTF", "notes"]
    if a.json:
        print(json.dumps([dict(zip(head, r)) for r in rows], ensure_ascii=False, indent=1))
    elif a.markdown:
        print("| " + " | ".join(head) + " |")
        print("|" + "---|" * len(head))
        for r in rows:
            print("| " + " | ".join(r) + " |")
    else:
        w = [max(len(h), *(len(r[i]) for r in rows)) if rows else len(h)
             for i, h in enumerate(head)]
        print("  ".join(h.ljust(w[i]) for i, h in enumerate(head)))
        for r in rows:
            print("  ".join(x.ljust(w[i]) for i, x in enumerate(r)))


def cmd_check(a):
    clips = load_manifest(a.manifest)
    ids, bad = set(), 0
    for c in clips:
        for k in ("id", "audio", "text", "lang", "source", "licence"):
            if k not in c:
                print(f"{c.get('id')}: missing {k}")
                bad += 1
        if c["id"] in ids:
            print(f"{c['id']}: duplicate id")
            bad += 1
        ids.add(c["id"])
        if not os.path.isfile(c.get("audio", "")):
            print(f"{c['id']}: audio missing: {c.get('audio')}")
            bad += 1
        if c.get("lang") not in ("si", "ta", "en", "si+en", "ta+en"):
            print(f"{c['id']}: lang {c.get('lang')}")
            bad += 1
    by = collections.Counter(c["lang"] for c in clips)
    print(f"{len(clips)} clips {dict(by)}; {bad} problems")
    return 1 if bad else 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--manifest", required=True)
    r.add_argument("--models", required=True, type=lambda s: s.split(","))
    r.add_argument("--langs", type=lambda s: s.split(","))
    r.add_argument("--limit", type=int)
    r.add_argument("--out", required=True)
    r.add_argument("--url", default=os.environ.get("WHISPER_URL", "http://127.0.0.1:8200"))
    r.add_argument("--no-force-lang", action="store_true")
    r.add_argument("--sets", type=lambda s: s.split(","), help="only these manifest sets")
    r.add_argument("--chunk-s", type=float, default=15.0,
                   help="clips longer than this go in chunks, like call audio (default 15)")
    r.add_argument("--wait-s", type=int, default=1800)
    s = sub.add_parser("score")
    s.add_argument("--manifest", required=True)
    s.add_argument("--results", required=True, nargs="+")
    s.add_argument("--langs", type=lambda x: x.split(","))
    s.add_argument("--by")
    s.add_argument("--include-trained", action="store_true",
                   help="score in-training clips too (excl then counts them; for reference only)")
    s.add_argument("--markdown", action="store_true")
    s.add_argument("--json", action="store_true")
    c = sub.add_parser("check")
    c.add_argument("--manifest", required=True)
    a = p.parse_args()
    if a.cmd == "run":
        cmd_run(a)
    elif a.cmd == "score":
        cmd_score(a)
    else:
        sys.exit(cmd_check(a))


if __name__ == "__main__":
    main()
