#!/usr/bin/env python3
"""Verifies ~/risime-eval-models against Hugging Face metadata (API tree listing only; no file
downloads). Every file of the repo's main revision is compared by size and hash: LFS files by
sha256 (`lfs.oid`), small files by their git blob sha1 (`oid`). Files left out on purpose by
eval/risi/hf_fetch.py (original/*, metal/*, *.pt, *.pth, optimizer*) are reported as excluded.
Weight shards are counted against model.safetensors.index.json when there is one.

  python3 -I eval/risi/verify_models.py [models dir] > table.md
"""
import fnmatch
import hashlib
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/risime-eval-models")
EXCLUDED = ["original/*", "metal/*", "*.pt", "*.pth", "optimizer*"]

cands = json.load(open(os.path.join(HERE, "candidates.json")))
repos = {n: c["repo"] for kind in ("llm", "asr") for n, c in cands[kind].items() if "repo" in c}


def tree(repo):
    url = f"https://huggingface.co/api/models/{repo}/tree/main?recursive=true&expand=false"
    out = []
    while url:
        with urllib.request.urlopen(url, timeout=60) as r:
            out += json.load(r)
            link = r.headers.get("Link") or ""
        url = None
        for part in link.split(","):
            if 'rel="next"' in part:
                url = part[part.index("<") + 1:part.index(">")]
    return [e for e in out if e.get("type") == "file"]


def digest(path, algo, git_blob=False):
    h = hashlib.new(algo)
    if git_blob:
        h.update(f"blob {os.path.getsize(path)}\0".encode())
    with open(path, "rb") as f:
        while chunk := f.read(1 << 24):
            h.update(chunk)
    return h.hexdigest()


rows = []
for name in sorted(os.listdir(MODELS)):
    d = os.path.join(MODELS, name)
    if not os.path.isdir(d) or os.path.islink(d) or name not in repos:
        continue
    repo = repos[name]
    files = tree(repo)
    ok = bad = excl = 0
    problems = []
    total = 0
    for e in files:
        p = e["path"]
        if any(fnmatch.fnmatch(p, pat) for pat in EXCLUDED):
            excl += 1
            continue
        lp = os.path.join(d, p)
        size = (e.get("lfs") or {}).get("size", e.get("size"))
        if not os.path.exists(lp):
            bad += 1
            problems.append(f"missing {p}")
            continue
        if os.path.getsize(lp) != size:
            bad += 1
            problems.append(f"size {p}")
            continue
        if e.get("lfs"):
            good = digest(lp, "sha256") == e["lfs"]["oid"]
        else:
            good = digest(lp, "sha1", git_blob=True) == e["oid"]
        total += size
        if good:
            ok += 1
        else:
            bad += 1
            problems.append(f"hash {p}")
    idx = os.path.join(d, "model.safetensors.index.json")
    shards = len(set(json.load(open(idx))["weight_map"].values())) if os.path.exists(idx) else \
        sum(1 for e in files if e["path"].endswith(".safetensors"))
    rows.append((name, repo, ok + bad, excl, shards, total / 1e9, "ok" if bad == 0 else "FAILED", "; ".join(problems[:5])))
    print(f"{name}: {ok} ok, {bad} bad, {excl} excluded", file=sys.stderr, flush=True)

print("| model dir | HF repo | files checked | excluded by pattern | weight shards | size GB | verified | problems |")
print("|---|---|---|---|---|---|---|---|")
for r in rows:
    print(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]} | {r[5]:.2f} | {r[6]} | {r[7] or '-'} |")
