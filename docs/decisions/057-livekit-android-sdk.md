# 057 — Group calls use livekit-android 2.29.0 on the pinned libwebrtc
**Status:** accepted 2026-10-08 (android), implementing contract v1.19 §20.5 (android review A1).

## Context
Rule 8: a framework outside the stack needs a note. Decision 052 pinned
`io.github.webrtc-sdk:android-prefixed` 144.7559.14 so that group calls would add only the LiveKit
Kotlin SDK on the same native library.

## Decision
- **Version:** `io.livekit:livekit-android` **exactly 2.29.0**.
  - Its pom pins android-prefixed 144.7559.14 (checked 2026-10-08), the same as §16.9.
  - It is upgraded only together with that pin.
  - `check<Variant>SingleWebRtc` and `WebRtcPackagingTest` keep failing on a second
    `*jingle_peerconnection_so.so`.
  - Licence: Apache-2.0.
- **Transitive extras:**
  - LiveKit's audioswitch fork `com.github.davidliu:audioswitch` (pinned to a commit) comes from
    JitPack; the repository is restricted to that one group.
  - `liblivekit_uniffi.so` is packaged for arm64-v8a and x86_64 only.
  - `protobuf-javalite` 3.25.9 is compile-only, for LiveKit's track info types.
- **Usage:**
  - `E2EEOptions` with a per-participant `BaseKeyProvider`: no shared key, ratchet window 0,
    ring 16, frames dropped while the cryptor is not ready, salt `risime-call-v1`.
  - Keys come only from MLS (`callFrameKeys`).
  - Own sender cryptors switch key index through reflection on `E2EEManager.frameCryptors`
    (private in 2.29; release builds have no R8).
  - `NoAudioHandler`, because core-telecom owns audio routing.
  - coturn goes in through an `RTCConfiguration`.
  - No data channel, chat or metadata.
- **Tests:** JVM tests never load the native library: the SFU sits behind the
  `SfuConnector`/`SfuSession` interfaces with a fake.

## Consequences
- The APK grows by about 3 MB.
- A 32-bit phone never advertises `group_calls`.
