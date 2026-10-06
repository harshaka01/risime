# 018 — Server: Keycloak token verification, identity mapping and socket expiry (contract v1.3)

## Context
Contract v1.3 §6 (decisions 013 and 014): the server accepts Keycloak access tokens from realm
`https://risicloud.ai/realms/aoa`, client `risime`, and maps them to phone-first RisiMe users.
The legacy dev login stays behind `DEV_LOCAL_AUTH`. The `risime` client doesn't exist yet, so
everything is tested with locally generated keys.

## Decision
- **Library: JOSE (`~> 1.11`) directly, with Req for HTTP.** JOSE is the standard Erlang JWS/JWK
  implementation and covers RS, PS, ES and EdDSA. `JOSE.JWT.verify_strict/3` takes our explicit
  alg list. Joken and JokenJwks were rejected: little value over about 40 lines of explicit
  checks, plus extra HTTP dependencies and their own cache semantics.
- **`RisiMe.Auth.JWKS`** is a GenServer that owns a protected ETS table of keys by `kid`.
  - The key source is discovery (`jwks_uri`, falling back to
    `<issuer>/protocol/openid-connect/certs`), or `OIDC_JWKS_URL`, or a static set in tests.
  - It refreshes in the background every 10 min. An unknown `kid` triggers a refetch that is
    single-flight (it goes through the process) and at most once per 60 s, counted from the last
    fetch attempt.
  - A failed fetch keeps the cached keys. With no keys it fails closed. Only keys with
    `use: "sig"` are kept.
- **`RisiMe.Auth.JWT`** applies §6.0 in order:
  - shape;
  - `alg` in {RS*, PS*, ES256/384/512, EdDSA} and matching the JWK's `kty`/`crv` (this blocks
    `none` and HS-with-public-key);
  - `kid` present, then the signature;
  - `iss` exact, `typ == "Bearer"`, `azp == client` or `client ∈ aud`;
  - `exp` required; `exp`/`nbf`/`iat` with 60 s leeway;
  - `sub`, `email` and `email_verified == true`.
- **`RisiMe.Auth.authenticate/1`** is used by REST (`RequireToken`), the socket and
  `auth:refresh`.
  - A JWT is accepted only with `OIDC_ENABLED=true`, and an opaque token only with
    `DEV_LOCAL_AUTH=true`.
  - JWT mappings are cached in ETS by SHA-256 of the token until `exp` + leeway, so the
    allowlist re-check happens on a cache miss: at the latest when the token is replaced, about
    every 5 min.
- **Mapping** (`Accounts.map_identity/1`), per §6.1:
  - a bound `sub` comes first, with an allowlist re-check;
  - otherwise the entry is found by `lower(email)` (unique index);
  - the user is created with the `sub`, or an unbound user is bound by an atomic
    `UPDATE … WHERE keycloak_sub IS NULL`. Then the row is re-read, so concurrent first requests
    converge without a false 409.
  - `mix risime.allow --rebind <phone>` clears a binding.
- **Socket.**
  - `RisiMeWeb.Endpoint.call/2` copies `Authorization: Bearer` into Phoenix's transport auth
    token (`auth_token: true`), so `connect_info[:auth_token]` carries the header token. The
    channels client's subprotocol token and `token=` also work.
  - Each JWT socket gets its own id, `jwt_socket:<random>`. Dev-token sockets keep
    `user_token:<id>` (logout).
  - `SocketTracker` holds the deadline `exp + 60 s` (`:auth_expiry_grace_ms`). At the deadline
    the inbox channel pushes `auth:expired` and then broadcasts the disconnect; both come from the
    channel process, so they arrive in order. A fallback disconnect 1 s later covers sockets
    without a joined channel.
  - `auth:refresh` must be a JWT for the same user. It moves the deadline. Errors are
    `invalid_token`, `identity_mismatch` (another user, or a 409 identity) and `not_allowlisted`.
- **Flags.**
  - `OIDC_ENABLED` defaults to false everywhere except test.
  - `DEV_LOCAL_AUTH` defaults to true in dev and test (app config) and false in prod; the env var
    overrides it. A prod boot with it on logs a warning.
  - `GET /api/v1/auth/config` lists the enabled modes.
- **Prod endpoint** (Caddy on spark → `tailscale serve` on spark2, decision 017 option 2b):
  - no `force_ssl` redirect, which would drop `/risime` and depend on forwarded headers;
  - `url` is `https://risicloud.ai/risime` (`PHX_HOST`, `PHX_PATH`);
  - `check_origin` allows only `https://<PHX_HOST>`;
  - no plug trusts `x-forwarded-*`.

## Consequences
- Removing someone from the allowlist takes effect on their next uncached token, within about
  one token lifetime. Their live socket closes at its expiry deadline unless the refresh fails.
- Presence, contacts and the load test are unchanged. The load test and interop use dev tokens,
  so their servers run with `DEV_LOCAL_AUTH=true`.
- **Before the first real login,** `typ`, `azp`, `email_verified` and the `kid`/alg have to be
  checked against a real token from the `risime` client. The client needs the default `email`
  scope, so `email` and `email_verified` are in the access token.
