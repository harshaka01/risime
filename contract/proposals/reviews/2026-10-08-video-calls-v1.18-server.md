# Review: 1:1 video calls v1.18, server side

**Reviewer:** server (root acting as reviewer), 2026-10-08.
**Proposal:** `contract/proposals/2026-10-08-video-calls-v1.18.md`.
**Verdict:** accept with the required changes below.

## What I checked
- `call:signal` (§16.3): order of checks, `calls_not_ready`, the `:calls` socket filter, the
  call-signal store, the device-level `call` push and its 3-s fallback (§16.8).
- The locked-phone blind ring (decision 051): it rings on the push alone, before any decrypt.
- coturn (`infra/coturn/turnserver.conf`, decision 046) and spark2's uplink.

## Required changes
- **S1. Old devices would ring blind for a video call.** The server can't see `media` (inside MLS),
  so it pushes the `call` wake-up to every `calls` device, including a ≤ v1.17 tablet. A locked
  one rings "Incoming RisiMe call" at once (§16.8); after the fingerprint it decrypts an offer it
  must drop (`m=video`) and shows "Call ended". The same happens for live sockets that ring
  nothing but still wake. Fix: `call:signal` gains a cleartext **`"media": "audio" | "video"`**
  (absent = `audio`), set on **every** signal of a video call. The server then:
  - delivers `media: "video"` signals (live and join/sync) only to sockets whose device advertises
    `video`, and sends the `call` push only to `video` devices;
  - refuses `ring: true` with `media: "video"` to a user without a `video` device that has a
    signature key: **`video_not_ready`** (as `calls_not_ready`, §16.1), checked right after
    `calls_not_ready`;
  - copies `media` onto the `call_signal` event (absent = `audio`), for the receivers' binding.
  `media` other than the two values → `bad_request`. The server learns voice vs video per call;
  coturn learns it from the bitrate anyway.
- **S2. `call_media` rate.** A user flapping the camera sends `ring: false` signals; the existing
  30 per pair per 10 s bound holds. No new limit; clients send at most one `call_media` per second
  per call (the last state wins).
- **S3. TURN.** `max-bps=300000` is per session and per direction (coturn treats input and output
  separately): fine for one call. `total-quota=300` allocations at 2.4 Mbit/s could in theory ask
  for 720 Mbit/s; spark2's uplink is the real bound. No `bps-capacity` for the pilot (a handful of
  relayed calls), but the decision must say so and the release check measures one relayed video
  call (expect 1–2 Mbit/s each way). Infra change: `max-bps=300000` (already in
  `infra/coturn/turnserver.conf` with the 2026-10-08 port split); decision 046 gets an amendment
  line.
- **S4. Readiness.** `video_ready` / `missing_video` are computed like `calls_ready` /
  `missing_calls` (one `video` device per user; superseded devices never count, §12.1).

## Answers
- *Server state for video?* None beyond the capability column value and the one cleartext field.
  Still no call table.
- *Storage?* The call-signal store is unchanged (payload text with one more field).
