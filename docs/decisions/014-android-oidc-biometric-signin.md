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
- **Token storage:**
  - The **refresh token** (offline) is encrypted with an AES-GCM key in the **Android Keystore**.
    The key requires user authentication (`BIOMETRIC_STRONG`, invalidated by a new biometric
    enrolment).
  - At app start, a **BiometricPrompt** with a `CryptoObject` unlocks it, then a refresh gives a
    fresh access token. Access tokens live in memory only.
  - If biometrics are unavailable, or the key was invalidated, a full browser sign-in happens.
  - Follow RisiWork's flow for the UX details; the orchestrator should ask Harsha for its
    reference if it's needed.
- **Logout:** the realm `end_session_endpoint`, wipe the stored token and key, and wipe local
  chat data (as today).
- **Dev login** (phone + email + dev OTP against `DEV_LOCAL_AUTH` servers) stays behind a
  **debug-only switch** (`BuildConfig.DEBUG`), and is absent from release builds.
- **The realtime socket** connects with the current access token. Before expiry the client pushes
  `auth:refresh` (contract v1.3), or reconnects with `since`.

## Consequences
- New dependencies: AppAuth and `androidx.biometric`.
- A real end-to-end sign-in needs the Keycloak client `risime` to exist (the RisiCloud lead), and
  the server reachable from the phone (decision 017).
