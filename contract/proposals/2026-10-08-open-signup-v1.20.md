# Proposal: open sign-up behind a server switch (contract v1.20)
Date: 2026-10-08. Author: root session (approved by Harsha). Decision 058.

## Problem
Sign-up is invite-only (§9.0): a Keycloak account whose verified email is neither on the
allowlist nor named by a pending invite gets `403 not_allowlisted`. Harsha wants people to be
able to join on their own, behind a switch that can be turned off again without a release.

## Proposal (normative text: PROTOCOL.md §21)
1. **Switch.** Server runtime config `OPEN_SIGNUP=true|false` (default `false`), read from the
   environment at boot (pilot.env + restart, no release). `GET /auth/config` gains
   `"signup": "open" | "invite"`.
2. **Mapping.** Unchanged for bound `sub`, allowlist and invites (§6.1, §9.1). Only the final
   "nothing matched" answer changes: `403 signup_required` while the switch is on,
   `403 not_allowlisted` while it is off.
3. **`POST /api/v1/auth/signup`** `{"phone", "display_name"}` with a Keycloak bearer token
   (verified email, §6.0) → `200 {"user": User}`. Idempotent: if the token already maps to a user
   it returns that user. Errors: `403 signup_closed`, `401 invalid_token`, `429 rate_limited`
   (Retry-After), `400 bad_request`, `409 phone_taken`; `403 not_allowlisted` / `409
   identity_conflict` pass through from mapping (a disabled bound user, a conflicting binding).
4. **Phone stays self-asserted.** New field `phone_confirmed` (User, Friend, incoming Request,
   the `friend` signal's `user`; absent = `true`) is `false` while the phone was typed in at open
   sign-up and hasn't been SMS-verified. Such a phone is **never matched by phone**: friend
   requests and invites addressed to it aren't delivered to that user, crossing requests don't
   auto-accept, and the user can't see, accept or decline requests addressed to the phone. The
   user can still send friend requests; the recipient sees `phone_confirmed: false` and the app
   shows "Phone not verified". Messaging still needs an accepted request (§9.3, unchanged).
5. **`phone_verified` keeps its §7.1 meaning** (passes the phone gate: `true` while
   `phone_verification` is `off`). See the review, item S5.
6. **Limits.** Per IP 5 attempts/hour, per Keycloak `sub` 3 attempts/24 h (both in memory, every
   attempt that reaches the limiter counts), global 200 successful open sign-ups/24 h (Postgres,
   `SIGNUP_GLOBAL_PER_DAY`). Invite and friend-request limits are unchanged.
7. **Logging.** Auth-log kinds `signup_refused` and `signup_rate_limited` (fail2ban jail
   `risime-signup`). Refused sign-ins (`not_allowlisted`, `signup_required`) log the email's
   domain and a 12-hex-digit HMAC of the full email (server secret as key) to the server log,
   never the address.
8. **Membership.** An open-sign-up user is a member until an admin disables them. Turning the
   switch off stops new sign-ups only.

## Self-review (security, abuse, privacy)
- **S1 Squatting a known number.** A stranger could sign up with a colleague's phone.
  - Refused with `409 phone_taken` when the phone belongs to any user, is on the allowlist, or has
    a pending invite (an invite for *this* email would already have redeemed, so any pending
    invite for the phone is "someone else's").
  - When the squatter gets there first, the phone is still never matched (item 4): nobody who
    types that number reaches the squatter, and requests to it wait for the real owner. Crossing
    auto-accept is off for unconfirmed phones, so "A asked +94…X, the squatter asks A" can't make
    them friends without A tapping Accept on a request marked "Phone not verified".
  - **Residual:** the real owner who arrives later gets `phone_taken` (or `identity_conflict` on
    an invite). An admin must disable the squatter and free the phone. Follow-up: an admin task
    (`mix risime.user.release_phone`) and, once SMS is live, "a verified claim beats an
    unconfirmed holder". Recorded in decision 058.
- **S2 Enumeration through `phone_taken`.** It reveals that a number is a member, allowlisted or
  invited. Kept: the real owner needs to know why they can't sign up, and §9.2 contacts and
  invites already make membership partly discoverable to friends. Bounded by 3 attempts per `sub`
  per day, 5 per IP per hour, a verified-email Keycloak account per `sub`, and fail2ban on
  repeated `signup_refused`. The error message doesn't say which of the three cases applied.
- **S3 Abuse (mass accounts).** Keycloak requires a verified email; plus the per-IP, per-`sub`
  and global caps. The global cap counts only successes, so a flood of refusals can't lock out
  real people; the per-IP limit and fail2ban handle floods.
- **S4 Spam from new accounts.** New users can only send friend requests (30/day, unchanged);
  messages need acceptance; the request shows "Phone not verified". Blocks work as before.
- **S5 Why not `phone_verified: false`.** §7.1's `phone_verified` is the gate flag, and every
  shipped app (≤ nightly.29 included) treats `false` as "show Confirm your phone and don't
  connect". With SMS not live, an open-sign-up user would be stuck on a screen that can only
  answer `503 sms_unavailable`. A separate `phone_confirmed` keeps old and new apps working and
  says exactly what the UI needs. When `PHONE_VERIFICATION=required` is turned on, open users get
  `phone_verified: false` like anyone unverified and verify by SMS, which also sets
  `phone_confirmed: true`.
- **S6 Privacy of logs.** No plain email in any log. The domain is logged (useful for "can't
  sign in" triage: a typo'd or personal domain). The hash is an HMAC with the server secret, so
  it can't be reversed by brute-forcing a dictionary without the secret; 12 hex digits is enough
  to match one complaint. Rate-limited to one line per email per 10 minutes. The auth log stays
  free of user data (time, IP, kind, path).
- **S7 Old apps.** Apps ≤ nightly.29 get `403 signup_required` from `GET /me`; they don't know
  the code and treat any other 403 as "server didn't accept this sign-in" (a generic refusal).
  Acceptable: they must update to sign up. Allowlist and invite sign-ins are unchanged for them.
- **S8 Switch-off semantics.** Existing open users keep working; turning the switch off never
  locks anyone out (no surprise loss of access, and hard rule 9 is untouched: nothing here deletes
  local data).
