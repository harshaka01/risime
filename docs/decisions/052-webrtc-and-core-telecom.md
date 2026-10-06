# 052 — 1:1 calls use LiveKit's libwebrtc build and androidx core-telecom

**Status:** accepted 2026-10-06 (android), implementing contract v1.13 §16.9 (android review A1, A2).

## Context
CLAUDE.md rule 8: a framework outside the stack needs a note. v1.13 adds 1:1 voice calls, which need
a WebRTC stack and the Android Telecom integration. Neither is in the listed Android stack.

## Decision
- **WebRTC:** `io.github.webrtc-sdk:android-prefixed` **144.7559.14**, the exact build
  `io.livekit:livekit-android` 2.29.0 pins (`livekit.org.webrtc`, `liblkjingle_peerconnection_so.so`,
  BSD-3-Clause + PATENTS). v1.14 group calls then add only the LiveKit Kotlin SDK on the same native
  library. `libs.versions.toml` says "must equal livekit-android's pom"; a Gradle task
  (`check<Variant>SingleWebRtc`, run by `assemble<Variant>`) and `WebRtcPackagingTest` fail if a
  second `*jingle_peerconnection_so.so` appears.
- **ABIs:** the WebRTC `.so` is packaged only for `arm64-v8a` and `x86_64` (`packaging.jniLibs.excludes`);
  the MLS core keeps all four ABIs. A 32-bit phone can't load it and never advertises `calls`.
- **Telecom:** `androidx.core:core-telecom` **1.0.1** (stable), self-managed calls
  (`CallsManager.registerAppWithTelecom`, `addCall`; ConnectionService on API 26–33, transactional
  Telecom on 34+). Apache-2.0.
- Both are prebuilt AARs: nothing is built on spark2. JVM and Robolectric tests never load the native
  library: media is behind the `CallMedia` interface with a fake.

## Consequences
- APK size: about +12.5 MB (release, compressed native libs) for the two 64-bit ABIs.
- Settings → "Open-source licences" lists libwebrtc and its bundled components and core-telecom.
- Not `stream-webrtc-android` (it would be a second libwebrtc copy once LiveKit arrives).
