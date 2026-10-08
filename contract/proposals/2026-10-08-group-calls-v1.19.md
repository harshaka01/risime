# Proposal 2026-10-08: group calls (voice and video) with LiveKit, v1.19

**Status: merged into `contract/v1` as v1.19 on 2026-10-08** (PROTOCOL §20; §16.15 now points to
it; the exporter label registry in §10.3), with decision 056, after review by server, android and
crypto (`reviews/2026-10-08-group-calls-v1.19-*.md`). Every required change was applied in §20; the
draft below is kept as written. The main changes from review: `action` `start`/`join`/`status`
with LiveKit's room list as the only call state, `auto_create: false`, `call_ended`/`call_full`,
HMAC room names, `RemoveParticipant` on removal, the device must be a leaf (server S1–S4); group
ring limits and a push without fallback (S5, S6); `call_member` for siblings, the durable silent
`group_call` started/ended lines instead of `call_end`, busy, TURN via `ConnectOptions`, simulcast
with two layers, no data channel (android A2–A7); the exporter secret kept in Rust behind
`call_frame_keys`, the exact HKDF info, rekey on every epoch, join at the newest epoch, no
auto-ratchet, roster from MLS, honest limits incl. the audio-level extension kept for the SFU
(crypto K1–K10).

From: root, 2026-10-08. Roadmap step 3, approved by Harsha: group voice then group video on a
self-hosted LiveKit, UDP 49500–49999, E2EE keys from MLS. Replaces the §16.15 outline.

## 1. Goal and constraints
Group calls in `grp:` conversations, up to 32 people for voice and 8 for video, through a
self-hosted LiveKit SFU on spark2. Constraints:
- **The SFU never has media keys.** Frames are encrypted on the sender's phone with keys derived
  from the group's MLS state (LiveKit's frame encryption, `FrameCryptor`), so LiveKit and coturn
  forward ciphertext.
- **No call table on the server** if possible: LiveKit's own room state is the only state.
- **Ringing through MLS**, like 1:1 calls: `call_offer` with `"mode": "sfu"`.

## 2. One version for voice and video
`media: "audio" | "video"` as in v1.18. Both ship in v1.19 behind one capability
**`group_calls`** (which requires `calls` and `video`): the LiveKit SDK handles video the same way
as audio, and a second rollout would double the testing for little gain. Video rooms are capped at
8 participants.

## 3. Ports and signalling
- LiveKit RTC media **UDP 49500–49999** on 10.20.20.15 (1:1 NAT from 203.115.26.139); coturn's relay
  range shrinks to 49152–49499. No ICE-TCP (7881 is closed at the edge): clients without UDP reach
  LiveKit through coturn (TURN over TCP 3478), with credentials from `GET /calls/turn`.
- Signalling 127.0.0.1:7880 behind Caddy at `wss://risime.risicloud.ai/livekit`.

## 4. Rooms and tokens
`POST /api/v1/calls/rooms` `{"conversation_id": "grp:…", "call_id", "media"}` → `{"url", "room",
"token", "expires_at"}`. The token is a LiveKit JWT: identity `<user_id>/<device_id>`, an opaque
room name, `roomJoin`, publish microphone (and camera for video), subscribe; no admin, no data;
TTL 10 minutes. Only for active members with a `group_calls` device.

## 5. Ringing and history
- The starter sends `call_offer` `{"mode": "sfu", "media", "call_id", "sent_at"}` (no SDP) to the
  group with `call:signal` (`conversation_id` instead of `to`). Members' `group_calls` devices
  ring; Answer joins the room.
- The last participant to leave sends `call_end` (`mode: "sfu"`). History line: "Voice call ·
  12:03" or "Video call · 12:03".

## 6. E2EE from MLS
- `call_secret = MLS-Exporter("risime-call-v1", call_id as 16 bytes, 32)` at the current epoch.
- `sender_key = HKDF-Expand(call_secret, "risime-call-v1 sender" ‖ identity, 32)` per participant.
- Key index = `epoch mod 16`; on a new epoch every device derives the new keys and keeps the old
  ones for 10 s. A removed member has no new keys.
- Fail closed: undecryptable or unencrypted frames are never played.
- Core FFI: `export_secret(group, label, context, length)`.

## 7. Limits
32 participants (voice), 8 (video). Ring: 1 per group per 30 s.

## 8. Examples
`device_put_group_calls.json`, `calls_room_request.json`, `calls_room_reply.json`,
`call_signal_push_group.json`, `call_signal_event_group.json`, `call_offer_sfu_payload.json`,
`call_end_sfu_payload.json`.
