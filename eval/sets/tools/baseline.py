"""One run of eval/sets/ against an OpenAI-compatible endpoint (risi-l1 on its loopback API),
with today's server prompts and schemas. No commercial LLM: the URL must be a loopback address.

  python3 -I eval/sets/tools/baseline.py --url http://127.0.0.1:8100 --model risi-l1 \
      --key-env LLM_API_KEY --prompts P.json --out DIR [--caps ledger,summary,actions] [--langs si,ta]
      [--only REGEX] [--limit N] [--resume]

P.json: today's prompts and wire schemas, dumped from the server source (keys extract_system,
extract_schema, summary_system, summary_schema, action_system_call, action_system_voice_note,
action_schema). One way (no app start, nothing compiled in the repo):

  # dump.exs: Code.require_file(prompts.ex); alias RisiMe.Agent.Prompts, as: P;
  #   w = &RisiMe.Agent.Tools.wire_schema/1; File.write!(out, Jason.encode!(%{extract_system: ...}))
  ERL_LIBS=<a server _build/dev/lib> mise exec -- elixir dump.exs server/lib/risime/agent/prompts.ex P.json

Rendering is a port of RisiMe.Agent.Prompts.render/4 (members and chat lines as JSON objects, the
harness clock Sun 2026-10-11 10:00 Asia/Colombo, one minute per line); the request is
RisiMe.Agent.LLM's (temperature 0.2, top_p 0.8, thinking off, json_schema strict). Scoring is a
port of RisiMe.Agents.Eval (ledger_case, summary_case, invented/2) plus the actions rules in
eval/sets/README.md. Writes DIR/<cap>.raw.jsonl (model outputs, resumable) and DIR/<cap>.score.json.
Sequential, one request at a time, pausing while the pilot's /health is slow (Harsha's rule).
"""

import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
LANGS = ["si", "ta", "singlish", "en"]
NOW_LOCAL = dt.datetime(2026, 10, 11, 10, 0)  # = Eval @now 04:30Z in Asia/Colombo
TZ = "Asia/Colombo"
MIN_CONF = 0.6  # :risi_min_confidence default (action items)
_last = [0.0]


def pilot_gate(every=30, limit=1.0):
    if time.monotonic() - _last[0] < every:
        return
    while True:
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen("http://127.0.0.1:4000/health", timeout=5) as r:
                ok = r.status == 200
        except Exception:  # noqa: BLE001
            ok = False
        took = time.monotonic() - t0
        _last[0] = time.monotonic()
        if ok and took <= limit:
            return
        print(f"pilot /health {'slow' if ok else 'down'} ({took:.2f}s): pausing {every}s", flush=True)
        time.sleep(every)


def jenc(obj):
    """Jason.encode!(map, escape: :html_safe) for a small map: keys sorted, no spaces."""
    s = json.dumps(dict(sorted(obj.items())), ensure_ascii=False, separators=(",", ":"))
    for a, b in (("/", "\\/"), ("<", "\\u003C"), (" ", "\\u2028"), (" ", "\\u2029")):
        s = s.replace(a, b)
    return s


def render(members, rows, with_new):
    k = len(rows)
    mlines = [jenc({"ref": f"u{i}", "name": n, "tz": TZ, "now": NOW_LOCAL.strftime("%Y-%m-%d %a %H:%M")})
              for i, n in enumerate(members, 1)]
    refs = {n: f"u{i}" for i, n in enumerate(members, 1)}
    lines = []
    for i, r in enumerate(rows):
        t = NOW_LOCAL + dt.timedelta(minutes=i - k)
        line = {"ref": f"m{i + 1}", "from": refs.get(r[0], "other"), "time": t.strftime("%a %H:%M"),
                "text": r[1][:2000]}
        if with_new:
            line["new"] = r[2]
        lines.append(jenc(line))
    text = ("Members (one JSON object per line):\n" + "\n".join(mlines) + "\n\n<chat>\n" +
            "\n".join(lines) + "\n</chat>")
    names = {f"u{i}": n for i, n in enumerate(members, 1)}
    return text, names


## Eval.invented/2

WEEKDAYS = "monday tuesday wednesday thursday friday saturday sunday".split()
MONTHS = ("january february march april may june july august september october november "
          "december").split()
STOP = set("""the a an i we you he she they it this that yes no ok okay risi summary who what when
where why how and or but not none nothing include book send update prepare write transfer pay
check meet call""".split())
WORD = re.compile(r"[^\W\d_]+")
NUM = re.compile(r"\d[\d,]*")
NAME = re.compile(r"(?<![.!?:;\n]\s)(?<![.!?:;\n])(?<=\s)[A-ZÀ-ÖØ-Þ][a-zß-öø-ÿ]+")


def invented(source, output):
    src = source.lower()
    src_digits = [m.replace(",", "") for m in NUM.findall(source)]
    src_words = set(WORD.findall(src))
    numbers = [n for n in (m.replace(",", "") for m in NUM.findall(output)) if n not in src_digits]
    dates = [w for w in WORD.findall(output.lower())
             if (w in WEEKDAYS or w in MONTHS) and w not in src_words]
    names = [w for w in NAME.findall(output)
             if not (w.lower() in STOP or w.lower() in WEEKDAYS or w.lower() in MONTHS
                     or w.lower() in src_words)]
    out = []
    for x in numbers + dates + names:
        if x not in out:
            out.append(x)
    return out


## The model call

def chat(url, key, model, system, user, schema_name, schema, max_tokens, timeout=180):
    body = {"model": model, "messages": [{"role": "system", "content": system},
                                         {"role": "user", "content": user}],
            "temperature": 0.2, "top_p": 0.8, "max_tokens": max_tokens,
            "chat_template_kwargs": {"enable_thinking": False},
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": schema_name, "schema": schema, "strict": True}}}
    h = {"Content-Type": "application/json"}
    if key:
        h["Authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url.rstrip("/") + "/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers=h, method="POST")
    t0 = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        resp = json.loads(r.read())
    ms = int((time.monotonic() - t0) * 1000)
    return json.loads(resp["choices"][0]["message"]["content"]), ms


def source_of(c):
    rows = c.get("messages") or c.get("lines")
    return "\n".join(r[1] for r in rows)


def ask(cap, c, P, a, key):
    if cap == "ledger":
        text, _ = render(c["members"], c["messages"], True)
        return chat(a.url, key, a.model, P["extract_system"], text, "commitments", P["extract_schema"], 800)
    if cap == "summary":
        text, _ = render(c["members"], c["messages"], True)
        return chat(a.url, key, a.model, P["summary_system"], text, "summary", P["summary_schema"], 800)
    text, _ = render(c["members"], c["lines"], False)
    system = P["action_system_call"] if c["source"] == "call" else P["action_system_voice_note"]
    return chat(a.url, key, a.model, system, text, "action_items", P["action_schema"], 2000)


## Scoring

def kw_hit(e, text):
    t = text.lower()
    return any(k.lower() in t for k in e["kw"])


def match(expect, got, same_kind):
    left, n, missed = list(got), 0, []
    for e in expect:
        # A meeting is matched on its words and day only (who "owns" a meeting is ambiguous).
        meet = e.get("kind") == "meeting"
        g = next((g for g in left if same_kind(e, g)
                  and (meet or g["owner"] == e.get("owner"))
                  and (meet or sorted(x or "?" for x in g["to"]) == sorted(e["to"]))
                  and g["due"] == e["due"] and kw_hit(e, g["text"])), None)
        if g is None:
            missed.append(e)
        else:
            n += 1
            left.remove(g)
    return n, left, missed


def item_of(i, names):
    return {"owner": names.get(i.get("owner")) if i.get("owner") else None,
            "owner_ref": i.get("owner"),
            "to": [names.get(x) for x in i.get("counterparts") or []],
            "due": (i.get("due_local") or None) and i["due_local"][:10],
            "text": i.get("text") or "", "kind": i.get("kind")}


def score_ledger(c, out):
    _, names = render(c["members"], c["messages"], True)
    got = [item_of(i, names) for i in out.get("commitments", [])]
    src = source_of(c) + "\n" + " ".join(c["members"])
    no_due = all(e["due"] is None for e in c["expect"])
    inv, halluc = [], 0
    for g in got:
        bad_inv = invented(src, g["text"])
        inv += bad_inv
        if (g["owner"] is None or None in g["to"] or (no_due and g["due"] and c["expect"]) or bad_inv):
            halluc += 1
    n, extra, _ = match(c["expect"], got, lambda e, g: True)
    ok = n == len(c["expect"]) and len(got) == len(c["expect"]) and halluc == 0
    why = [] if ok else ([f"missed {len(c['expect']) - n}"] if n < len(c["expect"]) else []) + \
        ([f"extra {len(extra)}"] if extra else []) + ([f"halluc {halluc}"] if halluc else [])
    return {"ok": ok, "halluc": halluc, "invented": inv, "forbid": [], "n_got": len(got),
            "false_pos": len(extra) if not c["expect"] else 0, "why": why}


def score_summary(c, out):
    parts = [out.get("summary") or ""] + (out.get("decisions") or []) + (out.get("action_items") or []) + \
        (out.get("open_questions") or [])
    allt = "\n".join(parts)
    low = allt.lower()
    missing = [g for g in c.get("must", []) if not any(s.lower() in low for s in g)]
    forbid = [f for f in c.get("forbid", []) if f.lower() in low]
    inv = invented(source_of(c) + "\n" + " ".join(c["members"]), allt)
    halluc = len(inv) + (1 if forbid else 0)
    ok = not missing and halluc == 0
    why = ([f"missing {missing}"] if missing else []) + ([f"forbid {forbid}"] if forbid else []) + \
        ([f"invented {inv}"] if inv else [])
    return {"ok": ok, "halluc": halluc, "invented": inv, "forbid": forbid, "missing": len(missing), "why": why}


ACT = {"promise", "task", "meeting"}


def score_actions(c, out):
    _, names = render(c["members"], c["lines"], False)
    items = [i for i in out.get("items", [])
             if (i.get("text") or "").strip() and (i.get("confidence") or 0) >= MIN_CONF]
    got = [item_of(i, names) for i in items]
    src = source_of(c) + "\n" + " ".join(c["members"])
    no_due = all(e["due"] is None for e in c["expect"])
    texts = [g["text"] for g in got] + ([out["summary"]] if out.get("summary") else [])
    inv = invented(src, "\n".join(texts))
    forbid = [f for f in c.get("forbid", []) if f.lower() in "\n".join(texts).lower()]
    halluc = len(inv) + len(forbid)
    for g in got:
        if (g["owner_ref"] and g["owner"] is None) or None in g["to"] or \
                (g["kind"] in ACT and no_due and g["due"] and c["expect"]):
            halluc += 1
    act = [g for g in got if g["kind"] in ACT]
    def same_kind(e, g):
        return e["kind"] == g["kind"] or {e["kind"], g["kind"]} <= {"promise", "task"}
    n, extra, _ = match(c["expect"], act, same_kind)
    extra_owned = [g for g in extra if g["owner"] and g["kind"] != "meeting"]
    ok = n == len(c["expect"]) and not extra_owned and halluc == 0
    why = ([f"missed {len(c['expect']) - n}"] if n < len(c["expect"]) else []) + \
        ([f"extra owned {len(extra_owned)}"] if extra_owned else []) + \
        ([f"invented {inv}"] if inv else []) + ([f"forbid {forbid}"] if forbid else []) + \
        ([f"halluc {halluc}"] if halluc and not inv and not forbid else [])
    return {"ok": ok, "halluc": halluc, "invented": inv, "forbid": forbid, "n_got": len(act),
            "false_pos": len(extra_owned) if not c["expect"] else 0, "why": why}


SCORE = {"ledger": score_ledger, "summary": score_summary, "actions": score_actions}


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p * (len(xs) - 1))))] if xs else None


def env_key(name):
    if not name:
        return None
    if os.environ.get(name):
        return os.environ[name]
    try:
        for line in open(os.path.join(ROOT, "..", "..", ".env"), encoding="utf-8"):
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--key-env", default=None)
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--caps", default="ledger,summary,actions")
    ap.add_argument("--langs", default=",".join(LANGS))
    ap.add_argument("--only", default=None)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--sleep", type=float, default=0.3)
    a = ap.parse_args()
    host = re.sub(r"^https?://", "", a.url).split("/")[0].split(":")[0]
    if host not in ("127.0.0.1", "localhost", "::1"):
        sys.exit("loopback endpoints only (no commercial LLM from this tool)")
    P = json.load(open(a.prompts, encoding="utf-8"))
    key = env_key(a.key_env)
    os.makedirs(a.out, exist_ok=True)
    report = {}
    for cap in a.caps.split(","):
        cases = []
        for lang in a.langs.split(","):
            p = os.path.join(ROOT, cap, lang + ".jsonl")
            cases += [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]
        if a.only:
            cases = [c for c in cases if re.search(a.only, c["id"])]
        if a.limit:
            cases = cases[: a.limit]
        raw_p = os.path.join(a.out, f"{cap}.raw.jsonl")
        raw = {}
        if a.resume and os.path.exists(raw_p):
            raw = {r["id"]: r for r in map(json.loads, open(raw_p, encoding="utf-8")) if "error" not in r}
        with open(raw_p, "w", encoding="utf-8") as fh:
            for r in raw.values():
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
            for n, c in enumerate(cases, 1):
                if c["id"] in raw:
                    continue
                pilot_gate()
                rec = {"id": c["id"]}
                try:
                    out, ms = ask(cap, c, P, a, key)
                    rec.update(out=out, ms=ms)
                except urllib.error.HTTPError as e:
                    rec["error"] = f"http {e.code}: {e.read()[:200].decode('utf-8', 'replace')}"
                except Exception as e:  # noqa: BLE001 - recorded, run continues
                    rec["error"] = f"{type(e).__name__}: {e}"
                raw[c["id"]] = rec
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
                fh.flush()
                print(f"[{cap} {n}/{len(cases)}] {c['id']}: {rec.get('error') or str(rec['ms']) + ' ms'}", flush=True)
                time.sleep(a.sleep)
        results = []
        for c in cases:
            r = raw.get(c["id"])
            if not r or "error" in r:
                s = {"ok": False, "halluc": 0, "invented": [], "forbid": [], "why": ["error"], "error": True}
            else:
                s = SCORE[cap](c, r["out"])
                s["ms"] = r["ms"]
            neg = cap != "summary" and not c["expect"]
            results.append({"id": c["id"], "lang": c["lang"], "neg": neg, "tags": c["tags"], **s})
        n = len(results)
        lat = [r["ms"] for r in results if "ms" in r]
        by_lang = {}
        for r in results:
            b = by_lang.setdefault(r["lang"], {"n": 0, "ok": 0, "halluc": 0})
            b["n"] += 1
            b["ok"] += r["ok"]
            b["halluc"] += r["halluc"]
        pos = [r for r in results if not r["neg"]]
        neg = [r for r in results if r["neg"]]
        rep = {"capability": cap, "model": a.model, "mode": "live", "checked": "unchecked set (decision 085)",
               "n": n, "ok": sum(r["ok"] for r in results),
               "accuracy": round(sum(r["ok"] for r in results) / n, 3) if n else None,
               "halluc": sum(r["halluc"] for r in results),
               "halluc_cases": sum(1 for r in results if r["halluc"]),
               "invented_tokens": sum(len(r["invented"]) for r in results),
               "forbid_hits": sum(len(r["forbid"]) for r in results),
               "positives": {"n": len(pos), "ok": sum(r["ok"] for r in pos)},
               "negatives": {"n": len(neg), "ok": sum(r["ok"] for r in neg),
                             "false_positive_cases": sum(1 for r in neg if not r["ok"])},
               "errors": sum(1 for r in results if r.get("error")),
               "p50_ms": pct(lat, 0.5), "p95_ms": pct(lat, 0.95), "by_lang": by_lang,
               "failed": {r["id"]: r["why"] for r in results if not r["ok"]}}
        json.dump(rep, open(os.path.join(a.out, f"{cap}.score.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
        report[cap] = rep
        print(f"{cap}: {rep['ok']}/{n} ok ({rep['accuracy']}), made-up items {rep['halluc']} in "
              f"{rep['halluc_cases']} cases, negatives {rep['negatives']['ok']}/{rep['negatives']['n']}, "
              f"p50 {rep['p50_ms']} ms, by lang {by_lang}", flush=True)
    return report


if __name__ == "__main__":
    main()
