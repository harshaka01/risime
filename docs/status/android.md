# Android status — 0.2 nightlies

## P0 hotfix after nightly.10 (data loss, group picker, scrolling, OTP) — READY (pending upgrade-test run)
Commits `3502c2c`, `1ea68a4`, `790a980`, `024afbd`. Gate green: `./gradlew assembleDebug testDebugUnitTest` (319 JVM tests).
- **Wipe rules:** `LocalAccount` is the only wipe decision: confirmed logout, confirmed server change, or a
  sign-in whose stable id (UUID, case-insensitive) differs. `logout()` needs a `UserConfirmation` that only
  `ui/common/ConfirmLogout.kt` creates; every Log out / Sign out has a confirm dialog. Logout is local-first
  (network best effort, 5 s bound; Keycloak end_session after the wipe). No destructive Room fallback (test).
- **Recovery:** one-time inbox replay per install (cursor reset; `seen_events` keep it additive).
- **MLS:** activates only once an OIDC session is unlocked (before: 401 while locked, E2EE/groups off).
- **Tests:** `LocalAccountTest`, `UpgradeKeepsDataTest` + `EveryReleasedSchemaUpgradeTest` (schemas 1..5 through
  the real `AppContainer`), `LogoutCallSitesTest`, `NoDestructiveMigrationTest`, `CreateGroupFlowTest`,
  `ChatScrollTest`, OTP `CodeScreensTest`.
- **Upgrade test:** `scripts/upgrade-test` needs an adb device (no aarch64 emulator exists for spark2); run it
  on the laptop (`--fetch-candidate`). `scripts/nightly-release` refuses to publish without a fresh PASS.
- **Server/root follow-ups:** group readiness (§12.1) counts every app instance of 30 days, including
  socket connects without `device_id`/`app_version` (old builds) — both testers are not group-ready today;
  Keycloak client `risime` needs valid post-logout redirect URIs `ai.risicloud.risime://logout` and
  `ai.risicloud.risime.debug://logout`.

## v1.10 history after a reinstall (§13) — READY
**READY** for contract v1.10 §13 (decision 043), following the review's chunk order
(`contract/proposals/reviews/2026-10-06-history-v1.10-android.md`). Gate green on `main` with
`app/google-services.json` in place (push build): `./gradlew assembleDebug testDebugUnitTest`
(279 JVM tests, 5 skipped live/opt-in). `scripts/interop` (own `risime_interop` store, server v1.10
`5c037a1`): **INTEROP OK 3 runs in a row**, including the new `LiveE2eeInteropTest.liveHistory`.

What's in the app:
- **Protocol:** `EventsPage.historyBefore`, `RealtimeListener.onHistoryBefore` (called before each
  join/sync page's events), `ApiErrorBody.used/limit` (`quota_exceeded`); typed decoders for the
  three v1.10 examples.
- **Pipeline:** `MlsResult.BeforeInstall` at the three loss points: no group / a generation the
  device doesn't hold (rule 1, `server_ts < history_before` as `Instant`s), the Welcome's replay of
  parked lower epochs (rule 2), and parked older-generation rows read before `dropOlderGenerations`
  (one marker, max `server_ts`). Rule 1 never pre-empts a decryptable message (existing group at our
  epoch decrypts first; a decrypt failure before `history_before` counts as pre-install) and never
  runs on replayed `mls_pending` rows. Null `history_before` = rule 2 only. Every other drop (decrypt
  exception, sender mismatch, missing generation/epoch, live stale generation, rejected Welcome)
  becomes the "Some messages couldn't be decrypted" line.
- **Markers:** `sys:history:<conv>` / `sys:undecryptable:<conv>`, `kind = system`, READ/acked READ,
  `INSERT OR IGNORE` + forward-only move (`local_ts` = server_ts + 1 ms), in the event's
  transaction. DM chat renders them as centred system lines (no bubble/ticks/reactions); chat-list
  preview is muted with no "You:"; group screen uses the new `systemText` branches; search skips
  system rows. Never unread, acked or notified.
- **Replay:** restored rows (before `history_before`, or > 5 min older than the clock) take
  `local_ts` from `server_ts` (group_event lines too); received pre-install plaintext is stored read
  with no acks (S-b); own copies are outgoing, never acked, ticked by replayed `status` /
  `group_receipt`; a copy matching a PENDING outbox row by `client_msg_id` fills its id and moves it
  to SENT (S-a). A `since: null` join suppresses notifications (`notifyFromLocal`, "added you to")
  until it is live, then sets `notifiedUpTo = now`.
- **Found in the live run, fixed client-side:** after logout + login on the same `device_id`, the
  replayed inbox holds the old Welcome for the wiped key package (no server_ts on Welcomes). A Welcome
  whose event_id TimeUUID is before `history_before` is now ignored instead of showing the
  undecryptable line. Root may want this sentence in §13.3.

Tests: `MlsPipelineTest` (rule 1/2 guards, null hb, parked rows never re-judged, older-generation
marker, old device's own sends, corrupted ciphertext, stale Welcome), `FreshInstallReplayTest`
(DM fresh install: both plaintext sides with ticks, one marker between old plaintext and readable
messages, server-time `local_ts`, only the readable message acked, idempotent across duplicate
delivery and restart, empty notification plan incl. reactions; group variant; new member without a
marker; live copy before the send reply), `HistoryMarkerRoomTest` (real Room SQL: upsert and all
exclusions), `DmSystemRowTest` (Robolectric Compose), `PushLogicTest`, `PhoenixRealtimeClientTest`,
`ContractExamplesTest`. Live: `liveHistory` (history_before stable / null without device_id, sender
copy event id, (i) second device, (ii) reinstall, (iii) logout/login with a new history_before).

Push (nightly.11): builds with `google-services.json`; registration path unchanged by v1.10; wake-ups
only sync; own sender copies are outgoing and never notify; a fresh-install replay never notifies.

Not done: the device run on the emulator + USB phone (needs the laptop); S-d/S-e/S-f/S-g.

## v1.9 groups with MLS (§12) — READY
**READY** for contract v1.9 §12 (decision 041), following the review's chunk order
(`contract/proposals/reviews/2026-10-06-groups-v1.9-android.md`). Gate green on `main`:
`cd android && ./gradlew assembleDebug testDebugUnitTest` (258 JVM tests, 3 live-server ones skipped, including the
real-core `RealGroupTest`). Not yet run against the live server or on a device.

What's in the app:
- **Keyed by conversation id** (chunk 1): engine, open chat, notification suppression and intents
  (old peer-id intents still open the DM). An open DM no longer hides the same person's group
  messages.
- **Wire models** for every v1.9 shape; `ContractExamplesTest` decodes all v1.9 examples into them and
  round-trips the client-sent ones (v1.7 payloads unchanged: new fields are omitted when unused).
- **Room v5** (additive): `groups`, `group_members`, `group_ops`; messages gain `kind`/`system_json`
  and the aggregated receipt columns (`to_id` holds the conversation id for groups); contacts gain
  `group_ready`. `Migration4To5Test` compares a migrated v4 DB with a fresh v5 one column by column.
- **`mls_kv` chunking:** values over 512 KB are split into sealed chunk rows (the 768-leaf tree is
  about 1.8 MB; Android's `CursorWindow` is 2 MB). No schema change.
- **Receive path:** `group_event` → members/roles + local system lines (never unread, acked or
  notified); `group_op` naming this device → the op outbox (deduped by `op_id`); `group_receipt` →
  ✓✓ / read ticks; group messages without `to`; commit/Welcome blob refs fetched and SHA-256
  checked before the event's transaction (a transient failure keeps the cursor); catch-up when a
  `grp:` event parks ahead (paged, `410 log_expired` → rejoin); a rejected commit or a gone blob →
  rejoin; group typing per conversation.
- **MLS:** `MlsEngine` group API over the crypto FFI (`createGroupWithMeta`, `changeMembers`,
  `removeUsers`, `updateGroupMeta`, `groupMeta`). Our own `grp:` commit still at our epoch (a crash
  between the `200` and the merge, or the event beating the reply) is merged from the log via
  `processCommits`.
- **Registration:** `mls.capabilities: ["groups"]` is sent only when the core is groups-capable
  (the real FFI is); the first time per signature key the key packages are re-uploaded with
  `replace: true` and a fresh last-resort package.
- **Op outbox** (`GroupOpsExecutor`, persisted): create (idempotent `client_group_id` → claim →
  epoch-0 commit with `group_meta`), add/remove/role (REST, then the op naming this device),
  leave (immediate locally, undone on `last_admin`), rename (`meta_changed`), server ops
  (add/remove/role/devices/rebuild) **re-derived from `GET /groups/{id}` every attempt**, rejoin,
  reset. Blob refs over 64 KiB; merge only on `200`; `409` → drop, catch up, rebuild; backoff and a
  final failed state. `GET /groups` on every (re)join finds owed ops (R5).
- **UI:** group rows in the chats list; "New group" FAB (only with a groups-capable core): friend
  picker (non-ready friends greyed "needs to update") → name (1–100 graphemes) → Create; group chat
  with coloured sender names per run, system lines, typing names, the e2ee strip until the Welcome,
  read-only after leave/removal; info screen (members, Admin chip, Adding…/Removing…, admin add /
  rename / remove / make or dismiss admin / reset, Leave with confirm, `last_admin` suggests the
  longest-standing member); long-press your message → Info → "Read by" sheet. Back always works
  (decision 016). The dev banner is unchanged.
- **Notifications:** MessagingStyle per group (group title, sender per line), "Kamal added you to
  <name>" (S4).

Tests: `GroupStoreTest`, `GroupChatEngineTest`, `GroupOpsExecutorTest` (the op state machine),
`GroupLogicTest`, `GroupNotificationsTest`, `GroupScreensTest` (Robolectric Compose: create group,
info, Read by; light and dark; 320×480 dp), `Migration4To5Test`, `SealedKvStoreTest` (chunking),
`DeviceRegistrarTest` (capability + replace), and `RealGroupTest` with the real core (create, join,
group messages through `ChatEngine`, rename, non-admin rename refused, removal lock-out, own commit
recovered from the log).

Known gaps / next:
- Not yet exercised against the server's §12 endpoints (server role in progress); the interop gate
  has no group cases yet, and no device run.
- No 256-member on-device benchmark yet (the crypto role measured 2.6 ms decrypt / 18 ms join in
  release on spark2); the FFI has no in-memory group cache.
- System lines appear when their `group_event` arrives, not gated on the local epoch reaching it.
- A network error on our own commit drops the pending commit; if the server did take it, the device
  rejoins (rare).
- Search doesn't cover group messages yet; per-group mute (local) not built.

**READY** — 0.1 (A1–A7) plus 0.2: release plumbing, Settings, presence/last seen and typing
(v1.2), chat polish, the design system pass, and **contract v1.3**: RisiCloud (Keycloak) sign-in,
fingerprint-unlocked tokens and the release-only in-app updater, and **contract v1.4**: one-time
SMS phone verification. Gate green on `main`:
`cd android && ./gradlew assembleDebug testDebugUnitTest assembleRelease` (109 JVM unit tests).
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
- Emulator: the debug default server URL is `http://10.0.2.2:4400`. Change it on the login
  screen ("Server: … · Change") or in Settings; phones use `https://risime.risicloud.ai` (the release default).
- USB phone: `adb reverse tcp:4000 tcp:4400`, then set the server URL to `http://127.0.0.1:4000`.
- Cleartext is allowed only for `10.0.2.2` and `127.0.0.1` (debug and release).
- Login: phone (defaults to `+94`, normalised to E.164 with libphonenumber, local `07…` numbers
  read as Sri Lankan) + allowlisted email → code from the server log
  (`tmux capture-pane -p -t risime-server -S -500 | grep "DEV OTP" | tail -3`).

## Sign-in (contract v1.3; decisions 014, 019)
- The login screen asks the server `GET /api/v1/auth/config`:
  - **release** builds show "Sign in with RisiCloud" when `oidc` is listed, and the dev OTP form
    only while it isn't (the interim);
  - **debug** builds also show "Developer sign-in (OTP)" whenever `dev` is listed. Settings →
    "Sign-in mode (debug)" can force either.
  - A pre-v1.3 server (404) means dev only.
- Default server: release `https://risime.risicloud.ai` (spark2, Caddy → 127.0.0.1:4000), debug
  `http://10.0.2.2:4400`. Installs that stored the old, never-live default
  `https://risicloud.ai/risime` are moved to the new one once on upgrade (DataStore migration);
  user-chosen URLs are kept. Downloads (updater) stay on `https://risicloud.ai/app/risime/`.
- RisiCloud sign-in uses AppAuth (PKCE S256, Custom Tabs, scopes
  `openid email profile offline_access`). Redirects: `ai.risicloud.risime://callback` and
  `ai.risicloud.risime.debug://callback`; post-logout `…://logout`. `GET /me` runs right after.
- **Tokens:**
  - The access token and the unlocked refresh token live in memory only.
  - The refresh token and ID token are sealed in `noBackupFilesDir/tokens.bin` (AES-256-GCM data
    key wrapped by a Keystore RSA-OAEP key). The key needs a fingerprint for every use (or the
    device PIN on API 30+) and is invalidated by a new enrolment.
  - There is one prompt per process start (the "RisiMe is locked" screen); rotated refresh tokens
    are stored without one.
- The socket sends `Authorization: Bearer` on the upgrade. `auth:refresh` goes about 60 s before
  expiry (from `expires_in`). `auth:expired` and refused upgrades lead to refresh/`GET /me`, then
  a reconnect.
- **`GET /me` outcomes:**
  - `401` → one refresh, then sign in again (chats kept);
  - `403 not_allowlisted` → full screen with "Use another account" / "Sign out";
  - `409 identity_conflict` → full screen with "Sign out".

  These screens show the server's message verbatim.
- **Logout:** revoke the refresh token, then Keycloak `end_session` in the browser, then delete
  the key, then wipe chats. Auth trouble never wipes; another user signing in does.

## E2EE phase B (decisions 036, 037)
- **The real engine** (`UniffiMlsEngine` over risime-mls-ffi) loads only when the server serves
  attestation keys. The pilot answers `mls_unavailable` today, so release behaves exactly like
  v1.6.
  - Trusted keys = `BuildConfig.MLS_PINNED_KEYS` (empty until rollout) + the server's keys.
- **Release packages the core** for arm64-v8a, x86_64, armeabi-v7a and x86, with compressed
  native libs: release APK 12.7 → 19.9 MB.
- **Registration with the MLS key** → attestation (verified in the core) → key packages topped up
  (also after every Welcome and on the low signal).
- **Membership executor:** add/remove after the named-committer delay. A `409` catches up and
  re-checks.
- **Real-crypto JVM tests** (`src/testCrypto`, host build via JNA): `RealMlsPipelineTest` (6
  tests). `LiveE2eeInteropTest` runs when the interop config has an `"e2ee"` block.

## E2EE phase A (contract v1.7; decisions 033, 035)
Built against an `MlsEngine` interface; the real core arrives in phase B. Until then the app
behaves exactly like v1.6 and the dev banner stays.
- **Census:** the socket sends `device_id` and `app_version`.
- **Device registration:** with the MLS key once the engine exists (`push_token` may be null).
  `503 mls_unavailable` falls back to a push-only registration.
- **Storage:** Room v3. `mls_kv` (sealed values, savepoints inside the message transaction) and
  `mls_pending`. The DB key is wrapped by Keystore without user authentication. Backup and
  device transfer exclude everything.
- **Pipeline and outbox:**
  - strict per-group order, welcome / commit / membership rules, sender check, pending replay;
  - the outbox encrypts at send time, with the `stale_epoch` / `e2ee_required` catch-up loops;
  - the upgrade on chat open.

  All of this is fake-tested.
- **UI:** "🔒 End-to-end encrypted" in the chat header, the "Encrypted message" hint, and the
  "Not end-to-end encrypted yet: <name> needs to update / waiting for <name>'s phone" strip.
- **JVM real crypto:** `cargoBuildHost` plus the JNA jar. `HostMlsSmokeTest` runs `self_test()`
  on the host build (spark2: "ok: epoch 3").

## E2EE groundwork: the native MLS core in debug builds (decisions 012, 031)
- Debug builds made on spark2 (where Rust and the NDK exist) package `libuniffi_risime.so`
  (arm64-v8a, x86_64) and JNA, built by `cargoBuildAndroid` from `crypto/risime-mls-ffi`.
- Without the toolchain (the laptop), the debug build skips it.
- Release builds never contain it (`CryptoPackagingTest`).
- Settings → About → **Crypto self-test** (debug only) runs the MLS lifecycle in-process.

## Push notifications (contract v1.5; decision 026)
- **Off until Harsha's Firebase config exists.**
  - With `android/app/google-services.json` present at build time, the `google-services` plugin
    applies automatically and `BuildConfig.PUSH_CONFIGURED` is true. The file is gitignored and
    copied in from `~/risime-keys/`.
  - It must contain **both** clients, `lk.codegen.risime` and `lk.codegen.risime.debug`, or the
    plugin fails the debug build.
  - Without it, everything below is a no-op: no Firebase init, and no device registration.
- **Wake-up only:** the FCM data message `{"type":"inbox"}` triggers an expedited WorkManager
  sync (unique, coalesced). The sync opens a short background channel connection (join + `sync`,
  delivered acks), then refetches `/friends`.
- **Local notifications:** sender name and preview come from the local DB; nothing from the
  payload is shown.
  - One notification per chat, grouped under a summary; the last 5 lines; tap → that chat.
  - Channels: Messages (high), Friend requests, Background sync (min).
  - Opening a chat clears its notification. Only messages that arrived after the app went to the
    background are notified.
  - A new incoming friend request posts "New friend request".
- **Fingerprint-locked sessions** (after the process died) can't sync in the background. They get
  a content-free "New messages — Open RisiMe to read them".
- **Device registration:** `PUT /me/devices/{device_id}` (a stable per-install UUID) once signed
  in and verified, and on every FCM token refresh. `DELETE` at logout.
- **Android 13+:** a rationale dialog once after sign-in, then the system prompt. "Not now" or a
  denial is respected; chat works without push.

## Invites and friends (contract v1.6; decision 029)
- **Friends replace contacts.**
  - `GET /friends` is the source of the local rows. A pre-v1.6 server's `/contacts` is used as a
    fallback.
  - The list is refetched after every (re)join and on the `friend` signal (debounced).
  - Presence is watched for friends only.
  - A former friend keeps their row (`friend = 0`), so an old chat stays visible but read-only.
- **Chats screen:** the tabs are **Chats** and **Requests**, with a badge showing the incoming
  count. An **Add friend** button sits bottom right, and **Invites** is in the ⋮ menu.
  "vouched by <name>" appears on rows and in the chat header.
- **Add friend:**
  - Enter a phone (normalised to E.164). The reply is always the same ("Request sent to …").
  - Then "Not on RisiMe yet? Send an invite": enter a name and the email they'll sign in with, and
    the Android share sheet opens with the server's `subject` and `share_text`. The server never
    messages invitees.
  - Invites list: Share again / Revoke.
- **Requests:**
  - incoming: Accept / Decline / Block (each confirmed);
  - sent: Cancel;
  - blocked: Unblock.
- **Not friends:**
  - the chat shows "You're not friends with X any more" with **Add friend** instead of the
    composer;
  - typing isn't sent;
  - a `not_friends` send stays "Not sent — you're not friends", with no Retry.
- **Room DB v2** (first real migration): `contacts.friend` (default 0) and
  `contacts.vouched_by_name`. Existing rows start as `friend = registered` until the first
  `/friends` refetch. `Migration1To2Test` runs the SQL on real SQLite against the exported
  schemas.

## Phone verification (contract v1.4; decisions 020, 021)
- When `GET /me` says `phone_verified: false` (or any call answers `403 phone_unverified`), the
  app shows **"Confirm your phone"**:
  - the masked allowlisted number, with "This number comes from your company's RisiMe
    allowlist…";
  - **Send code**, then a 6-digit field (SMS autofill hint, submits on the 6th digit);
  - Resend after 60 s, or after `Retry-After` on a 429;
  - Sign out.
- **Errors:**
  - wrong code (with "N attempts left" when the server sends it);
  - expired → send a new one;
  - too many attempts;
  - too many codes (wait N min);
  - `409 already_verified` → continues;
  - SMS unavailable → retry;
  - offline.
- **Gate order:** update required → blocked → locked → confirm phone → chats. Nothing connects
  until the phone is verified, and verification problems never wipe data.
- An absent `phone_verified` (pre-v1.4 server) counts as verified. Dev-login users are always
  verified.

## In-app updater (release only; decisions 016, 019)
- `https://risicloud.ai/app/risime/version.json` is fetched at start and at most every 6 h in the
  foreground. A newer `versionCode` shows an "Update available" bar. `required: true` shows a
  blocking "Update required" screen; the dev banner and "Sign out" stay.
- The APK downloads only from https URLs under that base, into app-private storage. It is then
  verified:
  - sha256;
  - the signing cert equals the pinned `da7b98…9e2e` and the advertised `certSha256`;
  - package `lk.codegen.risime`;
  - `versionCode`.

  It installs through a PackageInstaller session; the first time, Android asks to allow "install
  unknown apps".
- `VersionJson` holds every field name; the format is a draft until RisiWork's example arrives.
  Debug builds never self-update.
- **"Update required" gate (P0 fix):** the notes scroll in the space left; a bottom bar is always
  on screen with Update (Retry after a failure), "Open download page"
  (`https://risicloud.ai/app/risime/`) and Sign out. All colours come from the theme. The optional
  `summary` field is shown if present, otherwise the notes with Markdown stripped to plain text.
  Download, checksum/certificate, cancelled and failed installs show a readable error. The optional
  banner also has "Open download page". Robolectric Compose tests cover 320×480 dp at 2× font
  scale, light and dark (decision 040).

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
- The app theme is an AppCompat descendant, and AppAuth's `RedirectUriReceiverActivity` is
  themed `Theme.AppCompat.Translucent.NoTitleBar`. `MergedManifestThemeTest` checks both merged
  manifests, so a library activity that inherits a non-AppCompat theme fails the gate.
- **No live Keycloak test yet:** the `risime` client doesn't exist. The browser hop,
  BiometricPrompt, Keystore and PackageInstaller only run on a device.
- Devices without a secure lock screen keep tokens in memory only, so they need a browser sign-in
  after every process start.
- Keycloak's default 5-minute access tokens mean an `auth:refresh` about every 4 min while in the
  foreground.
- The updater's `version.json` format may change; it doesn't verify release notes or the date.
- Search is a plain substring match (no ranking, no diacritics folding), newest 100 messages.
- Message order in a chat is by local time (send time for mine, receive time for theirs).
- `seen_events` is never pruned (one small row per event).
- Server cursor race (see `docs/status/server.md`) can, rarely, skip an event after a reconnect.
- `bad_request` (contract v1.1) and any unknown reason are treated as permanent send failures.
- `PROTOCOL_VERSION` (1.6) is checked against the PROTOCOL.md header by a unit test, so a contract
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
18. Sign-in mode (interim, server without OIDC): the release build shows only the phone/email
    form; the debug build shows the same. With `OIDC_ENABLED` and `DEV_LOCAL_AUTH` both on, debug
    shows the RisiCloud button and "Developer sign-in (OTP)", and release only the button.
19. **Once the Keycloak `risime` client exists:** "Sign in with RisiCloud" opens a Custom Tab on
    risicloud.ai and takes the email code. You come back to Chats. Then:
    - kill the app and reopen: "RisiMe is locked" plus the fingerprint prompt (PIN allowed on
      Android 11+), then Chats with no browser;
    - cancel the prompt: you stay on the locked screen; "Unlock" prompts again.
20. Keep the chat open for over 5 min: messages keep flowing (`auth:refresh` every ~4 min, no
    reconnect storm in the server log).
21. Enrol a new fingerprint, then reopen: "Sign in again — your chats are kept"; after browser
    sign-in, the old chats are still there.
22. A RisiCloud account whose email isn't allowlisted shows "Not on the RisiMe allowlist" with
    the server's text; "Use another account" opens a fresh login form. An identity conflict
    (phone bound to another account) shows the conflict screen with only "Sign out".
23. Sign out (Settings): the browser flashes the Keycloak logout and returns. Reopen: the login
    screen, with no locked screen. In the Keycloak admin, the offline session is gone.
24. Updater (release build, after a newer nightly is published): "Update available" →
    Update → allow "install unknown apps" once → Update → the system install dialog → the app
    restarts on the new version with chats and sign-in kept. With `--required`, a blocking
    screen appears instead. Tampering (wrong sha in `version.json`) gives "The downloaded update
    failed its security check (checksum mismatch), so nothing was installed" with Retry.
25. Phone verification. The server runs `PHONE_VERIFICATION=required` with `SMS_MODE=log` until
    the "RisiMe" sender ID is approved, so take the code from the server log:
    `tmux capture-pane -p -t risime-server -S -500 | grep "DEV OTP" | tail -3`.
    - Sign in with RisiCloud as a not-yet-verified user: "Confirm your phone" appears, with the
      masked number and no Chats.
    - Send code → "Resend code in 60 s" counts down.
    - Wrong code: "Wrong code — N attempts left", and the field clears.
    - Right code (or paste it): it submits by itself, and Chats loads with contacts and
      connection.
26. Kill and reopen during verification: fingerprint unlock, then "Confirm your phone" again (no
    Chats).
27. Request codes until the server limits you: "Too many codes requested. Try again in N min",
    and the button stays disabled for that long.
28. An admin changes your allowlisted phone while you're in Chats: the socket drops, then "Confirm
    your phone" with the new masked number. Local chats are still there after confirming.
29. With an update marked `required` published, the update screen covers the phone screen; Sign
    out still works.
    - **Gate, dark mode, long notes (P0 fix):** phone in dark mode, display size and font at their
      largest, a `required` release with long Markdown notes. The title and subtitle are light on
      dark and readable. The notes show no `#`/`**`, and they scroll. Update, "Open download page"
      and Sign out stay visible at the bottom the whole time. Turn on airplane mode → Update →
      a "no connection" error and Retry. Cancel the system install dialog → "Install cancelled"
      and Retry. "Open download page" opens the browser on risicloud.ai/app/risime/.
30. Server URL: a fresh release install shows "Server: https://risime.risicloud.ai" on the login
    screen and reaches the server with no tunnel. An install upgraded from a build that stored
    `https://risicloud.ai/risime` also shows the new URL; a URL you typed yourself is kept.
31. **Keycloak redirect (crash fixed after v0.2.0-nightly.3):** "Sign in with RisiCloud", enter
    the email code; Keycloak redirects back to the app **without a crash**, and you land on
    "Confirm your phone" or Chats. On an Android 8–9 phone (API 26–28) the fingerprint unlock
    dialog also opens without a crash (it's an AppCompat dialog).
32. Friends: on a fresh sign-in with no friends, Chats shows "No friends yet…" with Add friend.
    - Add friend → enter Shenika's number → "Request sent to +94…". On her phone the
      **Requests** tab badge shows 1, with Accept / Decline / Block.
    - She accepts: both phones list each other within a second, and the chat works (presence dot,
      typing).
33. Invite: Add friend → a number not on RisiMe → "Send an invite" → name + email → the share
    sheet opens (WhatsApp/SMS) with the subject and the text containing the link and the email.
    - The invitee installs from the link, signs in with that email: they land in Chats with you
      as a friend, and you see "vouched by <you>" on their side until they verify their phone.
    - Invites (⋮ menu) shows the invite as accepted. A second, pending one can be revoked.
34. Block: on the Requests tab, block an incoming request (confirm). Their new requests never
    show. Unblock is under "Blocked".
35. Unfriend (from the other side, or by admin): the old chat stays visible. The composer becomes
    "You're not friends with X any more · Add friend". An old failed message shows "Not sent —
    you're not friends" with no Retry.
36. Upgrade from 0.2.0-nightly.4 (Room v1): install over it. Old chats and login survive (the
    v1 → v2 migration). Chats then shows only friends after the first refresh; a migrated chat
    partner (Harsha ↔ Shenika) is still a friend.
37. **Push** (needs `google-services.json` and server `FCM_ENABLED=true`):
    - On a fresh sign-in on Android 13+: the "Get notified of new messages?" dialog → Allow →
      the system prompt. On the server, `PUT /me/devices/<uuid>` is logged.
    - Swipe the app away, then send a message from another phone: within a few seconds a
      notification appears with the sender's name and the text (from the local DB). Tapping it
      opens that chat, and the sender sees ✓✓ (delivered).
    - Two chats → two notifications grouped under "RisiMe".
38. Push, locked: force-stop the app (fingerprint session), then send a message → "New messages —
    Open RisiMe to read them", with no content. Tap → unlock → the chat list is up to date.
39. Push, friend request: with the app in the background, another user sends you a friend request
    → "New friend request · <name> wants to be friends".
40. Push, logout: sign out. The server logs `DELETE /me/devices/<uuid>`, and further messages
    produce no notifications.
41. Without `google-services.json` (today's builds): no prompt failures, no crash at start, no
    device registration calls. Chat works only while the app is open, as before.
42. **Crypto self-test** (debug build from spark2: `scripts/install-apk`):
    - Settings → About → "Crypto self-test" shows **"ok: epoch 3, risime-mls 0.1.0
      (MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519)"** on the emulator (x86_64) and on the USB
      phone (arm64).
    - A "failed: …" line (for example `UnsatisfiedLinkError`) is the finding to report.
    - The release build has no such button.
43. Upgrade from 0.2.x (Room v2 → v3): install over it. Chats, friends and login survive.
    Everything looks as before (no MLS core yet), with no lock and no strip.
44. (Needs E2EE on: an attestation key on the server, and the key pinned in the build.) Two v1.7
    phones that are friends: opening the chat shows
    "🔒 End-to-end encrypted" within a second or two. Messages flow, and the server log shows
    only ciphertext. With one phone still on 0.2.x: "Not end-to-end encrypted yet: <name> needs
    to update", and plaintext keeps working.
45. E2EE off (today's pilot): a 0.3 build behaves exactly like 0.2. No lock, no strip; the
    server log shows `GET /mls/attestation_keys` → 503 once per sign-in and no `PUT` with `mls`.
46. E2EE on, multi-device: sign in on a second phone with the same account. Within about 30 s
    your first phone (the named committer) adds it. The new phone shows the lock and receives new
    messages; old history isn't shared (decision 032). Sign out on the second phone: it's removed
    from the group, and new messages don't reach it.
47. E2EE on, 32-bit phone (armeabi-v7a): it registers with MLS (the server shows `mls` for its
    device), and a chat with it becomes encrypted.
