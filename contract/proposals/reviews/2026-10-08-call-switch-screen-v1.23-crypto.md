# Review: voice↔video switching and screen sharing v1.23, crypto side

**Reviewer:** crypto (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-call-switch-screen-v1.23.md`.
**Verdict:** accept with the required changes below. No MLS core change; no new exporter label.

## What I checked
- §16.10 (one SDP source, encryption on, a fresh certificate, the fingerprint pinned for "any
  renegotiation", the post-connect check), §19.4 (BUNDLE, one fingerprint value, VP8 only in
  answers, no simulcast, stripped header extensions), §20.6 (frame keys, fail closed, roster from
  MLS, the badge).
- What a renegotiation could change on an established DTLS-SRTP session, and what a screen track
  adds to the metadata on the wire.

## Required changes
- **C1. A renegotiation must not move the transport.** A re-offer (and its answer) must keep:
  every `a=fingerprint` equal to that device's first SDP of the call (§16.10 d); `a=setup:actpass`
  in the re-offer and, in the answer, **the same role as the first answer** (no DTLS role flip, so no
  new handshake); `a=group:BUNDLE 0 1` with mid `0` first (the bundle transport stays the audio
  transport); the **same `a=ice-ufrag`/`a=ice-pwd`** as the current session (a renegotiation is not
  an ICE restart; `renegotiate` and `restart` never together). After the answer is applied, run the
  §16.10 (e) check again; a failure ends the call `failed`.
- **C2. No video decoder without consent.** v1.18 K4 holds only if a peer can't force `m=video` on
  us. A device **applies a `renegotiate` offer only if its own user accepted a switch (or asked for
  one) in this call**; any other re-offer is dropped and logged, the call goes on as voice. The same
  for groups: a participant's video is rendered only while that identity's last MLS `call_media`
  says `camera` or `screen` (the server could widen grants; the SFU could forward video; neither
  may make us render). For late joiners, participants re-send their `call_media` when someone
  connects. Compatibility exception: in a room started as video, a v1.19–v1.22 participant (it never
  sends `call_media`) is rendered as in §20.
- **C3. Voice mode means no rendering.** After `voice`, frames that still arrive on the video
  transceiver (a buggy or hostile peer) are not rendered (no sink attached) and the UI says voice.
  The decoder may exist; rendering doesn't.
- **C4. Screen content is the most sensitive media we carry.** Normative: sharing only after an
  explicit tap plus the platform consent each time (Android 14 single-use projection tokens; never
  cached); stop on screen lock, on the platform's `onStop`, on `voice`, on call end and on removal;
  RisiMe's own windows `FLAG_SECURE` while sharing (other chats never leak; no mirror loop); never
  device audio (no `AudioPlaybackCapture`). Screen frames go over exactly the same DTLS-SRTP
  transport (1:1) or frame keys (groups); no other path, no upload, no recording.
- **C5. Labels from MLS, not from the SFU.** In groups, LiveKit's track `source` is SFU-controlled
  metadata. "<name> is sharing their screen" comes from that identity's MLS `call_media`
  `video: "screen"`; the LiveKit source is used only for layout. Fail-closed rules of §20.6 apply to
  screen tracks unchanged (same per-identity key, `discardFrameWhenCryptorNotReady`, unencrypted
  track info → never rendered).
- **C6. Ordering inside a call.** MLS already rejects replays, but a stale `accept` from an earlier
  request must not switch a later state. `seq` per sender per call; an `accept`/`decline`/`cancel`
  names the request's `seq`; anything naming an unknown or older `seq` is ignored.
- **C7. Say what leaks.** 1:1: the server learns nothing new (the cleartext `media` stays the start
  media); coturn and on-path observers see the bitrate jump at a switch and a screencast's traffic
  shape (bursts on change, near-silence on a static screen), so "video started" and "probably a
  screen" are visible, never content. Groups: the server and LiveKit learn who upgraded when, and
  each track's source (camera vs screen). Stated in §23's privacy section.

## Answers
- *Does a renegotiation weaken the MLS binding?* No, if C1 holds: the SDP still comes only from
  MLS (§16.10 a), the fingerprint can't change, and one transport is checked.
- *New keys for screen tracks in groups?* No: frame keys are per identity, not per track; a screen
  track is just another encrypted track of that identity. The stated limit of §20.6 K8 (a member can
  forge another member's media) applies to screen tracks too.
- *Data used in `call_end`?* Keep it local: it is per-device usage metadata with no value to the
  peer.
