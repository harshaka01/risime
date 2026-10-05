# 011 — Load-test tool: `mix risime.loadtest` with Mint.WebSocket

## Context
Before the pilot grows, we need numbers for the realtime path at 200 concurrent users, and a
repeatable way to find bottlenecks.

## Decision
- A dev-only mix task, `mix risime.loadtest`, in `server/dev/` (compiled in dev and test only;
  `elixirc_paths`), with the WebSocket client **`mint_web_socket ~> 1.0`** (`only: [:dev, :test]`;
  never in prod builds). Mint is already in the tree via Finch. It is a process-less, low-level
  client, so one BEAM process per simulated user is cheap and close to how the Android client
  talks: the V2 serializer, real `phx_join`, `msg:send`, `msg:ack` and heartbeats.
- **Throwaway users** are inserted straight into the dev DB (allowlist entry, user, bearer token;
  no OTP) with phones under the reserved, unassigned `+999` prefix and emails at
  `loadtest.invalid`.
  - They are deleted in an `after` block, and all `+999…` rows from any run are matched.
    `mix risime.loadtest --cleanup` removes leftovers after a killed run. Cassandra rows expire by
    TTL.
  - While a run is going, the throwaway users are in the dev allowlist, so `/contacts` on the
    test server lists them. Run it when nobody is testing.
- **Traffic:** each user sends one DM per `--interval` ms (default 1500; 1000 in the standard
  run) to a random other user, below the 20 per 10 s limit. Recipients ack `delivered`, and
  `read` for every second message.
- **Metrics:**
  - send→reply and send→recipient-push latency (monotonic clock, same BEAM), as
    p50/p95/p99/max;
  - errors by reason;
  - reply timeouts (5 s);
  - missing pushes.
- **Target:** a temporary server from this checkout on 127.0.0.1:4100
  (`PORT=4100 LOG_LEVEL=warning mix phx.server`). The task refuses `:4000` (decision 006).
- Results go in `docs/status/loadtest.md`.

## Consequences
- The client and the server share the machine, so the numbers include client CPU. They are
  comparable run to run on spark2, not absolute capacity.
