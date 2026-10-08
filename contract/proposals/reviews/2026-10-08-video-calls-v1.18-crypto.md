# Review: 1:1 video calls v1.18, crypto side

**Reviewer:** crypto (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-video-calls-v1.18.md`.
**Verdict:** accept with the required changes below. No MLS core change.

## What I checked
- §16.10: one SDP source (MLS), encryption on, a fresh certificate, the pinned fingerprint, the
  post-connect `getStats` check, the stripped audio-level extension, strict SDP validation.
- What SRTP leaves in the clear: RTP headers and header extensions, packet sizes and timing.

## Required changes
- **K1. Fingerprints with two m-sections.** libwebrtc writes `a=fingerprint` in each m-section.
  §16.10's "exactly one `a=fingerprint`" must become: every fingerprint line in the SDP (session
  level or per m-section) is `sha-256`, all carry **the same value**, and there is at least one.
  The post-connect check compares that one value (one DTLS transport, BUNDLE).
- **K2. BUNDLE is required.** `a=group:BUNDLE` naming both mids, and `a=rtcp-mux`, so there is
  exactly one DTLS transport whose fingerprint §16.10 (e) checks. An unbundled answer would open a
  second transport the check doesn't cover: reject it.
- **K3. More header extensions leak.** Besides `ssrc-audio-level`, strip and reject
  `urn:ietf:params:rtp-hdrext:csrc-audio-level` (levels), `http://www.webrtc.org/experiments/rtp-hdrext/abs-capture-time`
  (the sender's capture clock) and `urn:3gpp:video-orientation` (how the phone is held). Without
  CVO libwebrtc rotates frames before encoding; that is cheap at these sizes. Keep `sdes:mid`,
  transport-cc and abs-send-time (congestion control needs them; they carry no content).
- **K4. Decoders only after a human answer.** A video decoder on attacker-chosen bytes is a large
  surface. The callee's video decoder exists only after Answer (the remote description is applied
  only then, as today), and **answers carry only VP8 and its `rtx`**: a v1.18 receiver rejects an
  answer whose video m-line lists any other codec, `red`, `ulpfec` or `flexfec`. Offers may list
  more (forward compatibility); the answerer's codec preferences pick VP8.
- **K5. No simulcast, one video source.** Reject `a=simulcast`, `a=rid`, and any `a=ssrc-group`
  other than one `FID` (video + its RTX). A second video sender would be a second camera the user
  can't see.
- **K6. Bind the cleartext `media`** (server S1): `event.media == envelope.media` for a
  `call_offer`; every later signal of the call carries the offer's media in the event; a mismatch
  is dropped and logged, as for `call_id` and `ring` (§16.3 binding).
- **K7. Say what leaks.** Video is VBR: coturn (relayed calls) and on-path observers see bitrate,
  keyframes and frame timing, hence motion and camera on/off. Not content. Stated in the privacy
  section, like DTX for audio.
- **K8. The "End-to-end encrypted" badge** in a video call follows §16.10 (e) unchanged (one
  transport); camera on/off doesn't touch it.

## Answers
- *Frame encryption (SFrame) for 1:1?* Not needed: media is DTLS-SRTP end to end between the two
  phones, also through coturn. Frame encryption is for the SFU (v1.19).
- *Does camera toggling weaken anything?* No: no SDP change, no new keys, the fingerprint never
  moves.
