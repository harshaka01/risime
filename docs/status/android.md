# Android status — 0.2 nightlies

**READY** — 0.1 (A1–A7) plus 0.2 night 1: release plumbing and the Settings screen. Gate green on
`main`: `cd android && ./gradlew assembleDebug testDebugUnitTest assembleRelease` (45 JVM unit
tests). Not yet run on a device or emulator (spark2 has none): see "Device check" below.

## Release build (decision 003, details in decision 005)
- `versionName` = top-level `VERSION`; `versionCode` = major·1 000 000 + minor·10 000 + patch·100
  + (N for `-nightly.N`, else 99). A bad `VERSION` fails the build.
- Release is signed with the dev release key when `~/risime-keys/keystore.properties` exists
  (spark2); otherwise (laptop) it is unsigned. Check:
  `$ANDROID_HOME/build-tools/36.1.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
  → `CN=RisiMe Dev Release`, SHA-256 `da7b9824…c99e2e`.
- Ids: release `lk.codegen.risime` ("RisiMe"), debug `lk.codegen.risime.debug` ("RisiMe Dev");
  both can be installed side by side. Minify off.
- Both builds: cleartext only to `10.0.2.2` and `127.0.0.1`; default server `http://10.0.2.2:4400`.

### Installing releases (laptop)
```bash
adb uninstall lk.codegen.risime        # ONE TIME: removes the debug-signed v0.1.0 build (wipes its local data)
scripts/fetch-latest-apk --install     # newest ~/risime-releases/v*/ from spark2, checksum-verified
```
Debug builds still go through `scripts/install-apk` (now id `lk.codegen.risime.debug`).

## Build (spark2 or laptop)
```bash
cd android
~/.local/bin/mise exec -- ./gradlew assembleDebug testDebugUnitTest   # spark2 (non-interactive SSH)
./gradlew assembleDebug testDebugUnitTest                            # laptop
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```
- AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, JDK 17. compileSdk/targetSdk 37, minSdk 26.
- `buildToolsVersion = "36.1.0"` is pinned (spark2 has arm64 drop-ins for it; AGP's default 36.0.0
  is x86-only). The spark2 aapt2 override lives only in `~/.gradle/gradle.properties` on spark2.
- First build downloads platform 37 and build-tools automatically.
- Unit tests read the real `contract/v1/examples/*.json` (copied into test resources by the
  `copyContractExamples` task) and fail if an example has no decoder.

## Install and run (laptop)
```bash
git pull && scripts/install-apk      # scp's the APK built on spark2, installs on every adb device
ssh -N spark2-tunnel                 # laptop :4400 -> spark2 127.0.0.1:4000
```
- Emulator: the default server URL is `http://10.0.2.2:4400` in every build. Change it on the login
  screen ("Server: … · Change") or in Settings; the tailnet uses `https://…ts.net` (`docs/TAILSCALE.md`).
- USB phone: `adb reverse tcp:4000 tcp:4400`, then set the server URL to `http://127.0.0.1:4000`.
- Cleartext is allowed only for `10.0.2.2` and `127.0.0.1` (debug and release).
- Login: phone (defaults to `+94`, normalised to E.164 with libphonenumber, local `07…` numbers
  read as Sri Lankan) + allowlisted email → code from the server log
  (`tmux capture-pane -p -t risime-server -S -500 | grep "DEV OTP" | tail -3`).

## What's in the app
- **Screens:** Login (wordmark, "Talk. Connect. Act."), OTP (6 digits, 60 s resend timer),
  Chats (registered contacts with initials avatar, company, last-message preview and time;
  unregistered greyed out and not tappable; refresh; log out), Chat (bubbles mine/theirs in
  primaryContainer/surfaceVariant, time, ticks: clock / ✓ / ✓✓ / ✓✓ saffron, auto-scroll).
- **Banner** "Dev build — not end-to-end encrypted" is pinned above every screen, including login.
- **Theme:** Material 3, light + dark, dynamic colour off; primary `#0B6E69`, accent `#F2A93B`.
- **Realtime:** own Phoenix V2 client (decision 002) behind `RealtimeClient`. Connected only while
  the app is in the foreground (ProcessLifecycleOwner). Join `inbox:<me>` with `since` = stored
  cursor, then `sync` until `has_more` is false; live events that arrive meanwhile are buffered and
  applied after. Heartbeat 30 s; a missing heartbeat or push reply (10 s) drops the socket.
  Reconnect backoff 1, 2, 5, 10, 30 s. Socket 401/403 or join `unauthorized` → local logout.
- **Store-and-forward (Room):** `messages`, `contacts`, `sync_state`, `seen_events`,
  `behaviour_events`. Outbox: insert `pending` → `msg:send` → `sent`; every pending message is
  resent in `local_ts` order with the same `client_msg_id` after each (re)join. Status is
  forward-only. Incoming deduped by `event_id`, `message_id` and `client_msg_id`; `delivered` ack
  after storing, `read` ack while the chat is on screen (resumed). Acks the server hasn't
  confirmed are retried on the next join. The cursor is saved in the same transaction as each event.
- **Behaviour log (A6):** `app_open`, `chat_open`, `message_sent` (length only),
  `reply_latency_ms`; `peer_hash = sha256(peer_id + per-install salt)`. Local only, no UI, never
  uploaded. Kept on logout (chat data is wiped).
- No analytics; the only network peer is the configured RisiMe server.

## Decisions
`docs/decisions/002-android-client-stack.md`: own Phoenix client, compileSdk 37, build-tools pin,
local `failed` state, per-row ack tracking, Room logic tested via DAO fakes.
`docs/decisions/005-android-release-build-and-settings.md`: versionCode in the build script with a
self-check, default server in every build, atomic server switch, protocol-version test.

## Known limits (0.1)
- **Not device-tested yet.** Everything above is covered by JVM unit tests (contract parsing,
  phone normalisation, conversation id, status machine, outbox/dedupe/acks, REST client, Phoenix
  client against a fake server incl. reconnect). Room SQL and the UI only run on a device.
- No background connection or push (FCM is 0.2): messages arrive when the app is opened.
- A send the server rejects permanently (`unknown_recipient`, `empty_body`, `too_long`,
  `bad_request`) shows a red warning icon and is not retried; there is no retry/delete UI.
  `rate_limited` stays pending and retries after 10 s.
- Message order in a chat is by local time (send time for mine, receive time for theirs).
- `seen_events` is never pruned (one small row per event).
- Server cursor race (see `docs/status/server.md`) can, rarely, skip an event after a reconnect.
- `bad_request` (contract v1.1) and any unknown reason are treated as permanent send failures.
- `PROTOCOL_VERSION` (1.1) is checked against the PROTOCOL.md header by a unit test, so a contract
  version bump fails the Android gate until the client implements it (decision 005).
- Logout wipes messages and contacts on the device (single-device history in 0.1).

## Device check (step 8, for Harsha on the laptop)
1. `git pull && scripts/install-apk` with the emulator running; start `ssh -N spark2-tunnel`.
2. Login screen shows the banner, "RisiMe", "Talk. Connect. Act.", "Server: http://10.0.2.2:4400 · Change".
3. Log in with an allowlisted phone + email and the code from the server log.
4. Chats lists contacts (empty until the second person is allowlisted); unregistered ones greyed.
5. With a second account (USB phone): messages both ways within ~1 s; ticks clock → ✓ → ✓✓ →
   saffron ✓✓ when the other side opens the chat.
6. Kill B, send 3 from A, reopen B: all 3 in order, no duplicates.
7. Airplane mode on A while sending: clock stays, then ✓ after reconnect, no duplicate on B.
8. Release build (`scripts/fetch-latest-apk --install`): launcher shows "RisiMe"; a debug build
   next to it shows "RisiMe Dev". Settings → About shows the version from `VERSION` and its code.
9. Settings: change the display name (the other phone's contact list shows it after refresh);
   enter an invalid URL (error), then a new valid one → confirm → back on login with that URL.
