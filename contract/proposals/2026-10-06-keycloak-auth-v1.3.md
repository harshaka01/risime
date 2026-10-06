# Proposal: PROTOCOL v1.3 — Keycloak (RisiCloud) access tokens

**Status:** merged into `contract/v1` as **v1.3** on 2026-10-06, after review by the server and
android roles. Decisions 013, 014, 016 and 017 hold the rationale.
It is additive: v1.2 clients keep working against servers that run with `DEV_LOCAL_AUTH=true`.

## 0. Conventions (additions)
- **Bearer tokens** are either:
  - (a) a **Keycloak access token** (a JWT from realm `https://risicloud.ai/realms/aoa`, client
    `risime`); or
  - (b) **only when the server runs with `DEV_LOCAL_AUTH=true`**, a legacy opaque RisiMe token
    from `/auth/verify`.

  The two are told apart by shape: a JWT is three dot-separated base64url segments, and an opaque
  token is 43 characters with no dot.
- **JWT acceptance.** All of the following must hold:
  - **Keys:** the signature verifies against the JWKS from the issuer's `jwks_uri`.
    - Keys are cached and refreshed every 10 min in the background. An unknown `kid` triggers an
      immediate single-flight refetch, at most once per 60 s.
    - A fetch failure keeps the cached keys. With no keys at all, the token is rejected (fail
      closed).
    - JWKs whose `use` isn't `sig` are ignored.
  - **`alg`** is one of RS256/384/512, PS256/384/512, ES256/384/512 or EdDSA, and matches the
    JWK's `kty`. `none` and `HS*` are never accepted.
  - **`iss`** equals the realm issuer exactly.
  - **`typ` claim == `"Bearer"`.** Keycloak ID and refresh tokens are rejected.
  - **`azp == "risime"`**, or `aud` contains `"risime"`.
  - **Expiry:** `exp` is present. `nbf` and `iat` are checked only if present. There is 60 s of
    leeway on all of them.
  - **`email` is present and `email_verified == true`.**
- **Error `message`s** for `not_allowlisted` and `identity_conflict` may be shown to the user
  verbatim.

## 1. REST (changes)
- **New, unauthenticated `GET /api/v1/auth/config`** → `{"modes": [...], "issuer": "...",
  "client_id": "risime"}`.
  - `modes` is a non-empty subset of `"oidc"` and `"dev"`. `"dev"` is present only when
    `DEV_LOCAL_AUTH=true`, and `"oidc"` only when OIDC is enabled on the server (the
    `OIDC_ENABLED` flag).
  - Clients choose their sign-in UI from it.
  - Release clients show the dev login **only while `modes` lacks `"oidc"`** (the interim before
    the Keycloak client exists). Debug clients may always offer it when it is listed.
  - `issuer` and `client_id` are present when `"oidc"` is listed.
- Every authenticated endpoint accepts `Authorization: Bearer <token>` (as in §0). New errors:
  - `401 invalid_token`: a missing, bad, expired or foreign token. Also returned for opaque
    tokens when `DEV_LOCAL_AUTH` is off.
  - `403 not_allowlisted`: a valid token, but the email isn't on the allowlist, or the user's
    phone was removed from it.
  - `409 identity_conflict`: the allowlisted phone is bound to another Keycloak account. An admin
    must re-bind it.
- **Mapping** runs on **every** authenticated request and socket connect (cached per token until
  `exp`). Clients call **`GET /me` first** after sign-in, because only REST can return the reason
  for a refusal.
  1. If a user has `keycloak_sub == sub`, that user is used. Their phone must still be on the
     allowlist, else `403 not_allowlisted`.
  2. Otherwise the allowlist entry for `lower(email)` is used. If there is none: `403 not_allowlisted`.
  3. The users row with that entry's phone:
     - none → create it, bound to `sub`;
     - one with no `keycloak_sub` → bind it atomically;
     - one bound to another `sub` → `409 identity_conflict`.
  4. Once bound, `sub` wins. Later Keycloak email changes don't move the user. Re-binding is
     admin-only.

  `GET /me` responds unchanged: `{"user": User}`. User and Contact are unchanged, and identity
  stays phone/user-id based.
- **`POST /auth/request` and `POST /auth/verify`** exist only when `DEV_LOCAL_AUTH=true`.
  Otherwise they return `404`.
- **`POST /auth/logout`:** `204`. With a JWT it is a no-op: the client revokes its refresh token
  and ends the Keycloak session itself. With a dev token it revokes the token, as before.

## 2. Realtime (changes)
- **Authentication:** the socket upgrade accepts **`Authorization: Bearer <token>`**, which is
  preferred: clients that can set headers (Android/OkHttp) must use it, so tokens stay out of
  proxy access logs. The `token=` query parameter remains for others.
- **A refused upgrade (401/403) no longer means "logged out"** by itself. Clients call `GET /me`
  and act on its answer: refresh on `401`, the `403`/`409` screens, otherwise reconnect.
- **Expiry:** the server records each JWT socket's `exp`. At `exp + 60 s` without a successful
  `auth:refresh`, it pushes **`auth:expired`** `{}` on `inbox:<own id>`, then disconnects the
  socket. This is enforced even if no channel is joined. Clients treat it as "refresh, then
  reconnect with `since`", never as a logout.
- **`auth:refresh`** (client → server, on `inbox:<own id>`): `{"token": "<new access token>"}`.
  - The token must pass §0 and map to the **same user**.
  - reply ok: `{"expires_at": "<the token's exp, ISO-8601 ms>"}`.
  - reply error: `{"reason": "invalid_token" | "identity_mismatch" | "not_allowlisted"}`. After an
    error, the old deadline stays.
  - Clients refresh about 60 s before expiry, computed from `expires_in` at receipt (or from the
    last `expires_at` reply) and **not from the device clock against `exp`**. Reconnecting with
    `since` is always a valid alternative.
  - Keycloak's default access-token lifetime is 5 min, so expect a refresh about every 4 min.

## Examples (added to `contract/v1/examples/`)
- `auth_config.json`: `{"modes":["oidc","dev"],"issuer":"https://risicloud.ai/realms/aoa","client_id":"risime"}`
- `error_not_allowlisted.json`:
  `{"error":{"code":"not_allowlisted","message":"This email is not on the RisiMe allowlist"}}`
- `error_invalid_token.json`:
  `{"error":{"code":"invalid_token","message":"Missing, invalid or expired token"}}`
- `error_identity_conflict.json`:
  `{"error":{"code":"identity_conflict","message":"This phone number is linked to another RisiCloud account"}}`
- `auth_refresh.json`: `{"token":"eyJhbGciOiJSUzI1NiJ9.e30.c2ln"}`
- `auth_refresh_reply.json`: `{"expires_at":"2026-10-07T08:20:00.000Z"}`
- `auth_refresh_error.json`: `{"reason":"identity_mismatch"}`

## Out of scope
- Keycloak admin API calls.
- Token introspection.
- Allowlist sync from Keycloak groups.
