# RisiMe – Next Phase Spec

Owner: Harsha · Written 2026-10-10 · For: cc-root (RisiMe build).
Rules for root:
- Write the contract section(s) first, then build.
- Use the normal release gates (two-phone Redroid, upgrade gate, logcat gate, ui-entry-test at font scale 1.0 and 1.3).
- No plaintext on the server.
- Downloads over 2 GB need Harsha's OK.
- Report each nightly number.

## Order of work
0. **P0 in flight:** calendar hotfix (nightly.48). Then the Official-calls connect bug (section D1).
1. **E. Basic messaging:** copy, forward, share, star, PDF export.
2. **G. UI polish batch** (if not already shipped in nightly.48).
3. **F. View once** (Private only).
4. **A. Media:** voice notes, files, video.
5. **H. Reliability:** watchdog and the monitoring console. These can run in parallel as infra work.
6. **Model evaluation:** finish it, write `docs/RISI-MODELS.md`, and switch models only after Harsha says yes.
7. **D2. Official calls with Risi** (transcribe, summarise, act). This needs the ASR results.
8. **C. Risi Calendar UI.**
9. **B. My Risi** (knowledge hub).
10. **SPEC-GAP:** keep `docs/status/SPEC-GAP.md` updated after each release.

---

## A. Media (WhatsApp parity, all E2EE blobs)
- **Voice notes:**
  - hold to record, slide to cancel, lock for hands-free;
  - waveform, 1x/1.5x/2x speed;
  - auto-play the next voice note;
  - keep playing with the screen off; switch to the earpiece near the ear.
- **Files:** any document up to 100 MB. Show a preview card (icon, name, size), open-with and save, a progress bar, and resume on a bad network.
- **Video:** record or pick. Compress to 720p by default with an HD toggle. Show a thumbnail and duration, and stream on playback.
- **Risi transcription:** voice notes are transcribed by Risi in Official chats only, using the ASR model that wins the evaluation. `whisper-small-sinhala` currently has 14.5% word error rate on Sinhala.
- **Gate:** two-phone send and receive of each type, the upgrade gate, and no plaintext on the server.

## B. My Risi – the global knowledge hub
- **Sources:** Official chats only (never Private), plus Official call summaries, Risi Notes, calendar and Skills.
- **Sealed items extracted:** projects and their progress, decisions and agreements, promises (who/what/when), meetings, open questions, people and their roles. Each item links back to its source message.
- **Retention:**
  - raw text is deleted after 24 h or less;
  - derived items are kept 30 days by default, longer if pinned;
  - the user can view, edit or delete each item, or everything.
- **Opt-out:** any participant can stop Risi learning from their messages.
- **The My Risi screen:**
  - Today brief;
  - Projects: status, decisions, blockers, who owes what;
  - People: open items per person;
  - an Ask box that answers only from stored items, with source links, and says "I don't know" when there is no item.
- **Proactive:**
  - a morning brief as a Risi message;
  - a nudge before deadlines;
  - a prep card 15 minutes before meetings.
- **Later:** verify promises against Git and the Risi ERP.

## C. Risi Calendar – better than Google or the phone calendar
- **One timeline:** phone calendar, every synced Google account, and Risi's own items (promises, follow-ups, reminders, deadlines, notes), colour-coded by source, with a filter.
- **Views:**
  - Agenda (default);
  - Day timeline with a now-line;
  - Week;
  - Month busy heat-map.
- **Smart cards:**
  - meeting prep (last summary, open items, attendees' promises);
  - conflict alerts;
  - free-slot finder;
  - "after this meeting" follow-ups.
- **Adding events:**
  - natural language in Sinhala, Tamil or English, always confirmed before saving;
  - writes go to the chosen calendar, followed by a read-back;
  - Risi says "added" only after the read-back succeeds.
- **Widget:** the next event plus promises due today.
- **Design:**
  - premium look: clean typography, bold headers, single-line ellipsis, dark and light themes;
  - deep-link action chips; no chip ever sends text.

## D. Official calls
**D1. Bug (P0).** Calling from the Official tab fails with "Group · Can't connect the call", because the Official chat (2 people + Risi) is routed as a group call.
- **Fix:** the Official 1:1 call goes to the human peer, and the screen shows their name.
- Private calls stay peer-to-peer and E2EE, and are never recorded.
- **Gate:** audio and video calls from both tabs.

**D2. Risi listens (voice and video, 1:1 and group).**
- **Transport:** Official calls go through our LiveKit server with E2EE (key from the MLS group). Risi joins as a visible bot participant. This also enables group calls.
- **Consent:**
  - at the start, everyone sees and hears "Risi is listening and will transcribe this call";
  - a red "Risi listening" indicator stays on screen;
  - anyone can tap "Stop Risi".
- **Transcription:** each participant's track separately (speaker = their name), with per-segment language detection (Sinhala, Tamil, English or mixed). Raw audio is deleted immediately and never stored.
- **Within 2 minutes after the call:**
  - transcript with speakers and timestamps;
  - summary in Sinhala, Tamil and English (each person sees their preferred language first, with buttons to switch);
  - decisions, promises and an action plan, proposed as calendar events, reminders and follow-ups that each owner confirms;
  - saved as a Risi Note, sent to every participant, exportable as PDF, and fed into My Risi.
- **Privacy:** the transcript is sealed and deleted after 24 h. The summary, note and actions follow the My Risi rules.
- **Test:** 3 Sinhala, 3 Tamil, 3 English and 3 mixed recorded calls. Report word error rate, summary quality and action-item accuracy. Run the two-phone gate with injected audio, and confirm no audio is left on disk.

## E. Basic messaging (WhatsApp parity)
- **Copy:**
  - single message;
  - multi-select, copied as "[10/10/26, 07:26] Name: text" in chat order;
  - text selection inside a bubble.
- **Forward:**
  - single or multi-select, up to 5 targets, with search and recent chats on top;
  - covers text, photos, files, voice notes, video and Risi notes;
  - re-encrypted on the device for each target, re-wrapping blob keys with no re-upload where possible;
  - "Forwarded" label, and "Forwarded many times" after 5 hops;
  - forwarding from Private into Official shows a one-time hint that Risi can read Official.
- **Selection bar:** Copy, Forward, Share (Android share sheet), Star, Reply, Info, Delete, and the selected count.
- **PDF export:** for any Risi summary, note, report, My Risi view or calendar view.
  - Generated on the phone, never on the server.
  - RisiMe header, title, date, participants, bold headings, page numbers.
  - Noto Sans Sinhala and Tamil fonts embedded.
  - Share, save to Downloads, or send into a chat.
  - Risi also does it when asked ("send this as a PDF").
- **Gate:**
  - copy format;
  - forward to a DM and a group;
  - "Forwarded" label on the second phone;
  - a Sinhala and Tamil PDF checked with pdftotext.

## F. View once – Private chats only (better than WhatsApp)
**Where:** only in the Private tab (DMs and groups). It is never offered in Official, and Risi never sees view-once content.

**What can be sent:** photo, video, voice note and **text** (WhatsApp has no view-once text).

**Sender options:** a small timer button in the composer cycles through:
- off;
- **View once**;
- **View twice**;
- **Disappears after opening**, with a chooser: 10 s / 1 min / 1 h.

Before sending, the sender sees a clear chip: "Opens once · can't be saved".

**Receiver experience:**
- The bubble is a frosted, blurred card with a lock-ring icon and "Photo · view once" (or Video / Voice / Message), plus the size or duration. There is no thumbnail and no text preview.
- Notifications say only "📷 View-once photo from <name>", never the content.
- Tap to open full screen in a secure viewer:
  - a thin countdown or "1 of 1 view" ring at the top;
  - a faint repeating watermark with the viewer's name and the time (discourages photos taken with another camera).
- Close, and it is gone. The bubble becomes "Opened · 14:05" in grey.

**Protection:**
- `FLAG_SECURE` on the viewer, so screenshots and screen recording come out black.
- On Android 14+, the ScreenCaptureCallback notifies the sender: "<name> tried to take a screenshot".
- No forward, copy, save, share, reply-quote, star, or backup inclusion.
- It is excluded from the Copy/Forward selection bar and from search.
- The decrypted content stays only in memory: no cache file, and it is wiped on close or app pause.

**Lifecycle:**
- The sender sees "Delivered", then "Opened at 14:05" (and "Opened twice" for view twice).
- The sender can "Unsend" before it's opened.
- Unopened items expire after 14 days.
- Multi-device: once it's opened on one device, the other devices show "Opened on another device".
- The server deletes the blob on the opened ack, or at expiry. The ack is end-to-end, so the server learns nothing extra.

**Voice view-once:** plays once through the earpiece or speaker. Scrubbing is allowed during that single play. It is gone when it ends.

**Gate:**
- two-phone open-once (the second open is impossible);
- a FLAG_SECURE check;
- no file left in the cache or app storage after close;
- blob deleted on the server;
- the expiry path;
- unsend;
- multi-device "opened elsewhere".

## G. UI polish batch
- **Chat list rows:**
  - 2 lines only: name (bold, ellipsis) and time on line 1; preview (1 line, ellipsis) and badge on line 2;
  - "last seen" removed from the list;
  - unread rows get a bold preview and an accent-coloured time and badge.
- **No wrapping:** every single-line label uses maxLines=1 with an ellipsis. The tabs (Chats / Calls / Requests) never wrap.
- **Chat header:** name (bold, ellipsis) on line 1, status on line 2. Video and call buttons in the bar; lock moves into the ⋮ menu.
- **One round button** for new chat, opening a sheet: New chat / New group / Add friend. Add bottom padding so it never covers the last row.
- **Bold headers everywhere;** markdown renders in Risi bubbles.
- **Profile and group photo sheet:**
  - Take photo (camera), Gallery, Remove;
  - square crop;
  - camera permission requested when needed, with a link to settings if denied.

## H. Reliability and monitoring
- **Watchdog:**
  - every 30 s, check /health locally and publicly with a 5 s timeout;
  - after 3 failures, capture diagnostics, restart, and alert Harsha (a Risi message, with email as fallback);
  - at most 3 restarts per hour.
- **Monitoring console** at monitor.risicloud.ai, behind a Keycloak admin login:
  - Prometheus, node_exporter and GPU exporter;
  - PromEx for Phoenix, Ecto, Oban and the BEAM;
  - Cassandra, Postgres and vLLM metrics;
  - Grafana dashboards: RisiMe overview, Risi, infra;
  - Loki logs;
  - Uptime Kuma external checks plus a status page;
  - Alertmanager alerts: health down, p95 over 2 s, RAM over 90%, disk over 85%, Cassandra GC over 1 s, certificate expiry under 14 days;
  - network usage (vnstat, and per-process), with an alert above 5 GB in daytime;
  - Phoenix LiveDashboard for admins;
  - documented in `docs/MONITORING.md`.
- **Downloads:** over 2 GB only between 22:00 and 06:00 Sri Lanka time, rate-limited, and logged to `docs/status/downloads.md`.
