#!/usr/bin/env python3
"""Updates docs/status/risi-scores.md (decision 084) from:

  risi-eval-scores.py golden <dir> <md>   the golden-set JSON results of scripts/risi-eval (appends one
                                          row per set and mode to the "Golden sets" table)
  risi-eval-scores.py daily <file> <md>   the pilot's daily scoreboard (Markdown from
                                          RisiMe.Agents.Scoreboard.markdown/1) -> the "Daily" section

Counts, rates and timings only. Sections are delimited by HTML comments and created if missing.
"""
import datetime
import glob
import json
import os
import sys

G0, G1 = "<!-- golden:start -->", "<!-- golden:end -->"
D0, D1 = "<!-- daily:start -->", "<!-- daily:end -->"
GOLDEN_HEAD = (
    "| Run (UTC) | Set | Mode | n | Accuracy | Halluc | Traps caught | p50 ms | p95 ms | Model accuracy | Model p50/p95 ms | Failed |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|\n"
)


def pct(x):
    return "–" if x is None else f"{x * 100:.1f}%"


def num(x):
    if x is None:
        return "–"
    return f"{x:.1f}" if isinstance(x, float) else str(x)


def section(md, a, b, default):
    if a in md and b in md:
        return md.split(a)[1].split(b)[0]
    return default


def replace(md, a, b, body):
    if a in md and b in md:
        pre, rest = md.split(a, 1)
        post = rest.split(b, 1)[1]
        return pre + a + body + b + post
    return md.rstrip() + "\n\n" + a + body + b + "\n"


def golden(d, md_path):
    md = open(md_path, encoding="utf-8").read() if os.path.exists(md_path) else "# Risi agent scores\n"
    body = section(md, G0, G1, "\n" + GOLDEN_HEAD)
    if GOLDEN_HEAD.strip() not in body:
        body = "\n" + GOLDEN_HEAD
    run = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M")
    rows = []
    for f in sorted(glob.glob(os.path.join(d, "*.json"))):
        r = json.load(open(f, encoding="utf-8"))
        m = r.get("model") or {}
        rows.append(
            f"| {run} | {r['agent']} | {r['mode']} | {r['n']} | {pct(r.get('accuracy'))} | {r.get('halluc', 0)} | "
            f"{r.get('halluc_caught', 0)} | {num(r.get('p50_ms'))} | {num(r.get('p95_ms'))} | "
            f"{pct(m.get('accuracy')) if m else '–'} | {num(m.get('p50_ms')) + '/' + num(m.get('p95_ms')) if m else '–'} | "
            f"{', '.join(r.get('failed', [])[:8]) or '–'} |"
        )
    body = body.rstrip("\n") + "\n" + "\n".join(rows) + "\n"
    open(md_path, "w", encoding="utf-8").write(replace(md, G0, G1, body))
    print(f"risi-scores: {len(rows)} golden rows -> {md_path}")


def daily(f, md_path):
    table = open(f, encoding="utf-8").read().strip()
    md = open(md_path, encoding="utf-8").read() if os.path.exists(md_path) else "# Risi agent scores\n"
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    body = f"\nFrom the pilot's `risi_agent_scores` (generated {stamp}).\n\n{table}\n"
    open(md_path, "w", encoding="utf-8").write(replace(md, D0, D1, body))
    print(f"risi-scores: daily section -> {md_path}")


if __name__ == "__main__":
    if len(sys.argv) != 4 or sys.argv[1] not in ("golden", "daily"):
        print(__doc__)
        sys.exit(2)
    (golden if sys.argv[1] == "golden" else daily)(sys.argv[2], sys.argv[3])
