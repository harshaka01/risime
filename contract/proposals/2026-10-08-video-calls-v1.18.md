# Proposal 2026-10-08: 1:1 video calls, v1.18

**Status: merged into `contract/v1` as v1.18 on 2026-10-08** (PROTOCOL §19, with pointers in
§16.2, §16.3, §16.9 and §16.10), after review by server, android and crypto
(`reviews/2026-10-08-video-calls-v1.18-*.md`). Every required change was applied in §19; the draft
below is kept as written. The main changes from review: a cleartext `media` on `call:signal` with a
delivery and push filter and `video_not_ready`, so old devices never ring blind (server S1); BUNDLE
required and one fingerprint value across m-sections (crypto K1, K2); more header extensions
stripped, answers limited to VP8 + `rtx`, no `rid`/simulcast (crypto K3–K5); the camera only while
the call screen is visible and never unasked, "Answer without video", glare across media, a 3-s
frozen-frame fallback, speaker by default (android A1–A6).

From: root, 2026-10-08. Roadmap step 2, approved by Harsha: 1:1 video on the existing §16 call
path.

## 1. Goal and constraints
A 1:1 video call between two e2ee DM participants, on exactly the §16 path: MLS signalling, P2P
WebRTC with coturn as the relay, DTLS-SRTP bound to MLS (§16.10). Nothing new on the server beyond
a capability and readiness. `media: "video"` is already reserved (§16.2).

## 2. Capability and readiness
- A v1.18 app advertises **`"video"`** next to `calls` once it can negotiate, decode and render
  video (camera optional: a phone without a camera still receives).
- `GET /mls/groups/{dm}` gains **`video_ready`** (each user has at least one `video` device) and
  **`missing_video`** (instances without it), exactly like `calls_ready` (§16.1).
- The DM header shows a video button next to the call button, disabled until `video_ready`.

## 3. Envelopes
- `call_offer` with **`"media": "video"`**: the SDP has one `m=audio` and one `m=video`.
- `call_end.media` is the offer's media. History lines: "Video call · 3:12", "Missed video call",
  "Video call · No answer", "Declined video call", "Video call · Couldn't connect".
- New ephemeral **`call_media`** `{"v":1,"type":"call_media","call_id","to_device","camera": bool}`
  through `call:signal` (`ring: false`): the sender's camera turned on or off.

## 4. SDP rules for `media: "video"` (extends §16.10)
- Exactly two m-lines, `m=audio` then `m=video`, both `UDP/TLS/RTP/SAVPF`, bundled.
- Video codec: **VP8** required. H264 and VP9 not used in v1.18: VP8 is a software codec in every
  libwebrtc build, so it behaves the same on every phone; Android H264 hardware encoders are the
  main source of OEM-specific failures; VP9 costs more CPU on low-end phones.
- No simulcast. `b=AS:1500` at most on the video m-line.
- Fingerprint rules of §16.10 unchanged.

## 5. Camera on/off without renegotiation
Both sides negotiate the video m-line `sendrecv` for the whole call. Turning the camera off
replaces the sender's track with none (`RtpSender.setTrack(null)`, no RTP sent); on is the
reverse. No SDP change, so no renegotiation, no new fingerprint question and no glare. `call_media`
tells the peer to show the avatar instead of a frozen frame. A voice call stays a voice call (no
upgrade to video in v1.18).

## 6. Old apps
A v1.13–v1.17 app drops an offer with an `m=video` line (§16.10 strict validation) and doesn't
ring. The caller therefore enables video only when `video_ready`; a callee's old second device
just doesn't ring for video calls.

## 7. TURN bandwidth
Decision 046's `max-bps=64000` bytes/s (512 kbit/s) per session is too low for video. Set
**`max-bps=300000`** (300 KB/s = 2.4 Mbit/s per direction per allocation): 1.5 Mbit/s video +
Opus + RTX and overhead fit with margin. Infra change in `infra/coturn/turnserver.conf`.

## 8. Examples
`call_offer_video_payload.json`, `call_offer_video_payload_bad.json`, `call_media_payload.json`,
`call_end_video_payload.json`, `device_put_video.json`, `mls_group_video_ready.json`.
