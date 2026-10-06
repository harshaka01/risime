# 020 — One-time phone verification by SMS (Notify.lk) at first sign-in

**Status:** accepted 2026-10-06 (Harsha). It is the next queue item after the Keycloak work. Wire
changes are in `contract/proposals/2026-10-06-phone-verification-v1.4.md`.

## Context
Keycloak proves the email (decision 013), but phone ownership comes only from the allowlist.
Harsha wants each user, once, to confirm the allowlisted phone with an SMS code. The provider is
**Notify.lk** (`https://app.notify.lk/api/v1/send`; docs at github.com/notifylk/documentation).
The credentials are in the repo `.env` on spark2: `NOTIFYLK_USER_ID`, `NOTIFYLK_API_KEY` and
`NOTIFYLK_SENDER_ID`. All three are set, and `.env` is gitignored.

## Decision
- **Flow:** sign in with Keycloak (email code), then `GET /me` returns the user with
  `phone_verified: false`. Every other endpoint, and the socket, refuses with
  `403 phone_unverified` until the user calls `POST /me/phone/verify/request` (an SMS to the
  **allowlisted** phone) and then `…/confirm` with the 6-digit code.
  - Done **once per user**: `users.phone_verified_at` is set.
  - It is reset when the allowlist phone changes or the identity is re-bound.
  - Users of the dev login (`DEV_LOCAL_AUTH`, tests, interop, load test) count as verified.
- **A pluggable sender:** a new behaviour, `RisiMe.Accounts.OtpSender`
  (`deliver(channel, destination, message) :: :ok | {:error, term}`), with three implementations:
  - `Email`: the current Swoosh mailer, refactored out of `OtpNotifier`;
  - `NotifyLk`: the SMS sender;
  - `DevLog`: logs `[DEV OTP] <masked destination>: <code>`, only when `OTP_DEV_LOG=true`.

  The SMS channel is chosen by **`SMS_MODE`**: `notifylk` or `log`. The default is `log` in dev
  and test, and `notifylk` in prod.
- **Notify.lk usage:**
  - **POST**, with the parameters in the form body, never in the URL, so credentials stay out of
    any URL, proxy log or Req error.
  - `to` is E.164 without `+` (`94771234567`), and `type=unicode` is never needed.
  - The message names the brand, as Notify.lk asks: `Your RisiMe verification code is 123456. It
    expires in 5 minutes. Do not share it.`
  - Success requires a JSON body with `"status": "success"`. Anything else is an error, logged
    with the provider's error text but never the request.
  - `GET /api/v1/status` (balance and active) feeds `/health` details for ops, without the key.
- **Sender ID from `.env`:** `NOTIFYLK_SENDER_ID`, today `NotifyDEMO`, later `RisiMe` when
  approved. Switching needs no code change.
  - **Guard:** Notify.lk's docs warn that sending **OTP content with `NotifyDEMO` risks account
    suspension**. While the sender ID is `NotifyDEMO`, `SMS_MODE=notifylk` **refuses to send OTPs**
    and falls back to `DevLog`, with a boot warning ("SMS OTP disabled until an approved sender ID
    is set").
  - The override is `NOTIFYLK_ALLOW_DEMO_OTP=true`, for one deliberate test only; Harsha's call.
- **Secrets:**
  - The API key is read once at boot, held in a config map, and redacted wherever the config is
    printed.
  - It is never logged, never `inspect`ed, never sent over the spark link (decision 015), and never
    put in a test.
  - Tests stub the HTTP call with `Req.Test` and fake credentials.
- **Security:**
  - Codes are 6 digits from a CSPRNG and stored as `HMAC-SHA256(secret_key_base, user_id <> phone
    <> code)`.
  - They expire after **5 min**, are single-use, and allow **5 attempts** per challenge. A new
    request supersedes the old code.
  - **Rate limits** (the existing `RateLimiter`):
    - 3 requests per user per 15 min;
    - **5 SMS per phone per 24 h** (cost and abuse cap);
    - 30 SMS per server per hour (a global budget; over it → `429` and an ops warning).
  - Responses show the phone masked (`+9477•••••22`).
- **Android:** after sign-in, if `phone_verified` is false, show a "Confirm your phone" screen
  (the masked number, "Send code", a 6-digit field, a resend timer, and the error states), and
  don't connect the socket until it's verified.

## Consequences
- SMS costs money. The per-phone and global caps bound it, and the status endpoint makes the
  balance visible.
- Until the `RisiMe` sender ID is approved, no real verification SMS is sent unless Harsha
  explicitly overrides the guard. The full flow still works end to end with `DevLog`.
