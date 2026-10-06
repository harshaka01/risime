# Proposal: PROTOCOL v1.3 — Keycloak (RisiCloud) access tokens

**Status:** proposed by root on 2026-10-06 (Harsha's amendment B). It must be reviewed by the
server and android role sessions before root merges it. It is additive: v1.2 clients keep working
against servers that run with `DEV_LOCAL_AUTH=true`. Decisions 013 and 014 hold the rationale.

## 0. Conventions (addition)
- **Bearer tokens** are either:
  - (a) a **Keycloak access token** (a JWT from realm `https://risicloud.ai/realms/aoa`, client
    `risime`); or
  - (b) **only when the server runs with `DEV_LOCAL_AUTH=true`**, a legacy opaque RisiMe token
    from `/auth/verify`.

  Clients treat both as opaque strings.
- A server accepts a JWT only if all of these hold:
  - the signature verifies against the realm JWKS (cached; refetched on an unknown `kid`, at
    most once a minute);
  - `alg` is asymmetric (RS*/PS*/ES*/EdDSA);
  - `iss` equals the realm issuer exactly;
  - `azp == "risime"`, or `aud` contains `"risime"`;
  - `exp` and `nbf` hold (60 s leeway);
  - `typ == "Bearer"`;
  - `email_verified == true`.

## 1. REST (changes)
- Every endpoint except the dev `/auth/*` accepts `Authorization: Bearer <token>` as above.
- New errors on any authenticated endpoint:
  - `401 invalid_token`: a bad, expired or foreign token. The client refreshes, or signs in again.
  - `403 not_allowlisted`: a valid token whose email isn't on the allowlist.
  - `409 identity_conflict`: the allowlisted phone is already bound to another Keycloak account.
    An admin must re-bind it.
- **`GET /me`** with a JWT **upserts the user on first use**:
  - an existing binding by `sub`, or else the allowlist entry for the token's verified `email`;
  - the user is created or linked by the entry's phone, and the `sub` is bound;
  - the response is unchanged: `{"user": User}`.

  Clients call `GET /me` right after sign-in, before contacts or the socket.
- **`POST /auth/request`, `POST /auth/verify`** exist only when `DEV_LOCAL_AUTH=true`.
  Otherwise they return `404`.
- **`POST /auth/logout`** with a JWT: `204`. It's a no-op, because the client ends the Keycloak
  session itself via `end_session_endpoint`. With a dev token it revokes the token, as before.
- User and Contact are unchanged. Identity stays phone/user-id based (decision 013).

## 2. Realtime (changes)
- `token=` on the socket URL accepts either kind. An invalid token, or a user who isn't
  allowlisted or bound, is refused at connect, as before. The client then calls `GET /me` to learn
  why.
- The server closes a JWT-authenticated socket **when its token expires** (plus a 60 s grace),
  unless the client renews it first.
- **New push `auth:refresh`** (client → server, on `inbox:<own id>`):
  `{"token": "<new access token>"}`.
  - reply ok: `{"expires_at": "<ISO-8601 ms>"}`;
  - reply error: `{"reason": "invalid_token"}`, or `{"reason": "identity_mismatch"}` when the
    token is for another user.
  - Clients send it about 60 s before `exp`. Clients that don't must reconnect with a fresh
    token, using `since` as usual.

## Examples (to add to `contract/v1/examples/` at merge)
- `error_not_allowlisted.json`:
  `{"error":{"code":"not_allowlisted","message":"This email is not on the RisiMe allowlist"}}`
- `error_invalid_token.json`:
  `{"error":{"code":"invalid_token","message":"Missing, invalid or expired token"}}`
- `auth_refresh.json`: `{"token":"eyJhbGciOiJSUzI1NiJ9.e30.c2ln"}` (any string)
- `auth_refresh_reply.json`: `{"expires_at":"2026-10-07T08:20:00.000Z"}`

## Out of scope
- Keycloak admin API calls of any kind.
- Server-side token introspection.
- Allowlist management through Keycloak groups. A later proposal could map a realm role or
  group to the allowlist.

## Open points for review
- Server: JWKS caching, `typ`/`azp` checks with the realm's actual tokens (verify against a real
  token once the `risime` client exists), and closing sockets at expiry vs. `auth:refresh`.
- Android: does AppAuth's token refresh plus BiometricPrompt fit `auth:refresh` timing? What
  should happen to a running socket when refresh needs a fingerprint while the app is in the
  foreground?
