# Android status — 0.2 nightlies

**READY** — 0.1 (A1–A7) plus 0.2 night 1: release plumbing, Settings, presence/last seen and
typing (contract v1.2), chat polish (unread, day separators, copy, retry/delete, search) and the
design system pass. Gate green on `main`:
`cd android && ./gradlew assembleDebug testDebugUnitTest assembleRelease` (72 JVM unit tests).
Not yet run on a device or emulator (spark2 has none): see "Device check" below.

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
  Chats (registered contacts with initials avatar + presence dot, company, last-message preview and
  time, unread badge with bold row, "typing…", "online"/"last seen …"; unregistered greyed out and
  not tappable; search, refresh, settings, log out), Chat (day separators, bubbles, time, ticks:
  clock / ✓ / ✓✓ / ✓✓ saffron, header with "typing…" / "online" / "last seen …", auto-scroll),
  Search, Settings.
- **Presence and typing (v1.2, decision 009):** `signal` pushes go straight to `PresenceTracker`
  (never buffered behind sync, never touch the cursor). `presence:watch` = registered contacts +
  the open chat (max 200), sent right after every join reply and on change; a missing id is
  "unknown". Presence and typing are cleared when the socket drops. Typing: `true` at most every
  3 s, `false` after 3 s idle / on clear / on send / when leaving the chat; only while live, never
  queued. Received "typing…" ends on `false`, a message from that user, them going offline, or 6 s
  without a refresh. "online" means the other person has the app open.
- **Chat polish:** unread = incoming messages not yet read (badge, bold); day separators (Today,
  Yesterday, weekday, date; local time zone); long-press a message → Copy; a FAILED message (tap or
  long-press) → Retry (same `client_msg_id`) or Delete (local only). Search (Chats → 🔍): people by
  name/company and messages by text (`LIKE`, newest 100; decision 009).
- **Banner** "Dev build — not end-to-end encrypted" is pinned above every screen, including login.
- **Design system:** tokens in `ui/theme/Tokens.kt` (light/dark palettes on the seeds teal
  `#0B6E69` and saffron `#F2A93B`, spacing 2–32 dp, sizes, shapes, type scale), components in
  `ui/common/Common.kt` (banner, avatar + presence dot, top bar, list row, unread badge, section
  header, empty/error states, day separator, message bubble, ticks). Dynamic colour off.
- **Accessibility:** `DesignTokensTest` checks every text pair ≥ 4.5:1 and meaningful marks (ticks,
  presence dot) ≥ 3:1 in both themes, so the light read tick is a darker saffron `#9C5F00`.
  Touch targets ≥ 48 dp (bubbles use `minimumInteractiveComponentSize`, rows ≥ 72 dp). TalkBack:
  a bubble is one node ("You: hi, 14:05, Read"), long-press is labelled "Message options", ticks,
  presence dot ("online"), unread badge ("3 unread"), icons and the banner have descriptions;
  titles and day separators are headings; typing/connection subtitle and errors are polite live
  regions.
- **Realtime:** own Phoenix V2 client (decision 002) behind `RealtimeClient`. Connected only while
  the app is in the foreground (ProcessLifecycleOwner). Join `inbox:<me>` with `since` = stored
  cursor, then `sync` until `has_more` is false; live events that arrive meanwhile are buffered and
  applied after. Heartbeat 30 s; a missing heartbeat or push reply (10 s) drops the socket.
  Reconnect backoff 1, 2, 5, 10, 30 s. Socket 401/403 or join `unauthorized` → local logout.
- **Store-and-forward (Room):** `messages`, `contacts`, `sync_state`, `seen_events`,
  `behaviour_events`. DB version 1; schemas are exported to `android/app/schemas/` (committed).
  Upgrades: bump `AppDatabase.VERSION`, build (exports the new schema), add a `Migration(n-1, n)`
  to `AppDatabase.MIGRATIONS`. No destructive fallback. `SchemaMigrationsTest` fails if a schema
  file or a migration step is missing; the SQL is verified on device. Outbox: insert `pending` → `msg:send` → `sent`; every pending message is
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
`docs/decisions/009-android-presence-search-room-migrations.md`: signal routing, watch list,
typing rules, LIKE search instead of FTS, exported schemas + migration guard, retry/delete rules.

## Known limits
- **Not device-tested yet.** Everything above is covered by JVM unit tests (contract parsing,
  phone normalisation, conversation id, status machine, outbox/dedupe/acks, REST client, Phoenix
  client against a fake server incl. reconnect, signals and watch-on-rejoin, presence/typing state
  machines, unread/separators/search/retry logic, token contrast). Room SQL and the UI only run on
  a device.
- No background connection or push (FCM is 0.2): messages arrive when the app is opened.
- A send the server rejects permanently (`unknown_recipient`, `empty_body`, `too_long`,
  `bad_request`) shows a red warning icon and "Not sent"; it is retried only when the user taps
  Retry. `rate_limited` stays pending and retries after 10 s.
- Presence is unknown while disconnected and refreshes only with the next watch reply. "last seen"
  labels are computed when the list recomposes (not on a timer).
- Search is a plain substring match (no ranking, no diacritics folding), newest 100 messages.
- Message order in a chat is by local time (send time for mine, receive time for theirs).
- `seen_events` is never pruned (one small row per event).
- Server cursor race (see `docs/status/server.md`) can, rarely, skip an event after a reconnect.
- `bad_request` (contract v1.1) and any unknown reason are treated as permanent send failures.
- `PROTOCOL_VERSION` (1.2) is checked against the PROTOCOL.md header by a unit test, so a contract
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
10. Presence (two accounts A and B): with B's app open, A's Chats shows a dot on B's avatar and
    B's chat header says "online". Background B: about 5 s later A shows "last seen today at …".
11. Typing: type on B without sending → A's header and Chats row show "typing…" within ~1 s; stop
    typing for 3 s → it disappears; send → it disappears and the message arrives. Kill B's app
    while it's typing → A clears "typing…" within 6 s.
12. Unread: send 3 messages from B while A is on Chats → B's row is bold with a "3" badge; open
    the chat → the badge goes away (and B sees saffron ✓✓).
13. Day separators: "Today" above today's messages (older days need older data; change the device
    date to check "Yesterday"/weekday).
14. Long-press a message → Copy → paste elsewhere. Send to a recipient the server rejects (or
    stop the server's allowlist entry) → "Not sent" → tap → Retry / Delete behave.
15. Search: Chats → 🔍 → part of a name and of a message text; tap a result opens the chat.
16. Dark mode and TalkBack: toggle system dark theme (all text readable); with TalkBack, a bubble
    reads as one item with its status, the presence dot reads "online", the badge "N unread".
17. Upgrade path: install the previous release, log in, chat, then install this release over it
    (`adb install -r`): chats and login survive (no migration needed at DB v1; repeat this check on
    every DB version bump).
