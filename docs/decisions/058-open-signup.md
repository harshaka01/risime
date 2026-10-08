# 058 — Open sign-up behind a server switch
**Status:** accepted 2026-10-08 (Harsha approved; root), contract v1.20 §21.

## Context
Sign-up has been invite-only (decision 029): a verified RisiCloud account that isn't on the
allowlist and has no pending invite gets `not_allowlisted`. Harsha wants people to be able to join
on their own, reversibly.

## Decision
- **Switch:** `OPEN_SIGNUP=true|false` in the server environment (default false). Pilot: a line in
  `infra/pilot/pilot.env` and a restart; no release. `GET /auth/config` reports `signup`.
- **Flow:** nothing maps → `403 signup_required` (switch on) → the app's "Create your RisiMe
  account" → `POST /auth/signup {"phone", "display_name"}` → a user bound to `sub` with the
  token's verified email, `signup_source = 'open'`.
- **Squatting:** `409 phone_taken` for a phone held by any user, on the allowlist, or with a
  pending invite. A self-asserted phone (`phone_confirmed: false`) is never matched by phone: no
  request delivery, no incoming list, no crossing auto-accept. The new user can only ask others.
- **`phone_confirmed`, not `phone_verified`.** `phone_verified` stays the §7.1 gate flag. Every
  shipped app treats `false` as "Confirm your phone, don't connect", and SMS isn't live, so
  reusing it would strand open users on a screen that can only answer `sms_unavailable`. When
  `PHONE_VERIFICATION=required` goes on, open users verify by SMS like everyone else.
- **Limits:** 5 attempts/h per IP, 3/24 h per `sub` (ETS, every attempt counts, refused hits don't
  extend), 200 successes/24 h globally (Postgres; `SIGNUP_GLOBAL_PER_DAY`). Overrides:
  `SIGNUP_PER_IP_HOUR`, `SIGNUP_PER_SUB_DAY`.
- **Membership:** an open user is a member until an admin disables them; turning the switch off
  only stops new sign-ups.
- **Logs:** auth-log kinds `signup_refused` and `signup_rate_limited`; a separate fail2ban jail
  `risime-signup` (6 in 1 h → 24 h ban) on top of the existing `risime-auth` jail. Refused
  sign-ins log the email domain and `HMAC-SHA256(secret_key_base, email)[0..12]` (hex), at most
  once per email per 10 min. Never the plain email (rule 5).
- **Migration:** additive: `users.signup_source` (text, nullable; `open` for open sign-ups).

## Consequences
- Old apps (≤ nightly.29) see `signup_required` as a generic refusal; they must update to sign up.
  Allowlist and invite sign-in are unchanged for every app.
- **Residual risk:** a squatter who signs up first with someone's number blocks that person's own
  sign-up (`phone_taken`, or `identity_conflict` on an invite) until an admin disables the squatter.
  Follow-ups: an admin task to free a phone, and "an SMS-verified claim beats an unconfirmed holder"
  once SMS is live.
- `phone_taken` reveals that a number is in use; bounded by the limits and fail2ban (the number's
  membership is already partly visible through contacts and invites).
