# 049 — Group and DM history sharing between devices

**Status:** proposed 2026-10-06 (root). Awaiting the crypto, server and android reviews and
Harsha's answer on own-device consent. Contract: proposal
`contract/proposals/2026-10-06-history-share.md` (v1.14 candidate, §17; the number is fixed at
merge).

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
