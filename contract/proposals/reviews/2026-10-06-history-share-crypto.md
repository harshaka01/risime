# Crypto review: history sharing (v1.14 candidate, §17)

**Reviewer:** crypto role, 2026-10-07. **Proposal:** `contract/proposals/2026-10-06-history-share.md`
(§17.0, §17.2, §17.3, §17.5, §17.6, §17.12, open points C1–C4), decision 049 (accepted, consent
option A). Read against PROTOCOL v1.13 (§10, §12.1 incl. the 2026-10-07 "superseded device" rule,
§13.3, §14.3, §15.3–§15.6, §15.10) and `crypto/risime-mls{,-ffi}` (OpenMLS 0.9.0,
openmls_rust_crypto 0.6.0 / hpke-rs 0.7.0).

**Context change since the proposal:** decision 050 (plain logout keeps the MLS state and the device
id). History sharing is now only needed after "Log out and delete chats", a reinstall, a new phone,
a §12.8 rejoin or a reset. That makes the **requester almost always a brand-new leaf** and the best
provider almost always **the user's own old phone**, which by §12.1 is usually *superseded* (last
seen before the new phone registered) until it is opened again. Nothing in the crypto design depends
on that, but the trust statements below assume it. §0 of the proposal ("after a logout and login")
and §13.2's "logout … wipes the device's MLS state" need rewording at merge (root).

**Verdict:** the split is right: **MLS authenticates, HPKE gives confidentiality to one device,
the gap index bounds what can be imported.** HPKE to a fresh per-request key is the correct choice
over a 1:1 MLS group. Merge after **R1–R7**. The ones that matter most:
- **R2**: the `history_request` ciphertext goes stale (past-epoch window and out-of-order tolerance)
  when a later candidate is named hours after the request; the provider then can't authenticate
  `rpk` at all. The server must never "fix" this by sending `rpk` in cleartext.
- **R4**: the provider's device must decide *own vs member* from the **MLS credential**, never from
  the server's `consent` field, or a malicious server can turn a member request into a silent
  auto-share (decision 049 option A makes own-device sharing automatic after one approval).
- **R5**: the attribution of other people's shared messages can't be made cryptographic with
  OpenMLS 0.9; state the limits honestly (below) and keep the "Shared by" label.

---

## Confirmed
1. **Authentication chain.** `history_request` and `history_share` are PrivateMessages. The core's
   `process_detailed` returns the sender's attested `"<user_id>/<device_id>"` (leaf credential,
   checked against the pinned attestation keys) and the `authenticated_data`. So:
   - the provider learns, authenticated, *which device* asked and the `rpk` it must seal to;
   - the requester learns, authenticated, *which device* sent the blob reference, its SHA-256,
     `hpke_enc` and `sealed_key`.
   The server can't substitute `rpk`, the blob or the sealed key (all inside the signed, AEAD-covered
   content), and can't read `K` (HPKE to a key only the requester holds).
2. **HPKE is already in the build.** `openmls_traits::OpenMlsCrypto` exposes `hpke_seal`,
   `hpke_open` and `derive_hpke_keypair` on the provider the core already holds
   (`openmls_rust_crypto` → hpke-rs). No new dependency.
3. **Rejecting HPKE to the leaf encryption key or a key-package init key** is correct (key
   separation; leaf keys rotate on every update; key packages are consumed).
4. **Generation gaps** from messages routed to one device are within the 20 000 forward distance
   (§15.10), like `scope: "me"` deletes.
5. **Even a mis-routed request or share** leaks only `rpk`, a blob id and an HPKE ciphertext.

---

## Required changes (blocking)

**R1. Pin the HPKE profile and the exact `info`/`aad` bytes, with vectors.**
- **Suite:** RFC 9180 **base mode**, `DHKEM(X25519, HKDF-SHA256)` (0x0020), `HKDF-SHA256` (0x0001),
  `AES-128-GCM` (0x0001): the HPKE triple of MLS ciphersuite 0x0001, so the same audited code path
  that opens Welcomes. Sealing a 32-byte AES-256 key under AES-128-GCM is fine: the X25519 KEM caps
  the scheme at 128-bit security anyway. Auth mode is **not** needed (MLS signs the envelope that
  carries `hpke_enc`/`sealed_key`).
- **`rpk`:** 32 bytes, from `derive_hpke_keypair(config, ikm)` with 32 bytes of `getrandom`
  inside the core. Receivers reject a wrong length; the HPKE open must fail on an all-zero DH
  output (RFC 9180 §7.1.4); a vector for a low-order `rpk` must show the **seal** refusing it.
- **`info`** (binds the context; length-prefixed, big-endian):
  `"risime-history-v1"` (17 ASCII bytes) ‖ `request_id` (16 raw bytes) ‖
  `u16 len ‖ conversation_id` (UTF-8) ‖ `u16 len ‖ requester "<user_id>/<device_id>"` ‖
  `u16 len ‖ provider "<user_id>/<device_id>"`. Both identities are taken from **MLS credentials**
  (requester: its own; provider: the sender of the `history_share`), never from JSON.
- **`aad`** of the HPKE seal (binds `K` to one blob): `u16 part ‖ u16 parts ‖ sha256 (32 raw bytes
  of the ciphertext) ‖ u64 plain_size`. A **fresh `K` and a fresh HPKE seal per part** (§14.3
  already forbids reusing `K`).
- **Vectors:** `contract/v1/history_vectors.json` produced by the core generator and, byte for
  byte, by an independent reference script (as `media_vectors.json`). Negative cases: wrong
  `request_id`, wrong provider identity, wrong requester identity, wrong part/sha256 in `aad`,
  swapped `hpke_enc`, truncated `sealed_key` (≠ 48 bytes), low-order `rpk`, wrong `rsk`.

**R2. A stale `history_request` must be refreshed by the requester, never bypassed.**
The request ciphertext is encrypted at the requester's epoch `e` and generation `g`. The server
stores it and copies it into each named provider's inbox **at naming time**. A provider named
hours later (after declines, after the 24-h member window opens on a second batch, or an own device
that comes online late) may be unable to decrypt it:
- the group moved more than `MAX_PAST_EPOCHS` (3) epochs past `e` (past-epoch secrets deleted);
- or the requester sent more than `out_of_order_tolerance` (32, §16.12) later messages in `e`
  that the provider already processed, so generation `g` is behind the window.
Then the provider has **no authenticated `rpk`**. Required:
- the provider answers `history:respond` `unable` with `"reason": "stale"`;
- the server then asks the requester (a `history_status` state `refresh`) for a **new**
  `history_request` ciphertext at the current epoch, **same `request_id`, same `rpk`**, pushed with
  `history:refresh` (same checks as `history:request`), and names the candidate again only with
  the fresh ciphertext; the server should also refresh proactively when the stored ciphertext's
  epoch is more than 2 behind the group's at naming time;
- a provider **never** accepts an `rpk` from anywhere but a decrypted `history_request` whose
  MLS sender equals the event's `from`/`from_device`.
- A §12.8 reset (new generation) closes open requests of that conversation (`expired`): the old
  generation's request can't be authenticated any more.

**R3. The `'H'` AAD: exact form, type pairing and the core's admin check.** (C3)
- `authenticated_data` = **`0x01 0x48` ‖ `request_id` (16 bytes)**, exactly 18 bytes, for
  `history_request` and `history_share` only.
- Receivers drop: a `history_*` envelope whose AAD isn't exactly that, or whose AAD `request_id`
  differs from the envelope's; an `'H'` AAD on any other type; a `'D'` AAD on a `history_*` type.
  The rule in §15.3 becomes "non-empty AAD is allowed only in the two canonical forms, each with
  its own types".
- Core helpers `history_aad_encode/decode` next to `encode_delete_aad` (one canonical parser on
  both sides; the server's `RisiMe.MLS.Wire` gets the same check and refuses `'H'` on `msg:send`
  and `msg:delete`).
- `process_detailed` today turns a missing admin record into `Malformed` **whenever the AAD is
  non-empty** (it assumes non-empty = `delete`). For `'H'` messages `sender_is_admin` is irrelevant:
  the core must skip the admin check for the `'H'` form (return `None`), not fail.

**R4. Consent is decided on the provider device from MLS identities.**
- *Own vs member* = whether the `user_id` of the request's **MLS sender** equals this device's
  user. The event's `consent` field is a display hint; if it disagrees with the credential, the
  request is treated as `member` (always ask) and logged.
- The option-A "approve once per new phone" record is keyed by **(requester `device_id`, its leaf
  signature key)** from the credential, so a re-registered device id with a new key (§10.1: a
  changed key counts as remove + add) asks again.
- The approval is stored in the sealed store (`mls_kv`, same transaction rules as MLS state) and
  forgotten when that device leaves the user's device set (`mls_membership` removed / not in the
  group any more).

**R5. Attribution limits: say what is true, in the contract and the privacy note.** (C2)
What the requester can and can't verify with OpenMLS 0.9:
- **The original MLS sender proof can't be carried.** Three routes, none feasible:
  1. *Original PrivateMessage + epoch secret*: the provider no longer has it
     (`MAX_PAST_EPOCHS` = 3; past-epoch secrets are deleted), and giving it away would decrypt
     **every** message of that epoch, including ones deleted for everyone or for me (§15) and ones
     outside the requester's entitlement. It breaks forward secrecy and "deletes win".
  2. *Per-message keys* (the ratchet's content key/nonce and the sender-data key for that one
     ciphertext, so the requester could open its own stored copy and check the inner signature):
     OpenMLS deletes consumed message keys (§15.6, crypto C4) and has no API to export them;
     keeping them would be a deliberate forward-secrecy regression and a fork.
  3. *Keep the signed `FramedContent`*: `ProcessedMessage` in 0.9 exposes `sender()`,
     `credential()`, `aad()`, `epoch()` and the content, but **not the signature** (it is
     `pub(crate)` in `framing/mls_auth_content*.rs`), and the signed `FramedContentTBS` includes the
     serialized `GroupContext` of that epoch, which the requester can't verify. It needs an OpenMLS
     fork. Rejected for v1.14.
- **So, in v1.14:**
  - **Sender, `message_id` and time are fixed by the gap index**, i.e. by server metadata the
    requester recorded before asking, cross-checked against the provider's copy. The provider's
    copy of `from` was MLS-verified *on the provider's device* when it decrypted it (§10.3 sender
    match). The requester trusts the provider for that check.
  - **Content of other people's messages is only as honest as the provider.** A member provider
    can alter text, swap the type (text ↔ image ↔ reaction) of an entry, point an image entry at
    any blob the requester's user can read (including one it uploads now), and omit any entry.
  - **It can't** add an entry without a gap row, change an entry's sender, time or position,
    revive a message the requester holds as a (hidden) tombstone or cleared, or read anything.
  - **Server + member provider together** can fabricate: the server can plant fake undecryptable
    events (fake ids, any `from`) that become gap rows, and the provider fills them. The result is
    a message "from" anyone at any time inside the range. This is the real bound and must be in
    §17.12: *a shared message is as trustworthy as the sharing device and the server together.*
  - **Own-device shares** carry the same technical limits, but the source is the user's own phone.
- **The UI consequence is already right:** the "Shared by <provider>" label (from the provider's
  **MLS credential**, never from the bundle header or the server's `provider`) on every block and
  in the info sheet of every entry not sent by the provider.
- **Later option (not v1.14), if Harsha wants verifiable shares:** sender-signed payloads. The
  sender's core signs `("risime-msgsig-v1", group_id, client_msg_id, SHA-256(payload))` with its
  leaf signature key and puts the 64-byte signature in the envelope; a bundle entry carries it plus
  the sender's attestation JWS (verifiable offline against the pinned keys); the requester matches
  `client_msg_id` to its gap row. Feasible inside the current core (no fork), but it only covers
  messages sent after every sender updated, and it turns each message into a **transferable,
  non-repudiable statement** (MLS signatures are only meaningful inside the group). That is a
  product decision (deniability), not a crypto default.

**R6. Matching rules the core and the app must share.**
The requester imports an entry only if all of these equal the gap row: `message_id` (the unique
key), **`client_msg_id`** (the `message` event carries it; add it to the gap row), `from`, and
`server_ts` (compared as instants). Notes:
- **Drop `from_device` as a required match key.** The Android `messages` table doesn't store it,
  so an honest provider can't supply it. If both sides have it, it must match; absent on either
  side → not checked. It adds nothing cryptographically (it is server metadata on both sides).
- Gap rows created **after** the requester sent its request are not importable for that request
  (a late server-planted gap row can't be filled by a provider racing it).
- `(generation, epoch)` in the gap row are diagnostics only: the bundle has no epoch (the provider
  doesn't store it) and they aren't authenticated either.
- Entry type is not bound to the gap row (the server can't tell types, so neither can the gap
  row). Accepted; covered by R5.

**R7. Key handling: `rsk` and `K` never cross the FFI.** (C4)
- The core generates `(rsk, rpk)`, stores `rsk` in its own storage under
  `risime/history/<request_id>` **in the caller's transaction** (the same sealed `mls_kv` as MLS
  state, savepoint semantics as §10.4), and returns only `rpk`. The app persists its request row
  in the same transaction, before `history:request` is pushed (crash safety).
- Opening is one core call that HPKE-opens `K` with the stored `rsk` and AEAD-opens the blob
  (§14.3: all segments and the final flag verified before any byte is returned). `K` stays in Rust.
  This mirrors §14.3's rule that no production API takes a caller-supplied key.
- Sealing is one core call that generates `K` internally, encrypts the bundle part
  (`A256GCM-S64K`) and HPKE-seals `K`.
- `history_forget(request_id)` deletes `rsk` (after the last part is imported, on cancel, on
  `history_request_closed`/`unavailable`, and by a start-up sweep at 48 h); `secure_delete` and the
  WAL checkpoint (§15.6) apply. A request row without its `rsk` is closed locally.
- The plaintext bundle is passed **as bytes** (≤ 16 515 072 per part), never written to a
  plaintext temp file (§14.7 Receiving 2 spirit).

---

## Suggestions (non-blocking)
- **S1. Domain-separate history blobs from media blobs.** Use the §14.3 format with the label
  `risime-history-v1` (HKDF info `"risime-history-v1 A256GCM-S64K"`, segment AAD
  `risime-history-v1`) instead of `risime-media-v1`. `K` is fresh and secret, so there is no attack
  today, but it makes a media blob and a history blob non-interchangeable at no cost
  (one label parameter in `media.rs`).
- **S2. Distinguish request and share in the AAD**: `0x01 0x48 ‖ kind (0x51 'Q' | 0x53 'S') ‖
  request_id`. The server then knows a `history:deliver` carries a share, not a replayed request.
  Harmless either way (types are checked after decryption); cheap now, impossible later.
- **S3.** The requester should require the share's MLS sender to be a device of a user it would
  accept: its own user, or (for `sources: "any"`) a current member of the conversation. The
  server's `history_status.provider` is a display hint, never the label source.
- **S4.** If the info string grows (other platforms with small HPKE `info` limits), hash the
  variable part: `info = "risime-history-v1" ‖ SHA-256(TLS-encoded context)`. Not needed for
  hpke-rs; record it so iOS doesn't diverge.
- **S5.** Pad the `history_*` envelopes to a fixed size bucket when MLS application padding lands
  (§14.9's S1), so the request and share can't be told apart by length. Low value today: the
  server routes them by kind anyway.

---

## Answers to the open points
- **C1 (HPKE vs 1:1 MLS group; `info`; provider identity from the credential).** HPKE: yes. A 1:1
  group would add a key-package claim, a Welcome, state on two devices and cleanup for a one-shot,
  and would still need the same request/response authentication. The `info` fields are right with
  R1's exact encoding plus the HPKE `aad` per part. Binding the provider identity from the **MLS
  credential** is enough: base-mode HPKE doesn't authenticate the sealer, but the MLS envelope that
  carries `hpke_enc`/`sealed_key` does; putting the provider in `info` stops a member from copying
  an honest provider's sealed key into its own envelope (cut-and-paste) because the open then fails.
- **C2 (per-message sender signatures).** Not feasible with OpenMLS 0.9 without a fork (signature
  not exposed; the GroupContext in the TBS isn't verifiable by a device that wasn't there; message
  keys are deleted). Ship v1.14 with the honest limits of R5 and the "Shared by" label. Sender-signed
  payloads are a possible v1.15 item and a deniability decision for Harsha.
- **C3 (widening §15.3's empty-AAD rule).** Agreed, in the strict form of R3 (exact 18 bytes,
  type pairing both ways, core admin check skipped for `'H'`).
- **C4 (`rsk` storage and deletion).** R7: in the core's sealed storage, written in the request's
  transaction, never exported, deleted on every terminal state and by a 48-h sweep, with
  `secure_delete`. At-rest protection equals the MLS state's (the database key needs no user
  authentication, §10.4), which is the right level: anyone who can read `rsk` can read the chats.
- **H2 (crypto view).** Keep the 30-day, gap-index-only rule. "Unverified" older messages would be
  pure provider assertions with no gap row to anchor their sender, id or time; that's what the
  encrypted backup is for.

## Core / FFI work (`risime-mls`, `risime-mls-ffi`)
| Function | Notes |
|---|---|
| `history_aad_encode(request_id) -> bytes`, `history_aad_decode(bytes) -> request_id` | canonical `'H'` form (R3); free functions like `delete_aad_*` |
| `Client::history_keygen(request_id) -> rpk` | stores `rsk` transactionally under `risime/history/<id>` (R7) |
| `history_seal(rpk, ctx: HistoryContext, plaintext: bytes, out_path) -> SealedPart {hpke_enc, sealed_key, plain_size, size, sha256}` | generates `K`, `A256GCM-S64K` (label per S1), HPKE seal with R1 `info`/`aad`; no `Client` state needed |
| `Client::history_open(request_id, ctx, hpke_enc, sealed_key, in_path) -> bytes` | HPKE open with stored `rsk`, full AEAD verification before returning |
| `Client::history_forget(request_id)`, `Client::history_open_requests() -> [request_id]` | deletion and the 48-h sweep |
| `HistoryContext {request_id, conversation_id, requester, provider, part, parts, sha256, plain_size}` | one record for `info` + `aad` |
| `process_detailed` change | skip the admin check for `'H'` AAD (R3) |
| generator for `contract/v1/history_vectors.json` + reference script | R1 vectors, positive and negative |

Tests (crypto gate): seal/open round trip; every negative vector; `rsk` absent after
`history_forget` and after a rolled-back transaction; the `'H'` AAD round trip, a mismatch and a
`'D'`/`'H'` type swap dropped; `process_detailed` on an `'H'` message at an epoch without an admin
record returns `None`; `K` and `rsk` not reachable from any FFI-exported function.
