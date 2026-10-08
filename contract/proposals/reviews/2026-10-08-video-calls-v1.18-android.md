# Review: 1:1 video calls v1.18, android side

**Reviewer:** android (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-video-calls-v1.18.md`.
**Verdict:** accept with the required changes below.

## What I checked
- The v1.13 call stack: `CallMedia`, core-telecom 1.0.1 (self-managed), the `phoneCall` and
  `phoneCall|microphone` foreground service, the in-call activity, the locked ring (§16.8).
- libwebrtc `android-prefixed` 144.7559.14: `Camera2Enumerator`, `RtpSender.setTrack`,
  `RtpParameters.encodings[0].maxBitrateBps`, `RtpTransceiver.setCodecPreferences`.

## Required changes
- **A1. Camera only while the call screen is visible.** Android 14+ allows camera use from a
  foreground service only with the `camera` type, started while visible; a backgrounded video
  call would need that type and a picture-in-picture design. For v1.18: the camera runs only
  while the in-call activity is visible and unlocked. Leaving it (home, screen off, another app)
  stops the camera (`setTrack(null)`, `call_media` `camera: false`); coming back restarts it if the
  user had it on. Audio continues as today. No `FOREGROUND_SERVICE_CAMERA`; picture-in-picture is
  a later step.
- **A2. `CAMERA` permission** is asked when the user starts or answers a video call with the
  camera on. Denied → the call goes on with the camera off, and a camera button that explains.
  `CAMERA` leaves §16.9's "never" list.
- **A3. Never turn the camera on unasked.** The caller's camera may show a local preview from the
  moment they tap "Video call". A callee's camera starts only after a human taps **Answer**
  (camera on) in the in-call activity; never while ringing, never for the locked ring (the user
  first unlocks, §16.8), never by auto-answer. Incoming video offers offer **Answer**, **Answer
  without video** (camera off; can be turned on later) and **Decline**.
- **A4. Glare between voice and video.** The lower `call_id` still wins (§16.5). The automatic
  answer uses the camera only if the losing device's own call was a video call; otherwise it
  answers with the camera off.
- **A5. Frozen frames.** A lost `call_media` (or a stalled sender) leaves the last frame on
  screen. Receivers also show the avatar after **3 s** without a decoded frame, and switch back on
  the next frame.
- **A6. Telecom and audio routing.** `addCall` with `CALL_TYPE_VIDEO_CALL`: the default endpoint
  is the **speaker** (unless a wired or Bluetooth headset is connected), no proximity wake lock;
  a camera-off video call keeps the speaker until the user switches.
- **A7. Capture and bitrate.** Front camera by default, a switch button (local only). Capture
  ≤ 1280×720 at ≤ 30 fps; on mobile data or a relayed path start at 640×480 at 24 fps. Sender
  `maxBitrateBps` ≤ 1 500 000 (Wi-Fi) or 800 000 (mobile), `degradationPreference` BALANCED; on
  `PowerManager` thermal status ≥ SEVERE drop to 640×480 at 15 fps.
- **A8. Codec preferences** on the video transceiver: VP8 then its `rtx`, nothing else, so offers
  and answers carry only those.
- **A9. The `video` capability** is advertised only together with `calls` (same conditions, §16.1)
  and only when the VP8 encoder and decoder factories load.

## Answers
- *Size:* no new native library; the VP8 codec is in the same libwebrtc. A video offer SDP is
  about 5–7 KiB with gathered candidates, under the 16 KiB SDP and 20 480-byte envelope bounds.
- *Tests:* the camera is behind `CallMedia` (a fake in JVM tests); the live check is manual.
