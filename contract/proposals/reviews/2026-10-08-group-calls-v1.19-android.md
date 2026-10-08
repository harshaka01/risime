# Review: group calls with LiveKit v1.19, android side

**Reviewer:** android (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-group-calls-v1.19.md`.
**Verdict:** accept with the required changes below.

## What I checked
- §16.9: the WebRTC library is `io.github.webrtc-sdk:android-prefixed` 144.7559.14, the build
  `io.livekit:livekit-android` 2.29.0 pins; the duplicate-libwebrtc build check (decision 052).
- livekit-android: `Room.connect(url, token, ConnectOptions(iceServers = …))`, `E2EEOptions` with
  `BaseKeyProvider` (`setKey(participantId, key, keyIndex)`, `setKeyIndex`), `RoomOptions`
  (`adaptiveStream`, `dynacast`, simulcast defaults), track and participant events.
- Telecom, foreground service and lock-screen rules of §16.8/§16.9; the camera rules of §19.

## Required changes
- **A1. Pin the SDK to the WebRTC build.** `io.livekit:livekit-android` exactly **2.29.0** (or a
  later version only together with the §16.9 pin), recorded in an android decision (rule 8). The
  duplicate-`.so` check stays.
- **A2. Siblings must stop ringing.** Answering or declining on one device must stop the user's
  other devices, and the SFU can't say so. Add the ephemeral envelope **`call_member`**
  `{"call_id", "state": "joined" | "declined"}`, sent to the group with `ring: false`; a device
  stops ringing for its own user's `joined`/`declined`; other members may show "<name> joined"
  before LiveKit says so. Decline is otherwise local (no message to the starter).
- **A3. Members who missed the ring.** The ring is ephemeral (60 s). A durable, silent
  **`call_start`** `{"call_id", "media", "mode": "sfu"}` from the starter gives every member a
  history line "<name> started a voice call" with **Join** while the call runs (checked with
  `status` on chat open and on tap; a missing room → "This call has ended").
- **A4. One call at a time.** A device in a 1:1 or group call doesn't ring for another call (busy,
  §16.5); a group ring while busy leaves the chat line with Join. No `call_busy` in groups.
- **A5. TURN for LiveKit.** Use `ConnectOptions.iceServers` from `GET /calls/turn` (the same 5-h
  credentials as 1:1); LiveKit's own list is only a fallback. A relay-only debug switch must work.
- **A6. Bandwidth.** `adaptiveStream` and `dynacast` on; video simulcast with **two layers**
  (about 320×180 at 150 kbit/s and 640×360 at 500 kbit/s), VP8, so a phone on mobile data gets
  small tiles; audio Opus as §16.9 (no CBR requirement in groups, DTX on).
- **A7. Data channel off.** No LiveKit data messages or chat (they bypass MLS); participant names
  and photos come from the app's own database by identity, never from LiveKit (names are empty).
- **A8. Lifecycle.** Max 4 h (as §16.4). LiveKit's reconnect handles ICE restarts; a full
  disconnect over 20 s → leave with "Lost connection". The socket and wake lock rules of §16.9
  apply while in a group call; the camera rules of §19.5 apply to group video.
- **A9. UI.** Ringing "Kamal · Pilot team" (voice/video), Answer (camera on for video), Answer
  without video, Decline; in-call: a grid (video) or a list (voice) of participants, speaking
  indicator, mute, camera, speaker, leave; an "Encrypted" badge only while every rendered track
  decrypts (crypto K9).

## Answers
- *APK size:* the LiveKit Kotlin SDK adds about 2–3 MB on the same native library.
- *JVM tests:* the room is behind an interface with a fake; key derivation and the state machine
  are testable without the native library.
