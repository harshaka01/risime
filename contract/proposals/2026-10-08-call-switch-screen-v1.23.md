# Proposal 2026-10-08: switching voice and video mid-call, and screen sharing, v1.23

**Status: merged into `contract/v1` as v1.23 on 2026-10-08** (PROTOCOL §23, with pointers in
§16.3, §19.0, §19.3, §20.2 and §20.4), after review by server, android and crypto
(`reviews/2026-10-08-call-switch-screen-v1.23-*.md`). Every required change was applied in §23; the
draft below is kept as written. The main changes from review: a renegotiation offer is applied only
after this device's user accepted the switch (crypto C2), and it must keep the DTLS role, mid 0's
transport and the ICE credentials (C1); the switch state machine gets a `seq` per sender and a 20-s
request timeout (android A9, crypto C6); the cap at `upgrade` also counts join tokens minted in the
last 600 s, `join` never refuses an identity already in the room, and `join` re-applies the grant
(server S2, S3); a participant's video in a group is rendered only while its MLS `call_media`
says camera or screen, re-sent when someone joins (crypto C2, C5);
the "is sharing" label comes from MLS, not from LiveKit's track source (C5); RisiMe's own windows
are `FLAG_SECURE` while sharing and sharing stops when the screen locks (android A2, crypto C4).

From: root, 2026-10-08. Calling slice approved by Harsha (after the current P0 work): mid-call
voice↔video switching, screen sharing, and the data a call-info screen needs.

## 1. Goal and constraints
- In a voice call a "Video" button asks to switch; the other side accepts or declines; audio never
  stops. Either side can go back to voice at any time. 1:1 and group.
- Screen sharing, 1:1 and group, on the existing E2EE paths (DTLS-SRTP bound to MLS for 1:1,
  MLS-derived frame keys through LiveKit for groups).
- A v1.13–v1.22 peer is never broken: nothing new is offered to it.
- A per-contact call-info screen with direction, time, duration and data used.

## 2. 1:1: how video gets into a voice call
Two options:
- **(a) Renegotiate once.** On the first accepted switch, add an `m=video` to the audio-only session
  through an MLS re-offer, keeping §16.10/§19.4 (one fingerprint, BUNDLE on the existing transport).
  Afterwards the session is the §19 shape for the rest of the call: further switches and screen
  sharing are `setTrack` plus `call_media`, as v1.18 camera on/off.
- **(b) Reserve a video transceiver in every call.** No renegotiation, but the first offer of every
  voice call would carry `m=video`. A v1.13–v1.22 callee device drops such an offer (§16.10 "exactly
  one `m=audio`") and the cleartext `media: "audio"` can't tell it apart, so **every old sibling
  device stops ringing for voice calls**. Rejected: it breaks old peers, and a caller can't know all
  callee devices before the offer.

**Pick (a).** Robustness comes from making it rare and glare-free:
- **at most one renegotiation per call** (only when the session has no `m=video`; a call that
  started as video never renegotiates);
- **only the original caller device sends re-offers** (it is already the DTLS `actpass` side), so
  two offers can't cross; a callee that wants video asks with a `call_switch` request and the caller
  re-offers after the accept;
- **audio is never touched**: the audio m-section, its SSRC and the transport stay; on any failure
  the caller rolls back (`ROLLBACK`) and the call stays a voice call.

**Capability:** per call, inside MLS: `call_offer` and `call_answer` gain
`"features": ["switch", "screen"]`. Old apps send none, so the buttons don't appear; old apps ignore
the unknown field. Device capabilities `call_switch` and `screen_share` (server-visible) are used by
the group path (§4) and the UI hints.

## 3. Signalling (1:1)
- New ephemeral **`call_switch`** (`call:signal`, `ring: false`, to the selected peer device):
  `{"v":1,"type":"call_switch","call_id","to_device","seq","action","source"}`, `action` one of
  `request` (with `source` `camera` | `screen`), `accept`, `decline`, `cancel` (naming the
  request's `seq`) and `voice` (back to voice, both sides stop sending video).
- **`call_offer` `renegotiate: true`** (`ring: false`, `to_device`, the same fingerprint), answered
  by `call_answer`. The binding rule becomes `ring == (call_offer && !restart && !renegotiate)`.
- **`call_media`** gains **`"video": "off" | "camera" | "screen"`** (`camera` kept as
  `video == "camera"` for v1.18 receivers).
- The cleartext `media` on `call:signal` stays the call's start media: **no server change for 1:1**.

## 4. Groups (LiveKit)
- Tokens for a voice room grant only `microphone` (§20.2). Options: video-capable tokens for every
  voice room, or a server-side switch. **Pick the switch:** `POST /calls/rooms` `action: "upgrade"`
  turns the room to video: the cap is checked (participants ≤ 8, else `409 too_many_for_video`), the
  room metadata `m` becomes `video`, and the server calls LiveKit's `UpdateParticipant` to add
  `camera` (and `screen_share` for `screen_share` devices) to every participant's grant. Later joins
  get video grants and the 8 cap. The requester gets a fresh token; others re-`join` for one.
- Then the requester sends a group **`call_switch` `action: "video"`** (MLS) and publishes.
- No per-member accept in groups: joining a group call is the consent to receive what members
  publish, as in every group-call product; **nobody's camera turns on unasked**, and each member has
  a local **"Voice only"** (stop own video, unsubscribe remote video). The room stays video for the
  cap.

## 5. Screen sharing
- **One video at a time per sender:** camera or screen. 1:1: one video sender, two tracks (a camera
  source and a screencast source), swapped with `setTrack`; no SDP change. Groups: LiveKit source
  `screen_share` (unpublish camera first).
- In a voice call, sharing starts with a `call_switch` request with `source: "screen"` (1:1) or an
  `upgrade` (group).
- Encoding: a screencast source (`isScreencast`), `MAINTAIN_RESOLUTION`, long side ≤ 1600 px,
  ≤ 15 fps, ≤ 1.2 Mbit/s on Wi-Fi and ≤ 600 kbit/s on mobile data; groups add a low simulcast layer
  (long side ≤ 800, ≤ 5 fps, ≤ 250 kbit/s). VP8 as before. No device audio.
- Privacy: only after an explicit tap and the system's MediaProjection consent, every time; a
  persistent banner and a Stop action; own notifications without content while sharing; the
  platform blacks out `FLAG_SECURE` windows; the viewer may pinch-zoom; stop on lock.

## 6. History and the call-info screen
- `call_end.media` and `group_call` `ended.media` become **`"video"` if the call was ever in video
  mode** (started as video, or a switch accepted). Old apps already render that as "Video call".
- The call-info screen needs nothing new on the wire: direction, time, duration and media come from
  the durable lines. **Data used is local only** (bytes from `getStats`, kept in the call row): the
  peer has its own numbers, and putting ours in `call_end` would only leak usage metadata.

## 7. Tests
Two redroid devices, 1:1 and a two-member group: voice → video → voice, start and stop sharing,
with audio `bytesSent` and `bytesReceived` growing on both devices in every phase.

## 8. Examples
`device_put_call_switch.json`, `call_offer_features_payload.json`,
`call_answer_features_payload.json`, `call_switch_request_payload.json`,
`call_switch_accept_payload.json`, `call_switch_voice_payload.json`,
`call_offer_renegotiate_payload.json`, `call_offer_renegotiate_payload_bad.json`,
`call_answer_renegotiate_payload.json`, `call_media_screen_payload.json`,
`call_switch_group_payload.json`, `call_media_group_payload.json`,
`calls_room_upgrade_request.json`, `calls_room_upgrade_reply.json`,
`calls_room_status_reply_v123.json`, `livekit_token_claims_v123.json`,
`livekit_update_participant.json`, `error_too_many_for_video.json`, `error_not_in_call.json`.
