# 062 — Mid-call voice↔video switching and screen sharing

**Status:** accepted 2026-10-08 (root), with contract v1.23 (PROTOCOL §23). To be built after the
current P0 work.

## Context
Harsha approved the next calling slice: switch a voice call to video and back (1:1 and group),
share the screen (1:1 and group), and show a per-contact call-info screen with data used. v1.18
(§19) fixed the media type at call start; v1.19 (§20) minted group tokens with camera only for
video rooms. Old apps (v1.13–v1.22) must keep working.

## Decision
- **1:1: one renegotiation per call at most, only by the original caller.** Adding `m=video` to a
  voice session through an MLS re-offer (`call_offer` `renegotiate: true`), keeping the fingerprint,
  DTLS role, ICE credentials and mid-0 bundle transport, then re-running the §16.10 (e) check.
  After that, every switch and every screen share is `setTrack` plus `call_media`, as v1.18 camera
  on/off. Rejected: reserving a video transceiver in every call, because v1.13–v1.22 devices drop an
  offer with `m=video` and would stop ringing for voice calls.
- **Per-call `features` inside MLS** (`call_offer`/`call_answer`) gate the buttons, so a v1.23
  device never asks an old one; no readiness fields, no server change for 1:1. Device capabilities
  `call_switch` and `screen_share` exist for the group path.
- **Consent:** 1:1 needs the peer's accept before any video reaches it, and the renegotiation is
  applied only after that accept. Groups have no per-member accept (joining is consent, as
  elsewhere), but video is rendered only per the sender's MLS `call_media`; nobody's camera is
  turned on unasked; each member has a local "Voice only".
- **Groups: a server-side `upgrade`** rather than video-capable tokens for every voice room: the
  server checks the 8 cap (present identities plus tokens minted in the last 600 s), sets the room
  metadata to video and widens live grants with LiveKit's `UpdateParticipant`. Enforcement stays on
  the server (tokens and grants), and a voice room never carries camera grants it doesn't need.
- **One video at a time per sender** (camera or screen), one sender with a source swap (1:1) or
  LiveKit's `screen_share` source after unpublishing the camera (groups). Screen sharing has
  normative privacy rules (consent each time, banner and Stop, stop on lock, own notifications
  without content, own windows `FLAG_SECURE`, no device audio).
- **History:** `call_end.media` / `group_call` `ended.media` = `video` if video was ever on.
  **Data used is local only** (per-device `getStats` bytes in the call row; never on the wire,
  never in backups schema 1).

## Consequences
- Server work is small: two capabilities, `upgrade`, the cap memory, `join` as a refresh, `media`
  in the rooms replies.
- Android carries most of it: the switch state machine, renegotiation with rollback, MediaProjection
  (foreground service type `mediaProjection`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`), the screencast
  encoder settings, the viewer, the call-info screen. An android decision records the
  MediaProjection service wiring.
- `scripts/call-device-test` grows 1:1 and group switch/share phases with audio checked in every
  phase.


## Amendment (2026-10-09, Harsha's real-phone check)
- **The rule changed:** RisiMe's own windows are no longer `FLAG_SECURE` while sharing. An "Entire
  screen" share showed RisiMe black.
- **Now, as on WhatsApp:**
  - only the app-lock screen, the Locked chats folder and an open locked chat are `FLAG_SECURE`,
    always;
  - a pre-share dialog warns that the whole screen, notifications included, will be visible;
  - notifications stay silent and content-free while sharing;
  - viewers see the shared screen aspect-fit, never cropped.
- **Code:** android `75991bf`, `1977973`. **Contract:** §23.5. **Proposal:**
  `contract/proposals/2026-10-09-screen-share-flag-secure.md`.
