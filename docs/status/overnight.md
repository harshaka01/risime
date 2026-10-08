# Status / morning summary — 2026-10-06 night (orchestrator, cc-root window 0)

## Morning summary
| Version | What's in it |
|---|---|
| nightly.12 | Groups rejoin automatically after logout/login (P0); inbox-cursor fix. First release through the automatic Redroid upgrade gate |
| nightly.13 | Encrypted photos (metadata stripping proven on real Android decoders); chats open at the newest message |
| nightly.14 | 1:1 E2EE now activates (stale installs no longer block it); no global dev banner, 🔒 per chat (decision 048); Log out keeps chats (decision 050); group ticks; delete-for-everyone receive side |
| nightly.15 | Photo progress and retry (groups too); scrolling verified on real Android 14 |
| **nightly.16** | **1:1 voice calls** (E2EE signalling, DTLS fingerprint verified, ringing when closed or locked, Bluetooth). Connects only directly until the TURN ports are open |

Every release went through: both gates, isolated live interop, the automatic Redroid upgrade gate
(the previous published APK upgraded in place), a backup, then publish and deploy. No overrides
were needed after nightly.11.

## Tester notes (per feature)
- **Encryption:** 1:1 chats switch to E2EE by themselves once both run nightly.14+. Encrypted
  chats show a 🔒. A chat that isn't encrypted yet says why.
- **Log out:** keeps your chats; signing back in brings everything back. "Log out and delete chats
  from this phone" removes them.
- **Groups:** after signing back in, groups are complete. After "Log out and delete chats", groups
  rejoin by themselves ("Rejoining group…"). Older group messages stay unavailable until history
  sharing ships (decision 049).
- **Photos:** attach in an encrypted chat, with progress and Retry. Location and camera details are
  removed. Tap for full screen and Save.
- **Calls (nightly.16):** the 📞 in an encrypted 1:1 chat. For now they work on the same Wi-Fi;
  across networks or on mobile data you get "Can't connect the call" until the ports open. A locked
  phone rings as "Incoming RisiMe call"; unlock to see who.
- **Always update over the old app. Never uninstall.**

## Needs Harsha
1. **Call ports (blocking calls across networks):** still blocked from outside at 19:00 UTC. 443
   is open; 3478 udp+tcp, 5349 tcp, 49152–49999 udp, 7881 tcp and 50000–60000 udp are not, and
   nothing reaches spark2. Ask Shirazi to forward them to 10.20.20.15 at the edge, and/or check
   `sudo ufw status verbose` (the `ufw allow` lines are in decision 046). Then tell me: I'll rerun
   the outside check, start coturn and set `TURN_URLS`, and calls will work everywhere.
2. **History sharing consent (decision 049):** A = approve once per new phone (recommended), or
   B = fully automatic.
3. **Calls option A or B (decision 051):** B is shipped (no caller name while locked). Say if you
   want A (a scoped background token that shows the name).
4. Still pending: the Notify.lk sender ID, and RisiWork's `version.json`.
5. Optional: Keycloak post-logout redirect URIs (`ai.risicloud.risime://logout`, `.debug`); the TLS
   certificate copy for TURN 5349 (decision 046).

## Next in the queue
- Delete-for-everyone **send** UI: turn on `DELETES_SEND_ENABLED` once most testers are on
  nightly.14+.
- History sharing (needs answer 2), then group voice via LiveKit (v1.14).

## Run finished (2026-10-08, orchestrator)
- **Live releases:**
  - nightly.27 GO UX;
  - nightly.28 profile and group photos, and 1:1 video;
  - nightly.29 group voice and video (LiveKit, MLS frame keys);
  - nightly.30 open sign-up (`OPEN_SIGNUP=true`).
  Each one passed the full gate, with every step under a 30-min timeout: server, android, live
  interop, and the upgrade test with per-conversation counts. 1:1 calls were checked by
  `scripts/call-device-test` (calls 1–10 incl. video, CALLTEST OK).
- **Agents:** none running.
- **Needs Harsha:**
  1. Caddy `/livekit` route:
     `sudo cp infra/caddy/Caddyfile /etc/caddy/Caddyfile && sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && sudo systemctl reload caddy`.
     Then root sets `LIVEKIT_URL` in pilot.env and restarts, and group calls work for testers.
  2. A by-hand check from outside that UDP 49500 and 49999 are reachable (decision 056).
  3. The fail2ban `risime-signup` jail (the sudo line is in decision 058).
  4. Dhammika: his phone has a pending invite for the gmail address. Sign up with that email, or
     have the invite revoked (it otherwise blocks the phone with `phone_taken`).
  5. Real-phone checks:
     - the dark screen after Back with the proximity sensor;
     - camera, Flip and speaker in video calls;
     - a relayed video call from mobile data.
- **Known limits:**
  - a batch of photos can arrive in a different order;
  - phone squatting by open sign-ups (an admin disables the squatter until SMS claims exist);
  - the country box defaults to +94.

## P0 run (2026-10-08): updater, chat safety, video audio
- **v0.2.0-nightly.31 (live ~08:50 UTC): P0-1 in-app updater fixed.**
  - **Root causes:**
    - the download ran inside the screen, so leaving the app cancelled it;
    - checks ran only every 6 h, with no manual check;
    - a silent update gave no feedback;
    - errors were dropped.
    The signing key never changed: one certificate for n1–n30, and versionCodes always went up.
  - **Fix:**
    - a WorkManager job with a progress notification, a resumable download and every check on
      every open (at most every 15 min);
    - Settings → About → Check for updates;
    - "RisiMe updated" afterwards, real errors with "Download from website", and "never uninstall"
      everywhere.
  - **Gate:** the release gate now updates through the in-app updater on Redroid (a local HTTPS
    mirror with a throwaway CA). From nightly.32 on, the "Back during the download" step is
    mandatory (`UPDATER_BACK_SAFE_FROM=20031`).
- **v1.21 (in nightly.31): reinstalls rejoin without reset.**
  - No only-admin group reset.
  - DM resets happen only when no device can ever re-add.
  - The server refuses a reset with `409 rejoin_pending`.
  - Stale leaves are cleaned up hourly. The one-off run after deploy queued 19 cleanup ops for 45
    stale leaves.
- **P0-2 cause:**
  - testers uninstalled because the updater failed, and nothing survives an uninstall;
  - recovery then mostly failed (7 of 29 history requests succeeded);
  - only-admin reinstalls reset groups.
  No silent wipe path was found.
- **Backups (v1.22 §22, decision 059):**
  - done: the server and the crypto core (44 vectors, cross-checked in Python);
  - in progress: the Android app (local daily and before every update, export to Downloads,
    server backup with a recovery key, a restore screen on a fresh install).
- **P0-3 (fix on main, ships in nightly.32):**
  - **Root causes:** two Telecom readers raced on one-shot queues, which left the button dead or
    the audio on the earpiece; and calls were registered without video capability.
  - **Tests:** a fan-out regression test (the old code failed 200 of 200 runs), and
    call-device-test calls 9, 12 and 13 assert audio both ways in video calls.
  - **Needs Harsha:** a real-phone check (speaker by default and a working button).

## Sign-in incident and open sign-up (2026-10-08 ~05:00 UTC)
- **What blocked dhammikajayanath775@:** Harsha's invite at 04:35 UTC was correct (pending,
  lowercase, phone set). His Keycloak token was valid and email-verified, but `/me` answered
  `not_allowlisted` at 04:41 and 04:46. So the email on his RisiCloud account differs from the
  invited one. Keycloak itself allows self-registration (realm aoa offers Register), so nothing is
  needed from the RisiCloud lead.
- **Open sign-up (contract v1.20 §21, decision 058):** `OPEN_SIGNUP=true` is set in
  `infra/pilot/pilot.env` and is **live with nightly.30** (`/auth/config` says `"signup":"open"`).
  - **How it works:** a verified email with no invite gets "Create your RisiMe account" (name and
    phone). Phones stay unconfirmed (`phone_confirmed: false`) and are never matched by phone.
    Messaging still needs an accepted friend request.
  - **Limits:** 5 per IP per hour, 3 per `sub` per day, 200 per day in total.
  - **Logging:** refused sign-ins log the email's domain and a hash.
  - **To close it:** set `false` and restart; no release needed.
  - **Harsha:** install the fail2ban `risime-signup` jail (the sudo line is in decision 058).

## v0.2.0-nightly.29 (live 2026-10-08, not required): group voice and video calls (v1.19)
- **What's in:** LiveKit SFU with frame encryption, using keys from the MLS exporter. Voice for up
  to 32 people, video for up to 8.
- **Proof on 3 Redroid phones:**
  - the frame cryptor was OK on every stream;
  - a phone with wrong keys got nothing (fail closed);
  - removing a member mid-call cut them off, and the others switched keys;
  - the 1:1 call test passed (CALLTEST OK).
- **Not reachable for real users yet:** the pilot answers 503 for rooms until Harsha installs the
  Caddy `/livekit` route. Then root sets `LIVEKIT_URL` in pilot.env and restarts. The by-hand UDP
  49500–49999 check is also still to do.

## v0.2.0-nightly.28 (live 2026-10-08, not required): profile photos, group photos, 1:1 video calls
- **Profile photos (v1.17):** Settings → Set photo. The photo is encrypted and goes to each e2ee
  chat silently. It shows in the list, headers, info screens, member lists, next to group bubbles
  and on call screens. Plaintext chats show initials.
- **Group photo:** admins tap the photo in group info.
- **Database:** Room v10 adds tables and columns only. `Migration9To10Test` passes, and so does
  the upgrade gate with message counts.
- **1:1 video calls (v1.18):**
  - a video button next to the voice button, enabled once both sides run nightly.28;
  - "Answer without video";
  - camera off and on, and Flip;
  - the camera runs only while the call screen is visible;
  - "Video call · m:ss" lines.
  - On Redroid, VP8 640×480 flowed both ways at about 14 fps, and all 8 voice calls passed.
  - Not yet checked on real phones: the camera, Flip, the speaker default and a relayed video
    call (coturn `max-bps` 300000).
- **Known limits:**
  - photos aren't used as notification icons yet;
  - the video preview in the corner can't be dragged.

## v0.2.0-nightly.27 (live 2026-10-08 ~03:40 UTC, not required): GO UX
- **Look:**
  - icon, splash and theme from Harsha's logo, in light and dark;
  - WhatsApp-style chat: bubbles at most 80% wide, the time and ticks inside the bubble, a
    full-width rounded input bar;
  - "+" sheet with Gallery, and Camera when a camera app exists: up to 10 photos, a caption on
    each, each one sent as its own encrypted photo;
  - a new call screen, with a slot already in place for video.
- **Dark screen after Back from a call:**
  - cause: the proximity-sensor screen-off lock stayed held after Back;
  - fix: it's held only while the call screen is in front (unit test; Redroid call 8 OK);
  - still to do: a check on a real phone (earpiece call, Back, cover the sensor; the screen stays
    on).
- **Server (dormant until the apps ship):**
  - v1.17: avatar blobs and `silent` sends;
  - v1.18: video signalling;
  - v1.19: `/calls/rooms`, which answers 503 until `LIVEKIT_URL` is set.
- **Gate:**
  - **Upgrade test:** passed for 26 → 27, with per-conversation counts kept. The two earlier
    failures were in the test script (the new "+" sheet and the Send button on the photo
    preview), now fixed.
  - **Known limit:** a batch of photos can arrive in a different order.

## Current state (2026-10-08)
- **Pilot:** v0.2.0-nightly.26 (versionCode 20026), live and healthy, contract v1.16. The TURN relay
  has been live since 2026-10-07 06:34 UTC (`TURN_URLS`; 3478/tcp reachable from outside).
- **Releases on 2026-10-07:** nightly.17–26 (details below). Every one went through both gates,
  isolated live interop, and the two-phone Redroid upgrade gate (device id, groups, photos,
  reactions and delete; since nightly.26 also per-conversation message counts after the update and
  after a re-sign-in), then a backup, then publish and deploy.
- **Agents:** none running. No Redroid containers. Only the release worktree remains.
- **Brand:** `design/brand/risime-logo.png` (Harsha's logo, 1254×1254) is committed. It isn't used
  in the app yet.
- **Needs Harsha:**
  1. The calls check on real phones (`docs/status/calls-manual-test.md`, one phone on mobile data,
     plus a locked phone ringing full screen).
  2. "go UX" or the exact UX list (logo-based adaptive icon and theme, WhatsApp-style layout,
     attachment sheet, in-call redesign; profile and group photos after a short proposal).
  3. Optional: `INVITE_ADMINS` in `.env`; his invite counter drains by itself.
  4. Still waiting: the Notify.lk sender ID, and RisiWork's `version.json`.
- **Queue after that:** voice notes, multi-photo selection, call quality, 1:1 video, group calls
  (LiveKit). `docs/NIGHTLY.md`'s queue (the RisiCloud sign-in phase) has nothing unblocked; the
  nightly cron only runs the preflight.

## v0.2.0-nightly.26 (live 2026-10-07, not required): lost history comes back by itself
- **"nightly.25 wiped my chats":** not the update. Android removed the app's data on Harsha's
  phone (a new device id at 10:40 UTC: an uninstall + reinstall or Clear storage). The update kept
  everything in the gate.
- **Fix:** a phone with history gaps requests the history automatically (own devices first, with
  the one-time approval; members are asked). The inbox replay restores what the server holds.
- **Hard rule 9 (CLAUDE.md, decision 055):** an update never deletes local chats.
- **The gate now:**
  - compares per-conversation message counts before and after the update, and after a plain
    Log out and sign-in;
  - asserts the login screen appears after Log out.
  Two script bugs were found and fixed on the way. Plain Log out itself works.

## v0.2.0-nightly.25 (live 2026-10-07, not required): hotfix, dark screen after a call
- **Fix:** an ended call always closes the call screen (back to the chat); a call screen opened
  with no call closes at once; the call card leaves Recents; Back during a call goes to the chat
  while the call continues.
- **Real bug fixed:** closing the call screen's task counted as "app swiped away" and hung up.
- **Redroid:** call 8 in `call-device-test`; CALLTEST OK for all 8 calls.
- **Next (awaiting Harsha's exact list):** a UX polish slice (layout, attachment sheet, in-call
  redesign; profile and group photos; branding).

## v0.2.0-nightly.24 (live 2026-10-07, not required): calls stay visible in the background, invite limits
- **Calls:** a "Call in progress — tap to return" bar; the call screen stays in Recents; a visible
  `call_ongoing` notification channel (lock screen, Hang up); a ringback tone for the caller.
  Proven on Redroid: Home plus the screen off for 60 s, audio both ways, the shade's Hang up ends
  the call on both phones.
- **Invites:** limits are server config (20 per user per day, 200 global, 50 pending, friend
  requests 50 per day); re-invites don't count; `INVITE_ADMINS` (in `.env`, Harsha's decision) skips
  the per-user limit.
- **Needs Harsha:**
  - his invite counter (10 in 24 h) drains by itself, or he runs the UPDATE given in chat (a
    production DB edit root won't do because the agent's attempt was refused);
  - `INVITE_ADMINS` in `.env` if wanted.

## TURN relay live on the pilot (2026-10-07 06:34 UTC)
- `TURN_URLS` (turn:203.115.26.139:3478 udp+tcp) is set in `infra/pilot/pilot.env` (`45dfb64`). The
  pilot was restarted at 06:34 UTC by the calls agent on Harsha's instruction to it.
- **Verified by root:**
  - the service environment has `TURN_URLS`;
  - `/health` is ok on nightly.23;
  - `GET /api/v1/calls/turn` has returned 200 since 06:45 (the last 503 was at 06:31);
  - 3478/tcp is reachable from outside (check-host, all nodes). UDP was proven by Harsha's home
    STUN test.
- `scripts/turn-smoke` checks the live setup (binds, REST credentials over UDP and TCP, a wrong
  secret refused). A Redroid relay-only call had audio both ways.
- **Harsha to verify on phones:** the 4-step check at the top of `docs/status/calls-manual-test.md`
  (one phone on Wi-Fi, one on mobile data, both on nightly.23).
- **Not started:** voice notes, multi-image selection, call quality (STEP 2–4).

## v0.2.0-nightly.23 (live 2026-10-07, not required): hotfix, one-way call audio
- **Cause:** the mic track was added with `addTransceiver`, which WebRTC doesn't reuse for an
  incoming offer, so the callee answered `a=recvonly` and was never heard (since nightly.16).
- **Fix:** `addTrack`, plus a guard that makes the offer's audio transceiver sendrecv before the
  answer.
- **Proof:** reproduced on Redroid (1854 bytes sent, 0 received); after the fix, 5 calls with audio
  both ways. `scripts/call-device-test` now requires bytes sent and received above 0 on both
  phones.
- **Both phones need nightly.23.**

## v0.2.0-nightly.22 (live 2026-10-07 06:15 UTC, not required): DMs repair themselves after a reinstall (v1.16)
- **Root cause of Harsha's stuck sends** (read-only evidence): his current phone wasn't in 3 of
  his 5 DM groups; they held only his superseded installs. Nothing re-added a new device to an
  existing DM.
- **Fix (§10.6):**
  - DM `devices` ops: either participant's in-group device re-adds the new phone and drops the
    superseded leaves;
  - a DM reset fallback after about 2 min if nobody can;
  - "Setting up encryption on this phone…" in the meantime.
- **Pilot recovery:** run 06:15 UTC. 3 ops were created (1 with a candidate committer, 2 heal by
  the reset once Harsha's phone runs nightly.22). They complete when the phones open RisiMe.

## v0.2.0-nightly.21 (live 2026-10-07, not required): hotfix, messages stuck on the clock icon
- **Cause:** the outbox stopped at the first message that could only be retried later (e.g. a DM
  answering `stale_epoch`/`e2ee_required`), so every chat's later messages stayed pending.
- **Fix:** messages are ordered per conversation; a waiting chat no longer blocks the others, and
  after 30 s the message shows "Not sent — tap to retry" (`e2ee_not_ready`).
- **Open:**
  - why Harsha's one DM refuses (his live state; fresh accounts on Redroid don't reproduce it; the
    calls agent is gathering read-only evidence and a safe repair);
  - the call button possibly missing after an upgrade (being checked).

## v0.2.0-nightly.20 (live 2026-10-07, not required): history sharing
- **What's in:** "Request history" on the gap marker. The user's own other phone asks once per new
  phone, then shares automatically; a group member is always asked, shares only the membership
  period, and the messages are labelled "Shared by <name>" (decision 049, option A).
- **Proof:** HPKE vectors cross-checked by an independent Python implementation; android 617 tests;
  live interop with 7 history checks; the two-phone upgrade gate (nightly.19 → nightly.20).
- **Not covered live:**
  - refresh after epoch drift and Clear chat during a request (untested);
  - a delete racing an import (unit tests only);
  - reactions confirmed before v1.15 aren't shared.
- **Switch:** `-Prisime.historyShare=false` turns it off if needed.

## Open items after nightly.20
- **Needs Harsha:** `TURN_URLS` in `infra/pilot/pilot.env` plus a restart (calls across networks;
  coturn is up, and TCP 3478 is reachable from outside). The TLS certificate copy for 5349 is
  optional.
- **Still waiting:** the Notify.lk sender ID, and RisiWork's `version.json`.
- **Possible next:** group voice via LiveKit (v1.16); the optional §3 of the readiness proposal
  (`app_version` and the reason in `missing*`).

## v0.2.0-nightly.19 (live 2026-10-07, not required): hotfix, a chat that crashed on opening
- **Cause:** with a message pending in an e2ee DM, opening the chat flushed the outbox on the main
  thread, and the MLS core's Room transaction threw. Every ChatEngine entry point into the MLS core
  now runs on `Dispatchers.IO`.
- **Detection:** debug StrictMode is on, and `scripts/logcat-gate` fails the release gate on a
  crash or on main-thread Room/MLS work.
- **Also inside (not visible):** history-sharing groundwork (Room v9 gap index); it passed the
  two-phone upgrade gate.

## v0.2.0-nightly.18 (live 2026-10-07, not required): delete UI, call audio fix, groups restore themselves
- **Delete for me / for everyone**, multi-select, Clear chat and Delete chat are on (§15 send UI).
- **Calls:**
  - the audio-capture fix: core-telecom only accepts LOCAL/REMOTE/MISSED/REJECTED disconnect
    causes; nightly.16/17 passed ERROR/BUSY, which left a ghost ACTIVE call holding the
    communication mode;
  - the audio is released on every end path; a 20-s no-audio watchdog.
- **Groups restore themselves (contract v1.14, §12.4a):**
  - any in-group member's phone re-adds an existing member's new device;
  - an independent crypto check found and fixed a device-swap denial-of-service;
  - the pilot's 6 waiting re-adds completed (group commits at 03:43 UTC); 0 waiting now.
- **Upgrade gate:** two Redroid phones. The group survives and works both ways, a photo sends,
  reactions work, delete for everyone leaves the deleted-message line on both phones, and the
  device id is kept. It passed for nightly.17 → nightly.18.
- **In progress:** history sharing (contract v1.15). Server and crypto are done, with the vectors
  checked by an independent Python HPKE implementation (it also caught one vector seeding error).
  The app side is being built.
- **Needs Harsha:** add `TURN_URLS=turn:203.115.26.139:3478?transport=udp,turn:203.115.26.139:3478?transport=tcp`
  to `infra/pilot/pilot.env` and restart (or allow root to). TCP 3478 is reachable from outside
  now, and coturn is running. The calls agent's attempt was refused by the permission check as a
  production change.

## v0.2.0-nightly.17 (live 2026-10-07, not required): calls stuck-state fix, readiness, photos, emoji
- **Calls (P0, decision 054):**
  - Ghost core-telecom calls held `MODE_IN_COMMUNICATION`, which counted as busy ("already in a
    call" on every call), and their callbacks declined the next ringing call, so the callee
    couldn't answer.
  - Fixed: one Telecom call per call id, busy only for a cellular call, bounded setup. The calls
    agent's final hardening and its 2-device Redroid test follow in the next release.
- **Readiness and photos:**
  - Every nightly.16 phone had a new device id, because of a reinstall or cleared data. A normal
    update keeps the id: tested nightly.12 → nightly.16 in Redroid.
  - The old rows blocked `images_ready`/`deletes_ready` and showed "needs to update". Superseded
    devices no longer count (§12.1). Capabilities are re-advertised on change.
- **Groups:** nobody was removed. Reinstalled phones wait to be re-added by an in-group admin
  device, or the admin uses Reset encryption.
- **Emoji:** the picker stays open for several emoji.
- **The upgrade gate now checks that the device id survives.**

## v0.2.0-nightly.16 (live, not required): 1:1 voice calls
- **What's in:** WebRTC/Opus with MLS signalling, the DTLS fingerprint verified end to end, the
  audio-level header extension stripped. Core-telecom, a full-screen ring, a nameless ring when
  locked (decision 051), Bluetooth.
- **Proof:** live interop with 10 call checks; real libwebrtc on arm64 Redroid, 3 of 3 direct
  connections, and a tampered fingerprint refused.
- **Rollout:** one release (decision 053). TURN answers 503 until the ports are open.

## v0.2.0-nightly.15 (live, not required): photo progress and retry, scrolling verified on a device
- **Photos:** "Encrypting photo…" and an upload percentage, "Couldn't send photo · Retry", retries
  idempotent by `client_blob_id`, and the same behaviour in groups as in 1:1.
- **Scrolling:** 11 instrumented tests on real Android 14 (Redroid), 240 mixed-height messages. As a
  control, removing stick-to-bottom on purpose made 6 of them fail.
- **Release process:** upgrade-test and redroid instances; the release gate uses its own `-rel`
  container.

## v0.2.0-nightly.14 (live 19:05 UTC, not required): E2EE switches on, per-chat lock, logout keeps chats, group ticks
- **1:1 E2EE now activates.** Cause: the 1:1 readiness counted stale installs (an old pre-v1.7 row
  each for Harsha and Hasitha, plus 2 dead install ids). Readiness now uses the §12.1 "can still
  receive" rule. The app retries until the chat is encrypted and shows the real reason.
- **No global dev banner** (decision 048). There's a lock per encrypted chat, an honest strip
  elsewhere, and the About text is fixed.
- **Log out keeps chats and MLS state** (decision 050). "Log out and delete chats from this phone"
  is the wipe. A live check: messages that arrive while logged out are there after signing back
  in, with no gap.
- **Group ticks:** a receipt that beat the send reply was dropped; fixed. ✓ stays while any member
  hasn't received the message.
- **Delete for everyone:** the receive side is in. The send UI ships later, behind
  `DELETES_SEND_ENABLED`, once everyone has this version.
- **Server:** v1.13 calls signalling is deployed (`005_call_signals.cql`). It's dormant until the
  calls app ships. TURN answers 503 until the ports are open.
- **Release-process fixes tonight:**
  - the test DB pool is capped (concurrent tests could exhaust the Postgres the pilot shares);
  - the release gate has its own test DB and keyspace;
  - interop runs are isolated per instance.

## v0.2.0-nightly.13 (live 18:06 UTC, not required): encrypted photos, chats stay at the bottom
- **Photos:** E2EE, resized to 2048 px, metadata stripped (proven on real Android 14 decoders in
  Redroid), encrypted blob, preview first, full screen, save to gallery.
- **Scrolling:** one bottom-anchored list for DMs and groups, with 14 UI tests.
- **Server:** v1.12 delete is live (`004_delete.cql` applied). The app's delete UI comes next,
  receive side first.
- **Release:** the automatic Redroid gate passed (nightly.12 → nightly.13). The fixed push merged
  cleanly with no manual steps.

## v0.2.0-nightly.12 (live 17:16 UTC, not required): groups rejoin after logout/login
- **Cause:** after logout, the app never rejoined its groups. Two server bugs also let a pending
  removal undo the re-add.
- **Fix:** automatic rejoin (another member's phone re-adds yours). The group shows "Rejoining
  group…", never "New group", and sending is blocked with a reason until the rejoin is done.
- **Also:** the cursor fix (nightly.11 kept replaying the inbox until a new message arrived).
- **First release through the automatic Redroid upgrade gate on spark2** (published nightly.11 →
  nightly.12: messages kept, no sign-in, new message delivered). No override was needed.
- Released from a clean worktree (`scripts/release-from-worktree`). The push race was fixed
  afterwards; nightly.12 itself was merged into main by hand, with the tag on the tested commit.

## P0 fixed in v0.2.0-nightly.11 (live 15:52 UTC, not required)
- **Cause of the lost chats:** in nightly.10, every Log out or Sign out button wiped all chats in
  one tap, including the escape screens (not allowlisted, locked, update, phone). Pilot logs show
  real logouts from the testers' phones. Now only the Settings and Chats-menu Log out wipes, after
  "This deletes the chats on this phone". Escape screens keep chats, and a different account asks
  first.
- **Recovery:** the app replays the inbox once. The server restore copied **173** senders' messages
  (idempotent; a second run copied 0).
- **Also fixed:**
  - device registration ran while the app was locked (tokenless 401), so E2EE, groups and push
    stayed off;
  - the group readiness rule (§12.1);
  - the picker rows;
  - scrolling;
  - code resend countdowns.
- **Upgrade gate overridden by Harsha** (decision 044); the shipped APK is the staged candidate,
  sha256 `59561bab1264`.
- **Push is on:** FCM auth was verified on the pilot. No phone has a push token until it runs
  nightly.11.
- **Open:** the unattended upgrade test (Redroid on spark2) needs Harsha's one-time sudo step to load
  the kernel binder module.

## Where things stand (updated 2026-10-06, after the spark2 reboot)
- **Reboot recovery verified.** spark2 rebooted and **everything came back by itself**:
  - Postgres and Cassandra healthy (Docker restart policy);
  - Caddy active;
  - `risime.service` active (systemd user unit with linger), with its health timer;
  - `https://risime.risicloud.ai/health` 200 on nightly.8.

  The orchestrator session and its agents ended with the reboot. Their uncommitted work was
  checked: the v1.8 interop test edits passed the gate and their live checks, and were committed
  (`4e6bb89`). Nothing was discarded.
- **P0 fixed in v0.2.0-nightly.9** (live, not required, versionCode 20009): the "Update required"
  gate locked testers out.
  - **Root causes:** notes passed raw into `Text`; no `Surface` behind the screen, so text was
    black on dark; a non-scrolling column pushed the buttons off-screen.
  - **Fix:** a pinned Update button, scrolling notes, theme colours, a plain-text `summary`
    (published in `version.json`), Open download page, Sign out, errors with Retry.
  - Robolectric Compose UI tests cover long notes, 320×480 dp, 2× font, dark and light mode, and
    the button visible and enabled.
  - Testers install from https://risicloud.ai/app/risime/ in the browser.
- **The interop E2EE "flake" was a test-harness bug**, now fixed. A killed run left its users, so
  the next run reused them and hit a stale MLS group. Setup now purges leftovers; proven by
  SIGKILLing a run.
- **E2EE is on:** pilot key `x-nu0EBHzGv-…`. Chats upgrade once both people run an E2EE-capable
  app (nightly.7+).
- **Emoji and reactions (v1.8) shipped in nightly.9.**
- **v0.2.0-nightly.10 live: end-to-end encrypted groups** (contract v1.9, decision 041;
  versionCode 20010, not required). Both gates passed, live interop passed with 11 new group checks
  (real MLS core, 5 devices), and the backup now includes blobs. The pilot was migrated by
  run-server after the backup.
- **Interop now runs on its own store** (`risime_interop` DB and keyspace, migrated each run). It
  used to write throwaway users into the pilot's `risime_dev`.
- **Server test flake fixed** (`5d4a61a`): test pushes leaked into the next test under load; they
  are now routed by a per-test token. 30/30 under heavy load.
- **Queue:** encrypted images (contract v1.10, extends the v1.9 blob API), then group icons.

## Waiting on Harsha (batched)
- ~~One sudo step on spark2 for redroid~~ **done by Harsha**: the gate runs automatically since
  nightly.12. For reference (decision 045):
  ```
  sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
  echo binder_linux | sudo tee /etc/modules-load.d/redroid.conf
  echo 'options binder_linux devices="binder,hwbinder,vndbinder"' | sudo tee /etc/modprobe.d/redroid.conf
  ```
  The first line loads the kernel's binder driver now; the other two load it at every boot. Check
  afterwards with `scripts/android-target check`. From then on, `scripts/nightly-release` runs the
  upgrade test in a redroid container on 127.0.0.1 and refuses to publish without a PASS.
- The fail2ban jail and removing Tailscale, if not done yet (`docs/PROD.md`).
- Firebase: `google-services.json` and `fcm-service-account.json` into `~/risime-keys/`.
- Notify.lk "RisiMe" sender approval (SMS phone verification goes live then).
- RisiWork `version.json` example.

## Built so far (shipped)
| Release | Contents |
|---|---|
| v0.1.0 | One-to-one chat, store-and-forward, ticks, dev OTP login (smoke-tested on two devices) |
| v0.2.0-nightly.1 | Presence/last seen and typing (contract v1.2), Settings, chat polish (unread, day separators, copy, retry/delete, search), design system + accessibility, Oban, JSON logs/metrics/`/health`, 200- and 2000-user load tests with fixes, prod prep files (not deployed), the E2EE spike (OpenMLS crate) |
| v0.2.0-nightly.9 | P0 update-screen fix, emoji and reactions (v1.8) |
| v0.2.0-nightly.11 | P0 hotfix (chats kept), push on, reinstall history (v1.10) |
| v0.2.0-nightly.10 | Encrypted MLS groups (v1.9), blob store, interop store isolation |
| v0.2.0-nightly.2 | RisiCloud sign-in (contract v1.3; dormant until the Keycloak client exists), fingerprint-unlocked refresh tokens, the release-only in-app updater, the publish step to risicloud.ai, the option 2b config (off), the E2EE `.so` built natively on spark2 |

## Done since nightly.2 (in v0.2.0-nightly.3)
- **SMS phone verification** (contract v1.4): server and app done. It is **off on the pilot**
  (`PHONE_VERIFICATION=off`) until Notify.lk approves the "RisiMe" sender ID. The NotifyDEMO guard
  blocks OTP sends, per Notify.lk's suspension warning.
- **Prod-mode pilot release** (decisions 023, 024): per-IP auth limits, the fail2ban auth log and
  jail, `scripts/run-server`. The release app defaults to `https://risime.risicloud.ai`.

## Blocked, and on whom
| Item | Waiting for | Who |
|---|---|---|
| Real Keycloak sign-in | Keycloak client `risime` (redirects `ai.risicloud.risime[.debug]://callback` and `…://logout`, `email` in access tokens) | RisiCloud lead |
| Real verification SMS | "RisiMe" sender ID approved by Notify.lk | Harsha / Notify.lk |
| Updater field names | RisiWork's `version.json` example | Harsha |

## Notes
- spark key: rrsync works and gives no shell, but the forced command lacks `-wo`, so reads are
  allowed. Adding `-wo` is recommended; publishing doesn't need reads.
- Release builds from nightly.3 on default to `https://risime.risicloud.ai`. It works once the
  public URL is live; until then, testers use "Server · Change".
