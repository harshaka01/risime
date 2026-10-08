# Review: group calls with LiveKit v1.19, crypto side

**Reviewer:** crypto (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-group-calls-v1.19.md`.
**Verdict:** accept with the required changes below. One MLS core change (the exporter FFI).

## What I checked
- RFC 9420 §8.5 (exporter: per epoch, `MLS-Exporter(Label, Context, Length)`), OpenMLS
  `export_secret` (current epoch only).
- LiveKit frame encryption: AES-GCM per frame, a key ring indexed by a one-byte key index in the
  frame trailer, per-participant keys in `BaseKeyProvider`, optional auto-ratchet on failure,
  codec-specific bytes left in the clear for the SFU.
- What LiveKit, coturn, a removed member and a current member can do.

## Required changes
- **K1. The exporter secret stays in Rust.** Kotlin needs per-sender frame keys (they go to
  `FrameCryptor`), not the exporter output. The core gets `export_secret(group, label, context,
  length)` that accepts **only registered labels** (`risime-call-v1`) and `length = 32`, and the
  FFI function Kotlin calls is **`call_frame_keys(conversation_id, call_id) → {epoch, key_index,
  keys: [{identity, key}]}`**, which derives every leaf's key inside Rust. A registry of exporter
  labels goes into §10.
- **K2. Exact derivation.** `call_secret = MLS-Exporter("risime-call-v1", call_id as its 16 raw
  bytes, 32)`; `frame_key(identity) = HKDF-Expand-SHA256(call_secret, "risime-call-v1 frame" ‖ 0x00
  ‖ identity UTF-8, 32)`, where `identity` = the leaf credential identity `"<user_id>/<device_id>"`;
  `key_index = epoch mod 16`. `call_id` comes from the MLS-authenticated offer, never from the
  server. Test vectors in `contract/v1/call_vectors.json`.
- **K3. Rekey on every epoch,** not only on membership changes: any merged commit derives new keys
  (a self-update rotates too). The sender switches to the new index as soon as it merged; receivers
  keep the previous epoch's keys for **10 s**, then wipe them. Frames with an unknown index are
  dropped and trigger a commit catch-up.
- **K4. Join only at the newest epoch.** Before deriving, the device catches up its commits; it
  never derives from an epoch older than the newest it knows.
- **K5. The removal gap.** Until the removal commit lands, a removed member still holds the
  current keys. The server's `RemoveParticipant` (server S3) closes the SFU path at once; the commit
  closes it cryptographically. Both are required; say so.
- **K6. No auto-ratchet.** LiveKit's ratchet-on-failure derives keys outside MLS: `ratchetWindowSize`
  0, no shared key, `discardFrameWhenCryptorNotReady`. A track whose LiveKit `TrackInfo` says it is
  not encrypted is never rendered; a participant whose frames don't decrypt is shown as "Can't
  verify" and never played (**fail closed**).
- **K7. The roster comes from MLS.** A LiveKit participant is shown with a name only if its
  identity is a leaf of the group at the current epoch; any other is "Not a member" and never
  played. The SFU and the server can add ghosts, but ghosts have no keys.
- **K8. State the limits honestly.**
  - Every member derives every member's frame key, so **a member can forge another member's media**
    in the call (no per-frame signatures). Same as SFrame with group keys.
  - LiveKit leaves codec headers in the clear (Opus TOC byte; VP8 payload header, which in a
    keyframe includes the frame size) and sees packet sizes and timing.
  - The RTP **audio-level** header extension: the SFU uses it for speaker detection and stream
    allocation, so it is **not** stripped in group calls (unlike §16.10). The SFU is our own server
    and DTX already shows who speaks; coturn sees it for relayed participants.
- **K9. The badge.** "End-to-end encrypted" in a group call means every rendered track decrypted
  with an MLS-derived key; it disappears for the call while any rendered participant fails.
- **K10. Key hygiene.** Frame keys live in memory only, are wiped at call end and on leaving, never
  persisted, never logged; `call_frame_keys` returns nothing for a group the device has no state
  for.

## Answers
- *Does the hop-by-hop DTLS to LiveKit matter?* Not for content: frames are encrypted before
  SRTP. LiveKit terminates SRTP and sees the frame ciphertext and the clear bytes above.
- *Why not one shared key?* Per-identity keys give each sender its own AES-GCM nonce space and let
  a receiver tell which leaf a frame claims; they don't stop insider forgery (K8).
