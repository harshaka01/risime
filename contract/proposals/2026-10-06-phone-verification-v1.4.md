# Proposal: PROTOCOL v1.4 — one-time phone verification by SMS

**Status:** proposed by root on 2026-10-06 (decision 020). It is queued after the Keycloak work,
needs review by the server and android roles before merge, and is additive.

## 1. REST
- **`User` gains `"phone_verified": true | false`.** It's an additive field, and old clients
  ignore it.
- **Gate:** a Keycloak-authenticated user with `phone_verified: false` gets **`403
  phone_unverified`** on every authenticated endpoint except `GET /me`, `POST /auth/logout` and
  the two below. The socket is refused at the upgrade, so clients call `GET /me`. Dev-login users
  (`DEV_LOCAL_AUTH`) are always verified.
- **`POST /api/v1/me/phone/verify/request`** with body `{}` sends a 6-digit code by SMS to the
  user's **allowlisted** phone (the client can't choose the number).
  - `200 {"status": "sent", "expires_in": 300, "to": "+9477•••••22"}`
  - `409 already_verified`
  - `429 rate_limited` (3 per user per 15 min, 5 per phone per 24 h, plus a server budget)
  - `503 sms_unavailable` (the provider failed; retry later)
- **`POST /api/v1/me/phone/verify/confirm`** `{"code": "123456"}`
  - `200 {"user": User}` with `phone_verified: true`
  - `401 invalid_code`
  - `410 expired`
  - `429 too_many_attempts` (5 per code)
  - `409 already_verified`

  The error codes match `/auth/verify`.

## 2. Realtime
No new messages. An unverified user's socket upgrade is refused, as with `403` above.

## Examples (to add at merge)
- `phone_verify_request_reply.json`: `{"status":"sent","expires_in":300,"to":"+9477•••••22"}`
- `phone_verify_confirm.json`: `{"code":"123456"}`
- `error_phone_unverified.json`:
  `{"error":{"code":"phone_unverified","message":"Confirm your phone number to continue"}}`
- `auth_verify_reply.json` / `me` examples: add `"phone_verified": true` to the user.
