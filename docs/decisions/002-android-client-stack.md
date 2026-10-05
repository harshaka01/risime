# 002 — Android client: Phoenix client, toolchain pins, outbox model

## Context
RELEASE-0.1 A1/A3 allow `JavaPhoenixClient` "if it works cleanly", otherwise a minimal Phoenix V2
client on OkHttp. The app is built on spark2 (ARM64) and on the laptop (x86_64).

## Decision
1. **Own minimal Phoenix V2 client** (`realtime/PhoenixRealtimeClient`) on OkHttp WebSocket, behind
   the `RealtimeClient` interface. JavaPhoenixClient is unmaintained, pulls Gson, and hides the
   reply/refs and reconnect policy we need to control (since/sync loop, buffering live events while
   syncing, backoff 1/2/5/10/30 s). The client is ~300 lines and tested against a scripted fake
   Phoenix server on MockWebServer.
2. **compileSdk/targetSdk 37.** Current AndroidX (Compose BOM 2026.09, core 1.19, navigation 2.10)
   and OkHttp 5.5 require compileSdk ≥ 37. AGP downloads platform 37 automatically (arch-neutral).
3. **`buildToolsVersion = "36.1.0"`** in `app/build.gradle.kts`. AGP 9.4's default (36.0.0) is only
   available as x86_64 binaries; 36.1.0 is the version spark2 has as arm64 drop-ins. The laptop
   downloads the normal 36.1.0. No machine paths are in the repo; the aapt2 override stays in
   `~/.gradle/gradle.properties` on spark2.
4. **Local `FAILED` state** for sends the server rejects permanently (`unknown_recipient`,
   `empty_body`, `too_long`, `bad_request`). It is local-only (not on the wire) and shown with an
   error icon. `rate_limited` keeps the message pending and retries after 10 s.
5. **Incoming acks are tracked per row** (`acked_status`), so delivered/read acks that fail while
   offline are resent on the next join. A `seen_events` table dedupes events by `event_id`;
   messages are also deduped by `message_id` and `client_msg_id`.
6. **Room logic tested without Robolectric.** `ChatEngine` depends on the DAO interfaces; unit tests
   use in-memory fakes. Room itself is exercised on device.

## Consequences
- We own reconnect/heartbeat bugs; the fake-server test covers join/sync/push/reply.
- `seen_events` grows without pruning in 0.1 (small: one row per event).
