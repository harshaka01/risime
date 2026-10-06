# 028 — Invite-only sign-up and private friend lists

**Status:** accepted 2026-10-06 (Harsha: "build invites and friends now, ahead of the other
slices"). Wire changes: contract v1.6 (`contract/proposals/2026-10-06-invites-friends-v1.6.md`).

## Context
- Keycloak realm `aoa` has **self-registration open**. The registration page asks for email,
  name, company, department and job title, with no password (email code). So a brand-new email can
  create a RisiCloud account by itself, and **RisiMe must gate access itself**.
- Until now the gate was the admin allowlist, and every allowlisted person saw every other in
  Contacts.

## Decision
- **Two ways in:**
  - an **invite** from an existing user (phone, email, name), redeemed when that email first signs
    in through Keycloak;
  - the **admin allowlist** (`mix risime.allow`).

  Anyone else gets `403 not_allowlisted`.
- An invite creates the account with the **inviter's stated phone**, which is marked
  **"vouched by <inviter>"** until SMS verification (decision 020) is live. The inviter and the
  invitee become friends automatically.
- **Friends are explicit and private:** request (by phone) → accept, decline or block. Only
  accepted friends can message, type to each other, or see each other's presence. Lists are
  visible only to their owner, and `/contacts` returns friends only.
- **No phone enumeration:** friend requests always get the same reply. Requests to unregistered
  numbers wait against that phone for 30 days, and declines and blocks are silent.
- **The server never sends invite messages.** The app's share sheet sends `share_text` (the
  download link plus "sign in with <email>").
- **Rate limits:** 10 invites per user per day (50 pending), and 30 friend requests per user per
  day.
- **Migration:** existing conversation pairs become friends (Harsha ↔ Shenika), so no chat breaks.

## Consequences
- Testers need an invite from someone already on RisiMe, or an admin allowlist entry.
- Old apps (≤ v1.5) keep working: they see only friends in Contacts and ignore the new signal.
- The load test and interop fixtures must create friendships first.
