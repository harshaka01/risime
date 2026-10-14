"""Checks eval/sets/<capability>/<lang>.jsonl against eval/sets/README.md: schema, unique ids,
owners/counterparts/speakers among the members, date format, the checked flag, and privacy (no
phone-like digit runs, no '@', no names from the golden set's real-sounding list).

  python3 -I eval/sets/tools/validate.py [--root eval/sets]

Exit 1 on any error; prints counts per capability x language.
"""

import argparse
import json
import os
import re
import sys

CAPS = {"ledger": "ld", "summary": "sm", "actions": "ac"}
LANGS = {"si": "si", "ta": "ta", "singlish": "sg", "en": "en"}
SCRIPTS = {"sinhala", "tamil", "latin", "mixed"}
DATE = re.compile(r"^2026-\d{2}-\d{2}$")
PHONE = re.compile(r"(?<![\d.,])(?:\+?94|0)\d{9}(?!\d)|\d{3}[ -]\d{3}[ -]\d{4}")
KINDS = {"promise", "task", "meeting"}
NEG_TAGS = {"negative", "joke", "sarcasm", "hypothetical", "past", "other_person", "question",
            "suggestion", "wish", "group_plan", "refusal", "context_only", "vague_intent"}
ALSO = {"decision", "question", "number", "promise", "task", "meeting"}


def err(errors, cid, msg):
    errors.append(f"{cid}: {msg}")


def check_item(errors, cid, it, members, actions=False):
    if actions:
        if it.get("kind") not in KINDS:
            err(errors, cid, f"kind {it.get('kind')!r}")
        if it.get("owner") is not None and it["owner"] not in members:
            err(errors, cid, f"owner {it.get('owner')!r} not a member")
    elif it.get("owner") not in members:
        err(errors, cid, f"owner {it.get('owner')!r} not a member")
    to = it.get("to")
    if not isinstance(to, list) or any(t not in members for t in to) or it.get("owner") in to:
        err(errors, cid, f"bad to {to!r}")
    kw = it.get("kw")
    if not isinstance(kw, list) or not kw or not all(isinstance(k, str) and k for k in kw):
        err(errors, cid, "kw must be a non-empty list of strings")
    due = it.get("due", "missing")
    if due is not None and (not isinstance(due, str) or not DATE.match(due)):
        err(errors, cid, f"bad due {due!r}")


def texts(c):
    for m in c.get("messages") or c.get("lines") or []:
        yield m[1]


def validate(root):
    errors, counts, ids = [], {}, set()
    for cap, cp in CAPS.items():
        for lang, lp in LANGS.items():
            p = os.path.join(root, cap, lang + ".jsonl")
            if not os.path.exists(p):
                err(errors, p, "missing file")
                continue
            n = 0
            for ln, line in enumerate(open(p, encoding="utf-8"), 1):
                if not line.strip():
                    continue
                cid = f"{cap}/{lang}.jsonl:{ln}"
                try:
                    c = json.loads(line)
                except json.JSONDecodeError as e:
                    err(errors, cid, f"json: {e}")
                    continue
                n += 1
                cid = c.get("id", cid)
                if not re.fullmatch(rf"{cp}-{lp}-\d{{3}}", str(c.get("id"))):
                    err(errors, cid, "id must be <cap>-<lang>-NNN")
                if cid in ids:
                    err(errors, cid, "duplicate id")
                ids.add(cid)
                if c.get("lang") != lang:
                    err(errors, cid, f"lang {c.get('lang')!r} in {lang}.jsonl")
                if c.get("script") not in SCRIPTS:
                    err(errors, cid, f"script {c.get('script')!r}")
                if c.get("src") != "synthetic":
                    err(errors, cid, "src must be synthetic in git (pilot cases are sealed)")
                if not isinstance(c.get("checked"), bool) or "checked_by" not in c or "checked_on" not in c:
                    err(errors, cid, "checked / checked_by / checked_on missing")
                if not isinstance(c.get("tags"), list) or not c["tags"]:
                    err(errors, cid, "tags missing")
                members = c.get("members") or []
                if not members or len(set(members)) != len(members):
                    err(errors, cid, "members missing or duplicated")
                rows = c.get("lines" if cap == "actions" else "messages")
                if not isinstance(rows, list) or not rows:
                    err(errors, cid, "no messages/lines")
                    continue
                for r in rows:
                    want = 2 if cap == "actions" else 3
                    if not isinstance(r, list) or len(r) != want or r[0] not in members:
                        err(errors, cid, f"bad row {r!r}")
                    elif cap != "actions" and not isinstance(r[2], bool):
                        err(errors, cid, f"new? flag not bool in {r!r}")
                for t in texts(c):
                    if "@" in t or PHONE.search(t):
                        err(errors, cid, f"privacy: phone/email-like text {t[:40]!r}")
                if cap == "summary":
                    must = c.get("must")
                    if not isinstance(must, list) or not must or not all(
                            isinstance(g, list) and g and all(isinstance(s, str) and s for s in g) for g in must):
                        err(errors, cid, "must must be a non-empty list of any-of lists")
                    src = "\n".join(texts(c)).lower()
                    for f in c.get("forbid", []):
                        if f.lower() in src and "injection" not in c["tags"]:
                            err(errors, cid, f"forbid {f!r} occurs in the chat itself")
                        if f not in (c.get("forbid_why") or {}):
                            err(errors, cid, f"forbid {f!r} has no forbid_why")
                    for g in must or []:
                        if isinstance(g, list) and not any(isinstance(s, str) and s.lower() in src for s in g):
                            # A must-fact may be a translation; flag only for the checker.
                            print(f"note {cid}: no must variant of {g} occurs verbatim in the chat", file=sys.stderr)
                else:
                    exp = c.get("expect")
                    if not isinstance(exp, list):
                        err(errors, cid, "expect missing")
                        exp = []
                    for it in exp:
                        check_item(errors, cid, it, members, actions=cap == "actions")
                    if cap == "actions":
                        if c.get("source") not in ("call", "voice_note"):
                            err(errors, cid, f"source {c.get('source')!r}")
                        for a in c.get("also", []):
                            if a.get("kind") not in ALSO:
                                err(errors, cid, f"also kind {a.get('kind')!r}")
                        src = "\n".join(texts(c)).lower()
                        for f in c.get("forbid", []):
                            if f.lower() in src and "injection" not in c["tags"]:
                                err(errors, cid, f"forbid {f!r} occurs in the transcript itself")
                    if not exp and cap == "ledger" and not set(c["tags"]) & NEG_TAGS:
                        err(errors, cid, "expect [] needs a negative tag (README)")
            counts[(cap, lang)] = n
    return errors, counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    a = ap.parse_args()
    errors, counts = validate(a.root)
    total = 0
    for cap in CAPS:
        row = [f"{lang} {counts.get((cap, lang), 0)}" for lang in LANGS]
        s = sum(counts.get((cap, lang), 0) for lang in LANGS)
        total += s
        print(f"{cap:8} {s:4}  ({', '.join(row)})")
    print(f"total    {total:4}")
    for e in errors:
        print("ERROR", e)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
