# Review: voice↔video switching and screen sharing v1.23, android side

**Reviewer:** android (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-call-switch-screen-v1.23.md`.
**Verdict:** accept with the required changes below.

## What I checked
- libwebrtc 144.7559.14 (`livekit.org.webrtc`): `addTransceiver`, `setCodecPreferences`,
  `RtpSender.setTrack`, `RtpParameters.degradationPreference`, `SessionDescription.Type.ROLLBACK`,
  `createVideoSource(isScreencast)`, `ScreenCapturerAndroid`.
- livekit-android 2.29.0: `setScreenShareEnabled` and its `ScreenCaptureService`, track sources,
  `setSubscribed`, permission updates on the local participant.
- Android 14–16 rules: MediaProjection needs a running `mediaProjection` foreground service before
  `getMediaProjection`, single-use consent tokens, the app-selection ("a single app") dialog on 14
  QPR2+, Android 15's hiding of sensitive notifications during projection and its status-bar chip.
- The v1.18 camera rules (only while the call screen is visible, never unasked), core-telecom 1.0.1
  routing, decision 054 bounds.

## Required changes
- **A1. One offerer, plus rollback.** The original caller is the only re-offerer; but a callee may
  still send an ICE restart (§16.2). If the callee has its own restart outstanding and receives the
  caller's re-offer, it rolls back its offer, answers, and repeats its restart afterwards; the caller
  ignores a callee restart that arrives while its own offer is outstanding (the caller is
  "impolite"). Every operation keeps the decision-054 10-s bound; a failed renegotiation rolls back
  and keeps audio.
- **A2. MediaProjection order and stop paths.** Start (or upgrade) the call's foreground service
  with `phoneCall|microphone|mediaProjection` **before** `getMediaProjection`; register the
  `MediaProjection.Callback` (`onStop`) and stop sharing on it; resize the virtual display on
  rotation; never keep a consent `Intent` for a later share. Stop on `SCREEN_OFF`. One notification
  only (LiveKit's `ScreenCaptureService` gets ours via its params, or the call service carries the
  projection; decide in an android decision).
- **A3. Swapping sources.** One video sender. Camera and screen are two tracks from two sources
  (a camera source and a screencast source, `isScreencast = true`); swapping is `setTrack` plus new
  `RtpParameters` (degradation preference, max bitrate, max frame rate). Never two video senders.
- **A4. Audio routing on a switch.** core-telecom 1.0.1 can't change the call type. On a switch to
  video, if the endpoint is the earpiece, request the speaker; on a switch back to voice, return to
  the earpiece only if the speaker was chosen by the switch (not by the user). Proximity wake lock
  only in voice mode on the earpiece.
- **A5. Camera vs screen in the background.** The camera keeps the v1.18 rule (only while the call
  screen is visible). Screen sharing must continue in the background (that is its point); returning
  to the call screen shows the banner.
- **A6. Old peers get the v1.18 UI.** Without `features` in the peer's offer/answer: no Video
  request, no Share screen; in a call started as video, "Switch to voice" is just v1.18 camera off.
- **A7. Viewer UI.** A screen tile is fitted, never cropped; pinch-zoom and pan; double tap resets.
  The label "<name> is sharing their screen" (from MLS, crypto C5).
- **A8. Data used.** Sample `getStats()` transport bytes every 2 s (and at end) into the call row,
  so a crash keeps the last sample; for groups sum LiveKit's publisher and subscriber connections.
- **A9. Request UX bounds.** One pending request per side; 20 s without an answer = "No answer";
  after a decline the requester's button waits 10 s. Crossing requests accept each other without a
  prompt (both asked). The prompt lives in the call screen; in the background the ongoing call
  notification says "<name> wants to switch to video" with "Open" (never accept from the
  notification: the camera needs the visible screen).
- **A10. Test hooks on redroid.** Redroid has no camera (debug builds already feed a test pattern,
  §19 tests) and needs MediaProjection consent: `appops set <pkg> PROJECT_MEDIA allow` (or the
  dialog through uiautomator). The debug stats line gains the sender's video source
  (`src=camera|screen`).

## Answers
- *Renegotiation or reserved transceiver?* Renegotiation, once, from the caller: a reserved
  `m=video` in every voice offer makes v1.13–v1.22 devices drop the ring (their strict SDP rules).
- *Separate screen track in 1:1?* No: one sender, source swap. In groups LiveKit's `screen_share`
  source is used, but camera is unpublished first.
