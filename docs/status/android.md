# Android status — 0.2 nightlies

## Decision 064 review fixes — READY (895 unit tests, 0 failed)
- A Keystore error while reading the vault (keystore2/StrongBox busy after boot) is `Load.Transient`: blob and key
  kept, `RESTORING` retried with backoff 1 s → 60 s (log `vault not readable now … retry #n`). Only a failed GCM
  tag, `KeyPermanentlyInvalidatedException`, an unknown format or a genuinely absent key (`!containsAlias`) clears.
- Vault writes: fsync + atomic rename (+ directory fsync); a failed write at sign-in or on rotation is kept and
  written again on the next refresh (≤ every 30 s, every forced refresh).
- "Show content" off holds before the lock settings load (a push-started process reads them first; unreadable in
  3 s → hidden). The lock turns itself off only for NONE_ENROLLED / NO_HARDWARE; a busy sensor keeps it on and
  shows "Fingerprint unavailable — try again" (lock and migration screens; the old vault is kept).
- Lock on: no Recents thumbnail (`setRecentsScreenshotEnabled(false)` on 33+, FLAG_SECURE below) on MainActivity
  only, never CallActivity; undone when the lock is off.
- Calls: `refreshService()` decides and stops under `serviceLock` (wake-ups counted), so no stop lands between a
  push's start and its `startForeground`. `CallPushTest.theStartUpCleanup…` now asserts ordering (10/10 green).

## Decision 064: no mandatory lock, tokens readable in the background, optional fingerprint lock — READY (real phone to confirm)
- **Token vault** (`data/auth/SessionVault.kt`): the token set (refresh + ID token) is sealed with AES-256-GCM
  under the Keystore key `risime_session_aes` (StrongBox, falling back to the TEE on `StrongBoxUnavailableException`;
  no user authentication, not invalidated by an enrolment; random IV; AAD = `<alias>|v2`), in
  `noBackupFilesDir/session.bin`. `AuthManager.restore()` reads it at process start with no UI, so a push-started
  process, the workers and the call wake-up always have a bearer (`SessionState.READY`). Access tokens stay in memory.
- **Migration (rule 9):** a pre-064 vault (`tokens.bin`, the fingerprint-bound RSA key) puts the app in
  `NEEDS_MIGRATION`: the next normal open shows one prompt, **"Confirm to finish updating RisiMe"**; the set is
  re-sealed under the new key and the old key + blob are deleted. Cancel/failure keeps the old vault and asks
  again next open (never a sign-out). An invalidated old key → sign in again, chats kept (trigger
  `key_invalidated`). A memory-only install (no vault) → sign in once more (trigger `no_stored_session`), then it
  stays. While unmigrated the app behaves as before: no background bearer, the content-free "New messages"
  notice and the nameless blind ring (decision 051 option B) — those two paths now exist only for that case.
- **Optional app lock** (UI gate only, `data/lock/AppLock.kt`, `ui/lock/AppLockUi.kt`): Settings → Privacy →
  **Fingerprint lock** (off by default; the row exists only when `canAuthenticate(BIOMETRIC_STRONG) == SUCCESS`;
  turning it on asks for one fingerprint), **Automatically lock** Immediately / After 1 minute / After 30 minutes
  (elapsedRealtime since the app left the screen, persisted so a push-started process knows it; a reboot
  locks), **Show content in notifications** (off → "New message", no name or text; friend requests "New
  notification"). Locked screen: the brand mark + "Unlock with fingerprint" (prompts on show). Settings live in
  DataStore (`app_lock_*`). Calls ring with the caller's name and can be answered over it (the call screen is
  its own activity); after the call the lock remains. A fingerprint removed later turns the lock off at the next
  start/resume (log `RisiMe lock: fingerprint no longer available`). It never signs out, wipes or uses the network.
- **Sign-out diagnostics:** every sign-out logs `RisiMe auth: sign-out trigger=<invalid_grant|key_invalidated|
  vault_unreadable|no_stored_session|unauthorized|user_logout|user_logout_delete|escape_screen|switch_server|
  another_account|sign_in_failed>`; a sign-in logs `RisiMe auth: signed in, refresh token typ=Offline exp=none`
  (the payload's `typ`/`exp` only). Notifications health has an **Account** row for OIDC sessions: "Stays signed
  in (offline session)" ✓ or "Short session — ask the admin" ✗ (typ=Refresh), with the last sign-out trigger.
  A 401 while the Keycloak refresh is failing for a network reason no longer signs out (the socket retries).
- **Tests:** 883 JVM tests (8 skipped), 0 failed. Seen flaky once in the gate and 1 of 3 `--rerun`s:
  `CallPushTest.theStartUpCleanupNeverStopsTheServiceAPushStarted` (a timing race in the test's service
  shadow; code untouched by this work) — to look at separately. New: `AuthManagerTest` (vault round trip without auth,
  fresh IV, tamper/AAD rejection, process death → silent restore + refresh, transient failure keeps the
  session, invalid_grant ends it, migration done / cancelled / no new key / invalidated / memory-only, sign-out
  clears both vaults), `AppLockTest` (default off, Immediately/1 min/30 min, reboot, biometric removed → off,
  never touches the session, notification content rule), `AppLockUiTest` (Privacy rows hidden without
  biometrics, options appear when on, lock screen logo/auto-prompt, nothing on it signs out),
  `AuthLogicTest` (gate order with migration and app lock, refresh-token typ/exp decoding),
  `NotificationHealthTest` (Account row).
- **Device gate (`scripts/push-device-test`, root-delegated):** B now signs in with an **OIDC-style session** by
  default (`PUSHTEST_B_AUTH=oidc`): a stand-in issuer (discovery, JWKS, refresh-grant endpoint) and the temp
  server with OIDC on; Redroid has no browser, so the debug build's shell-only broadcast
  (`lk.codegen.risime.debug.OIDC_SIGN_IN`, `android.permission.DUMP`) hands the issuer's tokens to the real
  sign-in path. Checks: force-stop + restart → chat list, no lock, no sign-in, a silent refresh from the vault;
  the Recents-removed push wake-up refreshes with no UI; no sign-out trigger during the run.
  `scripts/upgrade-test`: no "Unlock with fingerprint"/migration prompt after an update.
  **Run: PUSHTEST OK** (own instances `_lk`, B on OIDC): restart after force-stop → chats, no lock, no sign-in,
  silent refresh; message 416 ms / ring 671 ms (Home, deep idle); Recents-removed 1106 ms / 1356 ms with the
  push-started process restoring the OIDC session and refreshing with no UI; message+call 653/2600 ms;
  restricted 411 ms; registration failing 3/3 rows; 5 silent refreshes, no sign-out. upgrade-test not run
  here (needs a staged candidate; the nightly gate runs it).
- **Needs a real phone (Redroid has no biometrics):**
  1. Upgrade a phone signed in with a fingerprint session (≤ nightly.35): the first open shows "Confirm to finish
     updating RisiMe" once; after it, kill the app / reboot: it opens to the chats with no prompt; a message
     sent while it was killed notifies with the name and text, and a call rings with the name.
  2. Same, but Cancel the prompt: the screen stays (with "Sign out (keeps your chats)"); the next open asks again;
     nothing is lost.
  3. Enrol a new fingerprint before opening the updated app: "Sign in again — your chats are kept", then signed
     in for good (health screen: last sign-out `key_invalidated`).
  4. Settings → Privacy → Fingerprint lock on (one fingerprint) → Immediately: Home and back → the lock screen
     prompts; Unlock. After 1 minute / 30 minutes: back within the time → no lock; after it → locked.
  5. Lock on, "Show content" off: a message from the background shows "RisiMe · New message" only.
  6. Lock on and locked: an incoming call rings with the caller's name and can be answered; after hanging up
     RisiMe is still locked.
  7. Lock on, then remove every fingerprint in Android settings: the next open shows no lock and the switch is off.
  8. Notifications health → Account: "Stays signed in (offline session)" (✗ "Short session" means Keycloak issued
     a plain refresh token: the realm/client needs `offline_access`).

## P0 background delivery (nightly.31: messages and calls only after opening the app) — READY (real phone to confirm)
- **Root causes found (each reproduced on redroid by `scripts/push-device-test`, then fixed):**
  1. **Every call push crashed the app.** `onCallPush` launched the stale-call cleanup, which stopped the
     `CallService` the push had just started, before its `startForeground` → `ForegroundServiceDidNotStartInTimeException`
     (logcat: "Bringing down service while still waiting for start foreground"), process dead, no ring. Fixed:
     start and stop ordered under a lock; the cleanup leaves the service alone while a wake-up/blind ring runs.
  2. **Dead socket kept "online" on the server.** The app closed the socket at `onStop`, but by then (or soon after)
     Doze/OEM savers cut its network, so the close frame never left the phone: the server's presence row stayed for
     the websocket timeout (60 s) and **no push** went out for anything sent meanwhile (run 5: server presence
     still `count=1` for B long after the app logged "socket closed"). Fixed: screen-off closes at once; a 5-s grace
     only for app switches with the screen on (below the 10-s cached-app freezer).
  3. **MLS events lost when a push-started process synced before the MLS core opened**: `MlsPipeline` returned
     `Ignored`, the event was marked seen and the cursor moved past it (message or call invite gone on this phone).
     Fixed: `ChatEngine` refuses such a batch untouched (`MlsNotReady`, waits ≤ 15 s for the core) and the
     socket rejoins from the unchanged cursor.
  4. The inbox sync ran only in an expedited worker (deferred in restricted buckets / out of quota) with `KEEP`, so
     a deferred request swallowed every later wake-up. Now `onMessageReceived` syncs **directly** (≤ 9 s, notifies as
     soon as the join's catch-up is applied); the worker finishes the rest and is `REPLACE`d when merely queued.
- **Also:** messages over the socket while not in the foreground notify like a push; `messages_v2` channel (high,
  `VISIBILITY_PRIVATE`, public version = sender only; a user's "off" carried over; old `messages` deleted);
  the current push token is re-registered on every foreground start (a registration before Play services
  had a token left the device unpushable); `RisiMe push:` log lines (received kind/delay/priority, sync start/live/end,
  notification posted, socket up/closed, health).
- **Settings → Notifications health** (and once per versionCode after an update when a row is ✗; never in debug
  builds or on a fresh install): notifications allowed, Messages channel, Calls channel, battery unrestricted
  (`isIgnoringBatteryOptimizations` + `isBackgroundRestricted`; Fix → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  on tap only), full-screen calls (34+ `canUseFullScreenIntent` → `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`),
  push registered (token hash vs the last token the server accepted; Retry). OEM block by `Build.MANUFACTURER`
  (Xiaomi/Huawei/Samsung/Oppo/Realme/Vivo/OnePlus component intents, app-details fallback).
- **Tests:** 846 JVM tests on main + this work, 0 failed (8 skipped): `BackgroundDeliveryTest` (grace, screen-off, socket policy,
  socket-notify rule, log line, channel carry-over), `NotificationHealthTest` (every row from its inputs, once per
  version, OEM detection), `MlsPipelineTest` (batch before the core opens: refused, then applied on the rejoin; the
  old path lost it), `CallPushTest` (the cleanup never stops a push-started service).
- **Gate `scripts/push-device-test`: PUSHTEST OK** (run under `timeout 1800`, ~6 min). B in the background, screen
  off, `force-idle`: message notification **436 ms** after the server's push, ring (CallStyle on `calls` with a
  fullScreenIntent) **333 ms**; after removal from Recents (process dead, not stopped): **1106 ms** / **1003 ms**.
  Every push came from the server's offline decision (`call ring … push_now=1 live=0`). Redroid has no Play
  services: the hook records pushes and the script delivers them as GMS would (temp allowlist + c2dm broadcast
  as root; a uid-2000 shell broadcast is dropped silently by the SEND permission).
- **Real phone to check:** the full-screen call screen on a locked phone (redroid's SystemUI doesn't launch
  full-screen intents with the display off: the notification carries it, the activity didn't open there); real
  FCM delivery in Doze; OEM autostart pages; the health screen after the update.
- **For server (request, not done):** a dead socket still hides pushes until Phoenix's websocket timeout (60 s) when
  the phone loses network without closing (tunnel, network switch). Suggest: (a) an inbox-delivery watchdog: if a
  live channel hasn't acked a pushed-eligible event within ~10 s, send the push anyway; or (b) a websocket
  `timeout` ≈ 2 × the client heartbeat (30 s → 65 s isn't better; with (a) it doesn't matter). The script fails on the
  server's `push: skipped kind=inbox user=<hash> reason=online` audit line (nightly.33) for B.
- **Review fixes (55e0a3a/c49647d review) — READY:**
  1. *(HIGH, rule 9)* "core open" and "registered" are tracked apart (`MlsCoreState`): a failed device
     registration keeps the MLS core open (decrypting needs no registration; registration retries on its
     own); offline the core opens with the last served attestation keys. `MlsPipeline` itself throws
     `MlsNotReady` (never `Ignored`) when MLS applies and the core is closed, so an event is never marked
     seen or skipped; commit/call-signal/history `runCatching`s rethrow it. `ChatEngine` applies plaintext
     events ahead of the first MLS event and stops there (no whole-batch 15-s stall). Bounded: a Welcome to
     other devices and our own echoes are still ignored; an install whose core never opened expects nothing.
  2. `onMessageReceived` no longer blocks FCM's thread: the worker is enqueued first, then the direct sync
     runs on the app scope under a ≤ 9-s partial wake lock; a call push right behind an inbox push rings at once.
     The worker skips its own socket session when a direct sync went live since its enqueue (`PushWakeTracker`).
  3. `messages_v2` copies importance, sound, vibration (pattern), lights and DND from `messages`; only the
     lock-screen visibility changes; fresh installs HIGH.
  4. Health auto-open after an update only for: notifications off, Messages/Calls channel off, full-screen
     denied, push unregistered *after* the registration settled (its result, not a 3-s timer). A Silent
     channel is a "!" hint; battery is shown but never opens it. Dismissals per row + failing state.
  5. Home → screen off → on inside the 5-s grace no longer reopens the socket (`foregroundHoldFlow`).
  - `scripts/push-device-test` (root-delegated) adds: message then call at once in deep idle; restricted
    (redroid has app standby off — every app EXEMPT — so `RUN_ANY_IN_BACKGROUND ignore`, the user-facing
    "Restricted", stands in); exactly one notification pass per message; a cold start by push while a
    filtering proxy answers 503 to every device-registration PUT (sent texts vs B's Room rows).
  - **Tests:** 863 JVM tests, 0 failed (8 skipped): `MlsCoreStateTest` (5), `MlsPipelineTest` (+5: refusal
    after the gate, commit not swallowed, plaintext ahead applied, not-for-this-device bounded, no-MLS
    ignored), `BackgroundDeliveryTest` (+5: channel carry-over, hold flow, wake tracker),
    `NotificationHealthTest` (auto-open rules, hints, dismissals per row/state).
  - **PUSHTEST OK** (spark2, ~7 min, on server eae4dcf with the push watchdog): message 437 ms / ring 659 ms
    (Home, deep idle); after Recents removal 1068 / 1383 ms; message+call at once: ring 696 ms after the
    call push (message 2473 ms); restricted: 468 ms; registration failing (3 × 503): messages 1120 / 631 /
    647 ms after their pushes, 3 sent = 3 Room rows; one notification pass per message everywhere.

## P0: call audio routing (route button greyed out, calls stuck on the earpiece) — READY
- **Cause:** `routeButtonEnabled` needed Telecom to list > 1 endpoint (or an empty list while ACTIVE); real phones
  report empty, late or one-entry lists (only the earpiece), so the button stayed grey and the call on the earpiece.
- **Fix (`CallRoutes.kt`, `CallManager.kt`, `CallScreens.kt`):** routes = Telecom's endpoints merged with
  AudioManager's devices (`mergeRoutes`; the speaker is always offered); a route Telecom doesn't list (or refuses)
  goes through AudioManager (`setCommunicationDevice` on API 31+, speakerphone/SCO below), and its current route is
  then AudioManager's. The button is enabled in every in-call phase, its icon/label is the real route
  (Phone/Speaker/Bluetooth/Headset); a tap toggles earpiece↔speaker, or opens the picker (Bluetooth / Headset /
  Phone / Speaker, current checked) when a BT device or headset is there.
- **Policy (`routeTarget` + per-call `RoutePolicyState`, pure):** video defaults to the speaker (unless BT/headset);
  BT takes the route at start and whenever it connects (even over an earlier pick); a BT disconnect falls back to
  the speaker (video) / earpiece (voice); the user's pick is never overridden otherwise; a refused move is asked at
  most 3 times. `CallManager.onCallVideoChanged(callId, video)` is the v1.23 voice→video hook (→ speaker; called
  from `onState` when a snapshot's video flag changes). Group calls use the same path (LiveKit has
  `NoAudioHandler`; routing is Telecom/AudioManager for both).
- **Logs:** `calls: route current=<K> available=<K,…> via=telecom|audio` (call-device-test's parse unchanged),
  `calls: route decision (<why>): → <K>|keep: <reason> [...]`, `calls: route set <K> (<reason>) via telecom|audio: …`.
- **Tests:** `RoutePolicyTest` (24), `CallScreensTest` (route button enabled in every in-call phase with 0 or 1
  Telecom route; picker; real-route label), `TelecomEndpointFanOutTest` on the new policy.
- **NOTE for root (call-device-test):** `android/scripts/calltest-route.sh` holds the new steps; run with
  `CALLTEST_EXTRA=android/scripts/calltest-route.sh scripts/call-device-test` or source it from the script:
  call R1 voice → tap "Audio output" until `current=SPEAKER` (user decision logged) → `audio_ok`; call R2 video
  B→A starts on SPEAKER (`route_ok`) with `audio_ok`. Run on spark2 (`CALLTEST_FAST=1`, own redroids): **CALLTEST
  OK** (calls 9/12/13/R2 on SPEAKER, audio both ways everywhere). Redroid lists only the speaker
  (`available=SPEAKER`; R1 already starts on it, so the tap stays SPEAKER), so the toggle and BT paths need a real
  phone.
- **Needs a real-phone check:** voice call → tap → speaker and back; video call starts on the speaker; BT buds
  connect mid-call → BT, disconnect → speaker/earpiece; group voice/video the same.
- **Review fixes (READY):** SCO stopped on clear/release below API 31 (`clearAudioManagerRoute`); an ending call
  gives its AudioManager route back at once (the still-running call re-reads and re-asks); a route change made
  outside RisiMe (car, headset button, system switcher) with no request of ours pending and the same devices is
  the user's pick (BT newly connecting still wins); AudioManager routes are read back
  (`route set <K> (…) via audio: requested=<K> actual=<K>`, mismatch = failed try, then Telecom's endpoint if
  untried); no core-telecom → an AudioManager-only handle; all route work on one serial lane, device callbacks
  unregistered when no call is left. Tests: `RoutePolicyTest` (32), `AudioRouteReleaseTest`, `CallManagerCleanupTest`.
  Real phone: also check "move from BT buds to Phone in the system output switcher → stays on the phone".

## v1.23: WhatsApp-style emoji panel and attachment sheet — READY
- **Emoji panel:** the smiley in the composer (DM and group chats) now toggles between the keyboard and an inline panel in the keyboard's place (height = the tallest keyboard seen, minus the nav bar; 280dp before one is known). It reuses the emoji2 `EmojiPickerView` (category tabs Recent/Smileys/People/Animals/Food/Activities/Travel/Objects/Symbols/Flags, scrollable grid, skin tones; no new dependency), plus a bottom row: "ABC" (back to keyboard) and backspace (a whole grapheme via `deleteBeforeCursor`). Picks insert at the cursor and the panel stays open; Back closes it; tapping the field brings the keyboard back. Recents are now DataStore-backed (`risime_emoji`, max 32, most recent first) via `RecentEmojiProvider`, shared with the reaction picker. Code: `ui/chat/EmojiPanel.kt`, `Composer` in `ChatScreen.kt`. The old modal emoji sheet in the composer is gone (the reaction "+" still uses `EmojiPickerSheet`).
- **Attachment sheet:** a 3-column grid of round coloured tiles: Gallery, Camera (both as before: Photo Picker / `TakePicture` into the cache FileProvider, then the encrypted photo path), Document, Location, Contact (dimmed; tap shows the "Coming soon" notice bar). The "+" button gating is unchanged (images only in e2ee chats; metadata stripping unchanged). `AttachOption.visible(canCamera)`.
- **Tests:** `EmojiPanelLogicTest` (recents order/cap/encode, panel height, sheet items and which are wired); existing `ComposerEmojiTest` (Robolectric) and `ComposerEditTest` (grapheme backspace) cover the panel flow. Gate: 797 unit tests, 0 failures.
- **Not verified on a device** (no emulator on spark2): keyboard/panel switching feel and the panel height on a real IME.
- **For root:** no new library, no decision note needed.

## Fix: a member never commits a reinstalled admin's group re-add ("a commit is already pending") — READY
- **Cause (reinstall gate, nightly.30/31, C's evidence):** the group-op runner is `groupOpsRun.collectLatest { runDue() }`, and
  every `syncGroups()` kicks it (C syncs twice on start: on Live and again after MLS registration). A kick **cancels** a running
  pass. C's first attempt claimed A's key package, built the commit (staged in the core, persisted by Room's transaction), and was
  cancelled before the POST: no reject on that path, so the staged commit stayed. Every later attempt claimed (burned) another of
  A's key packages and failed `build: a commit is already pending for this group`; a staged commit also blocks `encrypt` in that
  group. `runDue`'s `runCatching` swallowed the cancellation and only the last error was kept. The DM re-add is a separate MLS
  group (no shared state; the core serialises its calls); its traffic only made the cancelling kick likely.
- **Fix:** `MlsCommitGate` (per conversation, shared by the group outbox, the DM membership/op executor and the DM upgrade): one
  own commit at a time from build to the server's answer; a staged commit left when the attempt ends (exception, cancellation) is
  dropped (`commitRejected`, in `NonCancellable`); one found on entry is stale and dropped; a start-up sweep over DMs and groups
  drops leftovers from older builds. `MlsEngine.hasPendingCommit` (FFI's existing `has_pending_commit`; no crypto change). The
  runner no longer cancels a running pass (only its wait); `runDue` rethrows cancellation. A server op's retries reuse its claimed
  key packages (memory, by op id; dropped on build refusal/no key package/op end). Every attempt is logged
  (`group op commit <op> attempt n/8: <reason>`); `last_error` keeps the first error (`first (last: …)` on exhaustion).
- **Tests:** `ConcurrentReAddTest` (real core): DM re-add and group re-add of the same user at once, with an exception and with a
  cancellation between build and submit; on the old code both fail (`claims=2 lastError=build: a commit is already pending for
  this group`). Plus a stale-commit start-up sweep and `retriesKeepTheFirstErrorAndReuseTheClaimedKeyPackages`.

## v1.22 encrypted backups (§22, decision 059, P0-2) — READY
**READY.** Commits `264984e` (core: bundle, manager, hooks, workers), `b0279bc` (UI), and the status/label commit after it.
- **Bundle (§22.5):** `BundleExporter` writes JSON Lines per conversation in 500-row pages (header first with exact
  counts from a counting pass; `conversation` line, rows oldest first, then confirmed reactions and hidden tombstones;
  `contact` lines last: contacts with a user id plus group members). Text, image (by reference with `enc` and the
  thumbnail, reconstructed and §14.4-validated), `call_end`/`group_call` lines (always; local lines without a
  message id go with `message_id: null`), visible tombstones (and rows being deleted), §12.7 group lines
  (`sys:<event_id>`), `cleared_upto` at 100-ns precision, `hidden`. Never: MLS state/`mls_kv`, cursor, device id,
  tokens, gap rows, §13.3/§17.12 markers, outbox rows (pending/failed), media files, avatars, settings, behaviour log.
- **Restore (§22.6):** `BundleImporter`: insert-only (hard rule 9): skips an existing `message_id` or `client_msg_id`,
  a (hidden) tombstone (deletes win), anything at/before the local Clear chat watermark, malformed payloads (live
  validation); rows are `origin: "backup"` (or the line's), read, never acked/notified, outgoing keep their status;
  images re-sealed in the media row (downloadable while the blob lives); tombstone lines add the placed tombstone +
  a hidden one; conversation lines create chat_state (watermark = the later one) and a display-cache group row;
  matching gap rows are deleted and the §13.3 marker refreshed (no "history shared" line). 1 000 lines per
  transaction, progress (`backup_id`, line) persisted; a killed restore resumes, a repeat changes nothing.
  §13.3 extension in `ChatEngine`: a pre-install event whose message ids are all held adds no marker.
- **Crypto:** `BackupKeys` over `MlsClient` (inside the engine's Room transaction + lock), `BackupTools` (free
  functions) found by name. Streams: `Deflater(nowrap)` → `writer.write`; `reader.verify()` before any `read()`, then
  `Inflater(nowrap)`. A picked file is copied into app-private storage first. `NoKey` → unlock the server's record
  (when its `bk_id` is the file's) or the file header's record; `makeCurrent` only for the account key.
- **Local backups:** `noBackupFilesDir/backups/<ms>-<reason>.risimebk`, newest 2 + the latest pre-update one;
  older local keys no file needs are dropped. Daily `BackupWorker` (charging, battery not low, foreground
  `dataSync`) + an on-open catch-up 60 s after the socket is live when the last one is > 24 h old (a locked OIDC app
  has no core in the background). The silent local `BK`/`R` pair is made on the first backup.
- **Pre-update (P0-1 seam):** `Updater.beforeInstall` → `backups.beforeUpdate()`: a local backup, plus the upload
  (≤ 2 min) with server backup on; a failure posts "Backup failed — don't uninstall" (channel "Backups") and the
  update still installs. A failed install shows "Don't uninstall RisiMe: your chats are only safe if they're backed
  up." with **Back up now** in the update banner.
- **Pre-wipe:** "Log out and delete chats" and the server switch make a backup first (uploaded when server backup
  is on, ≤ 150 s); both confirmations say so and offer **Save a backup file**. The wipe then deletes the
  app-private backups and the backup settings (`BK`/`R` go with `mls_kv`).
- **Server (§22.3):** parts of 33 562 624 B with `purpose=backup&backup_id`, sizes/digests checked against the
  replies, then `POST /backups` (409 `backup_device_mismatch` → "Back up this phone instead of <device>?" →
  `replace_device`; after a server restore this phone may take over and server backup stays on).
  `PUT /backup_key` when the server has none; another `bk_id` → unlock with the recovery key/passphrase (never a
  silent new key). Reset backup key = `DELETE /backups` + forget the local keys. `GET /blobs/usage` `backup` parsed.
- **First-sign-in gate:** a sign-in with no local messages marks the install fresh; `GET /backups` with entries
  shows **Restore your chats** (newest current backup: device name, date, size) in front of the chat list with
  Restore / Restore from file / Skip (confirmed). Until restored or skipped: no server backup; the §17 history
  auto-request waits too. Empty list, 404/403 (pre-v1.22) open the gate; offline keeps it closed.
- **UI:** Settings → **Manage backups** → Backups: last local/server backup (time, size), Back up now, Export backup
  file (MediaStore Downloads on 29+, `ACTION_CREATE_DOCUMENT` on 26–28), Restore from file (`ACTION_OPEN_DOCUMENT`),
  Back up to the RisiMe server (+ Use mobile data), Show recovery key (behind the device credential when the phone
  has one), Change recovery key, Reset backup key. Recovery key shown once with Copy (clip marked sensitive),
  confirmed by typing groups 2 and 7; optional passphrase with the floor (core rules + the bundled list of the
  10 000 most common passwords, `assets/common-passwords-10k.txt`, SecLists, MIT). Live "Check the recovery key
  for a typo" while typing.
- **Tests:** gate green: **786** JVM tests, 0 failed (8 skipped). New: BundleRoundTripTest (Room: every line type,
  nothing from the never list, fresh-phone round trip with equal per-conversation counts, merge into a non-empty
  phone, deletes win, Clear chat watermark, idempotent re-import, killed restore resumes, gap rows/marker, another
  account rejected, unknown types skipped), BackupManagerTest (MockWebServer §22.3 server: retention, export →
  new phone → recovery key, wrong key, tampered file, parts + commit, the gate blocks uploads until restore, server
  restore, device rule + replace, no silent key change, pre-update hook incl. switch off, pre-wipe, reset key),
  BackupCoreTest (real core: 44 vectors, recovery-key input + floor, a file from one phone on a reinstalled one,
  NoKey/WrongKey/WrongAccount, read before verify, tampered → Integrity, rotate, forget), BackupUiTest (Robolectric:
  Backups screen, key show-once + two groups, secret entry key/passphrase, gate + skip confirmation, floor),
  ContractExamplesTest types all 19 v1.22 examples (placeholders gone) and re-encodes the commit body and every
  bundle line byte for byte.
- **Redroid proof (Android 14, instance `-bk1`, temp server from main `b0279bc` incl. server v1.22, port 4171):**
  1. A signs in, sends 3 DMs; Back up now; Export backup file → `Download/risime-backup-2026-10-08.risimebk` (a real
     `RISIMEBK` v1 file); recovery key shown, two groups typed back.
  2. `adb uninstall` → install → sign in: the inbox replay alone brought 12 rows; Restore from file (system picker)
     with a mistyped key ("Check the recovery key for a typo"), then the key in lowercase with spaces → "Restored 0
     messages" (merge: all held). Export again (12 rows).
  3. `adb uninstall`, **A's inbox purged on the server** (nothing can come from the replay), install, sign in: 0
     rows → Restore from file + recovery key → **"Restored 12 messages", counts {dm: [12,0,0,0]} = before**.
  4. Server backup on: key shown, groups typed back, a 5-letter passphrase refused by the floor, "orange tiger river
     cloud" accepted → "Backed up", last server backup shown. `adb uninstall`, inbox purged again, install, sign in →
     **"Restore your chats · redroid14_arm64_only · 8 Oct 2026, 09:45 · 2 KB"** → Restore → passphrase → chat list,
     **counts {dm: [12,0,0,0]} = before**. Then `install -r` of a newer build: counts unchanged.
  Redroid note: showing the soft keyboard hung system_server twice (blocked writing to lmkd while binding the IME;
  watchdog restart). Disabling the IME (`pm disable-user com.android.inputmethod.latin`) fixed it; `input text`
  still types into Compose fields.
- **For root's upgrade gate (`reinstall_restore_hook`):** see the report; the texts above are stable constants in
  `ui/backup/BackupUi.kt`.
- **Known limits:** no "Back up your chats" card on the chat list and no post-update prompt yet (Settings → Manage
  backups only); a switch to a *different account* can't back up the previous account's chats first (its keys are
  closed after its sign-out; the dialog's wipe still happens only on the user's confirm); group icons travel as
  `icon: null` (the name, admins and state do); pin/mute/archive/last_read don't exist locally (exported as
  false/null); the daily job needs the unlocked core, so a never-opened phone backs up on its next open.

## P0-3 video calls: no sound / speaker button greyed out — READY (real phones to confirm)
**READY.** Commits `eb25ada` (calls/) and `2c225cd` (root script `call-device-test`, changed for this task).
- **Cause:** core-telecom 1.0.1's `availableEndpoints`/`currentCallEndpoint` are `Channel.receiveAsFlow()` (each value reaches
  ONE collector). Video calls ran a second reader (the speaker coroutine) beside the UI readers, so the UI's route list could
  stay `[]` (button disabled all call), the UI could miss SPEAKER, and the speaker logic could miss the earpiece value (audio left
  on the earpiece). Telecom also saw the app as audio-only (`CAPABILITY_BASELINE`), and nothing re-applied the speaker after answer.
- **Fix:** `EndpointTracker` (CallRoutes.kt) is the only reader, started for every Telecom call, copying into per-call
  StateFlows; the UI flows copy the current call's handle. `registerAppWithTelecom(BASELINE or SUPPORTS_VIDEO_CALLING)`. Pure rule
  `videoSpeakerTarget` (video, CONNECTING/ACTIVE, no BT/wired, on the earpiece, user hasn't picked) checked on every route change
  and after answer/setActive; CallControlResult logged, one retry after 500 ms; `selectEndpoint` sets `userPicked`. The route
  button is enabled with >1 routes or an empty list while ACTIVE (AudioManager fallback toggle). Log line
  `calls: route current=<T> available=<T,…>` on every change. Debug stats add `audio_sent=`/`audio_recv=` (kind audio);
  `DtlsStats.bytesReceived` is audio-only, so the decision-054 stall watchdog now sees a silent video call.
  The v1.18 ring-text bug was already fixed (v1.19); the ongoing notification's status now also gets `video`.
- **Tests:** VideoSpeakerRuleTest (9), TelecomEndpointFanOutTest (200 reps on Dispatchers.Default; the old wiring on the same
  fake lost the endpoints or the speaker in **200/200** reps), CallScreensTest (empty routes while ACTIVE → enabled; label/state
  with [earpiece, speaker]), `userPicked` never overridden. Gate `assembleDebug testDebugUnitTest` green.
- **Redroid (`CALLTEST_INSTANCE=_p03fix`, port 4474, redroid 5761/5762): CALLTEST OK.** audio_ok on call 9, call 9 after B's Home,
  call 12 (B → A, in-screen) and call 13 (answered from the notification); `route current=SPEAKER available=SPEAKER` on both phones.
- **Real phones must confirm:** a 1:1 and a group video call start on the speaker after answering (both directions, in-screen and
  from the notification, and "Answer without video"); the speaker button is enabled, its label follows taps, and a tap to the
  earpiece sticks; a Bluetooth/wired headset keeps audio on the headset; `dumpsys telecom` shows the call as video.

## P0-1 in-app updates that testers can finish (no more uninstalling) — READY
**READY.** Commits `2c2be21` (worker, resume, checks, feedback, failures, copy, hook, test hook) and `9bd7a93` (Retry takes
the server's current offer; offline checks don't use up the throttle; MY_PACKAGE_REPLACED keeps a newer offer).
- **A, leaving the app:** download → verify → install run in `UpdateWorker` (expedited WorkManager job, foreground `dataSync`,
  "Updating RisiMe to X · Downloading… 42%" with Cancel), not in `rememberCoroutineScope()`. The UI only calls
  `Updater.start(info)` and observes `Updater.state`. The download (`ResumableDownload`) cancels the OkHttp call the moment the
  job is cancelled, keeps `files/updates/risime-<code>.apk.part` and continues it with `Range: bytes=N-` (206 append, 200
  restart, 416 complete, other bytes → whole file again); the sha256, pinned cert, package and versionCode are checked on the
  finished file every time (all decision 016 checks unchanged). A killed process: WorkManager runs the job again and it resumes.
  Retry re-reads `version.json` (a republished file or a newer nightly replaces the tapped one).
- **B, checking:** on every foreground with a 15 min throttle (was 6 h per process) and every 15 min while in the foreground,
  `If-None-Match`/ETag (304 reuses the body). A failed check doesn't start the throttle. Settings → About: app version, "Last
  checked: <time> — <result>", **Check for updates** (forced), the never-uninstall line and "Download from website".
- **C, silent update feedback:** right before `commit`, "Installing RisiMe X — the app will restart"; the release-only
  `PackageReplacedReceiver` (ACTION_MY_PACKAGE_REPLACED) posts "RisiMe updated to X — tap to open · Your chats are kept." and
  tries to reopen the app if it was in the foreground at commit (Android may block that; the notification stays).
- **D, failures:** installer statuses are handled in every state (`stateAfterInstallStatus`: Idle/Available/Failed after a
  restart or a dismissed banner use the persisted release; a stale session's late status is ignored). The last attempt
  (versionCode, step, status, EXTRA_STATUS_MESSAGE, error text, session) is kept in a separate DataStore (`risime_update`); a
  failure for a still-newer release shows again after a restart. On screen, in red, the real error: "App not installed: <what>:
  <installer message>.", "Couldn't download the update: the update server answered HTTP 503.", "… failed its security check
  (checksum mismatch / APK is signed by another key …), so nothing was installed." Every surface (banner, gate, failure
  notification, About) offers **Download from website** (https://risicloud.ai/app/risime/). Log: `RisiMe update: status=…
  msg=…` (plus `committed session=…`, `replaced, now …`). A failure while the app is in the background posts a notification.
- **E, copy:** banner, required gate, notifications and About: "Install over the old app — never uninstall: uninstalling deletes
  your chats."
- **P0-2 seam:** `Updater.beforeInstall: BeforeUpdateInstall` (`suspend fun onBeforeUpdateInstall(target)`, default no-op) runs
  after every check passed and before the PackageInstaller session; the install waits for it. A throwing hook is logged and the
  install continues (an in-place update keeps chats by itself; a broken backup must not block updates). P0-2 sets it in
  AppContainer.
- **Test hook (decision 016 unchanged for releases):** `-Prisime.updateBaseUrl=<url>` (Gradle validates: `https://host/path/`, or
  plain `http://` only for `127.0.0.1`/`10.0.2.2`; logged as TEST BUILD). `urlAllowed` takes plain http only when the base itself
  is such a loopback mirror, so the release base (https://risicloud.ai/app/risime/) never accepts http. About shows an overridden
  server in red. Not used by any script.
- **Not done (needs a contract change):** reporting update failures to the server. Proposal-worthy later; for now the failure
  is in logcat and on screen.
- **Gates:** `./gradlew assembleDebug testDebugUnitTest` green after rebasing on `a91a01b`: **744** JVM tests, 0 failed (8 skipped). New: UpdateFlowTest (10:
  check vs running/failed update, late and stale installer statuses, cold-start restore, Retry target, attempt JSON, Range
  answers, progress text), ResumableDownloadTest (4, MockWebServer: Range resume, server ignoring Range, HTTP 503 keeps the
  partial, cancel stops < 1 s and the next run resumes), UpdateLogicTest (15 min throttle + force, loopback mirror URL rule,
  error texts), RequiredUpdateScreenTest (real error + never-uninstall line on the gate).
- **Redroid (Android 14, own instance `-p01fix`, removed after):** release builds signed with the real key (local only, never
  published) with `-Prisime.updateBaseUrl=http://127.0.0.1:8480/app/risime/` as 0.2.0-nightly.30 ("old") and nightly.31
  ("new"), a throttled local mirror (Range + ETag) via `adb reverse`:
  1. Old shows "RisiMe 0.2.0-nightly.31 is available"; Update → "Downloading… 25%"; **Back** to the launcher; the notification
     went on to 92 %, 93 %…; installed silently (`committed session=… silent=true`), then "RisiMe updated to 0.2.0-nightly.31 —
     tap to open" in the shade and `status=0 msg=INSTALL_SUCCEEDED`. firstInstallTime unchanged, DataStore + DB kept.
  2. `kill -9` of the app at 10.0 MB: the job came back by itself and the mirror saw `Range: bytes=10000000-` (206); installed.
  3. version.json with a wrong sha256: banner "RisiMe 0.2.0-nightly.31 wasn't installed" + in red "The downloaded update
     failed its security check (checksum mismatch), so nothing was installed." + Retry, Download from website, never-uninstall
     line; still shown after force-stop + relaunch offline (persisted). Republished with the right sha → Retry fetched the new
     version.json and installed.
  4. Cancel in the progress notification at 11 %: stopped in the same second, 8.8 MB partial kept, banner back to "Update";
     Update → `version.json` 304, `Range: bytes=8800000-` (206) → installed.
- **For root's gate (`scripts/upgrade-test --via-updater`):** set `UPDATER_BACK_SAFE_FROM` to the first versionCode carrying
  this (nightly.31 = 20031 if that's the next tag). The error grep should add `App not installed[^"]*` and `wasn't installed`
  (the installer failure text is now "App not installed: …"; "Install failed"/"Update interrupted" no longer exist), and the
  logcat check can add `RisiMe update: status=[1-9]`. "RisiMe X is available", "Update" and "Later" are unchanged.

## v1.21 reinstalls without reset, and stale leaves (§12.12, decision 060) — READY
**READY.** P0-2 (a reinstalled only admin reset its groups; DMs reset 2 minutes after a reinstall).
- **Groups:** `rejoinPlan` lost its `RESET` branch: every device without state for the current generation rejoins,
  the only admin included. The reset decision now comes from the rejoin reply (`GroupRejoinReply`: `op`, `candidates`,
  `exhausted`) via `RejoinRules.group`: admin devices only, when `exhausted`, or `candidates: 0` for 24 h after the op's
  `created_at`; non-admins never; a reply without `candidates` (pre-v1.21 server) never. An automatic `reset` row queued
  by an older app before the update runs as a rejoin. `409 rejoin_pending` on the last-resort reset = keep waiting (op
  done, not failed). Rejoin again on sync/reconnect, on chat open and every 15 min while the waiting chat is open.
- **DMs:** the 120-s `RESET_AFTER_MS` timer is gone. `RejoinRules.dm`: reset only for `candidates: 0` or `exhausted`
  (absent = false); otherwise wait with no timeout. Rejoin on a kick (open/resume/reconnect, ≥ 20 s apart) and at most
  every 15 min otherwise. `409 rejoin_pending` keeps waiting; `generation_conflict` re-checks.
- **Texts:** "Setting up encryption on this phone…", plus "Waiting for <name> to open RisiMe" (DM, new state
  `E2eeState.RepairWaiting`) / "Waiting for a group member to open RisiMe" (group strip) once the reply has a candidate and
  no committer is online, or after a minute. The group composer stays on while rejoining (it used to be disabled): sends
  stay pending in the outbox and go out after the Welcome, never failed.
- **Cleanup ops (§12.12.6):** the executor commits a `devices` op with `added: []` named to this device: its own old
  leaves (any role) or, for admins, another user's; no claim, no Welcome. A member never commits another user's cleanup
  op (H1, reported as policy); a cleanup op listing this very device is never committed.
- **Manual reset:** group info "Reset encryption" (admins) with the §12.12.3 confirmation text; disabled while this phone
  waits to be re-added; a `409 rejoin_pending` shows "This phone is still being re-added to the group. Try again later."
  and queues a rejoin.
- **Hard rule 9:** no path deletes messages; the reset path is the unchanged `onReset` (MLS group + parked events only).
  No Room schema change (the waiting state is in memory; a restart asks the server again).
- **Gates:** `./gradlew assembleDebug testDebugUnitTest` green: **730** JVM tests, 0 failed (8 skipped). New tests cover
  every decision branch (`DmRepairTest`, `GroupOpsExecutorTest`, `GroupStoreTest`, `GroupLogicTest`), and
  ContractExamplesTest now types all seven v1.21/v1.16 DM-op examples (no placeholders left for v1.21).
- **For root's upgrade gate:** (1) the only admin of a 3-member group reinstalls with one member online → the new phone
  shows "Setting up encryption…", is re-added by the member, **no generation change** (`GET /mls/groups/{grp}` generation
  unchanged, no "Encryption was reset" line on any phone), both directions work; (2) a DM whose peer stays offline ≥ 10 min
  after the other side reinstalls → no reset, the strip says "Waiting for <name> to open RisiMe", the queued message is
  pending (clock) and arrives once the peer opens the app; (3) per-conversation message counts unchanged (hard rule 9);
  (4) with `STALE_LEAF_HOURS` lowered, the old leaf is removed by the user's own new phone (or an admin).

## v1.19 group calls with LiveKit, voice and video (§20) — READY
**READY.** Commits `f885238` (receive side: group `call:signal`, `group_call` lines, rooms API models), `8200e58`
(livekit-android 2.29.0, `callFrameKeys`, FrameKeyRing, GroupCallMachine), `52d6dbf` (LiveKit SFU, CallManager/Telecom
integration, UI, video grid; plus the v1.18 ring-text fix), and this note (group info "needs to update" list).
- **Gates:** `./gradlew assembleDebug testDebugUnitTest` green: **699** JVM tests, 0 failed (8 skipped). New: GroupCallEnvelopeTest,
  GroupCallMachineTest (13), FrameKeyRingTest, GroupCallUiTest, CallVectorsTest (real core: all **29** `call_vectors.json`
  cases, an independent HKDF-Expand of every frame key, the base64 key text, a real 3-device group agreeing and rekeying on add and
  remove; a removed device gets UnknownGroup/RemovedFromGroup). ContractExamplesTest types every v1.19 example (no
  `@pending_v1_19` left). One libwebrtc copy (`checkDebugSingleWebRtc`); no Room schema change (lines reuse `kind = 'call'`).
- **SDK (decision 057, text in the handoff report):** `io.livekit:livekit-android` **2.29.0**, whose pom pins
  `io.github.webrtc-sdk:android-prefixed` **144.7559.14** = our §16.9 pin (verified). JitPack is allowed only for
  `com.github.davidliu` (LiveKit's audioswitch fork, pinned to a commit); `protobuf-javalite` 3.25.9 compile-only (LiveKit's
  track info types); `liblivekit_uniffi.so` packaged for 64-bit ABIs only. LiveKit 2.29 uses `ConnectOptions.iceServers` only
  with an `rtcConfig`, so the app passes an `RTCConfiguration` with our coturn servers (LiveKit's own list only when we have none).
- **E2EE (§20.6):** `E2EEOptions` + `BaseKeyProvider(sharedKey off, ratchetWindowSize 0, keyRingSize 16,
  discardFrameWhenCryptorNotReady, salt "risime-call-v1")`. Keys only from `callFrameKeys` after a commit catch-up, installed
  as their base64 text at `epoch mod 16` before anything is published; rekey on every merged commit (ObservedMlsEngine hook +
  a 500-ms epoch poll), own sender cryptors switched at once (E2EEManager keeps them private in 2.29: reflection, no R8 in
  release), previous index overwritten after 10 s, wipe at the end (keys never logged or persisted). Fail closed: no
  E2EEManager → no call; a non-leaf identity is unsubscribed ("Not a member"); a track whose info says unencrypted is silent and
  never rendered; a track whose cryptor isn't OK (or never reports OK within 5 s) is "Can't verify · not played" and makes the
  device catch up commits; the badge shows only while every rendered participant decrypts.
- **Calls:** start (TURN → catch-up → `rooms start` → connect → keys → mic → durable silent `group_call started` → `call_offer`
  `mode: "sfu"`), ring (only `group_calls` devices; "Kamal · Pilot team", "Group voice/video call", Answer / Answer without video
  / Decline), `call_member` joined/declined (my other devices stop), sender-pinned `call_cancel`, busy both ways with 1:1 calls (no
  `call_busy` in groups), Join on a running line (status on chat open; an empty or gone room turns the line "… ended"), the
  starter's 45-s timeout (`call_cancel` + `ended timeout`), the last one out sends `ended hangup` with `connected_at`/duration,
  alone 10 s → leave, SFU disconnect / 20-s reconnect → leave without claiming the end, 4 h max, one Telecom call per call id,
  the foreground service, wake lock, notification and return-to-call bar as for 1:1 (decision 054). Video: grid up to 8 tiles,
  VP8 simulcast 320×180 + 640×360 with adaptive stream and dynacast, camera only while the call screen is visible, 3-s frozen
  tile → avatar, the v1.18 debug test pattern on redroid.
- **Capability:** `group_calls` only with `groups`, `calls`, `video`, `callKeysSupported` and LiveKit loaded; build switch
  `GROUP_CALLS_ENABLED` (`-Prisime.groupCalls=false` builds the §20.10 receive-only step: lines render, nothing advertised).
- **Fix (v1.18):** the incoming-call notification said "Incoming voice call" for a video call; it now says "Incoming video call"
  ("Incoming group voice/video call" for groups).
- **Redroid verification (3 phones, real local LiveKit 1.13.9 at ws://127.0.0.1:7880 via `adb reverse`, media through
  203.115.26.139 hairpin; a temp server with LIVEKIT_* from .env; script in my scratchpad, modelled on call-device-test):**
  - A, B, C sign in, A creates "Call group", messages flow; A's header shows enabled Voice/Video call buttons (`group_calls_ready`).
  - Voice: A starts; B and C ring ("ZZ Call A started a voice call" line with Join); both answer from the notification. All
    three show "End-to-end encrypted" and the other two by name. Debug stats every 5 s on each phone, for each remote:
    `cryptor=OK`, audio packets and `jitterBufferEmittedCount` rising (e.g. A ← B `audio_packets=111 jb_emitted=…`, A ← C 85, B ←
    A 108, C ← A 80 …; energy 0 because redroid's mic is silent). C leaves, B leaves, A alone leaves after 10 s → all three
    lines "Voice call · 1:02"; no Telecom call left.
  - **Fail closed:** C with `files/debug_wrong_call_keys` (XOR-ed keys). A and B: C's stream `jb_emitted=0` (no frame
    decrypts, nothing played) while A↔B `jb_emitted` 114 240/132 480; C: both others `jb_emitted=0`. UI: "ZZ Call C, Can't
    verify · not played" on A and B, both others "Can't verify" on C, and the badge withdrawn on all three.
  - **Removal mid-call:** A removes C in group info during a call. C is cut by the SFU (server `RemoveParticipant`, "disconnected
    by the SFU"), A and B install epoch 2 keys (index 1 wiped 10 s later) and keep decrypting each other (`cryptor=OK`,
    `jb_emitted` rising 688 320 → 757 440). C re-added afterwards rejoins normally.
  - **Video:** A starts a video call; B and C "Group video call" → in-activity Answer. Every phone decodes both others
    (`video_frames` 174–185 per remote in 20 s, the adaptive-stream low layer for the small tiles), grid screenshots right;
    B pressed Home → its camera stopped, A's tile froze → avatar after 3 s (rule added after this run).
  - Decline on B while C rings; nobody answers → A "Voice call · No answer", B and C "Missed voice call" (no notification).
  - 1:1 regression: `scripts/call-device-test` (main 46b43b2, the `52d6dbf` APK, isolated `CALLTEST_INSTANCE=_and`, port 4420,
    redroid `-an1/-an2` on 5750/5751): **CALLTEST OK**, 59 PASS lines, incl. call 9 (video, "Video call · 1:10" on both) and
    call 10 (voice after video).
- **What root's device test should check for group calls:** 3 redroid phones in one group, a temp server with LIVEKIT_URL
  `ws://127.0.0.1:7880` (+ API URL/key/secret) and `adb reverse tcp:7880 tcp:7880`. (1) A taps "Voice call" in the group header;
  B and C ring (calls channel notification) and show "<A> started a voice call" with Join; answer from the notification. (2)
  Within ~15 s every phone shows "End-to-end encrypted", and for every remote identity the debug line `calls: group stats
  <identity> cryptor=OK audio_packets=N … jb_emitted=M` with N and M rising between two lines. (3) Fail closed: `run-as
  lk.codegen.risime.debug touch files/debug_wrong_call_keys` on C before a call → on A and B, C's line shows `jb_emitted=0`
  and the UI "Can't verify · not played", no badge; on C both others `jb_emitted=0`. (4) Removal: A removes C in Group info →
  C's call ends within seconds; A and B log `frame keys: epoch …` for the new epoch and keep `cryptor=OK` with rising
  `jb_emitted`. (5) Everyone leaves → each phone shows "Voice call · m:ss" (or "Voice call ended" after reopening the chat when
  two left at once); `dumpsys telecom` has no RisiMe call. (6) Video: CAMERA granted; A taps "Video call"; B and C open the call
  screen and tap Answer; `video_frames` rises for every remote on every phone. (7) Nobody answers → A "Voice call · No answer",
  B/C "Missed voice call" after 45 s. UI dumps can fail while video tiles render: tap End call by coordinates or screenshot.
- **Known limits:** sibling stop (`call_member` from my own other device) is covered by JVM tests only (redroid has one device
  per user). Relay-only through coturn and public-network reachability need decision 056's ports and the Caddy `/livekit` route
  (the pilot answers 503 for rooms until then). Real camera, Flip and Bluetooth routing need a real phone. If two people leave
  within ~1 s, neither may see the room empty, so no `ended` is sent; the line reads "… ended" after the next status check. The
  call screen's four-control row is slightly too wide for a 360-dp screen (shared with v1.18 video; Mute clips). Redroid's mic is
  silent, so audio *energy* can't prove sound; `jitterBufferEmittedCount` (frames past the cryptor) is the evidence instead.

## v1.17 profile photos + group photos (§18) and v1.18 1:1 video calls (§19) — READY
**READY.** Commits `f89f5c7` (v1.17 profile + group photos, Room v10), `b54ffcf` (v1.18 1:1 video calls), plus this note.
- **Gates:** `./gradlew assembleDebug testDebugUnitTest` green: **673** JVM tests, 0 failed (8 skipped).
  ContractExamplesTest now parses and validates every v1.17 and v1.18 example (no `@pending_v1_17/18` placeholders left;
  v1.19 group-call examples stay parse-only). Redroid (`CALLTEST_INSTANCE=_vid`, 4471, `-vid1/-vid2` on 5732/5733):
  **CALLTEST OK** on all 8 voice calls (incl. kill mid-call, background answer, Home + screen off, Back), then a
  video call on the same phones (debug pattern capturer, CAMERA granted): B showed "Incoming video call" with Answer /
  Answer without video / Decline; after the in-activity Answer the debug stats showed VP8 640×480 decoded frames growing
  on both phones (A decoded 197→305, B 140→461, ~14 fps each way); B's camera off → A showed B's avatar; on → frames
  resumed; B pressed Home → B's sender stopped and A fell back to the avatar after 3 s; screenshots looked right. No crash.
- **Room v10 (additive, hard rule 9):** new tables `profile_photos(user_id PK, ver, blob_id, size, sha256, key_sealed, plain_size,
  w, h, fetched, mime, checked_at)` and `profile_photo_convs(conversation_id PK, pending_ver, due_at, dirty, leaves)`;
  `groups` gains `icon_sha`, `announced_name`. Migration9To10Test: a v9 DB with messages, an image row and a group migrates
  to exactly the fresh v10 schema and keeps every row. Wiped only by the confirmed "Log out and delete chats".
- **v1.17 receive:** `profile_photo` strictly validated (JPEG, 64–512 square, ≤ 512 KiB, `ver` ≤ server-now + 24 h), subject =
  the MLS sender only, the K1 winner rule (ver, then null, then greater sha256), key sealed (AAD `avatar`‖subject‖blob),
  the old key and cached ciphertext dropped with the winner. Never a row, unread, ack, notification, search hit or bundle
  entry. A `silent` event that is pre-install or undecryptable leaves no marker and no gap row. Downloads after the commit
  (any network except Data Saver/roaming; then on first show), size → SHA-256 → AEAD; decode JPEG-sniffed, header = w/h ≤ 512,
  off-main with a timeout; bitmaps in memory only. 404 → initials, retried daily; blocked users → initials.
- **v1.17 send:** Settings → Profile: Set/Change/Remove photo (Photo Picker, a visible square crop with pinch/drag and a
  circle guide, 512 px JPEG without metadata at q85→q65 under 512 KiB, "Choose a larger photo" under 64 px), `avatar` upload,
  3 changes/hour ("Try again later"), the small print. A change goes to every e2ee DM and group, paced 1/s, `silent: true`.
  Triggers 2/3 come from the MLS leaves (a debounced check after every commit/Welcome): DMs after the 5–30 s sibling wait
  (settled by a sibling's send with ver ≥ mine), groups dirty until the next own message or the chat opens. Remove sends
  `photo: null`, then deletes the blob once nothing is queued.
- **Where photos show:** chat list (DMs and groups), DM and group headers, chat/group info, group member lists, beside
  incoming group bubbles (first of a run), the add-members picker, search, Settings and the call screens (incoming and in-call).
- **Group photos (§18.7):** admins tap the photo in group info → Change/Remove (same picker and crop), `purpose=icon` upload,
  then a `meta_changed` commit through a new `icon` op (the icon object sealed with the DB key while queued). Local lines
  "<actor> changed/removed the group photo" (and the name line only when the name really changed).
- **v1.18:** `video` advertised only with `calls` and when the software VP8 encoder/decoder load. DM header: a video button
  next to the call button, disabled until `video_ready` ("<name> needs to update the app for video calls"). `media: "video"`
  on every `call:signal` of a video call, bound to the offer (K6); `video_not_ready` shown. SDP: §19.4 strict checks
  (exactly m=audio then m=video, BUNDLE + rtcp-mux, one fingerprint value, VP8 + its rtx only in answers, no simulcast/rid,
  at most one FID group, `b=AS` ≤ 1500, directions, the four denied header extensions); every SDP the app produces strips
  them (voice too). One sendrecv video transceiver (caller adds it before the offer; callee uses the one the offer
  created), codec preferences VP8 + rtx. Camera on/off = `setTrack(video|null)` + `call_media` (≤ 1/s, last state wins,
  pinned to the selected peer device); the peer's avatar on `camera: false` and after 3 s without a frame. The camera runs
  only while the call screen is resumed and the phone unlocked (Back/home/screen off stop it and send `camera: false`;
  return restarts it). Incoming: "Incoming video call" with Answer / Answer without video / Decline; the camera never
  starts while ringing, from the notification's Answer or Telecom. Glare across media per A4; an answer with m=video port 0
  makes it an audio call. Telecom `CALL_TYPE_VIDEO_CALL`, speaker by default unless a headset, no proximity lock. Front
  camera by default, Flip button; 1280×720@30 (640×480@24 on mobile data or relay-only, 640×480@15 when thermal ≥ SEVERE),
  1.5/0.8 Mbit/s, BALANCED. History: "Video call · m:ss", "Video call · No answer", "Missed video call" (+ "Missed video
  call from <name>"), "Declined video call"; "Call back" on a video line offers video or voice.
- **CAMERA:** now in the manifest (asked only for a video call with the camera on, or the camera button). Because a declared
  CAMERA permission is enforced for `ACTION_IMAGE_CAPTURE` too, the + sheet's Camera now asks for it first.
- **Debug fake camera:** debug builds only, when the phone has no camera (redroid) or `files/debug_fake_camera` exists: a
  generated moving test pattern feeds the capturer so frames flow end to end; never in release builds.
- **Known limits:** photos are not yet used as notification `Person` icons (§18.2 A6 allows it; initials-free notifications
  unchanged). The video PiP can't be dragged. Redroid has no camera sensor: real-camera capture, Flip and the speaker
  default need a real phone. `scripts/call-device-test`'s `open_chat` taps the peer's name even when that chat is already
  open; since GO UX the header title opens Chat info there (the run reported "A: could not type"). Verified with a local copy
  that first leaves the chat — root should adopt that in the script.
- **What root's device test should add for video:** grant CAMERA on both; A taps "Video call"; B rings and shows "Incoming
  video call" with "Answer without video"; B taps the in-activity "Answer" (not the notification Answer, which answers with
  the camera off); within ~10 s the debug log line `call video VP8 in WxH@fps decoded=N` shows decoded > 0 on both phones
  (frames both ways); B taps "Turn camera off" → A's screen shows B's avatar (no frozen frame) and `call_media` arrives;
  "Turn camera on" → decoded frames grow again on A; B presses Home → A gets `camera: false` and B's camera stops; back →
  resumes; hang up → both lines "Video call · m:ss", no Telecom call / communication mode left; plus a voice call after it
  (unchanged). With CALLTEST_TURN=1 the relay-only video call should show ~1–2 Mbit/s each way.

## GO UX (brand, WhatsApp-style layout, + sheet, multi-photo captions, call screens, dark screen) — READY
**READY.** Commits `023362f` (brand, theme, chat layout, + sheet, multi-photo), `3cf0b5e` (call screens,
dark-screen fix), `5cb01ed` (polish from Redroid screenshots). No wire change, no Room change, no new library.
- **Gates:** `./gradlew assembleDebug testDebugUnitTest` green: **639** JVM tests, 0 failed (8 skipped).
  `scripts/call-device-test` (`CALLTEST_INSTANCE=_ux`, 4470, redroid `-ux1/-ux2` on 5730/5731, now stopped):
  **CALLTEST OK**, all 8 calls incl. call 8 (Back during/after a call). Then, on the same two phones, by hand:
  3 photos picked in the system picker, captions on 2, sent, received on B in DM; dark mode chat, list,
  outgoing/incoming/active call, Back → chat with "Call in progress" bar. Screenshots looked right in both themes.
- **Brand:** adaptive icon from `design/brand/risime-logo.png` (gradient background with the wordmark
  removed, robot foreground in the safe zone, PNG mipmaps), Android 13 monochrome + notification icon
  (`ic_stat_risime`, one vector glyph), Android 12+ splash via platform theme attributes (values-v31).
- **Theme:** M3 light/dark from the logo (seed #1565E8 → primary #0056D3 / #B0C6FF; cyan accent for read
  ticks; violet in the call gradient). All surface-container roles set. DesignTokensTest checks every pair.
- **Layout:** chat wallpaper, bubbles ≤ 80 % width with a tail on the first of a run, time + ticks inline
  when they fit; input bar = rounded field (emoji, text, +) + round Send; coloured initials avatars; round
  unread badge; brand mark in the chats bar; tapping the chat title opens info. The 048 lock / strip stay.
- **+ sheet:** Gallery (Photo Picker, up to 10) and Camera (only when a camera app resolves; FileProvider
  cache file, no CAMERA permission, deleted after encrypting). Preview: pager, thumbnails, caption per photo,
  remove, Send; each photo = its own §14 image message through the existing pipeline.
- **Dark screen after Back:** the proximity screen-off wake lock was held for any active earpiece call,
  even after Back put the call behind the chats → the chat went black whenever the sensor read "near".
  Now held only while CallActivity is resumed (`wantsProximity`, ProximityRuleTest). Also: the call activity
  never draws an empty window while closing, and the window background is the theme surface.
- **Known limits:** redroid has no proximity sensor, so the root cause is verified by unit test + code, not
  on a device (check on a real phone: call on earpiece, Back, cover the sensor → screen stays on). A batch of
  photos can arrive on the receiver in upload-completion order (each photo uploads independently; the sender
  shows them in pick order). Camera capture not exercised end to end (redroid camera has no sensor).

## v1.15 history sharing (§17) — READY (behind `HISTORY_SHARE_ENABLED`, off by default)
**READY** for contract v1.15 §17 (decision 049, consent option A), in the chunk order of
`contract/proposals/reviews/2026-10-06-history-share-android.md`. Commits `51f4071` (gap index, Room v9), `8e3e117`
(models, strict envelopes, 'H' AAD), `9ff76c6` (core over UniFFI + vectors), `0a88040` (reactions keep their confirmed
client_msg_id, v9 before any release), `3f5a62e` (provider, requester, UI behind the flag), `1f66641` (live interop).
- **Flag:** `BuildConfig.HISTORY_SHARE_ENABLED` (`-Prisime.historyShare=true`) gates the `history_share` capability, the
  ChatEngine hooks, the "History requests" channel, every history UI and Settings → Privacy. Off by default as asked
  (nightly.19 shipped chunks 1–3 dark). The gap index (Room v9) runs regardless, so gap rows exist when it is turned on.
  Flipping the default is a one-line change in `app/build.gradle.kts`.
- **Gates:** `./gradlew assembleDebug testDebugUnitTest` green (617 JVM tests, 0 failed). `scripts/interop` on
  `INTEROP_INSTANCE=_hsa` (5000/5600): **INTEROP OK twice in a row**, with 7 new checks (H0–H6).
- **Gap index (§17.2):** `history_gap` rows (message_id, client_msg_id, from, from_device, server_ts, generation, epoch;
  no content) in the event's transaction, for rule 1/rule 2 pre-install messages, older-generation parked rows dropped
  on a newer Welcome, and old-generation messages after a reset. Never for controls, own-device echoes, held,
  hidden-tombstoned or cleared ids. Removed by `delete` events, Clear/Delete chat, a successful import and the prune at
  `server_ts + 30 days`. Room **v9** also adds `messages.origin/shared_by/from_device`, `reactions.confirmed_client_msg_id`,
  `history_requests`, `history_parts`, `history_provides`.
- **Requester:** "Request history" on the gap marker (only with gap rows and no open request), with the source sheet
  (own / own or members; in a DM "own or <name>"). The request row is written in the same transaction as the core's
  `historyKeygen`, then `history:request` goes through the conversation's send lane (stale_epoch → catch-up and
  re-encrypt; `request_open` for an unknown request → cancel and retry). Then status, progress lines, refresh (the same
  request id and rpk, at the current epoch), "Ask group members now", cancel (also on Clear chat). Parts are stored by
  the pipeline in event order. Afterwards they are fetched, the SHA-256 is checked in Kotlin, then `historyOpen`, the
  header is checked, and the part is imported in one transaction. An entry is imported only when it matches a gap row
  on message_id + client_msg_id + from + server_ts (from_device only when both sides have it), and the gap row is
  older than the request. It is never imported over an existing row or past the watermark. A hidden tombstone `me`
  blocks it; `everyone` is re-judged with the gap row's sender. Imported rows are read, never acked or notified, carry
  `origin`/`shared_by`, and use the gap row's client_msg_id. Images keep their reference, re-sealed in the media row.
  Reactions apply only to a target that is held. Markers follow the exception: `sys:history-shared`, and a gap marker
  that is deleted or moved to the newest remaining gap row; after `done` it reads "couldn't be restored" and offers the
  other source. Then `history:ack` (owed until the server takes it). Every terminal state runs `historyForget`, and a
  start-up sweep runs at 48 h.
- **Provider:** `history_request` is decrypted in order (parked or caught up; unreadable → `unable`/`stale`), with
  the 'H' AAD bound to the request id and the sender bound to from/from_device. Own versus member comes from
  `historySender`; a disagreeing `consent` hint means member. Option A: one approval per (device id, leaf signature
  key), kept in the sealed store, and automatic afterwards while unlocked. A re-registered key asks again, and the
  approval is forgotten when the device is removed. Members are always asked; members' prompts expire after 24 h.
  Settings → Privacy has both switches and the approvals list with Remove. The export (R2–R4): server intervals ∩ the
  local membership view from group lines; no tombstones, hidden, deleting, unverified, outbox or cleared rows; images
  by reference (reconstructed and §14.4-validated); call lines only to own devices; reactions after their target with
  their client_msg_id. Parts hold at most 5 000 entries or 16 515 072 bytes, the newest part is part 1, and the 20-part
  cap cuts the oldest end. Nothing to share → `unable`/`no_data`. The export runs in a foreground `dataSync`
  WorkManager worker ("Sharing chat history…", FOREGROUND_SERVICE_DATA_SYNC, holding the socket only while unlocked).
  Each part is sealed in the core and uploaded as a `history` blob with X-Device-Id. Progress per part (boundaries,
  client_blob_id, blob_id, sealed metadata, delivered) is persisted, so a resumed part is never re-sealed. Parts are
  delivered through the send lane. There is a quiet "Shared chat history with your new phone".
- **UI (Robolectric):** Request history and its sheet, progress ("Open RisiMe on Pixel 8 to share", "Receiving
  history… 2 of 3", "No device could share…", Try again), the own approval prompt (with the small print) and the
  member prompt, "Shared by <provider>" in the message sheet (no label for own-device restores), Settings → Privacy.
- **Tests:** GapIndexTest, HistoryEnvelopeTest, HistoryExportTest (interval ∩ local view against a lying server,
  deletes, call lines, part bounds), HistoryImportTest (each match key, gap newer than the request, existing row,
  malformed, dedupe, reactions, header, marker exception), HistoryFlowTest (two app instances end to end: option A once
  then automatic, a rotated key asks again, a member always asks with a lying consent hint, the members switch
  declines, deleted messages never come back), HistoryUiTest, Migration8To9Test, every v1.15 example parsed and
  re-encoded, HistoryVectorsTest (testCrypto: every `history_vectors.json` case in the core, aad_h through the app's
  and the core's parser, limits, seal/open round trip, forget, rolled-back keygen).
- **Live interop (`LiveGroupInteropTest.liveHistoryShare`):** D's new install (D2) gets gap rows for a group and a fresh
  C–D DM. D1 asks once, Allow, and shares the group; then the DM goes automatically. D2 imports with no duplicates,
  and a message deleted for everyone never returns. In the member path, B is added to an A+C group after a message;
  B's only phone is deleted ("Log out and delete chats") and B2 rejoins. A and C are both named; C is asked and
  shares; A's prompt closes. B2 gets only its two interval messages, labelled "Shared by C"; the bundle had 2 entries.
- **Known limits:** reactions confirmed before v1.15 have no client_msg_id and stay residual gap rows. The approval
  list shows device-id prefixes; there is no device name on the wire. Not covered live: a refresh after epoch drift
  (unit-level only), a delete racing an import (covered by HistoryFlowTest), Clear chat during a request (unit path).

## Calls never get stuck (nightly.16 P0, decision 054) — READY
**READY.** The callee couldn't answer, and the caller stayed "in call" after a kill. Root causes and
fixes are in `docs/decisions/054-calls-never-stuck.md`: ghost core-telecom calls, busy-by-audio-mode,
unbound Telecom callbacks, unbounded setup, and the mic prompt behind the keyguard.
- Gates: `./gradlew assembleDebug testDebugUnitTest` green; server `mix test` green (432).
- `scripts/interop` (`_callfix`, 4400/5099): **INTEROP OK twice**, with 3 new call checks (caller
  crash, callee kill, busy only for real).
- `scripts/call-device-test`: **CALLTEST OK** on two redroid containers (`-cf1`/`-cf2`, 5720/5721)
  with the real app. Four calls answered through the notification's Answer action, with direct
  media and DTLS-SRTP; one of them had the caller killed mid-call, and the next call was fine.
- Manual test for the pilot: `docs/status/calls-manual-test.md`.

## v1.13 1:1 voice calls (§16) — READY
**READY** for contract v1.13 §16 (decisions 046, 051, new **052**), in the chunk order of
`contract/proposals/reviews/2026-10-06-calls-v1.13-android.md` with crypto R1–R6. Commits `8ffb708` (protocol, envelopes,
SDP rules, state machine), `d6c2110` (pipeline, lanes, Room v8, call lines), `68deb50` (libwebrtc + decision 052),
`0586fec` (Telecom, service, notifications, UI, push, capability, button), `f91b14d` (live interop), `6cbe05f`
(redroid device tests), `ed47623` (FSI card, push tests), `4330b09` (rollout switch).
Gates green: `./gradlew assembleDebug testDebugUnitTest` (**529** JVM tests, 0 failed); `scripts/interop` on
`INTEROP_INSTANCE=_calls` (4300/4999) **INTEROP OK twice in a row** with 10 new call checks; instrumented on redroid
Android 14 arm64 (own instances `-calls`/`-calls2` on 5700/5701, stopped afterwards) **OK (3 tests)** three times, plus
the two-container run **OK** on both sides.
- **Library (decision 052):** `io.github.webrtc-sdk:android-prefixed` 144.7559.14 (= livekit-android 2.29.0's pin,
  "must equal" comment in `libs.versions.toml`), `androidx.core:core-telecom` 1.0.1. The WebRTC `.so` only for
  arm64-v8a/x86_64 (`packaging.jniLibs.excludes`); `check<Variant>SingleWebRtc` (run by `assemble*`) and
  `WebRtcPackagingTest` fail on a second libwebrtc. Debug APK 42.6 MB.
- **Signalling over MLS (§16.2–16.3):** `calls/CallEnvelope` (strict decode: lowercase UUIDs, `sent_at`, 20 480 B, ICE
  ≤ 20 × 512 B, restart needs `to_device`; `call_end` with an unknown reason renders "Voice call"), `calls/SdpRules`
  (16 KiB, one `m=audio` UDP/TLS/RTP/SAVPF, exactly one `a=fingerprint:sha-256` of 32 bytes, no `a=crypto`, setup
  actpass/active|passive, ufrag/pwd, rtcp-mux, Opus, no audio-level extmap; we strip it and set Opus
  `useinbandfec=1;usedtx=1;cbr=1;stereo=0;maxaveragebitrate=32000`, ptime 20). `call_signal` events go through
  `MlsPipeline` in event order (parked like messages; a park ahead of the epoch now triggers commit catch-up in **DMs
  too**, android R7), binding `call_id`/`ring`/sender (crypto R3), every failure a `ControlDropped` (no §13.3 line).
  The calls layer gets each signal **after the event's transaction commits** and `onPageEnd` after every batch
  (ring after the page, android R3). **One encrypt-and-push lane per conversation** (`ChatEngine.lane(conv)`, was one
  global mutex) shared by texts, reactions, images, deletes and `call:signal`; stale_epoch → catch up, re-encrypt,
  same client_msg_id.
- **State machine (`calls/CallStateMachine`, pure Kotlin, ports for media/signals/marks/clock):** offer sent at once with
  `sent_at` (server-corrected), trickle ICE in 250-ms batches ≤ 20 (`to_device: null` before accepted), ringing,
  first answer wins + `call_accepted`, 10-s accept wait (no call_end), 45-s ring timeout/validity, 20-s connect,
  ICE failed → "Can't connect the call" at once, reconnect: the caller restarts ICE (same fingerprint required) for
  15 s, 4-h max; freshness (server_ts and sent_at < 45 s), 24-h persisted dedupe (`call_marks`), sender pinning;
  glare (lower id wins, `call_cancel` glare, loser auto-answers, no call_end) + the sibling rule; busy (own call or
  `AudioManager.mode` IN_CALL/IN_COMMUNICATION, no READ_PHONE_STATE) with siblings stopping on the sender copy;
  decline; the fingerprint pinned from the MLS SDP and the §16.10 (e) `getStats` check (dtlsState connected, SRTP
  cipher, remote certificate fingerprint) before "End-to-end encrypted"; debug-only tamper hook (Settings → Calls).
  Placing a call to someone who is ringing you answers their call.
- **History lines (§16.6):** `call_end` is an outbox row (kind `call`, Room **v8**: `messages.call_id` + index,
  `call_marks`); one line per call id; both perspectives incl. failed-but-rang = "Missed voice call"; missed is
  unread and posts "Missed call from <name>" (live only, never on a replay/restore); previews "📞 …"; tap → Call back,
  Delete for me; no reactions; Clear chat removes them.
- **Android:** `CallManager` (socket kept up while a call/ring/wake exists, partial wake lock ≤ 4 h, proximity only on
  the earpiece while active), core-telecom self-managed calls (answer/active/disconnect, endpoints for the picker;
  a cellular call taking over ends ours), `CallService` (phoneCall; + microphone only with RECORD_AUDIO), `calls`
  channel (ringtone, vibration, IMPORTANCE_HIGH) with an insistent `CallStyle.forIncomingCall` that always has the
  full-screen intent (canUseFullScreenIntent checked before every ring; denied → a card on the chats screen and the
  Settings row), `CallActivity` (showWhenLocked/turnScreenOn, API 26 flags; Answer is an activity intent; RECORD_AUDIO
  asked there or at the call button), TURN fetch ≤ 3 s (503 → direct ICE only; cached only while ≥ 4 h 5 min left).
  **Call push** (`{"type":"call"}`) has its own handler: the phoneCall service starts at once; unlocked → sync on the
  kept-up socket; **locked → decision 051**: full-screen "Incoming RisiMe call", no name; Answer → MainActivity's
  fingerprint unlock → the unlocked sync answers if the offer is still ringing, else "Call ended"; Decline only stops
  the local ring; 45-s cap.
- **Enable:** `calls` is advertised only with libwebrtc loaded + Telecom registered + notifications allowed (and
  `BuildConfig.CALLS_ENABLED`, default on; `-Prisime.calls=false` = the §16.14 receive-only nightly). DM header call
  button: this phone → e2ee → `calls_ready` (refetched on open, `mls_membership`, reconnect) with the §16.1 texts.
  Settings → Calls (what's missing, deep links, OEM hint) and Open-source licences.
- **Live interop (JVM, real server + real MLS core, fake media):** C calls B → both of B's devices ring, B answers on
  B1, B2 stops, SDP rules and fingerprints checked on what crossed the server, trickled ICE, DTLS check → active;
  hangup → one line per call id on C, B1, B2; missed (B→C, cancel: C unread + notification, B1/B2 "No answer"); A→B
  declined on B2; the negative fingerprint test; glare A↔C; calls_not_ready. Uses the "groups" block's A, B, C (DMs
  C–B, A–B, A–C), no fixture change. TURN answered 503 (no secret on the interop server).
- **Redroid (real libwebrtc on arm64 Android):** `androidTest/.../calls/WebRtcDeviceTest`: the library loads, our
  prepared SDP passes our rules and libwebrtc accepts it, a loopback call connects over host candidates, DTLS-SRTP
  up, both getStats fingerprints equal the delivered SDP's; a tampered fingerprint never reaches DTLS connected; two
  state machines with real media reach active + verified and hang up with `call_end hangup`.
  `WebRtcTwoDeviceTest` ran on **two containers** (172.17.0.2 ↔ .3) with a host line relay via `adb reverse`:
  pair **host/host**, verified, on both sides. Redroid has no audio HAL, so no sound was played.
  Run: `REDROID_INSTANCE=-calls REDROID_PORT=5700 scripts/android-target start` (and `-calls2`/5701), install both
  APKs, `pm grant lk.codegen.risime.debug android.permission.RECORD_AUDIO`, `am instrument -w -e class
  lk.codegen.risime.calls.WebRtcDeviceTest …`; the two-device test takes `-e role caller|callee -e relayPort N`.
- **Findings / open:**
  - SRTP negotiates **AES_CM_128_HMAC_SHA1_80** (the allowed fallback) on this libwebrtc build although
    `CryptoOptions.enableGcmCryptoSuites` is set; GCM preference to investigate (doesn't block: §16.9 allows it).
  - Server: the ring limit "1 per callee per 5 s" is a sliding-window approximation keyed by (caller, callee): a
    repeat ring to the same pair can be refused for up to ~10 s (the app shows "Too many calls…").
  - `LiveInteropTest`'s 60-s Bshort token has little margin as the live suites grow (root: mint later/longer); the
    calls checks are kept short (no waits) for this.
  - Not done: the on-screen debug stats overlay (stats go to logcat in debug only), the "Always relay calls" setting
    (relay-only code path exists in `WebRtcConfig`), the "Your tablet won't ring until it updates" line from
    `missing_calls`, the API 26–33 ConnectionService path and the real-network matrix (needs phones).
- **Harsha, on real phones (debug build; TURN ports are still blocked, so calls connect only directly):**
  1. Same Wi-Fi, phone ↔ phone and emulator ↔ phone: call both ways; hear each other; mute; earpiece/speaker/wired/
     Bluetooth switching; proximity turns the screen off on the earpiece; "End-to-end encrypted" appears.
  2. Two devices on one account: both ring, answer on one, the other stops; decline from the notification.
  3. Locked/killed: phone screen-locked (known caller, answer from the lock screen); app swiped away / Doze
     (`adb shell dumpsys deviceidle force-idle`) → FCM rings; fingerprint-locked session → "Incoming RisiMe call"
     with no name → Answer → fingerprint → answers (or "Call ended").
  4. Missed call: notification "Missed call from <name>", line in the chat, Call back.
  5. Android 14/15/16 from the browser install and the updater install: full-screen ring (Settings → Calls shows if
     the permission is missing); Xiaomi/Samsung background/lock-screen toggles.
  6. Wi-Fi ↔ mobile data: expect "Can't connect the call" quickly (no TURN yet), never a hang.
  7. Settings → Calls → debug tamper switch on → a call must fail with "Can't connect the call".
  8. Check the pilot phones for 32-bit (they'd show "Calls aren't supported on this phone").

## P3 scrolling on a real device + P4 photos polish — READY
Commits `a6354c9` (device scroll tests), `45a40e4` (photos going out), `f4e943b` (live 13a2). Gates green:
`./gradlew assembleDebug testDebugUnitTest` (472 JVM tests, 0 failed); instrumented on redroid Android 14 arm64
**OK (15 tests)** twice (11 scroll + 4 decoder); `scripts/interop` **OK twice** (86 checks, new 13a2).
- **Scrolling (P3):** `androidTest/.../ui/ChatScrollDeviceTest` runs the real `ChatMessageList` with the real DM
  `Bubble` and the group `GroupMessageList` inside the screens' layout (Scaffold, `imePadding`, Composer) in an
  edge-to-edge adjustResize activity, 240 messages of 1–9 lines with day separators, data through a StateFlow:
  opens at the latest (preloaded / Room after the first frame); a 50-message replay in emissions (some in one
  frame); a fresh-install restore in pages (and older history landing under a live message); a group rejoin with
  and without the old history; the real LatinIME opening/closing (+ a message while typing); a real rotation
  (`UiAutomation.setRotation`, the activity is recreated; also scrolled-up + 2 unseen survives it); scrolled up
  (real swipes) + 3 incoming + a 50 batch → "New messages ↓" 53, the viewport doesn't move, the button jumps.
  **Nothing failed on the device**; a mutation that drops the stick-to-bottom rule fails 6 of the 11.
  Run: as for the decoder test below (`am instrument -w -e class lk.codegen.risime.ui.ChatScrollDeviceTest …`).
- **Photos (P4), DM and group alike:** determinate upload % from the upload job's request body (`UploadProgress`,
  ring + "Uploading 42%"); "Encrypting photo…" step in the attach sheet; "Encrypted · waiting to upload",
  "Upload interrupted · retrying" (automatic backoff unchanged), "Sending…"; failures "Couldn't send photo · Retry"
  (§14.7 texts for quota/too large/not e2ee) with a Retry tap target on the photo, also a TalkBack custom action
  (the merged bubble now reads the photo state). Retry keeps `client_blob_id` (a lost 201 replays 200, even at the
  quota); a new id only after digest_mismatch/not_found/bad_request; on a waiting upload Retry re-runs the job now
  (WorkManager REPLACE). Groups: attach (when encrypted), thumbnail, full screen and "📷 Photo" previews and
  notifications checked; no gap found. Live 13a2: group upload progress 0→100 %, A's row SENT with the blob,
  lost-201 Retry → same blob, B and C decrypt.

## v1.12 deleting messages and chats (§15) — READY (receive live, send UI behind a flag)
**READY** for contract v1.12 §15 (decision 047), following `contract/proposals/reviews/2026-10-06-delete-v1.12-android.md`
R1–R10. Commits `c792017` (protocol, envelope, AAD, rules, examples), `3a5eb72` (Room v7), `759b6d6` (receive side),
`2203e76` (send side, Clear/Delete chat), `006a854` (UI), `990c5c6` (real-core tests), plus the live interop commit.
Gate green: `./gradlew assembleDebug testDebugUnitTest` (in a clean worktree of these commits; the shared checkout had
other sessions' uncommitted work). `scripts/interop`: **INTEROP OK 2 runs in a row** (85 checks each, 6 new delete checks D1–D6), on HEAD + the live commit.

**Rollout (§15.11): the send UI flag.** One switch: `BuildConfig.DELETES_SEND_ENABLED` (`app/build.gradle.kts`,
`defaultConfig`; **false**, `-Prisime.deletesSend=true` turns it on), read once into
`lk.codegen.risime.data.deletes.DeleteFeature.sendEnabled` (a plain `@Volatile var`, so a later remote flag or a test
can flip it at runtime). It gates long-press **Delete / Select**, select mode and the Delete for me / for everyone
dialog. **Always on:** the whole receive side, the `deletes` capability, and **Clear chat / Delete chat** (chat menu ⋮
and chat-list long-press; local plus `chat:clear` on my own inbox). The live tests set the flag on.

Receive side (live in this build):
- `delete` events (plaintext legacy DMs; E2EE through `MlsPipeline`, parked/replayed like messages). The deleter is the
  **attested user id** from the core (`processDetailed`), never the device; `sender_is_admin` at the control's epoch for
  groups; AAD (`deleteAadDecode` rules, Kotlin `DeleteAad` checked equal to the core) = envelope = event targets, else the
  whole control is dropped and logged. 48 h + 5 min grace on `server_ts`. A plaintext delete in a group or in a DM this
  device holds an MLS group for is ignored (could only come from a misbehaving server).
- Tombstones in place (`kind = 'deleted'`, body/image ref/receipts cleared, never unread, acked READ): "This message was
  deleted" / "You deleted this message" / "This message was deleted by an admin"; chat-list previews too; search
  excludes them. Reactions on it are purged and later ones dropped (also never notified).
- Pre-arrival deletes: a **hidden tombstone** (`deleted_ids`, 30 days) or, with server metadata, a placed `del:<id>`
  tombstone (no sender shown unless S = D). When the message arrives it is **decrypted first and re-judged**: authorised →
  tombstone; unauthorised → shown normally, the hidden/placed tombstone dropped (never blind hiding).
- Images: media row (sealed key, thumbnail, blob ref) purged in the transaction; files unlinked, upload cancelled and
  bitmaps evicted **after the commit**; the existing start-up sweep removes orphans.
- Notifications: after any delete, every chat with a posted notification is re-planned from its unread rows and reposted
  **silently** (`setOnlyAlertOnce` + `setSilent`) or cancelled; the summary goes when none is left; "added you"
  notifications are untouched (chat notifications are tagged). Runs in the foreground too.
- Controls never create §13.3 lines (pre-install, undecryptable, parked-then-failed: logged only).
- **Malformed (> 3 epochs back) — choice: shown, not dropped.** When the core throws `Malformed` for a delete control
  (no admin record for that epoch), the stored targets keep their content with a small muted note **"Couldn't verify a
  delete for this message"** (`messages.delete_unverified`); nothing is deleted and no §13.3 line is added.
- `PRAGMA secure_delete = ON` on open (callback on the primary connection), best-effort `wal_checkpoint(TRUNCATE)` on IO
  after delete transactions. Server-clock offset from every join/sync `server_time`, persisted in DataStore.
- `deletes` advertised with a delete-capable core (`["groups","images","deletes"]`).

Send side (flagged off): delete outbox (`delete_outbox`, retried with the same `client_msg_id`), rows shown as "You
deleted this message" at once; ≤ 100 targets per request (101 → 2 requests); `encryptWithAad` with the canonical AAD;
`stale_epoch` re-encrypts; refusals (`too_old`, `not_sender`, `not_admin`, `bad_request`) restore the failing rows
(ticks kept), re-request the others, and offer "Delete for me" ("You can only delete messages for everyone within 48
hours"); own echo completes the outbox after a crash. Pending rules (R2): `send_attempts` counted before every push
(migration counts existing PENDING rows once); never pushed → cancelled locally; pushed → **cancel after send**
(finishes with the same id, then deleted for everyone). One serial encrypt-and-push lane for texts, reactions, images and
deletes (R6). Delete for me: rows go, a `scope = me` hidden tombstone blocks replays, `msg:delete` `me`. Eligibility uses
the server-clock offset (48 h − 1 min) and `groups.my_role`; "People on older app versions may still see it" while
`deletes_ready` is false. Clear/Delete chat: one transaction with the `cleared_upto` watermark (max of the cursor and the
removed ids, compared as TimeUUID times) on every apply path (messages, reactions, deletes, markers, group lines, parked
replays, re-login replays); pushed pending sends and pending deletes for everyone are kept; Delete chat hides the row
until a new message. Logout flushes the delete outbox within its 5 s bound.

Room **v7** (`Migration6To7` + `Migration6To7Test`; `EveryReleasedSchemaUpgradeTest` covers 1–7): messages gain
`deleted_by`, `deleted_by_admin`, `deleted_at`, `delete_state`, `send_attempts`, `delete_unverified`; new `deleted_ids`,
`delete_outbox`, `chat_state`; wipe covers them.

Tests: `DeleteRulesTest` (envelope strictness, AAD, the 48 h rule, eligibility with the offset, texts, TimeUUID order),
`DeleteReceiveTest` (13: tombstones, other own device, not authorised, window ± grace, admin at epoch, AAD/envelope
mismatch dropped without a line, pre-arrival re-check both ways, placed then corrected, Malformed note, pre-install no
marker, image files after commit, plaintext rules, own echo, watermark + Delete chat unhide), `DeleteSendTest` (10:
AAD-bound request, split at 100, refusal restore/retry/notice, pending cancel vs cancel-after-send, attempts counted,
delete for me + replay, Clear chat watermark/chat:clear/kept pending delete, Delete chat hide/unhide, stale_epoch),
`NotificationRefreshTest` (Robolectric: silent repost, cancel, summary, "added you" untouched), `DeleteUiTest`
(Robolectric: tombstone texts and placed attribution, dialog, previews, select-mode copy), `SecureDeleteTest`,
`Migration6To7Test`, `DeviceRegistrarTest` (capability), typed v1.12 examples + round trips, `RealDeleteTest` (real core:
AAD equals the core's, admin delete, binding mismatch, non-admin refused).
Live (`LiveGroupInteropTest`, flag on): D1 own delete in a group; D2 admin deletes another member's message; D3 non-admin
→ `not_admin`, rows restored; D4 image → tombstones and `GET /blobs/{id}` 404 for every member; D5 e2ee DM delete, the
other user's attempt → `not_sender`, and a reinstall-style replay no longer returns the deleted event (only the delete);
D6 Clear chat → watermark, `chat:clear`, MLS untouched, a cursor-reset replay restores nothing and the server stops
replaying the cleared events. **Not testable live:** `too_old` (needs a message older than 48 h on the server clock;
covered by unit tests of the refusal path).

Later / notes: the one-time catch-up replay of skipped deletes (deferred, S-g); "Delete for me"/Clear sync to my other
devices (A6); quotes. Concurrency note for root: `scripts/interop` uses fixed tmux session names, ports (4100/4799), store
(`risime_interop`) and log paths, so two sessions running it at once kill each other's server (seen 4 times today). The
two green runs above used a local, uncommitted copy of the script in a scratch worktree with other ports/names/store;
root has since added `INTEROP_INSTANCE`/ports (`2a08768`), which fixes this.

## v1.11 encrypted images (§14) — READY
**READY** for contract v1.11 §14 (decision 042), following the android review's chunk order and R1–R8
(`contract/proposals/reviews/2026-10-06-images-v1.11-android.md`). Commits `db339dc` (envelope, examples,
vectors), `0fd00bb` (Room v6), `2b8618f` (codec), `8ef9cc3` (blob client), `699f083` (receive, viewer, send),
plus the live interop commit. Gate green: `./gradlew assembleDebug testDebugUnitTest`. `scripts/interop`:
**INTEROP OK 2 runs in a row** (74 checks each, 9 new image checks).

What's in the app:
- **Envelope:** `MlsPayload` decodes `image` with every §14.4 drop (alg, 32-byte key, `cipher_size(plain_size) ==
  blob.size`, cap, 32-byte sha, mime, 1–2048, thumb JPEG/WebP ≤ 128 px ≤ 4096 B); malformed → ignored and logged.
  Encoder keeps 22 KiB (drops the thumbnail for a long caption). Typed decoders for all v1.11 examples;
  `media_vectors.json` (5 + 9) through the real core on the JVM; Kotlin Padmé/cipher_size checked against it.
- **Storage (R1, R8):** Room **v6** (`Migration5To6` + test): `media` table (sealed `enc` and thumbnail with
  KvSealer under MlsDbKey, AAD `media|client_msg_id` / `media-thumb|…`; blob ref, state, cache file, TTL estimate)
  and `messages.blob_id` (kept for v1.12). The sender stores the complete envelope; its file is only a cache.
- **Codec (R2):** decode (ImageDecoder 28+, BitmapFactory 26–27 + EXIF orientation parsed in Kotlin), redraw into
  a fresh software sRGB ARGB_8888 bitmap (no gain map), ≤ 2048 px never upscaled, JPEG q85→75→65 under 6 MiB, PNG
  only with alpha ≤ 4 MiB (else flattened JPEG), then a byte-level strip of every APPn/COM, PNG ancillary and WebP
  ICCP/EXIF/XMP. Thumbnail from the same bitmap, q60→50→40, 96, 64 px. Tests re-read the output and find no
  EXIF/GPS/XMP/ICC/MPF/IPTC/embedded thumbnail (hostile AWT encoders that inject them; Robolectric has no native
  graphics on linux-aarch64, so the platform decoders are covered on devices only).
- **Receive (R3, R4):** envelope + sealed key/thumb stored with the cursor; downloads scheduled after the
  transaction. Streamed, resumable download (`Range` + `If-Range`, resume from the core's verified prefix),
  size → SHA-256 → AEAD before caching; one re-download, then "Couldn't open this photo"; 404 → "This photo is no
  longer available" (thumbnail stays). Display: sniff must match mime, header ≤ 2048 and = w/h, BitmapFactory only,
  sampled to the slot, 2 decode threads, 10 s timeout, memory LRUs only (no image library, no plaintext on disk).
  Auto-download: unmetered in the background (WorkManager, UNMETERED), metered only when visible, tap-only on Data
  Saver/roaming. LRU 500 MiB evicts only sent/received images still fetchable (`server_ts + 29 d`).
- **Send (R5):** Photo Picker (no permission) → in-process re-encode + encrypt (core, file to file; the plaintext
  encrypt input lives in `media/tmp` only during that call) → caption sheet → one transaction → `MediaUploadWorker`
  (unique per message, expedited, CONNECTED) → idempotent upload by `client_blob_id`, own size/sha compared (mismatch
  → new id), backoff 1–60 s, Retry-After, 507 hourly → the outbox sends the stored envelope (an image row never blocks
  it; text typed meanwhile goes first; `stale_epoch` re-encrypts the same envelope). Failures show the §14.7 texts
  with Retry/Delete; Cancel deletes an uploaded blob. Never plaintext: a DM without a group fails `not_e2ee`.
- **UI:** image bubble (blurred thumbnail first, spinner, state line), full-screen viewer (pinch 1–5×, double tap,
  caption), Save re-encodes decrypted pixels (MediaStore `Pictures/RisiMe` on 29+, system Save dialog on 26–28; no
  storage permission — manifest test). DM attach disabled unless e2ee and `images_ready` ("<name> needs to update…",
  "Your other phone…", the not-e2ee text; a tap explains and refetches). Groups: allowed, the sheet shows "Some
  members need to update to see photos", group info lists who. "📷 Photo" / "📷 <caption>" in the chat list,
  notifications, reaction lines and search; Copy copies the caption; TalkBack "Photo, <caption>".
- **Capability:** `images` advertised with a groups core whose media API is present (`device_put_images.json`).
- **Behaviour log:** `image_sent` with bytes and w/h only.
- **Tests:** ImageEnvelopeTest, MediaSealerTest, ImageBytesTest, ImagePipelineTest, ImageTransferTest (upload state
  machine, resume, digest-before-AEAD, 404, cleanup), ImageRepositoryTest (prepare/commit, R8 envelope, cancel →
  DELETE, retry, startup, LRU rules, auto-download policy), ImageChatEngineTest (stored in the transaction, no
  network inside, outbox order, stale_epoch, never plaintext), ImageUiTest (Robolectric, dark, 320 dp: attach
  states, viewer, not-e2ee refusal, bubble, previews), Migration5To6Test, MediaVectorsTest, DeviceRegistrar.
- **Live interop:** DM A→B (thumbnail first, download + decrypt SHA match, A's tablet as its own, same
  `client_blob_id` replay, `Range` 206 + `.part` resume, 409 not_e2ee / 415 / 400 / non-participant 404,
  `images_ready` false → true after re-PUT, usage); group (B and C decrypt; removed C still fetches the old image
  and gets 404 for a new one; non-member L gets 404; D, active since after the upload, may read it (§14.2 interval
  overlaps `[uploaded_at, now]`); the owner reads its own).
- **Real decoders (release blocker closed):** `androidTest` `RealDecoderMetadataTest` runs the real pipeline
  (ImageDecoder + Skia) on redroid Android 14 arm64: **OK (4 tests)**. Fixtures in `androidTest/assets`
  (`fixtures/make_fixtures.py`): a 3000×2000 JPEG with EXIF orientation 6 + GPS IFD + IFD1 (blue) thumbnail + XMP
  (hdrgm) + MPF + IPTC + COM; a PNG with tEXt/zTXt/iTXt/iCCP/eXIf; a Display P3 JPEG made by the platform encoder.
  Asserted on the produced bytes: `ExifInterface` finds no lat/long, GPS tags, Software, XMP, thumbnail or
  orientation; no APP1–APP15/COM marker, no ICC/MPF/XMP/IPTC/GPS strings, no PNG ancillary chunks; 1365×2048
  portrait with the red corner rotated to top-right; decoded colour space sRGB and the P3 pixel converted to its
  sRGB value; thumbnail ≤ 4096 B, 128 px, from the re-encoded pixels (rotated red corner, not the blue EXIF one).
  HEIC skipped (no HEIF encoder on spark2 for a fixture). The API 26–27 BitmapFactory path still needs a device.
  Run: `scripts/android-target start`; with the arm64 adb on PATH, `./gradlew assembleDebug assembleDebugAndroidTest`,
  `adb install -r -t` both APKs, `adb shell am instrument -w lk.codegen.risime.debug.test/androidx.test.runner.AndroidJUnitRunner`
  (`connectedDebugAndroidTest` can't be used: AGP insists on the SDK's x86 adb); then `scripts/android-target stop`.
- **Later:** group icon (§14.4), camera capture, the auto-download setting / storage screen, v1.12 delete-for-
  everyone of images (the blob id is on the row). On-device checks for Harsha: HEIC, Ultra HDR (no gain map in the
  received file), P3 colours, save on Android 14 and on 8/9, kill mid-upload.

## P0 after nightly.11: groups after logout/login — READY
Commits `7f99b13` (server, by the android role), `53e3184` (android). Gates green: android
`assembleDebug testDebugUnitTest`, server `mix format/compile/test`; `scripts/interop` **OK 2 runs in a row**
(clean worktree at `53e3184`, new `LiveGroupInteropTest` 12a–d).
- **Cause:** a logout wipes the MLS state but keeps the device id; nothing re-added the device. The app never
  called `rejoin`, and the server ignored a re-registration while the old leaf was still in the group (the
  removal op from the logout waits for an admin device), then that removal kicked the device out for good.
- **App:** after registration and on every join, `GET /groups` → `GroupStore.queueRejoins` (rejoin, or reset
  for the only admin) with an 8 s grace; "Rejoining group…" instead of "New group"; composer disabled with
  "Rejoining… you can send once this phone is back in the group"; queued sends go out after the Welcome.
- **Server:** `Ops.ensure_rejoin` (idempotent, drops stale removal-only ops), rejoining device never named,
  removals before additions in `mls_group_devices`. No wire change.
- **Known limit:** the only admin of a group resets it (§12.8); after a wiping logout its name is unknown
  locally, so the rebuilt group is named "Group" until renamed.

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
