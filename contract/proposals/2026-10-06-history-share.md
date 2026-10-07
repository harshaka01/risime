# Proposal: PROTOCOL v1.15 candidate — group and DM history sharing between devices

**Status:** proposal (root, 2026-10-06), for review by crypto, server and android. Not part of
`contract/v1` until merged. The version number is decided at merge: v1.13 (calls) is already
proposed, and its §16.12 sketches group calls as "v1.14", so whichever lands second takes the next
number (2026-10-07: v1.13 went to calls and v1.14 to member device restore, so this is v1.15). The section numbers below assume calls take §16, so history sharing is **§17**.
Decision 049 (proposed). Additive: apps without the `history_share` capability are never asked to
share and never see the new event kinds (§17.1).

## 0. The problem
After a logout and login (or a reinstall, or a new phone), a group or e2ee DM shows **"Earlier
messages aren't available on this device"** (§13.3). That is correct: a fresh sign-in is a new MLS
leaf, it can't decrypt anything encrypted before it joined (decision 032), and the server holds
only ciphertext. v1.10 made the gap visible; this proposal lets the user **fill it** from a device
that still holds the plaintext.

**Sources, in order of preference:**
1. **The same user's other device** (multi-device). The safest source: it's the user's own
   history, and no other person's privacy decision is involved.
2. **Another member's device**, with that member's consent, and **only the messages from periods
   when the requester was a member** (the server's `group_member_intervals`, §14.8) and only of
   that one conversation.
3. **A client-encrypted backup** (§13 "next step", proposal still to come). That is the proper
   long-term path: it works when no other device is online and when the user has no other device.
   §17.11 says how the two fit together. Sharing does not replace it.

What this proposal does **not** do: restore anything from the server (it still holds only
ciphertext), share MLS state or keys, or let a member read messages from before they joined.

## 17.0 Principles
- **Device to device, end to end.** The history travels as one encrypted bundle from the provider
  device to the **one requesting device**. The server routes the request, stores the encrypted
  bundle as a short-lived blob, and never sees plaintext or the bundle key.
- **Not through the group's MLS application messages.** A bundle key sent as a normal group
  application message would be readable by every current member, including members who were not
  there at the time of the shared messages. So the bundle key is sealed with **HPKE to a fresh
  per-request key of the requesting device** (§17.3); MLS is used only to authenticate the request
  and the reply.
- **The requester accepts only what it can place.** It imports only messages whose ids, senders
  and timestamps it already received from the server as undecryptable pre-install events (the
  **gap index**, §17.2). A provider can't invent messages, re-attribute a message to another
  sender, resurrect a deleted one, or leak messages from outside the requester's membership.
  It **can** alter the text of a message it shares (§17.6); the UI says who shared it.
- **Consent.** Another member's phone **always asks its user**. The same user's own devices share
  after one approval per new device (§17.7; the question for Harsha).
- **Deletes win.** Nothing deleted for everyone, deleted for me or cleared (§15) comes back.
- **No learning-log entries:** no model call is involved (§17.12).

## 17.1 Capability
- A v1.15 app advertises **`"history_share"`** with its MLS device
  (`PUT /me/devices/{device_id}` `"mls": {"signature_key", "capabilities": [..., "history_share"]}`)
  once it can both **provide** (export, encrypt, upload) and **receive** (verify, import) bundles.
- Only devices with the capability are named as providers (§17.4). A requester whose eligible
  devices all lack it sees "No device that can share this history is available".
- No readiness flag in `GET /mls/groups/{id}`: sharing is opportunistic.

## 17.2 The gap index (client; extends §13.3)
Today a pre-install message "advances the cursor" and leaves nothing behind. From v1.15 the client
keeps a **content-free gap row** for every pre-install `message` event:
`history_gap(conversation_id, message_id, from, from_device, server_ts, generation, epoch)`, in the
same transaction as the cursor (and the §13.3 marker upsert), kept until
`server_ts + 30 days` (the inbox TTL; after that the server holds nothing either).
- Also recorded: messages that §12.8 rejoin and reset make unreadable (parked events dropped by
  rule 2, old-generation events discarded on a reset). They are pre-install by §13.3, so they're
  already covered; a reset's missing messages become requestable too.
- E2EE reactions are indistinguishable from messages to the server (`kind` null), so they get gap
  rows as well; a gap row says nothing about the content type.
- **Not** recorded: control events (`delete`, `history_*`, §15.6 "control events never create §13.3
  lines"), own-device echoes, plaintext events (they replay readable).
- When a `delete` event (§15.5) or `chat:clear` watermark (§15.7) covers a gap row, the row is
  removed. Events the server has already deleted (for everyone, or "for me" from the user's other
  device) are never replayed, so they never get a gap row.
- **v1.10–v1.13 installs already past the gap** have no gap rows. They get them on the next fresh
  sign-in, or by the optional one-off re-scan in open point A5.

The request range is `[min(server_ts), max(server_ts)]` over the conversation's gap rows.

## 17.3 Crypto: authentication by MLS, confidentiality by HPKE
Three options were considered for protecting the bundle:

| Option | Verdict |
|---|---|
| The group's MLS application messages | **Rejected.** Every current member can decrypt them, so the bundle would be exposed to members who weren't present at the time. |
| A separate 1:1 MLS group between the two devices | Works, but costs a key-package claim, a Welcome, a new group's state on both devices, its lifecycle and cleanup, for one transfer. Heavy for a one-shot. |
| **HPKE (RFC 9180) to a fresh per-request key, inside MLS-authenticated envelopes** | **Chosen.** One-shot, forward secret (the private key is deleted after import or expiry), no new MLS state, no key-package consumption, and HPKE is already in the core's crypto provider (OpenMLS uses it for Welcomes). |

HPKE directly to the requester's **MLS leaf encryption key or key-package init key** was rejected:
reusing an MLS key outside MLS breaks key separation, the leaf key rotates on every update, and
key packages are one-time and consumed by claims.

**The scheme** (the core implements it; parameters for the crypto review):
1. The requesting device generates an X25519 key pair `(rsk, rpk)` per request and stores `rsk` in
   the sealed store until the request ends (import done, cancelled, or 48 h).
2. It sends the **`history_request`** envelope (§17.5) as an MLS application message in the
   conversation's group at its current epoch. MLS authenticates it: the provider learns the
   requester's attested `user_id/device_id`, that the device is a member leaf at that epoch, and
   `rpk`.
3. The provider builds the bundle (§17.6), picks a fresh 32-byte content key `K`, encrypts the
   bundle as an `A256GCM-S64K` blob (§14.3, unchanged), and seals `K` with HPKE base mode
   (`DHKEM(X25519, HKDF-SHA256)`, `HKDF-SHA256`, `AES-128-GCM`, as ciphersuite 0x0001) to `rpk`,
   with `info = "risime-history-v1" ‖ request_id(16) ‖ conversation_id(UTF-8) ‖
   requester "<user_id>/<device_id>" ‖ provider "<user_id>/<device_id>"` (length-prefixed fields).
4. The provider sends the **`history_share`** envelope (§17.5) as an MLS application message: the
   blob reference, the HPKE `enc` and sealed `K`. MLS authenticates the provider.
5. Both envelopes carry a canonical MLS `authenticated_data` of **`0x01 0x48` (`'H'`) ‖
   request_id as 16 bytes**, so the server can check that the cleartext `request_id` it routes on
   matches the encrypted request (as §15.3 does for deletes). §15.3's rule "receivers drop a
   non-`delete` application message with non-empty `authenticated_data`" is widened to allow the
   `'H'` form for the two `history_*` types only.

Even if the server delivered these MLS messages to other members (it routes them to one device
only, §17.4), they would learn only `rpk`, a blob id and an HPKE-sealed key: nothing usable.
Generation gaps from messages that only one device receives are within the forward distance
(§15.10), as for `scope: "me"` deletes.

## 17.4 Server: requests, provider naming and routing
**Push `history:request`** (client → server), `history_request_push.json`:
```json
{"request_id": "uuid-v4", "conversation_id": "grp:…" | "dm:…",
 "range": {"from": "<ISO ms>", "to": "<ISO ms>"}, "gap_count": 412,
 "ciphertext": "<b64 PrivateMessage, the history_request envelope>",
 "generation": 3, "epoch": 17}
```
→ reply ok `{"request_id", "state": "searching"}`. Errors: `not_member` (not an active member /
not a DM participant; also friends-and-no-block for a DM), `not_e2ee`, `stale_epoch`, `too_long`,
`bad_request` (the AAD doesn't match `request_id`; `from > to`; a range older than 30 days),
`rate_limited` (§17.10), **`request_open`** (this device already has an open request for this
conversation; the reply carries the existing `request_id`).

**Eligibility.** The server computes, from metadata it already has:
- **the requester's membership intervals** in the conversation, intersected with `range`
  (`group_member_intervals`; a DM: the whole range). Empty → `bad_request`.
- **eligible devices:** current MLS devices with `history_share` that are in the group at the
  current generation, excluding the requesting device. Same-user devices are always eligible.
  Another member's device is eligible only if that member's own interval overlaps the requester's
  (it can't have messages from anywhere else).

**Naming providers** (like the §12.4 committers: the server names, devices don't race):
1. **Own devices first**, most recently seen first, **one at a time with 120 s** each
   (`provider_until`). An own device that is offline at naming time is skipped and named again when
   its inbox joins, while the request is open.
2. **Then other members**, only if no own device accepted. Up to **3 member users at a time**,
   preferring the largest overlap with the requester's intervals, then the most recently seen
   device. Each named user's devices get the request; the user has **24 h** to answer. The first
   `accept` wins; the others get `history_request_closed`.
3. Nobody accepts within 24 h, or nobody is eligible → state `unavailable`.

The requester may also choose "Only my devices" (`"sources": "own"`, default `"any"`), which stops
after step 1.

**Events** (stored in the inbox, cursor-ordered, TTL 48 h, never shown in history):
- **`history_request`** to each named provider's user, with `to_devices` (named devices only; others
  ignore it): `{"request_id", "conversation_id", "from", "from_device", "range",
  "intervals": [{"from", "to"}], "gap_count", "ciphertext", "generation", "epoch", "consent":
  "own" | "member", "expires_at"}` (`event_history_request.json`). `intervals` are the server's
  view, clipped to `range`. Pushed as a data push (own) or a visible-notification push (member).
- **`history_request_closed`** `{"request_id", "reason": "accepted_elsewhere" | "cancelled" |
  "expired"}` to every user previously named, so a pending prompt disappears.
- **`history_status`** to the requester's user, `to_devices: [requester]`: `{"request_id",
  "state": "searching" | "waiting_for_member" | "accepted" | "unavailable" | "declined" |
  "cancelled", "provider": {"user_id", "device_id"} | null}`. `provider` is set only on
  `accepted`. A decline is reported only as `unavailable` once no candidate is left, so the
  requester never learns **who** declined.
- **`history_share`** to the requester's user, `to_devices: [requester]`: `{"request_id",
  "conversation_id", "from", "from_device", "ciphertext", "generation", "epoch", "server_ts"}`.

**Push `history:respond`** (provider → server): `{"request_id", "decision": "accept" | "decline" |
"unable"}` → ok `{}` or `gone` (already accepted elsewhere, cancelled, expired). `unable` (e.g. not
at the epoch, no data) counts as a decline and moves to the next candidate.

**Push `history:deliver`** (provider → server): `{"request_id", "ciphertext", "generation",
"epoch", "parts": n, "part": i}` (the `history_share` envelope; checks as §10.3, AAD as §17.3) → ok.
Only the accepted provider device may deliver; a part may be redelivered idempotently by
`(request_id, part)`. The request is `done` when all parts are delivered.

**Push `history:cancel`** (requester → server): `{"request_id"}` → ok; closes it everywhere.

**Server storage.** Postgres `history_requests(request_id, requester_user, requester_device,
conversation_id, range_from, range_to, sources, state, provider_device, candidates jsonb,
named_until, created_at, expires_at)`, rows deleted 7 days after closing. No content. The events
are ordinary inbox rows behind `RisiMe.Messaging.Store` with a 48 h TTL. A sweeper (Oban) moves
naming on at `provider_until` and expires requests.

## 17.5 The envelopes (MLS application messages)
`history_request_payload.json`:
```json
{"v": 1, "type": "history_request", "request_id": "…",
 "range": {"from": "…", "to": "…"}, "gap_count": 412,
 "hpke": {"kem": "x25519", "pk": "<b64 32 bytes rpk>"}}
```
`history_share_payload.json`:
```json
{"v": 1, "type": "history_share", "request_id": "…", "part": 1, "parts": 1,
 "blob": {"blob_id": "…", "size": 1048592, "sha256": "<b64>"},
 "enc": {"alg": "A256GCM-S64K", "plain_size": 1040000,
         "hpke_enc": "<b64 32 bytes>", "sealed_key": "<b64 48 bytes>"},
 "count": 398, "range": {"from": "…", "to": "…"}}
```
Strict validation as §14.4 (sizes, `alg`, lengths; `request_id` equal to the AAD and to an open
request of **this** device). Anything else is dropped and logged. Old apps ignore both types
(§10.3) and never receive them anyway (routed only to capable devices).

## 17.6 The bundle: export (provider) and import (requester)
**Export (provider).** After accepting, the provider decrypts the request (it must be at or past
the request's epoch; it catches up first) and exports, for the one conversation:
- only rows it holds **as readable messages**: `text`, `image`, reactions (§11), and `call_end`
  lines (v1.13). Never tombstones, hidden-tombstoned or "deleted for me" rows, system lines,
  §13.3 markers, control envelopes, outbox rows or anything after the provider's own `cleared_upto`.
- only rows whose `server_ts` lies inside **both** the server's `intervals` from the event **and**
  the provider's own local view of the requester's membership (from the `group_event` lines it
  holds); if the two disagree, the narrower wins. A DM: the whole range.
- only rows within `range`, at most **5 000 entries per part**, oldest first.

Bundle plaintext (before the §14.3 blob encryption), JSON Lines, UTF-8:
```
{"v":1,"type":"history_bundle","request_id":"…","conversation_id":"…","provider":"<user>/<device>","part":1,"parts":1,"count":398}
{"message_id":"…","from":"<user_id>","from_device":"<device_id>","server_ts":"…","payload":{…the original application payload: text / image / reaction / call_end…}}
…
```
- **Images:** the original `image` envelope (blob ref, `K`, thumbnail). No re-upload: the requester's
  user is already a reader of a `grp:` `media` blob uploaded while it was a member, and of every
  `dm:` blob (§14.2). Blobs past their 30-day TTL or deleted show "This photo is no longer
  available". The thumbnail travels in the bundle.
- **Reactions** travel as their original envelopes, with their own `message_id`; the requester
  applies them only to a target it holds.
- **Size:** each part's blob is at most **16 MiB** ciphertext; at most **20 parts** per request.
  Larger history is cut at the oldest end, and the requester shows "Only part of the history was
  shared" with the range it got.

**Import (requester)** — each entry, in one transaction per part, with the cursor untouched:
1. The `history_share` envelope verified (§17.5), the blob fetched and `sha256` checked, `K`
   opened with `rsk` (HPKE `info` as §17.3, binding the provider identity from the **MLS
   credential**, never from the bundle header), the blob AEAD-opened (§14.3).
2. The header's `request_id`, `conversation_id` and `provider` must match; otherwise the whole part
   is dropped.
3. Each entry is accepted only if a **gap row** exists with the same `message_id`, `from`,
   `from_device` and `server_ts`, and no local row (message, tombstone or hidden tombstone) exists
   with that `message_id`, and `server_ts` is after the chat's `cleared_upto`. Otherwise the entry is
   skipped (counted, logged by count only).
4. The payload passes the same strict validation as a live message of its type (§11, §14.4,
   v1.13 for `call_end`).
5. Stored as an ordinary row with **`origin = "shared"`** and **`shared_by = <provider user_id>`**,
   local time from `server_ts` (§13.3 "Replayed history"), **never notified, never unread, never
   acked, no receipts**. The gap row is removed.

Then the private key `rsk` is deleted (after the last part, on cancel, or at 48 h).

## 17.7 Consent and policy
- **Same user's devices.** Recommended: **one approval per new device**, then automatic. The first
  time a new device of mine asks, my existing device shows **"Your new device (<model>, signed in
  <time>) wants your chat history. Share?"** [Share all chats] [Not now]. After "Share all chats"
  every later request from that device is answered automatically and silently. The alternative,
  fully automatic sharing, is simpler but means anyone who takes over the phone number (phone
  verification by SMS is the sign-in factor, decisions 013 and 020) gets every chat's last 30
  days without the owner noticing. Either way the providing device shows a quiet notification "Shared chat history with your new
  device" afterwards.
- **Another member's device: always ask.** A notification and an in-chat banner:
  **"<name> wants the group history from when they were in the group. Share?"** [Share] [Not now]
  (DM: "<name> signed in again and wants your chat history. Share?"). Expires after 24 h. No
  "always" option in v1.15.
- **Admin policy (later, not in this version):** a group setting "Members' phones may share history
  automatically" in `group_meta`, set by a `meta_changed` commit. Left out until we see whether the
  prompts annoy people.
- A provider's user can turn sharing off entirely: Settings → Privacy → "Share chat history with
  other members' new devices" (default on = "ask"); off → the device answers `decline` without
  prompting. Own-device sharing has its own switch.

## 17.8 Client UI
- **The marker line** "Earlier messages aren't available on this device" gains an action
  **"Request history"** while the chat has gap rows and the device has no open request for it.
  Tapping it asks "Request history from: [Your other devices] [Your devices and members]" in groups
  (DMs: "Your other devices / <name>'s phone").
- **Progress**, on the marker line: "Looking for a device…", "Waiting for <name>…" (member,
  `waiting_for_member`, without naming who declined earlier), "Receiving history… 2 of 3",
  "No device could share this history right now" with "Try again".
- **After import:** the marker becomes **"History shared by <name>"** (or "History restored from
  your other device") at the oldest imported message. If gap rows remain (partial coverage), a
  second line "Some earlier messages aren't available on this device" stays below the imported
  block, still with "Request history".
- **Shared messages from people other than the provider** carry a small label **"Shared by
  <provider>"** in the bubble's info sheet (not on every bubble; the block header covers it).
  Messages restored from my own device carry no label.
- The provider sees a pending request in the chat ("<name> asked for the group history") until it
  is answered or expires.

## 17.9 Interaction with §12.8, §13 and §15
- **§13 markers:** unchanged rules; the gap index is the new data behind them. A request is offered
  only where a marker exists.
- **§12.8 rejoin and reset:** messages lost to a rejoin or a reset get gap rows like pre-install
  messages and can be requested. The §12.8 reset line stays; it gains "Request history" when gap
  rows exist for that conversation.
- **§15 deletes:** deleted-for-everyone and deleted-for-me messages never get gap rows (the server
  doesn't replay them); a `delete` that arrives later removes the gap row or tombstones the imported
  row through the normal §15.6 path; hidden tombstones block the import (§17.6 step 3); the
  provider never exports tombstones or rows after its own `cleared_upto`; the requester skips rows
  before its own. `delete` of a shared message works as for any message (same `message_id`).
- **Removed members:** a user who is no longer an active member can't request (`not_member`).
  Members removed after a message keep it on their device and may provide it only to someone whose
  interval covered it.

## 17.10 Abuse limits
- `history:request`: at most **3 per user per conversation per day** and **30 per user per day**;
  one open request per device per conversation.
- Member prompts: a given member user is asked by a given requester **at most twice per
  conversation per 7 days**; a "Not now" counts. The server never names a member who declined that
  requester in the last 24 h.
- `history` blobs: purpose **`history`** (§14.2 extended): cap 16 MiB ciphertext, TTL **48 h**,
  readers = the uploader and the **requester's user** of an open or done request naming that
  blob's `request_id` (upload with `&request_id=`), 40 uploads per user per hour, quota 512 MiB
  live. Deleted by the server when the request closes as done + 1 h.
- A device that receives a `history_share` for a request it didn't make, or that fails
  verification, drops it and logs by count; repeated failures from one provider are telemetry only.

## 17.11 Encrypted backup (coordination)
- The future backup proposal should reuse the **bundle format** (§17.6) as its unit, so restore
  and sharing share one import path, one validation and one `origin` field (`"backup"`).
- Precedence on a new device: a backup restore runs first (it is the user's own data, and works
  offline); the gap rows left after it are what "Request history" fills.
- Backup restores don't need the gap index: they are the user's own history, authenticated by
  the user's backup key, so they may go past the 30-day window.
- Sharing stays the 30-day, ad-hoc path; backup is the complete path.

## 17.12 Privacy and the learning log
- The server learns that a device asked for a conversation's history, the range and count, which
  devices were named, who accepted (not who declined, to the requester), and the bundle sizes. It
  never sees content or keys.
- A member who shares discloses what they hold, filtered to what the requester was entitled to
  receive at the time; nothing from before the requester joined or after they left.
- **Learning log: none.** No model call. The on-device behaviour log records nothing about history
  sharing; shared plaintext stays on the two devices.

## 17.13 Test coverage (all gates)
- **Crypto (core):** HPKE seal/open round trip with the `info` binding; a wrong provider identity
  or `request_id` in `info` → open fails; the `'H'` AAD round trip and a mismatch → dropped;
  vectors in `contract/v1/history_vectors.json`.
- **Server:** naming order (own devices first, 120 s rotation, then up to 3 members by overlap),
  eligibility excludes members without overlapping intervals and devices without the capability,
  `intervals` clipped to the range, `request_open`, the rate limits, `not_member` for removed
  members and blocked DM partners, the AAD check, accept races (first wins, others `gone` and
  `history_request_closed`), decliners never named to the requester, `history` blob readers
  (requester user only; others `404`), TTL and deletion after done, events only to `to_devices`,
  no `ALLOW FILTERING`.
- **Android:** gap rows for pre-install, rejoin and reset messages; none for control events and
  deleted messages; gap rows removed by `delete` and `chat:clear`; export filters (intervals ∩
  local view, tombstones, hidden tombstones, `cleared_upto`, control types); import rejects entries
  with no gap row, a mismatched `from`/`from_device`/`server_ts`, an existing row, a hidden
  tombstone, a malformed payload; imported rows are not notified, unread or acked; images
  reference the original blob; the marker changes and partial coverage; consent prompts and
  expiry; parsing every new example.

## Examples (new)
`history_request_push.json`, `history_request_payload.json`, `history_share_payload.json`,
`event_history_request.json`, `event_history_request_closed.json`, `event_history_status.json`,
`event_history_share.json`, `history_respond.json`, `history_deliver.json`, `history_cancel.json`,
`blob_upload_history_reply.json`, `error_request_open.json`, `history_bundle_header.json`.

## Changelog entry (draft)
- **v1.15 (candidate)**: history sharing between devices: a requesting device asks per conversation
  (`history:request`), the server names eligible devices (own devices first, then members with
  consent), the provider sends an `A256GCM-S64K` bundle whose key is HPKE-sealed to the requester's
  per-request key inside MLS-authenticated envelopes; the requester imports only entries matching
  its gap index. New capability `history_share`, blob purpose `history`, four event kinds. Additive.

## Open points for review
**For Harsha**
- H1. **Consent for the user's own devices:** one approval per new device on the old phone
  (recommended), or fully automatic? Members' phones always ask in both cases.
- H2. Limit to the 30-day inbox window (recommended: only verifiable messages), or also accept
  older messages a provider holds, marked "unverified"?
- H3. Should a group admin later be able to set "share automatically"?

**Crypto**
- C1. HPKE to a per-request key vs a 1:1 MLS group between the devices; confirm the `info` fields
  and that binding the provider identity from the MLS credential is enough.
- C2. Forgery limit: a member provider can change the text of other people's messages it shares.
  Can the core keep each decrypted message's signed `FramedContent` (sender signature over content,
  group id, epoch, AAD) and the `GroupContext`, so a bundle entry carries a sender signature the
  requester checks against the sender's attested key? Cost: about 150–300 bytes per message, a core
  change, and the GroupContext itself can't be verified by a device that wasn't in that epoch.
  Without it, the "Shared by" label is the honest answer.
- C3. Widening §15.3's empty-AAD rule to the `'H'` form.
- C4. `rsk` storage (sealed store) and deletion guarantees.

**Server**
- S1. Naming state machine placement (reuse the §12.4 committer machinery or a separate Oban
  sweeper), and the visible-notification push for member prompts.
- S2. Computing eligibility with `group_member_intervals` (indexes; DMs have no interval table:
  use the friendship/e2ee start?).
- S3. The `history` blob purpose: readers keyed by `request_id`, quota and the cleanup on done.
- S4. Routing MLS application messages to one device only (`to_devices` on `history_*` events) and
  the AAD check on `history:request` / `history:deliver`.

**Android**
- A1. The gap table (Room migration), written in the cursor transaction of `MlsPipeline`.
- A2. Export cost on the provider for 5 000 entries (background worker, battery, and doing it while
  the app is in the background after a notification tap).
- A3. Import: one transaction per part; interaction with the chat-list preview and search index.
- A4. UI wording for the marker, the progress states and the prompts.
- A5. A one-off re-scan for installs that passed their gap before v1.15 (a metadata-only replay of
  the user's own inbox from `since: null`), or simply "sign in again after updating".
