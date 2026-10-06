# 014 — Android sign-in: AppAuth + PKCE, refresh token released by fingerprint

**Status:** accepted 2026-10-06 (Harsha, amendment C).

## Decision
- **AppAuth-Android** (`net.openid:appauth`): authorization code + **PKCE S256** against
  `https://risicloud.ai/realms/aoa`, using discovery, the public client `risime`, and scopes
  `openid email profile offline_access`.
  - The redirect is **`ai.risicloud.risime://callback`**, via the manifest placeholder
    `appAuthRedirectScheme`.
  - Debug builds (`lk.codegen.risime.debug`) use **`ai.risicloud.risime.debug://callback`**, so
    both builds can be installed without fighting over the scheme. Both URIs are registered on
    the Keycloak client.
- **The applicationId stays `lk.codegen.risime`**, which keeps the existing release signing and
  update path.
- **Token storage: envelope encryption** (from the Android review). Rotated refresh tokens must
  be stored without a second fingerprint.
  - Each token set (offline refresh token + ID token) is encrypted with a random AES-256-GCM data
    key.
  - The data key is wrapped with a Keystore **RSA-OAEP** key pair (SHA-256, MGF1-SHA1). Its
    private key requires **`BIOMETRIC_STRONG`** on every use, plus **`DEVICE_CREDENTIAL`** as a
    fallback on API 30+. It is invalidated by a new biometric enrolment.
  - Wrapping uses the public key, so it needs no authentication.
  - At app start a **BiometricPrompt** with a `CryptoObject` unwraps the token set. It stays in
    memory for the life of the process, so there is one prompt per process start and none during
    a foreground session.
  - Access tokens live in memory only. A cancelled prompt shows a "RisiMe is locked — Unlock"
    screen and doesn't connect.
  - If biometrics are unavailable or the key is invalidated: a browser sign-in. The same user
    keeps their local data; a different user wipes it.
  - RisiWork's flow is the UX reference.
- **Logout:**
  - **revoke** the refresh token at the realm's `revocation_endpoint` (RFC 7009; `end_session`
    alone leaves offline tokens valid), prompting for a fingerprint if the token isn't in memory;
  - then `end_session_endpoint` with `id_token_hint` and the post-logout redirect
    `ai.risicloud.risime[.debug]://logout`;
  - then delete the key pair and wipe local chat data.
  - If the user cancels the prompt, the key is deleted anyway, and the server-side offline
    session idles out.
- **The sign-in mode comes from the server** (`GET /api/v1/auth/config`, contract v1.3). This is
  the "flag until the Keycloak client exists", controlled on the server by `OIDC_ENABLED`.
  - **Release builds** show RisiCloud sign-in when `modes` contains `oidc`. Only in the interim
    (no `oidc`) do they fall back to the dev OTP login, so they never ship without a way in.
  - **Debug builds** also show a "Developer sign-in (OTP)" link whenever `dev` is listed, plus a
    debug-only override in Settings.
- **`GET /me` error UI:**
  - `401` → refresh once, then a browser sign-in (data kept);
  - `403 not_allowlisted` → a full screen with "Use another account" / "Sign out";
  - `409 identity_conflict` → a full screen with "Sign out".
- **The realtime socket** connects with `Authorization: Bearer`. About 60 s before expiry, computed
  from `expires_in`, the client pushes `auth:refresh`, or reconnects with `since`.
  `auth:expired` and refused upgrades never wipe data.
- **Release default server URL:** `https://risicloud.ai/risime` (option 2b). Debug keeps
  `http://10.0.2.2:4400`.

## Consequences
- New dependencies: AppAuth and `androidx.biometric`.
- A real end-to-end sign-in needs the Keycloak client `risime` to exist (the RisiCloud lead), and
  the server reachable from the phone (decision 017).
