# Proposal: PROTOCOL v1.4 — one-time phone verification by SMS

**Status:** merged into `contract/v1` as **v1.4** on 2026-10-06, after review by the server and
android roles. Decision 020 holds the rationale. It is additive: v1.3 clients keep working while
the server has `PHONE_VERIFICATION=off`, which is the default.

## 1. REST
- **`GET /auth/config` gains `"phone_verification": "required" | "off"`.** The gate below is
  active only when it is `required`. The server enables it only once SMS can actually be sent: an
  approved sender ID, or a deliberate demo override.
- **`User` gains `"phone_verified": true | false`.** **An absent field means `true`** (pre-v1.4
  servers have no gate).
  - It is true when the user's current phone equals the phone they last verified. Any phone change
    or identity re-bind resets it.
  - **Dev-login sessions are treated as verified.** That isn't persisted: the same user signing in
    through Keycloak must still verify.
  - Verification status is never cached with the token.
- **`Contact.registered`** is `true` only for users who are verified, or who need no
  verification. Unconfirmed identities can't be messaged.
- **Gate:** checks run in the order `401` → `403 not_allowlisted` / `409 identity_conflict` →
  **`403 phone_unverified`**. Exempt: `GET /me`, `POST /auth/logout`, `GET /auth/config` and the
  two routes below. The `phone_unverified` message may be shown verbatim.
- **`POST /api/v1/me/phone/verify/request`** with body `{}`. The code always goes to the user's
  **allowlisted** phone; clients can't choose the number. Responses:
  - `200 {"status": "sent", "expires_in": 300, "to": "+9477•••••22"}`. The masked number uses
    U+2022, UTF-8.
  - `409 already_verified`. Clients treat it as success and re-fetch `/me`.
  - `429 rate_limited`, which includes the per-user, per-phone and server budgets.
  - `503 sms_unavailable`: the provider failed, the sender is disabled, or the number isn't
    reachable through the gateway.
- **`POST /api/v1/me/phone/verify/confirm`** `{"code": "123456"}`. Responses:
  - `200 {"user": User}`, with `phone_verified: true`.
  - `401 invalid_code`. The error object may include `"attempts_left": n`.
  - `410 expired`.
  - `429 too_many_attempts` (5 per code).
  - `409 already_verified`.
- **Every `429` carries a `Retry-After` header** (seconds). Clients fall back to 60 s.

## 2. Realtime
- An unverified user's socket upgrade is refused, as for `403` above. Clients call `GET /me` and
  show the phone screen; **they don't reconnect in a loop.**
- **`auth:refresh` gains the error `phone_unverified`.**
- When verification is reset (phone change or re-bind), the server **closes that user's open
  sockets**.

## Examples (added to `contract/v1/examples/`)
- `auth_config_v14.json`:
  `{"modes":["oidc"],"issuer":"https://risicloud.ai/realms/aoa","client_id":"risime","phone_verification":"required"}`
- `me_reply_unverified.json`:
  `{"user":{"id":"7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3","phone":"+94770000001","display_name":"Test User A","company":"CodeGen","phone_verified":false}}`
- `phone_verify_request_reply.json`: `{"status":"sent","expires_in":300,"to":"+9477•••••01"}`
- `phone_verify_confirm.json`: `{"code":"123456"}`
- `error_phone_unverified.json`:
  `{"error":{"code":"phone_unverified","message":"Confirm your phone number to continue"}}`
- `error_invalid_code_attempts.json`:
  `{"error":{"code":"invalid_code","message":"The code is incorrect","attempts_left":3}}`
- `error_already_verified.json`:
  `{"error":{"code":"already_verified","message":"Your phone number is already confirmed"}}`
- `error_sms_unavailable.json`:
  `{"error":{"code":"sms_unavailable","message":"Couldn't send the SMS right now, try again shortly"}}`

The existing `auth_verify_reply.json` and `contacts_reply.json` stay unchanged (fields absent, so
verified). The server role may add `phone_verified` to them when it implements this.
