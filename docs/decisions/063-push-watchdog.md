# 063 — Push watchdog: WebSocket ping/pong decides "online" for push

**Status:** accepted 2026-10-08 (root). Server `eae4dcf`; no wire change.

## Context
P0 (Harsha, real phone, nightly.31): messages didn't show and calls didn't ring while RisiMe was in
the background or the screen was locked. One cause: `Push.Dispatcher` skips the push while the user
has a live inbox channel. A phone that loses network without closing its websocket (Doze, a
tunnel, a network switch, a frozen process) still counts as live until Phoenix's 60 s timeout.
Events in that window got no push. The existing `msg:ack` is per user, covers messages only and
isn't sent per device, so it can't confirm delivery to a device.

## Decision
- **The ping:** for each push-eligible event to a user with live inbox channels, each channel sends
  a WebSocket **ping** after the events it pushed. OkHttp answers with a pong by itself, so the app
  needs no change; a frozen process can't answer.
- **A pong:** no push (logged as `push: skipped … reason=online`).
- **No pong within 8 s** (`:push_watchdog_ms`):
  - `push: watchdog … device=<id8>`;
  - push that device;
  - close the dead socket, so presence drops.
- **Bursts:** one ping is outstanding per connection, so a burst costs one ping and at most one push.
- **Group call rings:** the same check with 4 s (`:push_call_watchdog_ms`). 1:1 rings keep the
  §16.8 3-s fallback.
- **The 5-s presence grace** no longer holds a push (§8.0: push when there is no *live* channel).
- **The websocket timeout** stays at 60 s. The client heartbeat is 30 s, so shorter would drop
  healthy sockets.

## Consequences
- A slow but healthy client that misses the 8 s gets one extra push and a reconnect. That costs
  little, and the client dedupes by event id.
- Caddy copies upgraded websocket bytes as they are, so ping/pong passes through. The real-phone
  check confirms it (look for `push: watchdog` and `reason=online` lines with Harsha's hash).
