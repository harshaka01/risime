# 049 — Group and DM history sharing between devices

**Status:** accepted (consent: option A, decided by root 2026-10-07) 2026-10-06 (root); merged
into the contract as **PROTOCOL v1.15 §17** on 2026-10-07 after the crypto, server and android
reviews (`contract/proposals/reviews/2026-10-06-history-share-*.md`). Contract: proposal
`contract/proposals/2026-10-06-history-share.md`; where they differ, PROTOCOL.md §17 wins.

## Context
After a logout and login, groups show "Earlier messages aren't available on this device" (§13.3).
A fresh sign-in is a new MLS leaf and can't decrypt older ciphertext (decision 032); the server
holds only ciphertext. Harsha asked to restore that history from a member's device. The proper
long-term answer, a client-encrypted backup (decision 043, "next step"), doesn't exist yet.

## Decision (proposed)
1. **Sources in order:** the user's own other devices, then other members' devices with their
   consent, and later the encrypted backup (which reuses the same bundle format and takes
   precedence when present).
2. **Authentication by MLS, confidentiality by HPKE.** The request and the reply are MLS
   application messages in the conversation's group (attested sender, membership at that epoch),
   routed by the server to one device only and bound to a `request_id` through MLS
   `authenticated_data`. The bundle is an `A256GCM-S64K` blob (§14.3) whose key is HPKE-sealed to a
   fresh per-request X25519 key of the requesting device. Rejected: group application messages
   (every current member could read the bundle); a 1:1 MLS group between the two devices (too
   much state for a one-shot); HPKE to MLS leaf or key-package keys (breaks key separation; those
   keys rotate or are consumed).
3. **The gap index bounds what a provider can inject.** The requester keeps content-free rows
   (`message_id`, sender, sender device, `server_ts`) for every pre-install event and imports only
   entries that match one. A provider can't invent messages, re-attribute them, resurrect deleted
   ones, or leak messages outside the requester's membership (also filtered by the provider using
   the server's `group_member_intervals`). It can still alter the text of others' messages it
   shares; the UI labels them "Shared by <name>". Per-message sender signatures are an open crypto
   point.
4. **Consent:** members' phones always ask their user. The user's own devices: one approval per
   new device on an existing device (recommended, against phone-number takeover), then automatic;
   fully automatic is the alternative Harsha decides.
5. **Deletes win** (§15): deleted or cleared messages never get gap rows and are never exported.
   Images reference the original `media` blob (the requester's user is already a reader). Limits:
   30-day window, 16 MiB × 20 parts, 3 requests per conversation per day, member prompts at most
   twice per 7 days.
6. **No learning-log entries** (no model call).

## Consequences
- New: capability `history_share`, blob purpose `history` (48 h TTL), pushes `history:request`,
  `history:respond`, `history:deliver`, `history:cancel`, event kinds `history_request`,
  `history_request_closed`, `history_status`, `history_share`, a `history_requests` Postgres table,
  a client gap table, and HPKE seal/open in the MLS core.
- Installs that passed their gap before this version need a fresh sign-in (or a re-scan) to get gap
  rows.
- Sharing covers only the last 30 days and needs another device online; the encrypted backup
  remains necessary.


## Consent (decided 2026-10-07 by root; Harsha: "ask me only for what you can't do yourself")
- **The user's own other phones:** approve once per new phone on the old phone ("Your new phone
  wants your chat history. Allow?"); after that, automatic for that phone.
- **Other members' phones:** always ask their owner.
- **Why:** fully automatic sharing would hand 30 days of every chat to anyone who takes over the
  phone number.

## At merge (v1.15, 2026-10-07)
The reviews changed the design in these ways (PROTOCOL.md §17 is normative): own devices are named
all at once, superseded ones included when they connect, for about 10 minutes before members
(`history:escalate` ends it early); stale request ciphertexts are refreshed by the requester
(`history:refresh`, same `request_id` and `rpk`); a reset closes requests; a 30-minute delivery
deadline; the `history` blob lives until `history:ack` or 48 h; day/week limits counted in
Postgres; HPKE base mode with the MLS 0x0001 suite and exact `info`/`aad` bytes, a fresh key per
part, vectors in `contract/v1/history_vectors.json`; the 18-byte `'H'` AAD; own versus member
decided from the MLS sender; matching on `message_id` + `client_msg_id` + `from` + `server_ts`
(`from_device` only when both sides have it); call lines only between one user's devices;
"automatic" sharing only while the old phone is unlocked; and the forgery limits stated plainly
(a shared message is as trustworthy as the sharing device and the server together).
