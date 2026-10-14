"""Writes the stratified 20 % spot-check sample of eval/sets/ as a Markdown checklist for Harsha.

  python3 -I eval/sets/tools/spotcheck.py [--out docs/status/testset-spotcheck-2026-10-14.md] [--seed 20261014]

Strata: capability x language x group (ledger/actions: positive vs negative; summary: one
group), 20 % of each stratum rounded up, at least one per stratum, fixed seed. The file lists only
ids, the synthetic text and the drafted gold: nothing else.
"""

import argparse
import datetime as dt
import json
import math
import os
import random

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
REPO = os.path.join(ROOT, "..", "..")
CAPS = ["ledger", "summary", "actions"]
LANGS = ["si", "ta", "singlish", "en"]
LANG_NAME = {"si": "Sinhala", "ta": "Tamil", "singlish": "Singlish", "en": "English"}


def group(cap, c):
    if cap == "summary":
        return "all"
    return "negative" if not c["expect"] else "positive"


def gold(cap, c):
    if cap == "summary":
        must = "; ".join(" / ".join(g) for g in c["must"])
        forb = "; ".join(f"{f} ({c.get('forbid_why', {}).get(f, '')})" for f in c.get("forbid", []))
        return f"must: {must}" + (f" | must NOT: {forb}" if forb else "")
    if not c["expect"]:
        return "no item (" + ", ".join(t for t in c["tags"]) + ")"
    parts = []
    for e in c["expect"]:
        k = f"{e['kind']}: " if "kind" in e else ""
        to = f" -> {', '.join(e['to'])}" if e["to"] else ""
        parts.append(f"{k}{e.get('owner') or '(no owner)'}{to}: [{' / '.join(e['kw'])}] due {e['due'] or 'none'}")
    return "; ".join(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(REPO, "docs", "status", "testset-spotcheck-2026-10-14.md"))
    ap.add_argument("--seed", type=int, default=20261014)
    ap.add_argument("--share", type=float, default=0.2)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    out = [f"# Test-set spot-check ({dt.date(2026, 10, 14)})", "",
           "Harsha: a stratified 20 % sample of the drafted test sets (`eval/sets/`, see its README). Every case is "
           "**synthetic** (written by Claude; fictional names and companies). For each case, tick the box if the "
           "drafted gold is right; otherwise write the fix next to it (or edit the case in `eval/sets/` and set "
           '`"checked": true, "checked_by": "HK", "checked_on": "<date>"`). Reading guide: the chat clock is '
           "Sunday 2026-10-11 10:00 (tomorrow = Mon 10-12, Friday = 10-16). `->` = promised to. "
           "Lines marked (old) are context: promises in them don't count.", ""]
    total = picked = 0
    for cap in CAPS:
        out += [f"## {cap}", ""]
        for lang in LANGS:
            p = os.path.join(ROOT, cap, lang + ".jsonl")
            cases = [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
            total += len(cases)
            strata = {}
            for c in cases:
                strata.setdefault(group(cap, c), []).append(c)
            sample = []
            for g in sorted(strata):
                cs = strata[g]
                k = max(1, math.ceil(len(cs) * a.share))
                sample += rnd.sample(cs, min(k, len(cs)))
            sample.sort(key=lambda c: c["id"])
            picked += len(sample)
            out += [f"### {cap} / {LANG_NAME[lang]} ({len(sample)} of {len(cases)})", ""]
            for c in sample:
                head = f"- [ ] **{c['id']}**"
                if cap == "actions":
                    head += f" ({c['source'].replace('_', ' ')})"
                out.append(head)
                rows = c.get("messages") or c.get("lines")
                for r in rows:
                    old = " (old)" if len(r) > 2 and r[2] is False else ""
                    out.append(f"    - {r[0]}{old}: {r[1]}")
                out.append(f"    - *gold:* {gold(cap, c)}")
            out.append("")
    out[4:4] = [f"**{picked} of {total} cases** (strata: capability x language x positive/negative).", ""]
    with open(a.out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out).rstrip() + "\n")
    print(f"wrote {a.out}: {picked} of {total}")


if __name__ == "__main__":
    main()
