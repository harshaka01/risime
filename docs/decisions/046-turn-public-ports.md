# 046 — TURN (coturn) on spark2 with public ports: an exception to rule 6

**Status:** accepted 2026-10-06 (root); coturn is **configured and smoke-tested on loopback, not
started publicly**. It goes live after the port check below passes and the server serves
`GET /api/v1/calls/turn` (contract proposal `contract/proposals/2026-10-06-calls-v1.13.md`).

## Context
1:1 voice calls (proposal v1.13) are WebRTC peer-to-peer with Opus. About 10–20 % of mobile
calls can't connect directly (symmetric NAT, carrier-grade NAT, UDP-blocking Wi-Fi) and need a
TURN relay. spark2 is the only host we run. A TURN server is useless unless the public internet
can reach it, while CLAUDE.md rule 6 says every service binds to 127.0.0.1. Group calls (LiveKit,
v1.14) will need public media ports too.

## Decision
- **coturn 4.7** (`coturn/coturn:4.7`, multi-arch, arm64 verified on spark2: `turnserver 4.7.0`),
  `infra/docker-compose.turn.yml` + `infra/coturn/turnserver.conf`. **Host networking** (no
  docker-proxy for 848 relay ports, and no Docker port publishing that bypasses ufw);
  `turnserver` binds **only `10.20.20.15`** (spark2's LAN address, 1:1 NAT from 203.115.26.139),
  never 0.0.0.0 or 127.0.0.1. `external-ip=203.115.26.139/10.20.20.15`.
- **Exactly these public ports (the rule-6 exception):**

  | Port | Proto | Purpose |
  |---|---|---|
  | 3478 | udp + tcp | STUN / TURN |
  | 5349 | tcp | TURN over TLS (when the certificate is in place; `no-dtls`, so no 5349/udp) |
  | 49152–49999 | udp | TURN relay range (848 ports) |

  Reserved for LiveKit (v1.14, not deployed): **7881/tcp** (ICE-TCP) and **50000–60000/udp**
  (RTC). LiveKit's signalling (7880) stays on 127.0.0.1 behind Caddy (`wss://…/livekit`), and its
  built-in TURN is off (coturn serves both).
- **Auth:** `use-auth-secret` (TURN REST credentials). The server mints
  `username = "<expiry unix ts>:<random id>"`, `credential = base64(HMAC-SHA1(TURN_SECRET, username))`,
  TTL 1 h. **`TURN_SECRET` lives only in `.env`** (gitignored) and is shared by Phoenix and
  coturn; `infra/coturn/entrypoint.sh` appends it to a private copy of the config at start, so
  it's never in the repo, on a command line or in a log. Rotation: set a new value, restart both.
- **Hardening:** `no-cli`, `no-tcp-relay` (no TCP relaying to arbitrary hosts), no web admin, no
  Prometheus, `no-multicast-peers`, `no-rfc5780`, `no-software-attribute`, `fingerprint`,
  TLS 1.2+ only. Container: read-only root, tmpfs, `cap_drop: ALL` (+ `NET_BIND_SERVICE`, a file
  capability the image's `turnserver` needs to exec), `no-new-privileges`.
- **`denied-peer-ip`** for 0/8, 10/8 (spark2's LAN), 100.64/10 (CGNAT, Tailscale), 127/8 (the
  pilot on :4000, Postgres, Cassandra, Caddy's admin API), 169.254/16, 172.16/12 (Docker
  bridges), 192.168/16, the TEST-NETs, 198.18/15, multicast and reserved, and the IPv6 equivalents
  (loopback, mapped, NAT64, ULA, link-local). The relay therefore reaches only public unicast
  addresses. The public IP 203.115.26.139 itself stays allowed (two relayed clients must reach each
  other; it only exposes what is already public). coturn 4.7 also denies loopback peers by default
  (seen in the smoke test: `403 Forbidden IP`).
- **Quotas:** `user-quota=8` allocations per username, `total-quota=600`, `max-bps=64000` bytes/s
  per session (512 kbit/s, ample for Opus voice), `max-allocate-lifetime=3600`, `stale-nonce=600`.
  The REST endpoint is rate-limited per user (proposal v1.13 §16.3).
- **TLS on 5349: later.** Caddy's certificate for risime.risicloud.ai is under `/var/lib/caddy`
  (root-only, not readable without sudo). TURN/TLS on 5349 starts once a copy is readable by the
  container (one sudo step: a root cron/`caddy` event hook copying the cert and key to
  `/home/harsha/risime-keys/turn-tls/`, mode 640, group harsha; then uncomment `cert`/`pkey` and the
  volume). Until then clients get `turn:` (udp + tcp) URLs only.
- **Smoke test:** `scripts/turn-smoke` runs the same config rebound to 127.0.0.1 with a throwaway
  secret, then `turnutils_uclient` (UDP and TCP to TURN, client-to-client relay, REST auth) and a
  wrong-secret refusal, then removes the container. Result 2026-10-06: 0 % loss both ways, wrong
  secret refused.

## Port check from outside (2026-10-06)
Method: temporary listeners on 0.0.0.0 for under 70 s (stopped at once), probed from
check-host.net (TCP and UDP, 3–4 nodes each) and portchecker.io. Control: 443/tcp reachable.

| Port | Result |
|---|---|
| 443/tcp (control) | reachable |
| 3478/tcp, 5349/tcp, 7880/tcp, 7881/tcp, 7882/tcp | **filtered**: timed out from every node, with a listener running; nothing arrived on spark2 |
| 3478/udp, 5349/udp, 7882/udp; relay samples 49152, 49500, 49999; LiveKit samples 50000, 55000, 60000 (udp) | **nothing arrived** at `scripts/udp-echo-probe` on spark2 from any check-host node, so very likely filtered too; confirm by hand (below) |
| 22/tcp | not reachable from outside although sshd listens on 0.0.0.0 and ufw allows OpenSSH (setup script): a hint that the edge firewall filters |

Without a listener the TCP ports also time out rather than refuse, so packets are dropped before
the kernel: either by the edge firewall (in front of the 1:1 NAT) or by ufw on spark2. ufw's rules
aren't readable without sudo. **Harsha:** `sudo ufw status verbose` shows which. If ufw is the
gap, the exact commands are:

```
sudo ufw allow 3478 comment 'RisiMe TURN/STUN (decision 046)'
sudo ufw allow 5349/tcp comment 'RisiMe TURN/TLS (decision 046)'
sudo ufw allow 49152:49999/udp comment 'RisiMe TURN relay (decision 046)'
# later, LiveKit (v1.14):
sudo ufw allow 7881/tcp comment 'RisiMe LiveKit ICE-TCP'
sudo ufw allow 50000:60000/udp comment 'RisiMe LiveKit RTC'
```

If ufw already has them, the edge firewall/NAT (Shirazi) doesn't forward them yet.

**UDP by hand** (from outside the office network, e.g. the laptop on a phone hotspot): on spark2
`scripts/udp-echo-probe --seconds 300 3478 49152 49999 50000 60000`, then on the laptop
`printf 'ping\n' | nc -u -w 3 203.115.26.139 3478` (repeat per port). A reachable port prints
`risime-echo 3478 ping` on the laptop and the sender's address on spark2. A phone "UDP sender" app
to 203.115.26.139:<port> works the same way. TCP re-check: on spark2
`python3 -m http.server 3478 --bind 0.0.0.0` for a few seconds, and from outside
`nc -vz -w 5 203.115.26.139 3478`.

## Consequences
- spark2 exposes a relay to the internet. Abuse is bounded by short-lived HMAC credentials that
  only signed-in users get, per-user and total quotas, a bandwidth cap per session and the peer
  deny-list; the relay can't be used to reach anything internal.
- Relayed media stays end-to-end encrypted (DTLS-SRTP between the phones); coturn sees IPs, ports,
  timing and volume, never audio.
- Going live is: open ports (edge + ufw) → port check passes → `TURN_SECRET` in `.env` →
  `docker compose --env-file .env -f infra/docker-compose.turn.yml up -d` → server ships v1.13.
- `.env.example` needs a `TURN_SECRET=` line (top-level file; added with the server change).
