# 056 — LiveKit for group calls: ports, configuration and the Caddy route

**Status:** accepted 2026-10-08 (root), with contract v1.19 (PROTOCOL §20). Amends decision 046
(coturn's relay range and `max-bps`; LiveKit's previously reserved ports dropped). The LiveKit
deployment files are `infra/docker-compose.livekit.yml`, `infra/livekit/` and
`scripts/livekit-smoke` (commit 383fced); the Caddy route is in `infra/caddy/Caddyfile`.

## Context
Group calls (voice and video, up to 32/8 people) need an SFU; 1:1 calls stay P2P with coturn
(decision 046). The SFU is a self-hosted LiveKit on spark2, with frame E2EE keyed from MLS (the
SFU never has keys). Decision 046 had reserved 7881/tcp and 50000–60000/udp for LiveKit, but none
of those ports is open at the edge yet, and a second large UDP range is harder to get opened than a
split of the one we already asked for (49152–49999).

## Decision
**Ports (the only public ones for calls; everything else stays on 127.0.0.1):**

| Port | Proto | Bound on | Purpose |
|---|---|---|---|
| 3478 | udp + tcp | 10.20.20.15 | coturn STUN/TURN (decision 046) |
| 5349 | tcp | 10.20.20.15 | coturn TURN/TLS, later (decision 046) |
| **49152–49499** | udp | 10.20.20.15 | **coturn relay** (348 ports; shrunk from 49152–49999) |
| **49500–49999** | udp | 10.20.20.15 | **LiveKit RTC media** (500 ports) |
| 7880 | tcp | **127.0.0.1** | LiveKit signalling and API; public only via Caddy |

- The two UDP ranges never overlap (`min-port`/`max-port` in `infra/coturn/turnserver.conf`,
  `rtc.port_range_start`/`port_range_end` in `infra/livekit/livekit.yaml`). Both sit behind the
  same 1:1 NAT (203.115.26.139 → 10.20.20.15).
- **No ICE-TCP** (7881 is not opened; `rtc.tcp_port: 0`). A client that can't use UDP reaches
  LiveKit through coturn (TURN over TCP 3478, later TLS 5349), with the credentials of
  `GET /api/v1/calls/turn` passed as the LiveKit SDK's ICE servers (PROTOCOL §20.5). LiveKit's own
  `rtc.turn_servers` (our coturn, REST credentials from `TURN_SECRET`) is only a fallback.
- **Dropped:** 7881/tcp and 50000–60000/udp (decision 046's reservation).
- **coturn `max-bps=300000`** bytes/s per session (2.4 Mbit/s each way) for 1:1 video (§19.8).

**LiveKit configuration** (`livekit/livekit-server` v1.13.9, multi-arch):
- **Host networking** (no docker-proxy for 500 ports, no Docker publishing that bypasses ufw);
  `bind_addresses: [127.0.0.1]` for signalling and the API; `rtc.ips.includes: [10.20.20.15/32]`
  for media; `node_ip: 203.115.26.139` (no STUN discovery); no loopback candidate.
- **Embedded TURN off** (`turn.enabled: false`): coturn serves 1:1 and group calls.
- **`room.auto_create: false`** (required by §20.2: only the server's `start` creates rooms, with
  `max_participants` 32/8 and metadata). **Infra follow-up:** commit 383fced has `true`; change it.
- No Prometheus, no debug port, no egress/recording service, no webhook (§20.7). JSON logs to
  stdout, rotated by Docker (20 MB × 5).
- Secrets only in `.env`: `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` (the server mints tokens and
  derives the room-name key from it) and `TURN_SECRET`; written to a private tmpfs by
  `infra/livekit/entrypoint.sh`. The Phoenix server additionally reads `LIVEKIT_URL`
  (`wss://risime.risicloud.ai/livekit`) and `LIVEKIT_API_URL` (`http://127.0.0.1:7880`).

**Caddy route** (Harsha adds it with sudo; the repo copy is `infra/caddy/Caddyfile`). LiveKit's
client SDKs take the URL `wss://risime.risicloud.ai/livekit` and append `/rtc` (livekit-android
2.29), `/rtc/v1` (newer SDKs) and, after a failed connect, `/rtc/validate` or `/rtc/v1/validate`.
LiveKit serves those at its root, so the prefix must be stripped: `handle_path` does that. Only
`/rtc*` is forwarded; LiveKit's server API (`/twirp/*`) stays reachable from loopback only. Inside
the `risime.risicloud.ai { … }` site block, before the final `handle { respond "Not found" 404 }`:

```
	handle_path /livekit/* {
		@rtc path /rtc /rtc/*
		handle @rtc {
			reverse_proxy 127.0.0.1:7880
		}
		handle {
			respond "Not found" 404
		}
	}
```

and in the access-log filter, next to `delete token` (the SDKs put the join token in the URL):

```
				request>uri query {
					delete token
					delete access_token
				}
```

Then `sudo cp infra/caddy/Caddyfile /etc/caddy/Caddyfile && sudo caddy validate --config
/etc/caddy/Caddyfile && sudo systemctl reload caddy`. WebSocket upgrades pass through
`reverse_proxy` unchanged (as `/socket/websocket` does today).

## What Harsha must do (sudo / edge)
1. **Caddy:** the route above (copy, validate, reload).
2. **ufw** (if ufw is what filters; decision 046's port check):
   ```
   sudo ufw allow 3478 comment 'RisiMe TURN/STUN (decision 046)'
   sudo ufw allow 49152:49499/udp comment 'RisiMe TURN relay (decision 046/056)'
   sudo ufw allow 49500:49999/udp comment 'RisiMe LiveKit RTC media (decision 056)'
   ```
   (An existing `49152:49999/udp` rule already covers both ranges.)
3. **Edge firewall/NAT:** forward 3478/udp+tcp and **49152–49999/udp** to 10.20.20.15 (one range
   for both services). 7881/tcp and 50000–60000/udp are no longer needed.
4. **Hairpin check.** coturn relays and LAN phones reach LiveKit (and coturn) at 203.115.26.139,
   so the edge must hairpin that address back to spark2. Test from spark2:
   `scripts/udp-echo-probe --seconds 60 49500` and `printf 'ping\n' | nc -u -w 3 203.115.26.139
   49500`. If nothing echoes, add a local rewrite for spark2's own packets (persist it in
   `/etc/ufw/before.rules`, `*nat` table):
   ```
   sudo iptables -t nat -A OUTPUT -d 203.115.26.139 -p udp -m multiport --dports 3478,49152:49999 -j DNAT --to-destination 10.20.20.15
   ```
   LAN phones then still need the edge to hairpin (or to be tested from mobile data).

## Consequences
- One public UDP range (49152–49999) serves both relay and SFU; the split is a config detail.
- Group calls work from outside only once steps 2–3 are done; until then from the LAN at most,
  and the app fails fast (§16.11).
- LiveKit (same host) sees identities, IPs, timing, volume and audio levels, never content
  (§20.8). Its in-memory room list is the only call state; nothing about calls is persisted.
- Capacity: a full 8-person video room is about 11 Mbit/s of LiveKit egress, a 32-person voice
  call in the low tens of Mbit/s at worst; the release check measures spark2's uplink.
