#!/usr/bin/env python3
"""Generates the provisioned dashboards in ./dashboards (run: python3 -I gen-dashboards.py)."""
import json, os

DS = {"type": "prometheus", "uid": "prometheus"}
HERE = os.path.dirname(os.path.abspath(__file__))

def panel(pid, title, exprs, x, y, w=8, h=7, kind="timeseries", unit="short", legends=None, mn=None, mx=None, thresholds=None, mappings=None):
    legends = legends or [""] * len(exprs)
    p = {"id": pid, "title": title, "type": kind, "datasource": DS, "gridPos": {"x": x, "y": y, "w": w, "h": h},
         "targets": [{"refId": chr(65 + i), "expr": e, "legendFormat": legends[i], "datasource": DS} for i, e in enumerate(exprs)],
         "fieldConfig": {"defaults": {"unit": unit}, "overrides": []}, "options": {}}
    d = p["fieldConfig"]["defaults"]
    if mn is not None: d["min"] = mn
    if mx is not None: d["max"] = mx
    if thresholds: d["thresholds"] = {"mode": "absolute", "steps": thresholds}
    if mappings: d["mappings"] = mappings
    if kind == "stat": p["options"] = {"colorMode": "background", "graphMode": "none", "reduceOptions": {"calcs": ["lastNotNull"]}}
    return p

UPDOWN = [{"type": "value", "options": {"0": {"text": "DOWN"}, "1": {"text": "UP"}}}]
UPDOWN_T = [{"color": "red", "value": None}, {"color": "green", "value": 1}]

def dash(uid, title, panels):
    return {"uid": uid, "title": title, "tags": ["risime"], "timezone": "browser", "schemaVersion": 39, "version": 1,
            "refresh": "30s", "time": {"from": "now-6h", "to": "now"}, "panels": panels, "templating": {"list": []}}

# PromEx metric names are a best guess (<otp_app>_prom_ex_...); adjust here once :4021/metrics is live.
REQ = "risime_prom_ex_phoenix_http_request_duration_milliseconds"
overview = dash("risime-overview", "RisiMe Overview", [
    panel(1, "risime /health (public)", ['probe_success{job="probe_health",path="public"}'], 0, 0, 4, 4, "stat", mappings=UPDOWN, thresholds=UPDOWN_T),
    panel(2, "risime /health (local)", ['probe_success{job="probe_health",path="local"}'], 4, 0, 4, 4, "stat", mappings=UPDOWN, thresholds=UPDOWN_T),
    panel(3, "Keycloak (public)", ['probe_success{job="probe_http"}'], 8, 0, 4, 4, "stat", mappings=UPDOWN, thresholds=UPDOWN_T),
    panel(4, "Keycloak (LAN)", ['probe_success{job="probe_keycloak_lan"}'], 12, 0, 4, 4, "stat", mappings=UPDOWN, thresholds=UPDOWN_T),
    panel(5, "TURN / LiveKit", ['probe_success{job="probe_tcp"}'], 16, 0, 8, 4, "stat", legends=["{{service}} {{path}}"], mappings=UPDOWN, thresholds=UPDOWN_T),
    panel(6, "Request rate (req/s)", [f"sum(rate({REQ}_count[5m]))"], 0, 4, unit="reqps"),
    panel(7, "p95 latency", [f"risime:http_p95_ms"], 8, 4, unit="ms"),
    panel(8, "Errors (5xx rate)", [f'sum(rate({REQ}_count{{status=~"5.."}}[5m]))'], 16, 4, unit="reqps"),
    panel(9, "Connect time: public VIP vs LAN", ['probe_http_duration_seconds{phase="connect"}', 'probe_http_duration_seconds{phase="tls"}'], 0, 11, 12, 8, unit="s", legends=["connect {{path}} {{service}}", "tls {{path}} {{service}}"]),
    panel(10, "Total probe time", ['probe_duration_seconds{job=~"probe_.*"}'], 12, 11, 12, 8, unit="s", legends=["{{path}} {{service}} {{instance}}"]),
    panel(11, "Sockets (BEAM ports + TCP established)", ['node_netstat_Tcp_CurrEstab', 'node_sockstat_TCP_inuse'], 0, 19, legends=["established", "in use"]),
    panel(12, "Restarts logged (restarts.log)", ['risime_restarts_logged'], 8, 19, kind="stat"),
    panel(13, "Host boot / uptime", ['time() - node_boot_time_seconds'], 16, 19, kind="stat", unit="s"),
])
risi = dash("risime-risi", "RisiMe Risi", [
    panel(1, "Risi turn duration p50/p95", ['histogram_quantile(0.5, sum by (le) (rate(risime_prom_ex_risi_turn_duration_milliseconds_bucket[5m])))', 'histogram_quantile(0.95, sum by (le) (rate(risime_prom_ex_risi_turn_duration_milliseconds_bucket[5m])))'], 0, 0, 12, 8, unit="ms", legends=["p50", "p95"]),
    panel(2, "Turn outcomes", ['sum by (outcome) (rate(risime_prom_ex_risi_turn_total[5m]))'], 12, 0, 12, 8, legends=["{{outcome}}"]),
    panel(3, "vLLM time to first token p95", ['histogram_quantile(0.95, sum by (le) (rate(vllm:time_to_first_token_seconds_bucket[5m])))'], 0, 8, 8, 8, unit="s"),
    panel(4, "vLLM e2e request latency p95", ['histogram_quantile(0.95, sum by (le) (rate(vllm:e2e_request_latency_seconds_bucket[5m])))'], 8, 8, 8, 8, unit="s"),
    panel(5, "vLLM running / waiting requests", ['vllm:num_requests_running', 'vllm:num_requests_waiting'], 16, 8, 8, 8, legends=["running", "waiting"]),
    panel(6, "vLLM tokens/s (prompt, generation)", ['rate(vllm:prompt_tokens_total[5m])', 'rate(vllm:generation_tokens_total[5m])'], 0, 16, 12, 8, legends=["prompt", "generation"]),
    panel(7, "Keycloak JWKS fetch failures (15 min)", ['increase(risime_jwks_fetch_total{result="error"}[15m])'], 12, 16, 12, 8, kind="stat"),
])
infra = dash("risime-infra", "RisiMe Infra", [
    panel(1, "CPU used %", ['100 * (1 - avg(rate(node_cpu_seconds_total{mode="idle"}[2m])))'], 0, 0, unit="percent", mn=0, mx=100),
    panel(2, "RAM used %", ['100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)'], 8, 0, unit="percent", mn=0, mx=100),
    panel(3, "Swap used", ['node_memory_SwapTotal_bytes - node_memory_SwapFree_bytes', 'node_memory_SwapTotal_bytes'], 16, 0, unit="bytes", legends=["used", "total"]),
    panel(4, "Disk used % (/)", ['100 * (1 - node_filesystem_avail_bytes{mountpoint="/",fstype!="tmpfs"} / node_filesystem_size_bytes{mountpoint="/",fstype!="tmpfs"})'], 0, 7, unit="percent", mn=0, mx=100),
    panel(5, "Swap in/out (pages/s)", ['rate(node_vmstat_pswpin[2m])', 'rate(node_vmstat_pswpout[2m])'], 8, 7, legends=["in", "out"]),
    panel(6, "GPU utilisation / temperature", ['risime_gpu_utilization_percent', 'risime_gpu_temperature_celsius'], 16, 7, legends=["util %", "temp C"]),
    panel(7, "GPU power (W)", ['risime_gpu_power_watts'], 0, 14, unit="watt"),
    panel(8, "GPU memory by process (MiB)", ['risime_gpu_process_memory_mib'], 8, 14, legends=["pid {{pid}}"]),
    panel(9, "Cassandra max GC pause (ms)", ['risime_cassandra_gc_max_pause_ms'], 16, 14, unit="ms"),
    panel(10, "Cassandra pending / blocked tasks", ['risime_cassandra_pending_tasks', 'risime_cassandra_blocked_tasks'], 0, 21, legends=["pending", "blocked"]),
    panel(11, "Cassandra heap (MB)", ['risime_cassandra_heap_used_mb', 'risime_cassandra_heap_max_mb'], 8, 21, legends=["used", "max"]),
    panel(12, "Postgres connections", ['sum(pg_stat_database_numbackends)', 'pg_settings_max_connections'], 16, 21, legends=["backends", "max"]),
    panel(13, "Load average", ['node_load1', 'node_load5'], 0, 28, legends=["1m", "5m"]),
    panel(14, "Network (bytes/s)", ['sum(rate(node_network_receive_bytes_total{device!~"lo|veth.*|br-.*|docker.*"}[2m]))', 'sum(rate(node_network_transmit_bytes_total{device!~"lo|veth.*|br-.*|docker.*"}[2m]))'], 8, 28, unit="Bps", legends=["rx", "tx"]),
    panel(15, "Scrape targets up", ['up'], 16, 28, legends=["{{job}}"]),
])
os.makedirs(os.path.join(HERE, "dashboards"), exist_ok=True)
for name, d in (("overview", overview), ("risi", risi), ("infra", infra)):
    with open(os.path.join(HERE, "dashboards", name + ".json"), "w") as f:
        json.dump(d, f, indent=1)
