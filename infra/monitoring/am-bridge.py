#!/usr/bin/env python3
"""Alertmanager webhook -> RisiMe ops alert bridge (decision 075, contract §32).

Listens on 127.0.0.1:9099. For each alert in an Alertmanager webhook payload it POSTs
  {"state": "alert"|"resolved", "check": <alertname, lower snake, <=40>, "detail": <summary, <=300>}
to http://127.0.0.1:4000/internal/ops-alert with "Authorization: Bearer $OPS_ALERT_TOKEN".
When the answer has sent < 1 or held > 0 (or the call fails), it falls back to e-mail (SMTP_* +
OPS_ALERT_EMAIL, STARTTLS, or implicit TLS on port 465), like scripts/watchdog.

Run with:  python3 -I infra/monitoring/am-bridge.py     (stdlib only)
Secrets are read from the repo .env only; they are never logged and never on a command line.
Test hooks (env): AMB_PORT, AMB_ENV_FILE, AMB_TARGET, AMB_DRY_MAIL=1 (log instead of sending mail).
"""
import json
import os
import re
import smtplib
import ssl
import sys
import time
import urllib.error
import urllib.request
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ENV_FILE = os.environ.get("AMB_ENV_FILE", os.path.join(HERE, "..", "..", ".env"))
TARGET = os.environ.get("AMB_TARGET", "http://127.0.0.1:4000/internal/ops-alert")
PORT = int(os.environ.get("AMB_PORT", "9099"))


def log(msg):
    sys.stderr.write("%s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), msg))
    sys.stderr.flush()


def read_env(path=None):
    out = {}
    try:
        with open(path or ENV_FILE, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                v = v.strip()
                if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                    v = v[1:-1]
                out[k.strip()] = v
    except OSError:
        pass
    return out


def check_name(name):
    """CamelCase / any alertname -> lower snake [a-z0-9_]{1,40}."""
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", name or "")
    s = re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")
    return (s or "alert")[:40]


def map_alert(alert):
    labels = alert.get("labels") or {}
    ann = alert.get("annotations") or {}
    detail = ann.get("summary") or ann.get("description") or labels.get("alertname", "alert")
    detail = " ".join(str(detail).split())[:300]
    return {
        "state": "resolved" if alert.get("status") == "resolved" else "alert",
        "check": check_name(labels.get("alertname", "")),
        "detail": detail,
    }


def post_ops_alert(body, token):
    """Returns (sent, held) or None when the call failed."""
    req = urllib.request.Request(
        TARGET,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read() or b"{}")
        return int(data.get("sent", 0)), int(data.get("held", 0))
    except (urllib.error.URLError, OSError, ValueError) as e:
        log("ops-alert call failed: %s %s" % (type(e).__name__, getattr(e, "code", "")))
        return None


def send_mail(env, body):
    host, to, frm = env.get("SMTP_HOST"), env.get("OPS_ALERT_EMAIL"), env.get("SMTP_FROM")
    if not (host and to and frm):
        log("no e-mail fallback (SMTP_HOST / SMTP_FROM / OPS_ALERT_EMAIL not set)")
        return False
    msg = EmailMessage()
    msg["From"], msg["To"] = frm, to
    msg["Subject"] = "RisiMe monitoring: %s (%s)" % (body["state"], body["check"])
    msg.set_content("RisiMe monitoring on spark2, %s UTC: %s (%s). %s" % (
        time.strftime("%F %H:%M", time.gmtime()), body["state"], body["check"], body["detail"]))
    if os.environ.get("AMB_DRY_MAIL") == "1":
        log("MAIL(dry) to=%s subject=%s" % (to, msg["Subject"]))
        return True
    port = int(env.get("SMTP_PORT") or 587)
    try:
        ctx = ssl.create_default_context()
        if port == 465:
            s = smtplib.SMTP_SSL(host, port, timeout=20, context=ctx)
        else:
            s = smtplib.SMTP(host, port, timeout=20)
            s.starttls(context=ctx)
        with s:
            if env.get("SMTP_USERNAME"):
                s.login(env["SMTP_USERNAME"], env.get("SMTP_PASSWORD", ""))
            s.send_message(msg)
        log("e-mail sent (%s)" % body["check"])
        return True
    except (smtplib.SMTPException, OSError) as e:
        log("e-mail FAILED: %s" % type(e).__name__)
        return False


def forward(body):
    env = read_env()
    token = env.get("OPS_ALERT_TOKEN", "")
    res = post_ops_alert(body, token) if token else None
    if res is not None and res[0] >= 1 and res[1] == 0:
        log("%s (%s): Risi message sent to %d" % (body["state"], body["check"], res[0]))
        return
    log("%s (%s): Risi message not delivered (%s); e-mail fallback" % (body["state"], body["check"], res))
    send_mail(env, body)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/alert":
            self.send_response(404)
            self.end_headers()
            return
        try:
            n = min(int(self.headers.get("Content-Length", "0")), 1 << 20)
            payload = json.loads(self.rfile.read(n) or b"{}")
            alerts = payload.get("alerts") or []
        except (ValueError, TypeError):
            self.send_response(400)
            self.end_headers()
            return
        for a in alerts:
            forward(map_alert(a))
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"{}")

    def do_GET(self):
        self.send_response(200 if self.path == "/health" else 404)
        self.end_headers()
        if self.path == "/health":
            self.wfile.write(b"ok")

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    srv = HTTPServer(("127.0.0.1", PORT), Handler)
    log("am-bridge listening on 127.0.0.1:%d -> %s" % (PORT, TARGET))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
