# 013 — Sign-in through RisiCloud Keycloak; identity stays phone-first

**Status:** accepted 2026-10-06 (Harsha, amendment A of the RisiCloud integration).
Wire changes: contract v1.3 (`contract/proposals/2026-10-06-keycloak-auth-v1.3.md`).

## Context
RisiCloud (spark, risicloud.ai) runs Keycloak, realm **`aoa`**: issuer
`https://risicloud.ai/realms/aoa`, verified from OIDC discovery. Sign-in uses an email code that
the Workspace relay sends. RisiMe's 0.1/0.2 login (`/auth/request` + `/auth/verify`, dev OTP log)
was a stand-in.

## Decision
- **Keycloak authenticates; RisiMe authorises and owns the identity.** A user is still a RisiMe
  user with a phone number from the allowlist. Contacts, conversations, `dm:<a>_<b>` ids,
  presence and every protocol id stay user-id/phone based. The Keycloak `sub` is only a login key.
- **The allowlist is keyed on both phone and email.** Both columns are unique, and email is
  compared case-insensitively (stored lowercase).
- **Mapping on first use (`GET /me`).**
  - Find the user by `users.keycloak_sub`. If there is none, take the token's `email`, which must
    have `email_verified = true`, and look it up in the allowlist.
  - Create the user from the allowlist entry (phone, name, company), or link an existing user
    with that phone, and store `keycloak_sub`.
  - An email that isn't on the allowlist gets **403 `not_allowlisted`**.
  - If the allowlisted phone already belongs to a user bound to a *different* `sub`, the answer
    is **409 `identity_conflict`**. That needs an admin re-bind (`mix risime.allow --rebind`). We
    never re-bind silently.
- **Tokens:**
  - The server verifies Keycloak **access tokens** (JWT) locally against the realm's JWKS, which
    is cached. It checks `iss`, `azp == "risime"` (or `"risime"` in `aud`), `exp`/`nbf` (60 s
    leeway), `typ == "Bearer"`, and an asymmetric `alg` only.
  - The server never calls Keycloak per request, and never holds a client secret: `risime` is a
    public client.
- **Dev login** (`/auth/request`, `/auth/verify`, opaque tokens, the `[DEV OTP]` log) stays only
  when **`DEV_LOCAL_AUTH=true`**:
  - on in `test` and for the local/test server (interop, load test, throwaway users);
  - **off** by default in prod releases;
  - the Android release build has no dev login UI (decision 014).

## Consequences
- The server needs outbound HTTPS to `risicloud.ai` only for discovery and JWKS (read-only,
  decision 015).
- Removing someone from the allowlist blocks them at the next token use, even though Keycloak
  still authenticates them.
- Phone ownership isn't verified by Keycloak. It comes from the allowlist, which an admin curates.
