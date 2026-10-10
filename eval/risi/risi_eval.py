#!/usr/bin/env python3
"""Risi model eval: run, score, blind A/B pairs, ASR WER, report (docs/RISI-MODELS.md).

Stdlib + jsonschema (host python3). Synthetic data only. Every request is a single, sequential
OpenAI-compatible call to a loopback vLLM (risi-l1 on :8100 read-only, a candidate on :8101).

  risi_eval.py run    --url http://127.0.0.1:8101 --model cand --label NAME [--key-env LLM_API_KEY]
                      [--files si,ta,en,mixed] [--limit N] [--no-thinking-kwarg] [--sleep 0.2]
  risi_eval.py score  NAME [NAME ...]           -> results/NAME.score.json (+ prints a summary)
  risi_eval.py pairs  BASE CAND [--seed 7]      -> results/pairs/BASE__CAND.jsonl + .key.json
  risi_eval.py unblind BASE CAND                -> merges results/pairs/BASE__CAND.verdicts.jsonl
  risi_eval.py report                           -> results/REPORT.md (every scored model)
  risi_eval.py asr    --url URL --model ALIAS --label NAME --manifest M.jsonl [--key-env K]
  risi_eval.py probe                            -> checks live risi-l1 + the pilot (read-only)
"""
import argparse
import datetime as dt
import json
import os
import random
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "results")
REPO = os.path.dirname(os.path.dirname(HERE))
NOW = dt.datetime(2026, 10, 12, 9, 15)
OFFSET = dt.timedelta(hours=5, minutes=30)  # Asia/Colombo
FILES = ["si", "ta", "en", "mixed"]


def env_key(name):
    if not name:
        return None
    if os.environ.get(name):
        return os.environ[name]
    try:
        for line in open(os.path.join(REPO, ".env"), encoding="utf-8"):
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def load_items(files=FILES):
    items = []
    for f in files:
        with open(os.path.join(HERE, f"{f}.jsonl"), encoding="utf-8") as fh:
            items += [json.loads(l) for l in fh if l.strip()]
    return items


PROMPTS = None


def schema_of(path):
    global PROMPTS
    if PROMPTS is None:
        PROMPTS = json.load(open(os.path.join(HERE, "prompts.json"), encoding="utf-8"))
    node = PROMPTS
    for p in path.split("."):
        node = node[p]
    return node


# ------------------------------------------------------------------------------------------ run

_last_health = [0.0]
PAUSES = {"n": 0, "s": 0.0}


def pilot_gate(every=30, limit=1.0):
    """Harsha's rule: watch RisiMe /health every 30 s; pause while it answers slower than 1 s
    (or not at all), resume once it is back under."""
    if time.monotonic() - _last_health[0] < every:
        return
    while True:
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen("http://127.0.0.1:4000/health", timeout=5) as r:
                ok = r.status == 200
        except Exception:  # noqa: BLE001
            ok = False
        dt_s = time.monotonic() - t0
        _last_health[0] = time.monotonic()
        if ok and dt_s <= limit:
            return
        PAUSES["n"] += 1
        PAUSES["s"] += every
        print(f"pilot /health {'slow' if ok else 'down'} ({dt_s:.2f}s): pausing {every}s", flush=True)
        time.sleep(every)


def tool_first(schema):
    """The risi_next_action schema with "tool" as the first property of every alternative.
    The server sends it Jason-encoded (sorted keys: "args"/"answer" before "tool"), and guided
    decoding follows property order, so the model must write args before naming the tool."""
    if not isinstance(schema, dict) or "anyOf" not in schema:
        return schema
    alts = []
    for alt in schema["anyOf"]:
        props = alt.get("properties", {})
        if "tool" in props:
            props = {"tool": props["tool"], **{k: v for k, v in props.items() if k != "tool"}}
            alt = {**alt, "properties": props,
                   "required": ["tool"] + [r for r in alt.get("required", []) if r != "tool"]}
        alts.append(alt)
    return {**schema, "anyOf": alts}


def stream_chat(url, key, body, timeout=180):
    h = {"Content-Type": "application/json"}
    if key:
        h["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url.rstrip("/") + "/v1/chat/completions",
                                 data=json.dumps(body).encode(), headers=h, method="POST")
    t0 = time.monotonic()
    ttft, parts, usage, reasoning = None, [], None, []
    with urllib.request.urlopen(req, timeout=timeout) as r:
        for raw in r:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data: ") or line == "data: [DONE]":
                continue
            ev = json.loads(line[6:])
            if ev.get("usage"):
                usage = ev["usage"]
            for ch in ev.get("choices", []):
                d = ch.get("delta", {})
                piece = d.get("content")
                rp = d.get("reasoning_content") or d.get("reasoning")
                if rp:
                    reasoning.append(rp)
                    if ttft is None:
                        ttft = time.monotonic() - t0
                if piece:
                    if ttft is None:
                        ttft = time.monotonic() - t0
                    parts.append(piece)
    return "".join(parts), usage or {}, ttft, time.monotonic() - t0, "".join(reasoning)


def cmd_run(a):
    key = env_key(a.key_env)
    items = load_items(a.files.split(","))
    if a.only:
        items = [i for i in items if re.search(a.only, i["id"])]
    if a.limit:
        items = items[: a.limit]
    os.makedirs(RES, exist_ok=True)
    out = os.path.join(RES, f"{a.label}.jsonl")
    done = set()
    if os.path.exists(out) and a.resume:
        done = {json.loads(l)["id"] for l in open(out, encoding="utf-8")}
    mode = "a" if a.resume else "w"
    extra = json.loads(a.extra_body) if a.extra_body else {}
    with open(out, mode, encoding="utf-8") as fh:
        for n, it in enumerate(items, 1):
            if it["id"] in done:
                continue
            pilot_gate()
            body = {"model": a.model, "messages": it["messages"], "temperature": 0.2, "top_p": 0.8,
                    "max_tokens": it["max_tokens"], "stream": True,
                    "stream_options": {"include_usage": True},
                    "response_format": {"type": "json_schema", "json_schema": {
                        "name": it["schema"].split(".")[-2] if it["kind"] == "turn" else it["schema"],
                        "schema": tool_first(schema_of(it["schema"])) if a.tool_first else schema_of(it["schema"]),
                        "strict": True}}}
            if not a.no_thinking_kwarg:
                body["chat_template_kwargs"] = {"enable_thinking": False}
            body.update(extra)
            rec = {"id": it["id"], "label": a.label, "model": a.model}
            try:
                text, usage, ttft, total, reasoning = stream_chat(a.url, key, body)
                rec.update({"text": text, "ttft_s": round(ttft or 0, 3), "total_s": round(total, 3),
                            "prompt_tokens": usage.get("prompt_tokens"),
                            "completion_tokens": usage.get("completion_tokens")})
                if reasoning:
                    rec["reasoning_chars"] = len(reasoning)
            except urllib.error.HTTPError as e:
                rec["error"] = f"http {e.code}: {e.read()[:300].decode('utf-8', 'replace')}"
            except Exception as e:  # noqa: BLE001 - recorded, run continues
                rec["error"] = f"{type(e).__name__}: {e}"
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            fh.flush()
            status = rec.get("error") or f"{rec['completion_tokens']} tok {rec['total_s']}s"
            print(f"[{n}/{len(items)}] {it['id']}: {status}", flush=True)
            time.sleep(a.sleep)
    print(f"wrote {out} (pilot-health pauses: {PAUSES['n']}, {PAUSES['s']:.0f} s)")


# ---------------------------------------------------------------------------------- time checks

WD = {"mon": 0, "monday": 0, "tue": 1, "tues": 1, "tuesday": 1, "wed": 2, "wednesday": 2,
      "thu": 3, "thur": 3, "thurs": 3, "thursday": 3, "fri": 4, "friday": 4, "sat": 5,
      "saturday": 5, "sun": 6, "sunday": 6}
MON = {m: i for i, m in enumerate(["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep",
                                   "oct", "nov", "dec"], 1)}


def parse_clock(s):
    """'06:00', '6am', '6:30 pm', '18.30', 'noon' -> (h, m) or None (a bare '6' is ambiguous)."""
    s = s.strip().lower()
    if s in ("noon", "midday"):
        return (12, 0)
    if s == "midnight":
        return (0, 0)
    m = re.search(r"\b(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?![a-z])", s)
    if m:
        h, mi = int(m.group(1)), int(m.group(2) or 0)
        if h > 12:
            return None
        h = h % 12 + (12 if m.group(3).startswith("p") else 0)
        return (h, mi)
    m = re.search(r"\b(\d{1,2})[:.](\d{2})\b", s)
    if m:
        h, mi = int(m.group(1)), int(m.group(2))
        if h < 24 and mi < 60:
            return (h, mi)
    return None


def resolve(v):
    """An ISO time or an English phrase -> (local naive datetime, 'datetime'|'date') or None."""
    if not isinstance(v, str):
        return None
    s = v.strip()
    m = re.fullmatch(r"(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{1,2}):(\d{2})(?::\d{2}(?:\.\d+)?)?)?\s*"
                     r"(Z|[+-]\d{2}:?\d{2})?", s)
    if m:
        y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if m.group(4) is None:
            return dt.datetime(y, mo, d), "date"
        t = dt.datetime(y, mo, d, int(m.group(4)), int(m.group(5)))
        z = m.group(6)
        if z:
            if z == "Z":
                off = dt.timedelta(0)
            else:
                zz = z.replace(":", "")
                off = dt.timedelta(hours=int(zz[1:3]), minutes=int(zz[3:5])) * (1 if zz[0] == "+" else -1)
            t = t - off + OFFSET
        return t, "datetime"
    low = s.lower()
    m = re.search(r"\bin\s+(\d+|an?|one)\s*(minutes?|mins?|hours?|hrs?|days?|weeks?)\b", low)
    if m:
        n = 1 if m.group(1) in ("a", "an", "one") else int(m.group(1))
        u = m.group(2)
        delta = (dt.timedelta(minutes=n) if u.startswith("m") else dt.timedelta(hours=n)
                 if u.startswith("h") else dt.timedelta(days=n) if u.startswith("d")
                 else dt.timedelta(weeks=n))
        return NOW + delta, "datetime"
    day = None
    m = re.search(r"(\d{4}-\d{2}-\d{2})", low)
    if m:
        day = dt.datetime.strptime(m.group(1), "%Y-%m-%d").date()
    elif re.search(r"\btomorrow\b", low):
        day = (NOW + dt.timedelta(days=1)).date()
    elif re.search(r"\b(today|tonight|this (morning|afternoon|evening))\b", low):
        day = NOW.date()
    else:
        m = re.search(r"\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})\b", low) \
            or re.search(r"\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)", low)
        if m:
            g = m.groups()
            mon, d = (MON[g[0][:3]], int(g[1])) if g[0][:3] in MON else (MON[g[1][:3]], int(g[0]))
            day = dt.date(2026, mon, d)
        else:
            m = re.search(r"\b(next\s+)?(" + "|".join(sorted(WD, key=len, reverse=True)) + r")\b", low)
            if m:
                ahead = (WD[m.group(2)] - NOW.weekday()) % 7
                if m.group(1) and ahead == 0:
                    ahead = 7
                day = (NOW + dt.timedelta(days=ahead)).date()
    rest = re.sub(r"\d{4}-\d{2}-\d{2}", " ", low)
    clock = parse_clock(rest)
    if clock is None and re.search(r"\bnoon\b", rest):
        clock = (12, 0)
    if clock is None and re.search(r"\bmidnight\b", rest):
        clock = (0, 0)
    if day is None and clock is None:
        return None
    if clock is None:
        return dt.datetime.combine(day, dt.time()), "date"
    if day is None:
        cand = NOW.replace(hour=clock[0], minute=clock[1])
        if cand <= NOW:
            cand += dt.timedelta(days=1)
        return cand, "datetime"
    return dt.datetime.combine(day, dt.time(*clock)), "datetime"


def iso(s):
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%M")


def check_value(spec, v):
    """One arg check -> (ok, why)."""
    if "null" in spec:
        return (v is None, "want null")
    if "eq" in spec:
        return (v == spec["eq"], f"want {spec['eq']!r}")
    if "set" in spec:
        return (isinstance(v, list) and sorted(v) == sorted(spec["set"]), f"want {spec['set']}")
    if "re" in spec:
        s = " ".join(v) if isinstance(v, list) else ("" if v is None else str(v))
        return (re.search(spec["re"], s, re.I) is not None, f"want /{spec['re']}/")
    if "clock" in spec:
        want = tuple(int(x) for x in spec["clock"].split(":"))
        c = parse_clock(v) if isinstance(v, str) else None
        if c is None:
            r = resolve(v)
            c = (r[0].hour, r[0].minute) if r and r[1] == "datetime" else None
        return (c == want, f"want clock {spec['clock']}")
    if "at" in spec:
        r = resolve(v)
        if not r or r[1] != "datetime":
            return (False, f"unreadable/ambiguous time {v!r}")
        tol = dt.timedelta(minutes=spec.get("tol", 0))
        return (abs(r[0] - iso(spec["at"])) <= tol, f"want {spec['at']} got {r[0]:%Y-%m-%dT%H:%M}")
    if "between" in spec:
        r = resolve(v)
        if not r:
            return (False, f"unreadable time {v!r}")
        lo, hi = iso(spec["between"][0]), iso(spec["between"][1])
        cands = [r[0]] if r[1] == "datetime" else [r[0], r[0] + dt.timedelta(hours=23, minutes=59)]
        return (any(lo <= c <= hi for c in cands), f"want {lo}..{hi}")
    return (False, f"unknown spec {spec}")


# ------------------------------------------------------------------------------- script checks

def script_class(text, want):
    """'ok' | 'romanised' | 'wrong_script' | 'mixed_script' | 'n/a' for the answer text."""
    if not text or not text.strip():
        return "n/a"
    c = {"si": 0, "ta": 0, "latin": 0, "other": 0}
    for ch in text:
        o = ord(ch)
        if 0x0D80 <= o <= 0x0DFF:
            c["si"] += 1
        elif 0x0B80 <= o <= 0x0BFF:
            c["ta"] += 1
        elif ch.isascii() and ch.isalpha():
            c["latin"] += 1
        elif ch.isalpha() or unicodedata.category(ch).startswith("M"):
            c["other"] += 1
    tot = sum(c.values())
    if tot == 0:
        return "n/a"
    frac = {k: v / tot for k, v in c.items()}
    if want in ("si", "ta"):
        foreign = (c["ta"] if want == "si" else c["si"]) + c["other"]
        if foreign / tot >= 0.05:
            return "wrong_script"
        if frac[want] >= 0.5:
            return "ok"
        if frac["latin"] >= 0.8:
            return "romanised"
        return "mixed_script"
    if want == "en":
        return "ok" if frac["latin"] >= 0.9 else "wrong_script"
    native = "si" if want.startswith("si") else "ta"
    other = "ta" if native == "si" else "si"
    if (c[other] + c["other"]) / tot >= 0.05:
        return "wrong_script"
    return "ok"


# Capabilities.forbidden?/1 (the "transcript does not contain" phrasing), ported.
FORBIDDEN = [
    re.compile(r"\b(transcript|context|conversation|chat|messages?)\b[^.!?\n]{0,40}\b(does not|doesn't|do not|"
               r"don't|did not|didn't|has no|have no|lacks?)\b[^.!?\n]{0,20}\b(contain|include|mention|say|"
               r"have|information|info|details?|data)", re.I),
    re.compile(r"\b(not|no)\b[^.!?\n]{0,30}\b(mentioned|found|included|contained|information|details?)\b"
               r"[^.!?\n]{0,20}\b(in|from)\s+the\s+(transcript|context|conversation|chat)", re.I),
]
# "I've set it" without a tool call or a filled draft: the asker gets no card, nothing happens.
CLAIM = re.compile(r"\b(I'?ve|I have|I'?ll|I will|I am going to)\s+(set|add|schedul|creat|sen|remind|wake|book|put)|"
                   r"has been (set|added|scheduled)|alarm (is )?set|"
                   r"දැම්මා|දානවා|දාන්නම්|සෙට් කළා|සෙට් කරනවා|ඇහැරවනවා|ඇහැරවන්නම්|යවනවා|යවන්නම්|සකස් කළා|එකතු කළා|"
                   r"මතක් කරන්නම්|"
                   r"அமைத்துவிட்டேன்|அமைத்தேன்|அமைக்கிறேன்|சேர்த்துவிட்டேன்|சேர்த்தேன்|சேர்க்கிறேன்|அனுப்புகிறேன்|"
                   r"அனுப்பிவிடுகிறேன்|எழுப்புகிறேன்|நினைவூட்டுகிறேன்|வைத்துவிட்டேன்|"
                   r"set pannit|vechitt|dhaanna|damma|yawannam|ahrawannam", re.I)
DRAFT_KIND = {"set_reminder": "reminder", "calendar_add": "event", "risi_calendar_add": "event"}
QUESTION = re.compile(r"\?|කුමක්ද|මොකක්ද|මොනවද|කීයටද|කවද|என்ன|எதை|எப்போது|எத்தனை|எந்த", re.I)


# ------------------------------------------------------------------------------------- scoring

def score_item(it, rec):
    s = {"id": it["id"], "lang": it["lang"], "category": it["category"], "kind": it["kind"],
         "halluc": 0, "notes": []}
    if rec is None or rec.get("error"):
        s.update({"error": (rec or {}).get("error", "missing"), "parse_ok": False, "schema_ok": False})
        if it["kind"] == "turn" and any(x["tool"] != "final" for x in it["expect"]["any"]):
            s["tool_case"], s["tool_ok"] = True, False
        return s
    s["ttft_s"], s["total_s"] = rec.get("ttft_s"), rec.get("total_s")
    ct = rec.get("completion_tokens") or 0
    if ct > 1 and rec.get("total_s") and rec.get("ttft_s") is not None and rec["total_s"] > rec["ttft_s"]:
        s["tps"] = (ct - 1) / (rec["total_s"] - rec["ttft_s"])
    s["completion_tokens"] = ct
    try:
        out = json.loads(rec["text"])
        s["parse_ok"] = True
    except Exception:  # noqa: BLE001
        s.update({"parse_ok": False, "schema_ok": False})
        if it["kind"] == "turn" and any(x["tool"] != "final" for x in it["expect"]["any"]):
            s["tool_case"], s["tool_ok"] = True, False
        return s
    try:
        import jsonschema
        jsonschema.validate(out, schema_of(it["schema"]))
        s["schema_ok"] = True
    except ImportError:
        s["schema_ok"] = None
    except Exception as e:  # noqa: BLE001
        s["schema_ok"] = False
        s["notes"].append(f"schema: {str(e).splitlines()[0][:120]}")
    exp = it["expect"]
    given = set(it.get("given_refs") or [])
    text = ""
    if it["kind"] == "turn":
        tool = out.get("tool")
        s["tool"] = tool
        s["tool_case"] = any(x["tool"] != "final" for x in exp["any"])
        names = {x["tool"] for x in exp["any"]}
        s["tool_name_ok"] = tool in names
        ok = False
        for alt in exp["any"]:
            if alt["tool"] != tool:
                continue
            why = []
            for k, spec in (alt.get("args") or {}).items():
                good, w = check_value(spec, (out.get("args") or {}).get(k))
                if not good:
                    why.append(f"{k}: {w} (got {(out.get('args') or {}).get(k)!r})")
            if tool == "final":
                ans = out.get("answer") or ""
                a = alt.get("answer") or {}
                if "re" in a and not re.search(a["re"], ans, re.I):
                    why.append(f"answer lacks /{a['re'][:40]}/")
                if "not_re" in a and re.search(a["not_re"], ans, re.I):
                    why.append("answer claims something false")
                    s["halluc"] += 1
                if "maxlen" in a and len(ans) > a["maxlen"]:
                    why.append("answer too long")
                if alt.get("question") and not QUESTION.search(ans):
                    why.append("no question asked")
                for k, spec in (alt.get("draft") or {}).items():
                    good, w = check_value(spec, (out.get("draft") or {}).get(k))
                    if not good:
                        why.append(f"draft.{k}: {w}")
            if not why:
                ok = True
                break
            s["notes"] += why
        # §25.1: a write may also come as a `final` whose `draft` fills the action card.
        if not ok and tool == "final" and isinstance(out.get("draft"), dict):
            d = out["draft"]
            for alt in exp["any"]:
                kind = DRAFT_KIND.get(alt["tool"])
                if not kind or d.get("kind") != kind:
                    continue
                args = alt.get("args") or {}
                tspec = args.get("when") or args.get("start")
                when = " ".join(str(x) for x in (d.get("date"), d.get("time")) if x)
                good = (tspec is None or check_value(tspec, when)[0])
                if good and "title" in args:
                    good = check_value(args["title"], d.get("title"))[0]
                if good:
                    ok = True
                    s["via_draft"] = True
                    break
                s["notes"].append(f"draft doesn't match: {json.dumps(d, ensure_ascii=False)[:120]}")
        s["tool_ok"] = ok and bool(s.get("schema_ok") is not False)
        if s["tool_case"] and tool == "final" and not ok and CLAIM.search(out.get("answer") or ""):
            s["false_claim"] = True
            s["halluc"] += 1
            s["notes"].append("claims an action it didn't take")
        if tool == "final":
            text = out.get("answer") or ""
            bad = [r for r in out.get("sources") or [] if r not in given]
            if bad:
                s["halluc"] += 1
                s["notes"].append(f"invented sources {bad[:5]}")
            if any(f.search(text) for f in FORBIDDEN):
                s["forbidden_phrase"] = True
    elif it["kind"] == "summary":
        text = "\n".join([out.get("summary") or ""] + [str(x) for k in ("decisions", "action_items", "open_questions")
                                                       for x in out.get(k) or []])
        why = []
        for r in exp.get("must", []):
            if not re.search(r, text, re.I):
                why.append(f"missing /{r[:30]}/")
        for r in exp.get("must_not", []):
            if re.search(r, text, re.I):
                why.append(f"has /{r[:30]}/")
                s["halluc"] += 1
        for k in exp.get("empty", []):
            if out.get(k):
                why.append(f"{k} not empty")
                s["halluc"] += len(out[k])
        if len(out.get("action_items") or []) < exp.get("min_actions", 0):
            why.append("too few action items")
        if len(out.get("open_questions") or []) < exp.get("min_questions", 0):
            why.append("open question missed")
        s["task_ok"] = not why
        s["notes"] += why
    elif it["kind"] == "extract":
        got = out.get("commitments") or []
        want = exp["commitments"]
        used, matched = set(), 0
        for w in want:
            for i, g in enumerate(got):
                if i in used or g.get("owner") != w["owner"]:
                    continue
                due = g.get("due_local")
                if w["due"] is None or (isinstance(due, str) and (due == w["due"] or
                                                                 (len(w["due"]) == 10 and due.startswith(w["due"])))):
                    used.add(i)
                    matched += 1
                    break
        fp = len(got) - len(used)
        s["task_ok"] = matched == len(want) and fp == 0
        s["halluc"] += fp
        bad = [r for g in got for r in g.get("source") or [] if r not in given]
        s["halluc"] += 1 if bad else 0
        if not s["task_ok"]:
            s["notes"].append(f"commitments matched {matched}/{len(want)}, extra {fp}: "
                              + json.dumps(got, ensure_ascii=False)[:200])
        text = "\n".join(g.get("text") or "" for g in got)
    elif it["kind"] == "ask":
        text = out.get("answer") or ""
        why = []
        for r in exp.get("must", []):
            if not re.search(r, text, re.I):
                why.append(f"missing /{r[:30]}/")
        for r in exp.get("must_not", []):
            if re.search(r, text, re.I):
                why.append(f"has /{r[:30]}/")
                s["halluc"] += 1
        bad = [r for r in out.get("refs") or [] if r not in given]
        if bad:
            s["halluc"] += 1
            why.append(f"invented refs {bad[:5]}")
        if any(f.search(text) for f in FORBIDDEN):
            s["forbidden_phrase"] = True
            why.append("forbidden 'transcript does not contain' phrasing")
        s["task_ok"] = not why
        s["notes"] += why
    s["text"] = text
    want_script = it["script"]
    s["script"] = script_class(text, want_script) if (text and it["kind"] != "extract") else "n/a"
    if it["kind"] == "extract" and it["lang"] in ("si", "ta") and text:
        s["script"] = script_class(text, want_script)
    return s


def load_jsonl(p):
    return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]


def aggregate(scores):
    def pct(xs):
        xs = [x for x in xs if x is not None]
        return round(100 * sum(1 for x in xs if x) / len(xs), 1) if xs else None

    def med(xs):
        xs = sorted(x for x in xs if x is not None)
        return round(xs[len(xs) // 2], 3) if xs else None

    agg = {"n": len(scores), "errors": sum(1 for s in scores if s.get("error")),
           "json_valid_pct": pct([s.get("parse_ok") and s.get("schema_ok") is not False for s in scores]),
           "tool_call_pct": pct([s.get("tool_ok") for s in scores if s.get("tool_case")]),
           "turn_ok_pct": pct([s.get("tool_ok") for s in scores if s["kind"] == "turn"]),
           "task_ok_pct": pct([s.get("task_ok") for s in scores if s["kind"] != "turn"]),
           "halluc": sum(s.get("halluc", 0) for s in scores),
           "forbidden_phrase": sum(1 for s in scores if s.get("forbidden_phrase")),
           "false_claims": sum(1 for s in scores if s.get("false_claim")),
           "ttft_med_s": med([s.get("ttft_s") for s in scores]),
           "tps_med": med([s.get("tps") for s in scores]),
           "total_med_s": med([s.get("total_s") for s in scores])}
    by = {}
    for lang in ["si", "ta", "en", "mixed"]:
        ss = [s for s in scores if s["lang"] == lang]
        if not ss:
            continue
        sc = [s["script"] for s in ss if s.get("script") not in (None, "n/a")]
        by[lang] = {"n": len(ss),
                    "tool_call_pct": pct([s.get("tool_ok") for s in ss if s.get("tool_case")]),
                    "turn_ok_pct": pct([s.get("tool_ok") for s in ss if s["kind"] == "turn"]),
                    "task_ok_pct": pct([s.get("task_ok") for s in ss if s["kind"] != "turn"]),
                    "halluc": sum(s.get("halluc", 0) for s in ss),
                    "script_ok_pct": pct([x == "ok" for x in sc]),
                    "script_bad": {k: sc.count(k) for k in ("wrong_script", "romanised", "mixed_script") if sc.count(k)}}
    agg["by_lang"] = by
    cats = {}
    for s in scores:
        c = cats.setdefault(s["category"], [])
        c.append(s.get("tool_ok") if s["kind"] == "turn" else s.get("task_ok"))
    agg["by_category"] = {k: pct(v) for k, v in sorted(cats.items())}
    return agg


def cmd_score(a):
    items = {i["id"]: i for i in load_items()}
    for label in a.labels:
        recs = {r["id"]: r for r in load_jsonl(os.path.join(RES, f"{label}.jsonl"))}
        scores = [score_item(it, recs.get(iid)) for iid, it in items.items() if iid in recs or not a.partial]
        agg = aggregate(scores)
        meta_p = os.path.join(RES, f"{label}.meta.json")
        meta = json.load(open(meta_p)) if os.path.exists(meta_p) else {}
        json.dump({"label": label, "meta": meta, "summary": agg, "items": scores},
                  open(os.path.join(RES, f"{label}.score.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
        print(f"== {label}")
        print(json.dumps({k: v for k, v in agg.items() if k != "by_category"}, ensure_ascii=False))
        print("   by category:", json.dumps(agg["by_category"]))


# ------------------------------------------------------------------------------ blind A/B pairs

def brief(it):
    """What the judge sees of the request: the question/chat, never the system prompt."""
    u = it["messages"][1]["content"]
    tail = [m["content"] for m in it["messages"][2:]]
    return u + ("\n\n[earlier steps]\n" + "\n".join(tail) if tail else "")


def cmd_pairs(a):
    items = {i["id"]: i for i in load_items()}
    A = {r["id"]: r for r in load_jsonl(os.path.join(RES, f"{a.base}.jsonl"))}
    B = {r["id"]: r for r in load_jsonl(os.path.join(RES, f"{a.cand}.jsonl"))}
    rnd = random.Random(a.seed)
    os.makedirs(os.path.join(RES, "pairs"), exist_ok=True)
    stem = os.path.join(RES, "pairs", f"{a.base}__{a.cand}")
    key = {}
    with open(stem + ".jsonl", "w", encoding="utf-8") as fh:
        for iid, it in items.items():
            if iid not in A or iid not in B:
                continue
            flip = rnd.random() < 0.5
            x, y = (B[iid], A[iid]) if flip else (A[iid], B[iid])
            key[iid] = {"A": a.cand if flip else a.base, "B": a.base if flip else a.cand}
            fh.write(json.dumps({"id": iid, "lang": it["lang"], "category": it["category"],
                                 "note": it.get("note"), "expect": it["expect"], "request": brief(it),
                                 "A": x.get("text") or x.get("error"), "B": y.get("text") or y.get("error")},
                                ensure_ascii=False) + "\n")
    json.dump(key, open(stem + ".key.json", "w"), indent=0)
    print(f"wrote {stem}.jsonl ({len(key)} pairs) and the key (keep it away from the judge)")


def cmd_unblind(a):
    stem = os.path.join(RES, "pairs", f"{a.base}__{a.cand}")
    key = json.load(open(stem + ".key.json"))
    v = load_jsonl(stem + ".verdicts.jsonl")
    res = {"cand_wins": 0, "base_wins": 0, "ties": 0, "cand_score": 0, "base_score": 0,
           "cand_halluc": 0, "base_halluc": 0, "n": 0}
    by_lang, by_kind = {}, {}
    items = {i["id"]: i for i in load_items()}
    for x in v:
        k = key[x["id"]]
        cand_side = "A" if k["A"] == a.cand else "B"
        base_side = "B" if cand_side == "A" else "A"
        lang = items[x["id"]]["lang"]
        bl = by_lang.setdefault(lang, {"cand_wins": 0, "base_wins": 0, "ties": 0})
        kind = "turn" if items[x["id"]]["kind"] == "turn" else "summary_extract_ask"
        bk = by_kind.setdefault(kind, {"cand_wins": 0, "base_wins": 0, "ties": 0})
        w = x["winner"]
        tag = "ties" if w == "tie" else ("cand_wins" if w == cand_side else "base_wins")
        res[tag] += 1
        bl[tag] += 1
        bk[tag] += 1
        res["cand_score"] += x[f"{cand_side.lower()}_score"]
        res["base_score"] += x[f"{base_side.lower()}_score"]
        res["cand_halluc"] += int(bool(x.get(f"{cand_side.lower()}_halluc")))
        res["base_halluc"] += int(bool(x.get(f"{base_side.lower()}_halluc")))
        res["n"] += 1
    if res["n"]:
        res["cand_score"] = round(res["cand_score"] / res["n"], 2)
        res["base_score"] = round(res["base_score"] / res["n"], 2)
    res["by_lang"] = by_lang
    res["by_kind"] = by_kind
    json.dump(res, open(stem + ".result.json", "w"), indent=1)
    print(json.dumps(res))


# ------------------------------------------------------------------------------------------ ASR

def norm_text(s):
    s = unicodedata.normalize("NFC", s).lower().replace("‍", "").replace("‌", "")
    s = "".join(c if (c.isalnum() or c.isspace() or unicodedata.category(c).startswith("M")) else " " for c in s)
    return " ".join(s.split())


def edit(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def transcribe(url, key, model, path, language):
    b = uuid.uuid4().hex
    fields = {"model": model, "response_format": "json", "temperature": "0"}
    if language:
        fields["language"] = language
    body = b""
    for k, v in fields.items():
        body += f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    ext = os.path.splitext(path)[1].lstrip(".") or "wav"
    body += (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.{ext}\"\r\n"
             f"Content-Type: audio/{ext}\r\n\r\n").encode() + open(path, "rb").read() + f"\r\n--{b}--\r\n".encode()
    h = {"Content-Type": f"multipart/form-data; boundary={b}"}
    if key:
        h["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url.rstrip("/") + "/v1/audio/transcriptions", data=body, headers=h, method="POST")
    t0 = time.monotonic()
    with urllib.request.urlopen(req, timeout=300) as r:
        out = json.load(r)
    return out.get("text", ""), time.monotonic() - t0


def cmd_asr(a):
    key = env_key(a.key_env)
    man = load_jsonl(a.manifest)
    if a.langs:
        man = [m for m in man if m["lang"] in a.langs.split(",")]
    os.makedirs(RES, exist_ok=True)
    out_p = os.path.join(RES, f"asr-{a.label}.jsonl")
    tot = {}
    with open(out_p, "w", encoding="utf-8") as fh:
        for i, m in enumerate(man, 1):
            pilot_gate()
            try:
                hyp, sec = transcribe(a.url, key, a.model, m["audio"], None if a.auto else m["lang"])
            except Exception as e:  # noqa: BLE001
                hyp, sec = f"<error {e}>", 0
            ref_n, hyp_n = norm_text(m["text"]), norm_text(hyp)
            we = edit(ref_n.split(), hyp_n.split())
            ce = edit(ref_n.replace(" ", ""), hyp_n.replace(" ", ""))
            t = tot.setdefault(m["lang"], {"w_err": 0, "w": 0, "c_err": 0, "c": 0, "n": 0, "sec": 0.0,
                                           "audio_s": 0.0, "script": {}})
            t["w_err"] += we
            t["w"] += len(ref_n.split())
            t["c_err"] += ce
            t["c"] += len(ref_n.replace(" ", ""))
            t["n"] += 1
            t["sec"] += sec
            t["audio_s"] += m.get("duration_s", 0) or 0
            sc = script_class(hyp, m["lang"])
            t["script"][sc] = t["script"].get(sc, 0) + 1
            fh.write(json.dumps({"id": m["id"], "lang": m["lang"], "ref": m["text"], "hyp": hyp,
                                 "wer": round(we / max(1, len(ref_n.split())), 3), "sec": round(sec, 2)},
                                ensure_ascii=False) + "\n")
            print(f"[{i}/{len(man)}] {m['id']} WER {we}/{len(ref_n.split())} {sec:.1f}s", flush=True)
    summ = {lang: {"n": t["n"], "wer": round(t["w_err"] / max(1, t["w"]), 3),
                   "cer": round(t["c_err"] / max(1, t["c"]), 3),
                   "rtf": round(t["sec"] / t["audio_s"], 3) if t["audio_s"] else None,
                   "script": t["script"]} for lang, t in tot.items()}
    json.dump({"label": a.label, "model": a.model, "manifest": a.manifest, "summary": summ},
              open(os.path.join(RES, f"asr-{a.label}.score.json"), "w"), indent=1)
    print(json.dumps(summ, ensure_ascii=False))


# ------------------------------------------------------------------------------- probe & report

def cmd_probe(a):
    """Read-only: live risi-l1 health + one 1-token completion, and the pilot's /health."""
    key = env_key("LLM_API_KEY")
    ok = True
    try:
        with urllib.request.urlopen("http://127.0.0.1:8100/health", timeout=5) as r:
            h = r.status
    except Exception as e:  # noqa: BLE001
        h = f"ERR {e}"
    t0 = time.monotonic()
    try:
        body = {"model": "risi-l1", "messages": [{"role": "user", "content": "Reply with: ok"}],
                "max_tokens": 3, "temperature": 0, "chat_template_kwargs": {"enable_thinking": False}}
        req = urllib.request.Request("http://127.0.0.1:8100/v1/chat/completions", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        with urllib.request.urlopen(req, timeout=30) as r:
            c = json.load(r)["choices"][0]["message"]["content"]
        comp = f"ok ({time.monotonic() - t0:.2f}s, {c.strip()[:10]!r})"
    except Exception as e:  # noqa: BLE001
        comp, ok = f"ERR {e}", False
    try:
        with urllib.request.urlopen("http://127.0.0.1:4000/health", timeout=5) as r:
            p = r.status
    except Exception as e:  # noqa: BLE001
        p = f"ERR {e}"
    ok = ok and h == 200 and p == 200
    print(f"probe: risi-l1 /health {h}, completion {comp}, pilot /health {p} -> {'OK' if ok else 'FAIL'}")
    sys.exit(0 if ok else 1)


def cmd_report(a):
    """results/REPORT.md: the LLM table (production schema and tool-first side by side, blind
    A/B vs risi-l1) and the ASR table."""
    def load(name):
        p = os.path.join(RES, name)
        return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None

    labels = sorted(f[:-len(".score.json")] for f in os.listdir(RES)
                    if f.endswith(".score.json") and not f.startswith("asr-") and ".toolfirst" not in f)
    labels.sort(key=lambda x: (x != "risi-l1", x))

    def pair(base, cand):
        return load(os.path.join("pairs", f"{base}__{cand}.result.json"))

    def pct(v):
        return "-" if v is None else f"{v:.0f}%"

    L = ["| model | peak memory GB | tok/s | TTFT s | JSON valid | tool calls ok (as served / tool first) | "
         "false action claims (as served / tool first) | summary+extract+ask ok | other hallucinations | "
         "si / ta script ok | blind A/B vs risi-l1, all 140 (W-T-L, judge 1-5) | blind A/B tool-first turns (W-T-L) |",
         "|" + "---|" * 12]
    for lab in labels:
        d = load(f"{lab}.score.json")
        t = load(f"{lab}.toolfirst.score.json")
        s, m = d["summary"], d.get("meta", {})
        ts = t["summary"] if t else {}
        bl = s.get("by_lang", {})
        p = pair("risi-l1", lab)
        pt = pair("risi-l1.toolfirst", f"{lab}.toolfirst")
        ab = "baseline" if lab == "risi-l1" else (
            f"{p['cand_wins']}-{p['ties']}-{p['base_wins']} ({p['cand_score']} vs {p['base_score']})" if p else "not judged")
        abt = "baseline" if lab == "risi-l1" else (
            f"{pt['cand_wins']}-{pt['ties']}-{pt['base_wins']} ({pt['cand_score']} vs {pt['base_score']})" if pt else "not judged")
        other = s["halluc"] - s.get("false_claims", 0)
        errs = f" ({s['errors']} errors)" if s.get("errors") else ""
        L.append(f"| {m.get('name', lab)}{errs} | {m.get('peak_mem_gb', '-')} | {s['tps_med'] and round(s['tps_med'], 1)} | "
                 f"{s['ttft_med_s']} | {pct(s['json_valid_pct'])} | {pct(s['tool_call_pct'])} / {pct(ts.get('tool_call_pct'))} | "
                 f"{s.get('false_claims', 0)} / {ts.get('false_claims', '-')} | {pct(s['task_ok_pct'])} | {other} | "
                 f"{pct(bl.get('si', {}).get('script_ok_pct'))} / {pct(bl.get('ta', {}).get('script_ok_pct'))} | {ab} | {abt} |")
    A = ["| ASR model | Sinhala WER / CER | Tamil WER / CER | real-time factor (si / ta) | peak memory GB |",
         "|---|---|---|---|---|"]
    for f in sorted(os.listdir(RES)):
        if f.startswith("asr-") and f.endswith(".score.json"):
            d = json.load(open(os.path.join(RES, f)))
            sm = d["summary"]
            mem_p = os.path.join(RES, f.replace(".score.json", ".mem.txt"))
            mem = open(mem_p).read().split()[1] if os.path.exists(mem_p) else "resident 6.9 (nvidia-smi)"

            def wc(lang):
                x = sm.get(lang)
                return f"{x['wer']:.1%} / {x['cer']:.1%}" if x else "-"

            rtf = " / ".join(str(sm.get(l, {}).get("rtf", "-")) for l in ("si", "ta"))
            A.append(f"| {d['label']} | {wc('si')} | {wc('ta')} | {rtf} | {mem} |")
    out = "\n".join(L) + "\n\n" + "\n".join(A) + "\n"
    open(os.path.join(RES, "REPORT.md"), "w").write(out)
    print(out)


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    r = sp.add_parser("run")
    r.add_argument("--url", required=True)
    r.add_argument("--model", required=True)
    r.add_argument("--label", required=True)
    r.add_argument("--key-env", default=None)
    r.add_argument("--files", default=",".join(FILES))
    r.add_argument("--only", default=None)
    r.add_argument("--limit", type=int, default=0)
    r.add_argument("--sleep", type=float, default=0.2)
    r.add_argument("--resume", action="store_true")
    r.add_argument("--no-thinking-kwarg", action="store_true")
    r.add_argument("--extra-body", default=None)
    r.add_argument("--tool-first", action="store_true", help="tool-first property order (turn items)")
    s = sp.add_parser("score")
    s.add_argument("labels", nargs="+")
    s.add_argument("--partial", action="store_true")
    p = sp.add_parser("pairs")
    p.add_argument("base")
    p.add_argument("cand")
    p.add_argument("--seed", type=int, default=7)
    u = sp.add_parser("unblind")
    u.add_argument("base")
    u.add_argument("cand")
    sp.add_parser("report")
    sp.add_parser("probe")
    s2 = sp.add_parser("asr")
    s2.add_argument("--url", required=True)
    s2.add_argument("--model", required=True)
    s2.add_argument("--label", required=True)
    s2.add_argument("--manifest", required=True)
    s2.add_argument("--key-env", default=None)
    s2.add_argument("--langs", default=None)
    s2.add_argument("--auto", action="store_true", help="no language hint (auto-detect)")
    a = ap.parse_args()
    {"run": cmd_run, "score": cmd_score, "pairs": cmd_pairs, "unblind": cmd_unblind, "report": cmd_report,
     "probe": cmd_probe, "asr": cmd_asr}[a.cmd](a)


if __name__ == "__main__":
    main()
