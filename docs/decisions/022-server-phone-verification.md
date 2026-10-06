# 022 — Server: one-time SMS phone verification (contract v1.4)

## Context
Contract v1.4 §7 and decision 020 (with the review amendments): Keycloak users confirm their
allowlisted phone once by SMS through Notify.lk. Until the `RisiMe` sender ID is approved, the
sender is `NotifyDEMO`, and Notify.lk forbids OTP content from it.

## Decision
- **Senders.** The `RisiMe.Accounts.OtpSender` behaviour is `deliver(to, %{subject, body, code})`.
  - `Email` (Swoosh) carries the dev email login, unchanged.
  - The SMS sender comes from `SMS_MODE`: `notifylk` → `NotifyLk`, `log` → `DevLog`
    (only with `OTP_DEV_LOG=true`), or `test`.
  - `DevLog` also writes the `[DEV OTP]` line beside any sender when `OTP_DEV_LOG=true`.
- **NotifyDEMO guard.** The sender ID is compared case-insensitively. While it is the demo ID
  and `NOTIFYLK_ALLOW_DEMO_OTP` isn't `true`:
  - the SMS sender is `DevLog` if `OTP_DEV_LOG=true`;
  - otherwise there is none, and the request answers `503 sms_unavailable`. It never fakes
    "sent".
- **Notify.lk calls.** The call is a POST form to `/api/v1/send`, with no Req retry, a 10 s
  timeout and no logging steps.
  - Success means HTTP 200 and `"status": "success"`.
  - Errors log only the HTTP status and up to 200 characters of the provider's text.
  - Every exception is rescued, and only its type is logged.
  - **`NOTIFYLK_API_KEY` is read from the OS environment inside the private form builder only.**
    It is never put in app config, logged or inspected. The user ID and sender ID are read the
    same way.
  - Tests replace all three variables with fakes in `test_helper.exs` and stub HTTP with
    `Req.Test`.
- **Only `+94` numbers** are sent; any other number answers `503`.
- **Data.**
  - `users.phone_verified_for` is the phone that was verified;
    `phone_verified = phone_verified_for == phone`.
  - `phone_challenges` has one row per *attempted* send: the HMAC of the code (keyed by
    `secret_key_base`, over user id + phone + code), a 5 min expiry, 5 attempts, single use.
    The latest row is the live one.
  - Oban prunes rows older than 48 h.
- **Limits.**
  - 3 requests per user per 15 min, in the in-memory `RateLimiter`.
  - **5 per phone per 24 h** and **30 per server per hour**, counted in `phone_challenges`, so
    they survive restarts.
  - Check order: already verified → `+94` and a sender available → per user → per phone →
    server → insert → send.
  - Every 429 has `Retry-After`: the remaining window, or the age of the oldest counted row, or
    60 s for `too_many_attempts`. A server-budget hit logs a warning at most once an hour.
- **Gate (`PHONE_VERIFICATION=required`, default `off`).**
  - `RequireToken` checks 401 → 403 `not_allowlisted` / 409 → 403 `phone_unverified`.
  - Exempt routes go through an ungated pipeline: `GET /me`, `POST /auth/logout`,
    `/me/phone/verify/*`. `GET /auth/config` has no auth.
  - Dev sessions are verified per request, never stored. With the gate off, `phone_verified`
    reads `true`.
  - The socket refuses unverified users at connect. `auth:refresh` answers `phone_unverified`.
  - `Contact.registered`, and the `msg:send`/`typing` recipient check, require a verified user
    while the gate is on.
- **Closing sockets on reset.** A Postgres trigger on `users` fires on a phone change, on
  `phone_verified_for` being cleared, or on a `keycloak_sub` re-bind, and runs
  `pg_notify('risime_verification_reset', id)`. `RisiMe.Accounts.ResetListener` (Postgrex
  `LISTEN`) then disconnects that user's sockets through `SocketTracker`. This also works for
  `mix risime.allow --rebind` run from another VM, and for manual SQL.
- **`/health`.** `checks.sms` comes from `SmsStatus`, which polls Notify.lk every 10 min (only
  with `SMS_MODE=notifylk`; POST form first, then the documented GET). Values are
  `ok | inactive | low_balance | error | demo_sender_blocked | log`.
  - The balance is only logged, with a warning below `SMS_BALANCE_WARN`.
  - SMS never makes `/health` a 503.
  - At boot: a demo-sender warning, an override warning, an error for missing credentials, and
    an error for "`required` but no sender".

## Consequences
- While the gate is on, dev-login-only users show as unregistered to Keycloak users (dev
  verification isn't stored). On the interop and load-test servers the gate stays off.
- The NOTIFY is delivered on commit, so it can't be exercised inside the test sandbox. Tests
  drive the listener directly, and the trigger was checked live on a temporary server.
- Count-then-insert can overshoot a budget by a request or two under concurrency. That's
  acceptable for cost caps.
