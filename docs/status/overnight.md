# Overnight summary — 2026-10-05 → 06 (orchestrator, cc-root window 0)

**Both roles are READY. Both test gates pass on `main`. The server was restarted from `main`
(`45dfd02`). Everything is ready for your two-phone smoke test.** All scheduled checks are stopped.

## What was built
**Server** (`server/`, done before you went to sleep; see `docs/status/server.md`)
- Phoenix on 127.0.0.1:4000: email-OTP login, tokens, `/me`, `/contacts`, and the realtime
  inbox channel (`msg:send`, `msg:ack`, `sync`, live `event`).
- Postgres (allowlist, users, OTP, tokens); Cassandra store-and-forward behind `Messaging.Store`.
- Allowlisted: you (+94770802222) and Shenika Herath (+94777888717), both CodeGen.

**Android** (`android/`, built overnight by an Android subagent; see `docs/status/android.md`)
| Commit | Step |
|---|---|
| `58087dc` | A1 Gradle project, debug network config (cleartext only for 10.0.2.2 and 127.0.0.1) |
| `f2ee4c3` | A2 contract models, plus tests over the real `contract/v1/examples` |
| `b37b757` | REST client, DataStore session, A3 own Phoenix V2 realtime client |
| `4af74a2` | Room, outbox, dedupe, cursor, delivered/read acks, A6 behaviour log (local only) |
| `98b3136` | A4/A5 screens and theme, the persistent "Dev build — not end-to-end encrypted" banner |
| `45dfd02` | Reconnect hardening; status READY |

## Test results (on `main` at `45dfd02`)
- **Server gate:** `mix format --check-formatted && mix compile --warnings-as-errors && mix test`
  → **57 tests, 0 failures**.
- **Android gate:** `./gradlew clean assembleDebug testDebugUnitTest` → **BUILD SUCCESSFUL,
  33 tests, 0 failures**. The APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.
- **Live interop (orchestrator, not committed):** the app's real `ApiClient` and
  `PhoenixRealtimeClient`, run on the JVM against the running server with two throwaway users
  (deleted afterwards, so your chats are clean):
  - REST `/me` and `/contacts` ✓
  - both clients reach Live ✓
  - A→B delivered live in **36 ms** ✓
  - delivered → read status reached A ✓
  - idempotent resend returns the same `message_id` ✓
  - `empty_body` rejected ✓
  - B offline, then 3 messages, then B reconnects: all 3 arrive in order with no duplicates ✓
  - a bad token ends in AuthFailed ✓

  Not covered: the Room SQL and the UI. They only run on a device, so the smoke test is their
  first real run.

## Decisions made
- `001-rate-limiter.md`: an in-memory ETS rate limiter instead of Hammer (server).
- `002-android-client-stack.md`:
  - own Phoenix client instead of JavaPhoenixClient;
  - compileSdk/targetSdk 37 (current AndroidX and OkHttp need it);
  - `buildToolsVersion` pinned to 36.1.0 (AGP's default 36.0.0 is x86-only);
  - a local `FAILED` state for messages the server rejects permanently;
  - per-message ack tracking;
  - logout wipes chats but keeps the behaviour log.
- Server: own `RisiMe.TimeUUID`, because `uniq` 0.6's `uuid1` breaks timeuuid ordering.

## Needs you
1. **The two-phone smoke test below.** It's the first time the app runs on a device. If anything
   crashes, send me `adb logcat -s RisiMe AndroidRuntime`.
2. ~~Approve the `bad_request` proposal~~: done. Merged as PROTOCOL v1.1 on 2026-10-06. The "not in PROTOCOL.md yet" notes in both role status files are now out of date.
3. After a good smoke test, tell me to **tag `v0.1.0`**. I'll write `docs/releases/v0.1.0.md` and
   copy the APK to `~/risime-releases/v0.1.0/`.
4. FYI: AGP put an unused x86 build-tools 36.0.0 and platform-tools into `~/Android/Sdk` on
   spark2. They're harmless.

## Smoke test (laptop): emulator = A (you), USB phone = B (Shenika)
1. On the laptop:
   ```bash
   cd ~/development/risime && git pull
   emulator -avd risime_a &        # or start it from Android Studio
   ssh -N spark2-tunnel &          # laptop :4400 → spark2 127.0.0.1:4000
   # plug in the phone with USB debugging on, then:
   adb reverse tcp:4000 tcp:4400   # needs the phone's serial (adb -s <serial> …) if the emulator is also attached
   scripts/install-apk             # installs on every adb device
   ```
2. **Emulator (A):**
   - the login screen shows the banner, "RisiMe" and "Talk. Connect. Act.";
   - the server URL is `http://10.0.2.2:4400`;
   - enter +94770802222 and harsha@codegen.co.uk;
   - get the code on spark2 with
     `tmux capture-pane -p -t risime-server -S -500 | grep "DEV OTP" | tail -3`, or open
     `http://127.0.0.1:4400/dev/mailbox` on the laptop.
3. **Phone (B):** set the server URL to `http://127.0.0.1:4000`. Log in with +94777888717 /
   shenika@codegen.net and the code from the same log.
4. Each side's Chats screen shows the other person, registered (not greyed). Pull to refresh if needed.
5. **A→B and B→A:** each message appears on the other side within 1 s.
6. **Ticks** on the sender: clock → ✓ → ✓✓ when it reaches the other device → saffron ✓✓ when
   the other side has the chat open.
7. **Offline catch-up:** kill the app on B (swipe it away), send 3 messages from A, reopen B. All 3
   arrive, in order, with no duplicates.
8. **Airplane mode on A:** turn it on, send a message (it stays on the clock), turn it off. It goes
   to ✓, and B gets exactly one copy.
9. The **"Dev build — not end-to-end encrypted"** banner is visible on every screen.
10. Optional: log out from the menu and you're back at login.

Server restart if ever needed (spark2):
`tmux kill-session -t risime-server; tmux new -d -s risime-server 'cd ~/development/risime/server && ~/.local/bin/mise exec -- mix phx.server'`

## Known limits worth knowing before the test
- No background connection or push (0.2): a message reaches B when B's app is in the foreground.
- A message the server rejects shows a red icon, with no retry or delete UI yet.
- Messages in a chat are ordered by local time.
- There's a rare race where a reconnecting client could skip one event that was being written
  at that exact moment.

Full lists: `docs/status/server.md` and `docs/status/android.md`.
