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

## 2026-10-10 network outage (09:20–10:00 Sri Lanka = 03:50–04:30 UTC): recovered
Checked at 04:32–04:45 UTC (pilot still nightly.47, RISI_EVENTS on):
- **/health** local + public 200 throughout. spark2's own health timer saw no failure, because the
  public URL loops back to spark2's IP, so it can't see an outside outage (fixed below).
- **boot-check:** BOOT CHECK OK (off, on, on-reopen, wrong key; RISI PREFLIGHT OK).
- **Risi canary:** RISI CANARY OK (140 s; the agent talked only to loopback).
- **Websocket:** the route is reachable (403 without a token), and phones reconnected at 04:32–04:33 UTC.
- **FCM:** fcm.googleapis.com and oauth2.googleapis.com are reachable. The last real push was ok at
  03:53. Pushes after that were "skipped: online", as expected, because the phones were connected.
- **Login / token refresh:** Keycloak is **not in use yet** (OIDC_ENABLED off; the `risime` client is
  still waiting on RisiCloud). Phone sign-in (/auth/config 200) works. There is no refresh endpoint;
  sessions are long-lived tokens.
- **Not outage-related, but open:** since ~02:00 UTC several phones (6–7 mobile IPs) get
  `401 invalid_token` on /friends, /me and PUT /me/devices/<id>, and one device has had nothing but
  401s since 04:18. This started before the outage. The affected phone probably needs to sign in again.
  To investigate (server): why a session became invalid, and whether the app shows "Sign in again".
- **Outage self-check added** (c520def): `scripts/outage-check --tick` runs every 5 min from
  scripts/healthcheck. It probes outside hosts. After an outage it runs the checks above plus
  boot-check (deferred while a release gate runs), appends them here and commits this file
  locally.
- **nightly.48 run n48f (03:18 UTC)** failed at 04:21, during the outage: every UI step passed,
  then one adb call failed with an empty device serial. The same steps re-run at 04:26 passed
  (UI ENTRY OK). The ui-entry-test ERR trap now names the line. Run **n48g** started 04:58 UTC.

### Plan (Harsha 2026-10-10), in order
1. nightly.48: Risi Calendar fixes + Notes code (RISI_NOTES stays off until "turn the ledger on"):
   gate running (n48g).
2. UI batch + contract **v1.32** `sync_off` (branch `v1.32`, brief docs/status/briefs/ui-batch-2026-10-10.md):
   the Android and server agents are working on it. Root adds ui-entry-test at font scale 1.0 and 1.3, then nightly.49.
3. Google Calendar link (v1.31, coded; RISI_GCAL off): the --google gate after nightly.48.
   Needs "Google clients done".
4. Model eval (8101 only): eval set + harness + risi-l1 baseline pushed. Live Whisper ASR
   baseline: si 108% WER, ta 58% WER. Candidates run when no gate is running.
5. SPEC-GAP.md: done (9237da0; 40 live, 8 behind a flag, 4 code only, 15 not started).
6. Next phase: voice notes, files, video first; then the Risi Calendar UI; then My Risi
   (an Official-only knowledge hub).

## v0.2.0-nightly.47 (live 2026-10-09 ~22:34 UTC, not required): Risi Calendar (RISI_EVENTS on since 22:34 UTC)
- **Risi Calendar (contract v1.29 §29, decision 073):** RisiMe's own calendar, sealed per user on the
  server and synced to every phone you sign in on. Nothing from Private tabs.
  - A **Calendar** tab with agenda, day, week and month views.
  - Risi adds events itself through a native card [Add][Edit][Cancel], never text chips.
  - Times agreed in Official chats become proposed events, with an invite card for each person
    [Accept][Decline][Suggest another time].
  - An event card with a small day timeline.
  - Reminders 30 min before, plus the digest.
  - "Am I free…" always names what was checked ("Checked: Risi Calendar · Not checked: Google
    Calendar (not connected)"); it never says "clear" without reading one.
- **Backfill:** existing upcoming agreed items (the Shenika interview among them) get proposed
  events and invite cards. 3 items qualified. The run at 22:40 UTC made **0 cards**: invites
  only go to phones running the nightly.47 app, and none had updated yet. The backfill is
  idempotent; root re-runs it once Harsha and Shenika have updated
  (`bin/risime eval "RisiMe.Release.risi_calendar_backfill(dry_run: false)"`). Next build: the
  server re-runs it for a person when their phone first reports Calendar support.
- **Release check:** the full set of checks plus phone tests, including Harsha's gate (his
  conversation replayed → event in the Risi Calendar within 2 turns; invite cards for both;
  accepted events in both calendars). Screens: docs/status/screens/calendar-*.png,
  event-card-official.png, risi-calendar-action-card.png.
- **Gate fix:** the test phone tapped "Risi skills" while it was behind the navigation buttons
  (Settings grew). Settings navigation now scrolls in small checked steps (1036837). Also
  e71d9ca (time-of-day test) and 3d9e7cd (loopback-only epmd allowed).
- **Switch:** `scripts/risi-flag RISI_EVENTS off` turns it off with the same safety checks.
- Risi Notes (§30) is in the build but **off** until Harsha says "turn the ledger on".

## v0.2.0-nightly.46 (live 2026-10-09 ~14:54 UTC, not required): honest calendar answers, calendar offers, My promises split
- **Calendar honesty** (Harsha's "your calendar is clear" while Google Calendar had events): every
  calendar answer ends with "I checked: Phone calendar — <calendars> N events"; with no successful
  read the server replaces the model's text with "I couldn't read your calendar on this phone
  (<reason>)…". Never "free/clear" without a read (server-enforced). Settings → Risi skills → Calendar →
  Details lists every phone calendar with account, 7-day event count, hidden / sync-off.
  - Root cause on Harsha's phone: the app's read was correct (Instances, all visible calendars); the
    phone's calendar store returned 0 events at 09:18 → most likely Google Calendar sync to the
    phone is off, or the calendars are hidden. Details now shows which.
- **Items 8–10:** "Add to calendar?" / "Remind me?" offers for dated promises; "When is it due?" for
  vague ones; duplicates merged; My promises "I promised (n)" / "Promised to me (n)" with tap-through,
  counts == digest.
- **Invalid-output loop fixed:** a model calling an unavailable tool gets one corrective retry, then a
  server-built answer in seconds (was: retried every 30 s without limit).
- Risi Calendar (§29) and Risi Notes (§30) code ships switched off (RISI_EVENTS, RISI_NOTES).
- **Gate:** all PASS incl. ui-entry --skills --ledger --calendar --offers (twice locally), boot
  check, canary, push, upgrade. First gate run failed once at the calendar step (the test phone left
  Settings; not reproduced). The deploy's final epmd check tripped on a loopback-only epmd from a
  test node: fixed in run-server (3d9e7cd) to fail only on non-loopback binds.
- **Next:** nightly.47 Risi Calendar on; nightly.48 Risi Notes (needs "turn the ledger on").

## Needs Harsha G (NOW, priority 2026-10-10): Google Calendar connection (Google Cloud Console, ~10 min)
**Use docs/GOOGLE-CALENDAR-SETUP.md** (click by click; SHA-1 checked against the published nightly.47
APK). Then send "Google clients done" + project ID + the Android client ID. The notes below are the
earlier draft.
Direction change 2026-10-09: RisiMe gets its own Risi Calendar and Risi Notes first (nightly.47, .48);
Google becomes an optional one-way mirror later. These steps can wait.
Project `risime` (734811134567), console.cloud.google.com. The section is "Google Auth Platform"
(older UI: "OAuth consent screen").
1. APIs & Services → Library → **Google Calendar API** → Enable.
2. Branding: app name RisiMe, your support email and developer email, no logo for now, home page
   `https://risime.risicloud.ai`, privacy policy `https://risime.risicloud.ai/privacy` (I'll publish
   it), authorized domain `risicloud.ai`.
3. Audience: External, **Testing**; Test users → add your Google account and the pilot testers'
   (up to 100).
4. Data Access → add scopes manually: `https://www.googleapis.com/auth/calendar.events` and
   `https://www.googleapis.com/auth/calendar.calendarlist.readonly` → Save. Nothing else.
5. Clients → Create client → **Android**:
   - release: package `lk.codegen.risime`, SHA-1 `4C:E1:A7:54:30:D4:D9:CB:E6:B2:1D:0A:D6:9B:12:5C:F8:67:D4:D0`;
   - debug: package `lk.codegen.risime.debug`, SHA-1 `E5:09:19:56:B7:4E:F6:58:2E:90:BD:3E:46:FD:7B:6A:9E:5A:30:4E`.
   If Console says one already exists (Firebase auto-created it), keep that one.
6. No Web client and no google-services.json change are needed. Tell me "Google clients done".
- **Testing mode:** only listed test users can connect; the grant ends every 7 days and the app
  shows "Reconnect Google Calendar".
- **Before a public launch (Google verification, no security assessment needed):** verify
  `risicloud.ai` in Search Console (DNS TXT); public home page and privacy policy on that domain
  (with the Limited Use statement); a one-paragraph justification per scope; an unlisted YouTube demo
  video (consent screen, each scope in use, Disconnect). Brand review takes 2–3 days, scope review
  days to weeks.

## v0.2.0-nightly.45 (live 2026-10-09 ~09:11 UTC, hotfix, not required): P0 Risi calendar action loop; §27 follow-ups (ledger off)
- **P0 (Harsha, real phone, nightly.43):** "add my interview with Shenika on Mon 12 Oct 2pm to my
  calendar" produced chips, then 6 turns of questions, and nothing was added (`risi_turn_steps`: every
  turn was one step `tool=final`, no tool chosen).
- **Fixed (server 2b448cc; android fee8d84 → 08603a6):**
  - Risi chat remembers the last 20 turns plus a sealed pending draft;
  - writes only as real action cards [Add][Edit][Cancel]; chips only fill the composer;
  - defaults (phone time zone, 1 h, title from context), at most one question;
  - a loop guard after 2 non-progress turns → a prefilled card;
  - a ledger lookup prefill; "12 Oct" no longer read as 12 o'clock;
  - a real calendar tool on a synced Google calendar (picker remembered, read-back verify, exact
    failure reasons), a success line with [Open][Undo];
  - digest counts == My promises.
- **Gate:** all PASS, including ui-entry-test --skills --ledger --calendar (Harsha's conversation
  replayed: one card, one Add, the event read back from the Google calendar), the 5-turn context
  test, the loop guard, boot check, canary, push, upgrade (updater, Back, locked chat, per-chat
  counts, restore, reinstall).
- **Gate harness fixes on the way:** ui-entry scrolls "What Risi knows" clear of the nav bar
  (039e36c); fake-llm matches the current `<question>` now that history precedes it (40577c2).
- **§27 follow-ups** (per-person summaries, call transcription plumbing) are in this build but off
  (RISI_LEDGER, RISI_TRANSCRIBE off) until Harsha says "turn the ledger on".
- **Next release:** item 8 proactive "Add to calendar?" offers (backfill the Shenika interview);
  item 9 My promises "I promised" / "Promised to me"; item 10 dedupe and vague-item clarification.

## v0.2.0-nightly.44 (live 2026-10-09 ~07:30 UTC, not required): screen share fixed, Risi text sealed at rest
- **What's in:**
  - "Entire screen" share shows RisiMe (FLAG_SECURE only on the app lock / Locked chats folder /
    an open locked chat), with a pre-share warning;
  - the viewer sees an aspect-fit share, never cropped; a still shared screen stays visible;
  - Lock chat removed from the in-chat ⋮;
  - Risi-derived text sealed with RISI_DATA_KEY (c69985a);
  - the fixed-clock tests.
- **Gate:** all PASS (ui-entry --skills, boot check, canary, push, upgrade).
- **Verified on the pilot after the migration:**
  - 0 plaintext / 6 sealed commitments;
  - 0 / 12 facts;
  - 0 / 89 learning-log outputs.
- **Pilot switches:** TABS, RISI, RISI_TOOLS, RISI_SKILLS on (skills turned on 06:08 UTC at
  Harsha's request, guarded, healthy). RISI_LEDGER and RISI_TRANSCRIBE off.

## v0.2.0-nightly.43 (live 2026-10-09 ~13:00 UTC, not required): Calls tab like WhatsApp; Risi Skills and reminders (switched off)
- **Calls tab:** select and delete, Clear call log, voice/video icons, New call, the call info screen.
- **§25/§26 shipped, OFF on the pilot** (`RISI_TOOLS`, `RISI_SKILLS` unset; `RISI_MEMORY_KEY` absent):
  - Settings → Risi skills;
  - confirm / skill_done / skill_needed cards;
  - set_reminder with Me too;
  - a real alarm;
  - scheduled messages sent by the phone.
- **Gate:** all PASS, including ui-entry-test --skills (a real alarm in DeskClock, a phone-sent
  scheduled message, revoke → skill_needed), the boot check, the canary, push and upgrade.
- **To switch tools/skills on for testers (Harsha decides):** `scripts/risi-enable`-style:
  `RISI_MEMORY_KEY` into .env (generated), then `RISI_TOOLS=on RISI_SKILLS=on` in pilot.env,
  restart, `/health` `risi_skills: ok`.
- **Next (n44):**
  - the sealing of Risi-derived text (c69985a; the migration seals 71+5+10 rows);
  - the screen-share fixes;
  - Lock chat removed from the in-chat ⋮;
  - the fixed-clock tests.

## Harsha's real-phone feedback (2026-10-09 morning): answers and work started
- **Model split so far (learning log):** 45 of 45 Risi calls on our own **risi-l1**
  (Qwen3.6-35B-A3B-FP8): 15 ask, 22 commitment_extract, 4 summarise, 4 report. The commercial
  fallback has never run (no provider configured).
- **Today's Harsha–Shenika call was NOT transcribed:**
  - no speech-to-text exists yet (Stage 3);
  - §20 keeps agents out of calls ("never rung, never join, no room token");
  - any summary mentioning the call came from Official chat text.
  No P0.
- **Started:**
  - **android:**
    - Lock chat removed from the in-chat ⋮;
    - "Entire screen" share not black (FLAG_SECURE only on the app lock / Locked chats folder /
      open locked chat), plus a pre-share warning;
    - shared screen aspect-FIT, never cropped;
    - device checks and screenshots.
  - **root:** the contract for §27 on branch `contract/v127`:
    - `made_by` on every Risi message;
    - per-person discussion summaries in each person's Risi chat, with own-item confirms and
      reminders/nudges for both parties;
    - call transcription only from Official, with "Risi is listening" from the start.

## v0.2.0-nightly.42 (live 2026-10-09 ~06:30 UTC, not required): Risi hotfix from Harsha's screens
- **What's in:**
  - the Risi system prompt (what it can do now, what's coming; never "the transcript does not
    contain");
  - sources as tappable quotes (no "Message 1");
  - the 1:1 Official header "Kumu · Risi";
  - the placeholder "Message";
  - the "Continuing with Risi" follow-up chip;
  - limiter and upstream logs, backoff and fallback;
  - no empty "created the group" line.
  The §25 S9–S12 groundwork ships too (`RISI_TOOLS` off).
- **Released from branch `hotfix/n42`** (96e1ed0 plus gate fixes), because main already had §26
  merged. The merge back into main was clean.
- **Gate:** all PASS (ui-entry-test --risi, boot check, canary, push, upgrade).
- **Attempts 1–3 failed on test infrastructure, all fixed:**
  - a one-off SIGABRT in the interop JVM;
  - the upgrade test's locked-chat step vs the n41 selection bar (`56c83e3`);
  - a fake-llm port clash with a parallel agent run (`d3940f8`).
- **On main, coming in n43:**
  - the Calls tab like WhatsApp;
  - the server side of §26 Skills, reminders, alarm and scheduled messages;
  - the app side of §26 (in progress).

## v0.2.0-nightly.41 (live 2026-10-09 ~03:00 UTC, not required): Lock chat everywhere, tab row, encryption label, Risi queue
- **What's in:**
  - Lock chat in Chat info, long-press ⋮ and the chat ⋮;
  - Chat lock settings (Hide locked chats, Secret code);
  - the tab row restored after a cold start, with Start Official in the row;
  - the Chat info encryption check that is never stuck;
  - Risi requests queued at 20/min (no "Too many requests" on the 2nd question).
- **Gate:** all PASS, including the new **ui-entry-test** (user-style taps; screenshots in
  docs/status/screens/), the boot check, the Risi canary, the push test and the upgrade test.
- **On main for n42:**
  - the server Risi hotfix (system prompt now/coming, no "Message 1", limiter/upstream logs, backoff
    and fallback);
  - §25 S9–S12 (`RISI_TOOLS` off on the pilot);
  - crypto C4;
  - the app's A7/A8 and hotfix items 3–6 are in progress.

## Risi queue (set 2026-10-09)
1. **Risi hotfix** (n42), from Harsha's real-phone screens:
   - our limiter → the queue (in n41) plus limiter and upstream logging, backoff and fallback;
   - the system prompt with what Risi can do now and what's coming;
   - sources as tappable quotes (no "Message 1");
   - the 1:1 Official header "Kumu · Risi";
   - the placeholder "Message";
   - the "Continuing with Risi" follow-up chip;
   - all of it in ui-entry-test with screenshots.
2. **§25 tools, wave 1** (running): S9–S12 (risi_tools, Risi chat, Turn loop, audience) and A7–A8
   (decoders, Risi chat row).
3. **Reminders tool:** set_reminder plus confirm card, Me too, firing (S13–S14 / A10).
4. **"Risi Skills"** (Harsha 2026-10-09). Skills are the permission layer every tool runs under.
   - **Settings → "Risi skills"** lists each skill with:
     - what it can do and its exact permissions;
     - a switch, off by default; turning it on asks for the Android/OAuth permission there and then;
     - "Ask me each time" / "Allowed";
     - an activity log, plus undo and Revoke.
   - **Rules:**
     - Risi acts only for the asker; others' messages never trigger a skill;
     - every action is logged and undoable where possible;
     - skills run from the Risi chat or @Risi in Official, never Private.
   - **Order:**
     - **(a) Alarm:** AlarmClock intent on the asker's phone, confirm first;
     - **(b) Reminders** (item 3);
     - **(c) Calendar:** a client tool. An agreed meeting in Official → "Add to calendar for: Harsha ✓
       Kumu ☐", each person confirms on their own phone, optional reminder;
     - **(d) Scheduled messages:** stored and sent by the asker's PHONE (exact alarm/WorkManager),
       encrypted at send time, never by the server, with a clock icon, edit/cancel and "sent late",
       and a list in chat ⋮; "every day" supported;
     - **(e) Email** (later, pilot): Gmail via Google OAuth (test-users mode, ≤100 users) and Outlook
       via Microsoft Graph, read-only first; sending only with a full-draft confirm card.
   - **Partner agents** (Lia, eDrop) later appear as skills with their own permissions.
   - **Tests:**
     - "wake me up at 6" → a real alarm in Redroid's clock;
     - "send Kumu good morning at 6am" → sent by the phone, arrives encrypted;
     - a revoked skill → Risi says it has no access and how to turn it back on;
     - another member's message can't trigger a skill.
   - **Contract:** a §25 addendum (skill registry/state, the client tools `set_alarm` and
     `schedule_message`, the meeting "add for" card).
   - **Risi summaries over 30 days (Harsha 2026-10-09):**
     - Summarise today / 7 days / 30 days / a range, from **daily summaries rolled up**
       (daily → weekly → 30-day) plus confirmed commitments; raw text stays at 24 h.
     - The answer states the period and links to the days it used.
     - Daily summaries are sealed with RISI_DATA_KEY, listed in "What Risi knows" → Summaries, and
       deletable.
     - Folded into the §27 build (a contract addendum: `scope` ranges, `daily_summary` storage and
       REST).
5. **"Teach Risi"** (Harsha 2026-10-09):
   - **Profile:** Settings → "My Risi profile" (bio, roles, people, preferences, interests).
   - **Ways to teach:**
     - paste text or upload PDF/DOCX → extracted fact cards to confirm/edit;
     - "Interview me" in the Risi chat (5–10 questions, stoppable and resumable);
     - "Risi, remember that …" → confirm card;
     - voice later.
   - **"What Risi knows about me"** becomes one grouped view with sources, edit and delete.
   - **Visibility per item:** Only me (default) / My contacts; never sent to partner agents without
     a per-request Yes.
   - **Storage:** sealed with the Risi data key, never in logs, nothing from Private.
   - Answers cite the profile.
   - Needs a §25 addendum (contract proposal: profile REST, extraction/confirm, document upload,
     visibility) when it starts.
6. **The rest of §25:**
   - calendar client tool (S15 / A9);
   - notes and memory (S16), merged into Teach Risi;
   - search / summarise / draft (S17);
   - RisiWork (S18);
   - routing (S19);
   - the ten-question and safety gate.

## v0.2.0-nightly.40 (live 2026-10-09 ~01:10 UTC, not required): Risi boot isolation, Risi REST without device header
- **What's in:**
  - Risi can never stop the server (`5503d83`): `/health` `checks.risi`, a KEK check, the read-only
    preflight;
  - `/api/v1/risi/*` without X-Device-Id (`ce8d347`); the app sends X-Device-Id on every request
    and shows honest Risi error messages (`2969b11`);
  - §24.13 queue limits are in the contract (the server change follows).
- **Gate:** all PASS, including the new **boot check** (prod release with the real env: off, on,
  reopen, wrong key → healthy with kek_mismatch; pilot preflight OK) and the Risi canary.
- **Live:** `/health` → `"risi":"ok"`.
- **Next (nightly.41):**
  - the lock-chat entry points in all 3 places;
  - the tab row under the header;
  - "Checking encryption…" fixed;
  - the UI entry gate with screenshots;
  - Risi request queueing.

## P0 (2026-10-08 23:24 UTC): pilot crash-looped with RISI=on; Risi is ON again since 23:33 UTC
- **What happened:**
  1. The orchestrator turned Risi on at 23:23 (keys pair #1 in .env; healthy; Risi sealed 33
     `risi_mls_kv` rows with key #1).
  2. Harsha then ran the same enable commands, which appended key pair #2. systemd uses the last
     value, so `RisiMe.Agent.Mls.init` got `Risi MLS open failed: :tampered`.
  3. The failure happened during the initial application start, so the whole app exited and
     systemd restart-looped.
  4. Harsha set the flags off: healthy again.
- **Two causes:**
  - **(a) operational:** a duplicate key pair (my enable appended without checking; my report didn't
    make clear it was already done);
  - **(b) server bug:** a Risi failure at boot takes the server down (the `:temporary` child only
    protects after start).
- **Done:**
  - **Guarded enable (Harsha approved):**
    - backups `~/risime-backups/{env,pilot.env}-before-risi-on2-20261008T233312Z`;
    - kept the FIRST key pair (it sealed the store), dropped the duplicate;
    - one TABS/RISI pair; restart;
    - 60 s health and Risi watch with automatic rollback to off.
    - **Result:** healthy, `tabs: on`, NRestarts=0, no errors, store intact (33 rows, 31 key
      packages).
  - **Gate:** `scripts/boot-check` (`96746fd`) in nightly-release, before publish. The prod release
    boots with the real .env + pilot.env against throwaway stores:
    - Risi off, on, and on again over its sealed store;
    - **on with a wrong RISI_MLS_KEK (must still boot healthy)**;
    - plus a read-only Risi preflight against the pilot DB.
    It reproduces tonight's crash on the current code (on-wrongkey FAIL), so **no release passes
    until the server fix lands**.
- **In progress (server agent):**
  - Risi can never stop the server (boot isolation);
  - a KEK check value with a `kek_mismatch` reason;
  - `RisiMe.Release.risi_preflight/0`;
  - `/health` gains a `risi` field.
- **To-do:** make the enable command idempotent (never append a second key) — use the script in the
  orchestrator's notes or `scripts/risi-enable` (root, next).

## v0.2.0-nightly.39 (live 2026-10-08 ~20:50 UTC, not required): one call screen, §23 1:1 switching and screen share; Risi gate
- **What's in:**
  - the WhatsApp-style single call screen;
  - voice↔video mid-call (1:1) and screen sharing;
  - the review fixes (renderer detach, camera off after a failed switch, ICE compared with the
    current SDP, late re-answer bounds, notifications redacted while sharing);
  - S8 `/risi/*` REST;
  - X-Device-Id on group GETs;
  - the deflakes.
- **Gate:** all PASS, the new steps included:
  - **Risi canary:** `RISI CANARY OK`. No Private canary in model input, Risi tables, the decrypted
    buffer, the learning log, server logs or pushes; egress loopback only.
  - **Upgrade test:** per-chat counts, and a locked chat kept across the update.
  - **Call test (run separately):** call-device-test CALLTEST OK, 99 checks.
- **Risi/tabs are still OFF on the pilot.** Turning them on was blocked by the permission system
  (production feature flag) and needs Harsha (see Needs Harsha #R).

## v0.2.0-nightly.38 (live 2026-10-08 ~18:30 UTC, not required): Risi stage 1 in the release, still OFF
- **What's in, all hidden (TABS=off, RISI=off):**
  - S5 (Risi's agent tree);
  - S6/S7 (the risi-l1 router, learning log, commitments ✓, reminders, escalation, digest, @Risi
    ask/summarise/report, learn and delete);
  - A6 (Risi cards, @Risi chip, "What Risi knows about me", My promises, tz, backup schema 2);
  - crypto backup schema 2.
  The release builds Risi's NIF (`--prod`).
- **Gate:** all PASS.
- **Next:** nightly.39 with S8 (`/risi/*` REST) and the R2 canary gate. Then the pilot env gets the
  keys and `TABS=on RISI=on`, only after `RISI CANARY OK`.

## v0.2.0-nightly.37 (live 2026-10-08 ~17:25 UTC, not required): call records, locked chats, two-tab groundwork (off)
- **Call records:**
  - chat rows for every outcome;
  - the Calls tab with grouping and call info;
  - the missed-call notification with Call back / Message.
- **Locked chats:**
  - a pull-down folder, unlocked with fingerprint or the phone PIN;
  - a secret code;
  - "New message" notifications.
- **Fixes:** the ringback crash; the app lock never traps the user (as in n36).
- **Hidden (TABS=off, RISI=off on the pilot):**
  - contract v1.24 §24;
  - the crypto tab and agent rules;
  - Risi's MLS NIF;
  - the server's two tabs (S1–S4 plus the privacy review fixes);
  - the app's tabs A1–A5.
- **Gate:** all PASS (push test, upgrade test with counts/restore/reinstall).
- **On main after n37, coming in nightly.38:**
  - S5 (Risi's agent tree);
  - S6/S7 (the secretary: risi-l1 router, learning log, commitments, reminders, digest, @Risi);
  - A6 (Risi cards, chip, "What Risi knows", My promises, tz, backup schema 2 wiring);
  - crypto backup schema 2.

## v0.2.0-nightly.36 (HOTFIX, live 2026-10-08 ~17:00 UTC): the app lock never traps the user, ringback crash
- **What's in:** branch `hotfix/applock` = v0.2.0-nightly.35 + `433b8c3` (the lock fix) + the
  ringback double-release fix + the HOTFIX release mode.
- **Gate:** all PASS, upgrade test included (counts, restore, reinstall); `scripts/applock-device-test`
  APPLOCK OK.
- **Main:** merged in by hand (`95b1c4c`): main already had the code; VERSION and the notes were
  taken.
- **Harsha:** install n36 over the top from risicloud.ai/app/risime/.

### (Earlier) P0 HOTFIX: the app lock traps the user (nightly.35)
- **Report (Harsha, real phone, n35):** "RisiMe is locked" shows, and "Unlock with fingerprint"
  does nothing.
- **Root cause (root's code analysis; the agent confirms it on a device):**
  - with "Automatically lock: Immediately", the lock sets itself while the activity is stopped;
  - the lock screen's auto-prompt then calls `BiometricPrompt.authenticate` after
    onSaveInstanceState, which androidx silently ignores, so no callback ever comes;
  - the coroutine never resumes, `AuthUi._busy` stays true, and every tap is ignored;
  - errors are also dropped silently, and there is no PIN fallback.
- **Fix (android agent, top model):**
  - prompt only when RESUMED, and on every tap;
  - BIOMETRIC_STRONG|DEVICE_CREDENTIAL (API 30+), "Use PIN" below that;
  - errors shown with "Use phone PIN/pattern";
  - the lock turns itself off when there is no credential at all;
  - a Redroid `scripts/applock-device-test` with a PIN.
- **Release:**
  - **HOTFIX branch `hotfix/applock`**, cut from v0.2.0-nightly.35 plus `f8aca60` (the HOTFIX
    release mode) and `72f7251` (the ringback crash fix);
  - released with `HOTFIX=hotfix/applock scripts/release-from-worktree` through the full gate, as
    **v0.2.0-nightly.36**;
  - main's unreleased work follows as nightly.37.
- **Workaround meanwhile:** Settings → Apps → RisiMe → Force stop, then open RisiMe. On a fresh start
  the prompt may appear. Nothing is lost either way.

## Overnight run (2026-10-08 night → 10-09): Harsha unavailable, release each slice when green
**Rules:**
- Work non-stop. Release each slice when it is green through the full gate.
- Stop only for sudo, credentials or a true product decision; batch those under "Needs Harsha" and
  carry on with the next item.
- The top model plans and reviews; Sonnet does routine work. Every step has a hard timeout.

**Order and state:**
1. ✅ Release main: nightly.32 (backups), nightly.33 (push audit, emoji panel, attachment sheet).
2. ✅ The P0s:
   - nightly.34: call audio routing;
   - nightly.35: background delivery + no mandatory lock / the optional Fingerprint lock (decision
     064).
3. ⏳ **One call screen for voice and video, as in WhatsApp** (§23 switching, screen share, call
   info). The android agent (top model) is on it.
4. ⏳ **Call records** (chat rows, Calls tab, missed notification) and **locked chats**: android
   agents (Sonnet), running.
   - **Gate to-do (root):** from nightly.37 on, `scripts/upgrade-test` sets a redroid PIN
     (`locksettings set-pin`) and locks a chat through the real DEVICE_CREDENTIAL prompt on the old
     app (n36+). After the update the chat must still be hidden, and its counts kept.
- **nightly.36 attempt 1 (from `8f8d7b6`): stopped by push-device-test scenario 5.**
  - **The crash:** the caller's app crashed natively at hang-up (`decStrong() called … too many
    times`, `ToneGenerator.release` from `CallManager.updateRingback` on two threads). A real
    crash for anyone hanging up quickly.
  - **The fix:** an android agent is on it. Nothing was published.
   - **Queue (android, after the call screen):**
     - **Route race:** CallManager's route decide-and-request isn't atomic. A "speaker" decision made
       before the user's earpiece pick can land after it (seen via the TelecomEndpointFanOutTest
       deflake `0e24934`). Fix in production under one lock, like the test.
     - **Locked-chats unlock:** move its gate onto `LockPrompter` (the same dead-prompt pattern as the
       app lock, though only tap-started).
5. ⏳ **Risi.** Harsha answered the §24 proposal `bf1a215`: **proceed**, with a new model, **two tabs
   in every chat: 🔒 Private | ● Official.**
   - Each tab is its own MLS group.
   - Private never admits an agent; the server refuses.
   - Official always has Risi as a visible member, as secretary.
   - Existing chats migrate into Private.
   - Risi rules:
     - no product pushing;
     - offers only as a [Yes]/[Not now] question, at most about 1 per chat per day, and a topic is
       muted after 2 Not nows;
     - learn and delete: only derived facts are kept, raw plaintext is deleted after processing;
     - "What Risi knows about me" with per-item delete;
     - Risi only sees Official.
   - Stages:
     - **Stage 1:** commitments, confirm card, reminders, escalation, daily digest, @Risi, reports;
       `risi-l1` first with a commercial fallback by confidence, every call logged;
     - **Stage 2:** help offers + partner agents (A2A/MCP: Lia, then eDrop);
     - **Stage 3:** transcription in Official (faster-whisper);
     - **Stage 4:** the digital twin.
   - **Next:** the two-tab contract proposal (being drafted by the top model), then PROTOCOL §24
     (v1.24), replacing the earlier Risi-only draft.

## v0.2.0-nightly.35 (live 2026-10-08 ~16:10 UTC, not required): background delivery (P0) + no mandatory lock (decision 064)
- **Background delivery:**
  - a call push no longer crashes the app;
  - the socket closes at screen-off;
  - no MLS events lost on a cold start (registration failures included);
  - a direct sync in the FCM window that never blocks a call push;
  - socket messages notify while the app is in the background;
  - the `messages_v2` channel keeps the user's settings;
  - the Notifications health screen;
  - the server push watchdog (decision 063, ping/pong, 8 s / 4 s).
- **Decision 064:**
  - a token vault without user auth, so the background always has a bearer;
  - a one-time migration prompt for fingerprint installs;
  - an optional Settings → Privacy → Fingerprint lock (Immediately / 1 min / 30 min, Show content);
  - no Recents thumbnail while locked;
  - transient Keystore errors keep the session, with a way out after about 60 s;
  - sign-out triggers logged, and an Account row on the health screen.
- **Gate:** all PASS (`84b5ee3`). The new push-device-test step (an OIDC-style session on B) passed
  every scenario: deep idle, removal from Recents, a message then a call, restricted, and registration
  failing. The upgrade test passed with restore and reinstall.
- **Real-phone checks (Harsha):**
  1. after updating: one "Confirm to finish updating RisiMe" prompt, then never again (also after a
     reboot);
  2. with RisiMe killed or locked, messages and calls arrive with names (also look for
     `grep 'push: .*user=0b1f9c32' ~/risime-logs/server.log`);
  3. the full-screen ring on a locked phone;
  4. the Fingerprint lock options;
  5. the health screen's Account row ("Stays signed in (offline session)", or "Short session" → the
     Keycloak settings above).

## v0.2.0-nightly.34 (live 2026-10-08 ~13:40 UTC, not required): call audio routing (P0)
- **What's in:**
  - the route button is always enabled;
  - Phone↔Speaker on a tap, or a picker when Bluetooth or a headset is connected;
  - video calls start on the speaker, and voice→video moves to the speaker;
  - Bluetooth comes first, and when it goes away, video falls back to the speaker and voice to the
    earpiece;
  - external route picks are respected.
  Commits `1a40df7` + `d27c07c`, which hold the review fixes: the SCO stop, the per-call clear, a
  verified AudioManager route, the no-Telecom path and single-lane threading.
- **Gate:** all PASS (from `d27c07c`). call-device-test with the route steps: CALLTEST OK.
- **Real-phone checks (Harsha):**
  - a voice call: tap Phone↔Speaker;
  - a video call starts on the speaker;
  - BT buds connecting mid-call take the route;
  - switching from BT to Phone in the system output switcher stays on Phone;
  - on Android 8–11 with BT, music plays normally after a call.

### (Hold lifted ~14:40 UTC: review fixes landed in `f104b23`) Was: don't release main past `d27c07c`
- **The blocker:** the review of `55e0a3a`/`c49647d` found that MLS events can still be lost, or the
  whole inbox can stall, when the device registration fails on a push-started cold start. That code
  is on main.
- **Also being fixed:** a 9-s block in `onMessageReceived` that delays call pushes, the channel
  migration losing a Silent setting, and the health screen nagging after every update.
- **The fix:** an agent is on it.
- **nightly.34 is unaffected:** it is released from `d27c07c` (audio routing only).

## Queue (set 2026-10-08 ~12:30 UTC)
1. **P0 background delivery** (agent running).
2. **P0 call audio routing** (`1a40df7` is on main; the review fixes are being made).
3. **WhatsApp parity** (Harsha, 2026-10-08), app-only unless a wire change shows up:
   - **call records:** chat rows, a Calls tab, call back, a missed-call notification with the time;
   - **app lock:** BiometricPrompt STRONG|DEVICE_CREDENTIAL; Immediately / 1 min / 30 min; hide
     content; never signs out or wipes; calls can be answered while locked;
   - **locked chats:** a Locked chats folder, pull-down reveal, a secret code, "New message"
     notifications, local and encrypted only.
   A Redroid gate for each; each released through the full gate.
   - **Call records need no wire change.** §16 already stores one `kind='call'` row per `call_id`
     on both phones.
4. **v1.23 calls:** voice↔video, screen share, call info. Then **Risi v1** (proposal `bf1a215`,
   waiting for Harsha), then the Commitment Ledger.

### Testers forced back to the email sign-in: findings so far
- **The app is already set up for long sessions:**
  - it asks for `openid email profile offline_access` (AppAuthGateway.kt);
  - refresh is single-flight (a Mutex in AuthManager);
  - it signs out to the sign-in screen (keeping chats) only on `invalid_grant` or when a Keystore
    key has been invalidated.
- **The realm (`aoa`) supports `offline_access`.** We can't see whether the `risime` client is
  allowed it, or the realm's offline-session timeouts. If the scope isn't assigned, Keycloak
  silently issues a normal refresh token. That token dies with the SSO session (Keycloak's defaults:
  30 min idle, 10 h max), which matches "forced back to sign-in".
- **Next (app-lock work):**
  - log the refresh token's `typ` (`Offline` vs `Refresh`) and its `exp` at sign-in, never the
    token itself;
  - show it on the Notifications/Account health screen;
  - log *which* trigger caused each sign-out (`invalid_grant` vs key invalidated).

## v0.2.0-nightly.33 (live 2026-10-08 ~12:25 UTC, not required): push audit log, emoji panel, attachment sheet
- **What's in:**
  - **Server push audit lines** (`edb1e9b`):
    - `push: kind=inbox|call user=<hash8> device=<id8> result=… ms=… msg=…`
    - `push: skipped kind=inbox user=<hash8> reason=online devices_online=N`
    - `push: none … reason=no_token`
  - **Android v1.23 UI:** the inline emoji panel (DataStore recents) and the 5-tile attachment sheet
    (`49282f2`).
- **Gate:** all PASS, with the restore phase on by default.
- **Harsha's evidence:** his user hash is **`0b1f9c32`** and his device is `ad92ca09`. After a background or
  locked test: `grep 'push: .*user=0b1f9c32' ~/risime-logs/server.log | tail`.
- **Not in it:** the P0 audio routing (`1a40df7`, on main) plus its review fixes (in progress), and
  the P0 background delivery (in progress).

## v0.2.0-nightly.32 (live 2026-10-08 ~11:50 UTC, not required): backups, video-call audio, fixes
- **What's in:**
  - v1.22 encrypted backups (local, export, server backup with a recovery key, restore on first
    sign-in);
  - P0-3 video-call audio (speaker by default, the route button race);
  - the stuck staged MLS commit fix (`943993a`);
  - the CallManager start-up NPE fix;
  - server v1.23 (switch and screen-share plumbing; the app UI isn't in yet).
- **Gate:** all PASS (`e29fa19`): server, android, live interop, and the upgrade test via the
  in-app updater with **Back during the download**. The upgrade test checked:
  - C updated to n32 before the reinstall (no counts lost);
  - **backup → uninstall → "Restore your chats" with the recovery key** (A's inbox replay purged,
    1:1 counts equal);
  - rejoin without reset.
  The restore phase is now on by default.
- **Not in it:**
  - the emoji panel and attachment sheet (`49282f2`, on main);
  - the P0 audio routing (in progress);
  - the P0 background delivery (in progress).

## Run in progress (2026-10-08, orchestrator, after the account switch)
- **nightly.32:** release running from `e29fa19`. Gate changes in that commit: C updates to the new app
  before the reinstall phase (`UPGRADE_C_STAYS_OLD=1` keeps it old); `RESTORE_CHECK_FROM` defaults to
  20032 and `UPDATER_BACK_SAFE_FROM` to 20031 in nightly-release.
- **Agents:** android P0 audio routing (the route button is always enabled; Earpiece↔Speaker toggle or
  a picker with Bluetooth/headset; video on speaker; voice→video moves to speaker; Bluetooth first);
  android emoji panel and attachment sheet (v1.23 UI); a Risi v1 plan (read-only).
- fail2ban `risime-signup` jail: installed by Harsha (done).

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
L. **Follow-ups and call transcription (§27 on branch `contract/v127`, decision 070); defaults are
   chosen, nothing blocks:**
   1. **"Risi listens for follow-ups"** is ON by default when starting an Official call (anyone in
      the call can tap Stop, and everyone sees "Risi is listening" from the start). Keep it on?
   2. **The consent explainer and banner wording.** Does any customer need a spoken notice?
   3. **Quiet rule:** summarise after 10 min of quiet, with at least 6 messages from at least
      2 people since the last summary, at most every 30 min and 8 times a day. OK?
   4. **Whisper's Sinhala and Tamil transcription quality is limited.** Expect weaker summaries of
      calls in those languages.
S. **Risi skills (§26, decision 069), open questions:**
   1. Should pending scheduled messages be included in backups? Default: no; they stay on the phone.
   2. Do you also want "Schedule send" in the normal composer, without Risi? It needs no wire change.
   3. Email needs the OAuth accounts (item E), then its own proposal.
E. **Email skill (later, for the pilot), needs your accounts:**
   - **Google:** a Google Cloud project with the Gmail API enabled, an OAuth consent screen in
     "Testing" with up to 100 test users, an Android OAuth client (package `lk.codegen.risime`, our
     release SHA-1) and the `gmail.readonly` scope.
     - **Public launch:** a restricted scope, so Google verification plus a yearly CASA security
       assessment.
   - **Microsoft:** an Entra app registration (public client, redirect for Android) with
     `Mail.Read` and `Calendars.Read` delegated permissions.
     - **Public use:** publisher verification.
T. **Risi tools (§25 proposal `contract/proposals/2026-10-09-risi-client-tools.md`, decision 068):**
   1. **The commercial model** for tool use and Sinhala/Tamil: which provider, an API key in `.env`,
      and a zero-retention agreement. Until then everything runs on risi-l1, and the gate shows what
      it fails.
   2. **Learning log:**
      - **(A, default)** hashes only (decision 066): risi-l1 can be evaluated, not trained on these
        logs;
      - **(B)** sealed prompts, 30-day TTL, Official only.
   3. **Keycloak token exchange for RisiWork Ask (RisiCloud lead, realm `aoa`; Keycloak ≥ 26.2):**
      - New confidential client **`risime-risi`**:
        - Client authentication ON; Standard flow / Direct access grants / Service accounts OFF;
        - **Standard Token Exchange ON**; "Allow refresh token in Standard Token Exchange" = No;
        - credentials: a client secret (or signed JWT) → sent to root for `.env`
          `RISIWORK_EXCHANGE_SECRET`;
        - access token lifespan 5 min.
      - Client scope **`risime-risi-aud`**: an Audience mapper (Included Client Audience
        `risime-risi`, add to access token), set as a **Default** scope on client `risime`.
      - Client scope **`risiwork-ask`**: an Audience mapper (Included Client Audience `risiwork`) and
        a role-scope mapping of the client role **`risiwork:ask-user`**. Add it as an **Optional**
        scope on `risime-risi`.
      - **RisiWork Ask must accept:** `aud=risiwork`, `azp=risime-risi`, scope `risiwork-ask` and
        role `risiwork:ask-user`. Anything else → 403, which Risi turns into "you don't have
        RisiWork".
      - **Keycloak < 26.2 instead:** legacy V1 token exchange, using Fine-Grained Admin Permissions
        on `risiwork` ("token-exchange" permission, with a client policy allowing `risime-risi`).
      - Please send back the Keycloak version.
R. ✅ **DONE 2026-10-08 23:23 UTC** (Harsha: "turn Risi on"). Verified:
   - `/auth/config` says `tabs: on`;
   - the Risi user `kind=agent` is seeded;
   - its device is attested with `{groups,tabs}`;
   - `risi_mls_kv` has 33 sealed rows and 31 key packages are uploaded;
   - health is ok.
   Backup of the previous `.env` in `~/risime-backups/env-before-risi-*`. Undo: set `TABS`/`RISI` to `off`
   in pilot.env and restart. Was: **Turn on two tabs and Risi for the pilot** (all gates green in nightly.39). It's reversible
   (set both to `off`, then restart). Run on spark2:
   ```
   cd ~/development/risime && install -m 600 .env ~/risime-backups/env-before-risi-$(date -u +%Y%m%dT%H%M%SZ)
   printf 'RISI_MLS_KEK=%s\nRISI_DATA_KEY=%s\n' "$(openssl rand -base64 32)" "$(openssl rand -base64 32)" >> .env
   printf '\nTABS=on\nRISI=on\n' >> infra/pilot/pilot.env
   scripts/run-server v0.2.0-nightly.39   # backup, migrate (seeds Risi), restart
   ```
   Or tell the orchestrator "turn Risi on" and allow it.
   - **Risi (decisions 065/066), nothing blocks; defaults are in place:**
     - **(a)** The commercial-model fallback stays **off** until a zero-retention agreement with
       the provider exists. Which provider, and is there an agreement?
     - **(b)** Raw Official text is kept at most 24 h (sealed), so "summarise" covers at most the
       last day. Say if you want longer.
     - **(c)** Veto any of the 11 [D] decisions in `contract/proposals/2026-10-08-two-tabs-risi.md`.
       The main ones: Official is a separate group even for a 1:1; old apps never see Official;
       in groups only admins can turn Official off.
0. **Keycloak, for the RisiCloud lead (realm `aoa`, client `risime`).**
   - **Update (decision 064):** the main cause of the forced email sign-ins is the app's own token
     storage. On phones without biometrics the tokens were kept in memory only, and every process
     restart needed a sign-in. That is being fixed in the app.
   - Still worth checking, so sessions last until Log out:
   - Clients → `risime` → Client scopes: `offline_access` assigned (**Default** or Optional).
   - Realm settings → Sessions:
     - **Offline Session Idle** ≥ 90 days (default 30);
     - **Offline Session Max Limited** = **Off**.
   - Clients → `risime` → Advanced: no client-level override with shorter "Client Offline Session
     Idle/Max" (leave them empty).
   - Realm settings → Tokens:
     - **Revoke Refresh Token = Off**, or Refresh Token Max Reuse ≥ 1, so a refresh lost on a bad
       network doesn't end the session;
     - Access Token Lifespan of 5–15 min is fine.
   - Users → the tester → Consents/Sessions shows an **Offline** session for `risime` once it works.
   - Please send back the current values of these settings, so we know which one was the cause.
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

## Handoff (2026-10-08 ~10:30 UTC, account switch): current state and next queue
**Live:** v0.2.0-nightly.31 (pilot healthy, contract v1.20 deployed). It contains the P0-1 updater
fix and v1.21 rejoin without reset. After the deploy, a one-off cleanup queued 19 ops for 45 stale
leaves.

**Agents:** none running. Working tree clean. Agent worktrees were removed; only the standing gate
and release worktrees remain (`~/.cache/risime-upgrade-wt*`, `~/risime-release-wt`).

**Services on spark2:**
- `risime.service` (n31);
- Postgres and Cassandra;
- coturn (49152–49499);
- LiveKit 1.13.9 (127.0.0.1:7880, UDP 49500–49999);
- **vLLM `risi-l1`** (Qwen3.6-35B-A3B-FP8, 127.0.0.1:8100, about 41 GB; decision 061).

**On main, not released yet (all gates green):**
- **P0-3 video-call audio** (`eb25ada`): one Telecom reader per flow, video-capable Telecom
  registration, the speaker rule, audio-only stats and watchdog. `call-device-test` calls 9, 12 and
  13 assert audio both ways.
- **P0-2 backups, v1.22:**
  - server `aff6a27`, crypto `1611634` (44 vectors, cross-checked in Python), android
    `264984e`/`b0279bc`/`4945d78`;
  - local daily, before every update and before any wipe; export to Downloads; server backup with a
    recovery key; a restore screen on first sign-in.
- **Stuck staged commit fix** (`943993a`):
  - **Cause:** `collectLatest` cancelled a group-op pass between build and submit, and the staged
    commit was never dropped. Every retry then failed with "a commit is already pending" and used
    up a key package.
  - **Fix:** a per-conversation `MlsCommitGate` that drops a staged commit at the end of an attempt
    and sweeps leftovers at start-up, plus a non-cancellable runner and key-package reuse.
- **CallManager start-up NPE** (`4c2a847`): field initialisation order.
- **logcat-gate** now also fails on swallowed programming errors.
- **Server v1.23** (`1d18fa6`): rooms `upgrade`, `media` in replies, `call_switch`/`screen_share`
  capabilities.
- **Contract v1.23** (`44bb655`, §23, decision 062): mid-call voice↔video and screen sharing.

**The first nightly.32 attempt** (from `fd863fa`, P0-3 only) failed at interop group step 7. A
rerun on the same commit passed, so it was a flake under load. It was then held so backups and the
fixes go out together.

**Next queue (in order):**
1. **Release nightly.32.** Run `UPDATER_BACK_SAFE_FROM=20031 scripts/release-from-worktree`. Watch
   these two gate settings:
   - **`REINSTALL_CHECK_FROM` defaults to 20031 in nightly-release, but C runs the OLD app (n31,
     without `943993a`).** So the reinstall phase can hit the stuck-commit bug intermittently.
     Before releasing, either change `scripts/upgrade-test` to update C to NEW before the reinstall
     phase (recommended: more realistic), or skip it for n32 only (`UPGRADE_NO_REINSTALL=1`, and
     record why).
   - **`RESTORE_CHECK_FROM` defaults to empty, so the restore phase is off.** One run showed the
     restore working (11 messages before the uninstall and 11 after) and then hit a script bug, now
     fixed in `1e51b69` but not re-run. Re-run it per the steps below, plus `--restore-from-file`,
     then set the default to the n32 versionCode (20032):
     `UPGRADE_INSTANCE=_bk UPGRADE_PORT=4160 REINSTALL_CHECK_FROM=20031 RESTORE_CHECK_FROM=20032 timeout 2400 scripts/upgrade-test --target redroid --via-updater --new <n32 test apk> --old v0.2.0-nightly.31 --version 0.2.0-nightly.32-bktest`
     The test APK is built with VERSION=0.2.0-nightly.32 in a throwaway worktree with
     `assembleRelease -Prisime.mlsPinnedKeys=…` and is never published.
   - Make `UPDATER_BACK_SAFE_FROM` a default in nightly-release (20031).
2. **Android v1.23,** the calling slice Harsha approved after the P0s:
   - mid-call voice↔video, 1:1 and group;
   - screen sharing (MediaProjection, privacy rules);
   - the call info screen with data used.
   Then extend `call-device-test` for switching and sharing, with audio flowing throughout.
   Checklists are in the v1.23 merge notes and decision 062.
3. **Risi v1 (stage 0.5),** estimated 2–3 days of agent time after 2:
   - a contract for Risi as a visible MLS member (consent banner, any member removes it);
   - a server-side MLS client (Rustler NIF on the crypto core);
   - "Risi, summarise" with decisions and action items with owners, through `risi-l1`;
   - every model call in the Cassandra learning log;
   - no chat text leaves spark2.
4. **Then the Commitment Ledger:** confirm ✓/✗, follow-ups, reminders.

**Needs Harsha:**
1. **Caddy `/livekit` route,** so group calls reach testers:
   `sudo cp infra/caddy/Caddyfile /etc/caddy/Caddyfile && sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && sudo systemctl reload caddy`.
   Then root sets `LIVEKIT_URL` in pilot.env and restarts.
2. A by-hand check from outside that UDP 49500 and 49999 are reachable (decision 056).
3. The fail2ban `risime-signup` jail (the sudo line is in decision 058).
4. Optional: `sudo swapoff -a && sudo swapon -a` (11 GB went to swap at the first LLM start).
5. **Real-phone checks:**
   - video calls start on speaker with a working route button (after n32);
   - the dark screen after Back with the proximity sensor;
   - camera and Flip.
6. **Testers:** updating to n31 still goes through the old updater, so keep RisiMe open, or install
   from the website over the old app. Never uninstall. From n32 on, updates survive leaving the app.

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

## Current state (2026-10-08 ~11:45 UTC, checked by the orchestrator)
- **Pilot:** v0.2.0-nightly.31 live (`risime.service` active), contract v1.20 deployed.
- **Interrupted by the account switch:** nothing. The handoff (`a3670be`) left no agents and a clean
  tree; the only leftovers were the two failed experiment results `v0.2.0-nightly.32-bktest` (the
  script bug fixed in `1e51b69`) and `v0.2.0-nightly.33` (aborted by hand). Neither needs resuming;
  the nightly.32 gate below re-runs the restore phase.
- **Running now (this session):**
  - the restore-from-file check on the published nightly.32 APK (Redroid `_bk`, port 4160);
  - android agent: P0 call audio routing (worktree; Redroid `andr1`/`andr2`, tmux
    `risime-calltest_andr` for its call-device-test);
  - android agent: P0 background delivery (app side + `scripts/push-device-test`);
  - server agent: push audit log lines.
- **Finished this session:**
  - the gate changes (`e29fa19`);
  - the emoji panel and attachment sheet (`49282f2`, not released yet);
  - the Risi v1 proposal (`bf1a215`, waiting for Harsha).
- **Worktrees:** the standing ones (`~/.cache/risime-upgrade-wt*`, `~/risime-release-wt`,
  `~/risime-run/<tag>`) plus the agents' own under `.claude/worktrees/`.

### P0 background delivery: server evidence (2026-10-08 11:40 UTC)
- **Harsha's devices:** his current device (`ad92ca09…`, nightly.31, last seen 11:23 UTC) **has a
  push token**. His six older devices (nightly.12–28) have none, since FCM unregistered them.
- **FCM results in `~/risime-logs/server.log`:**
  - `call push: result=ok` 34× (latest 11:20 and 11:21 UTC);
  - `result=unregistered` 2×, both old devices;
  - `HTTP 404 UNREGISTERED` 9× in total;
  - 1 `:timeout` (04:08).
  - FCM accepts the pushes. They are data-only, `android.priority: high`; calls have TTL 45 s.
- **Gaps:** inbox (message) pushes log nothing on success, no line names the device a push went
  to, and pushes skipped because the user counts as online (`Presence.online?`, 5 s grace) aren't
  logged. A server change is adding per-device lines.
- **Leading hypothesis (being checked):** the server pushes only when no inbox socket is live. If
  the backgrounded app keeps its websocket, or Doze freezes it while the server still sees it, no
  push is sent. The app then posts message notifications only on the sync path (`syncAndNotify`),
  so nothing shows until the app is opened.
- **Gate limit:** Redroid has no Google Play Services, so the new push gate emulates the FCM delivery
  (a high-priority temporary allowlist plus the c2dm broadcast).

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
