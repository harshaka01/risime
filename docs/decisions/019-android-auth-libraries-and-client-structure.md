# 019 — Android: auth/updater libraries and client structure (contract v1.3)

## Context
Decisions 014 and 016 fix the behaviour: AppAuth + PKCE, a fingerprint-released token set,
GET /me error screens, and a release-only updater. This note records the libraries chosen
(latest stable on 2026-10-06) and how the client is organised so it stays testable without the
Keycloak `risime` client.

## Decision
1. **Libraries**
   - `net.openid:appauth:0.11.1`: the latest release; it ships `RedirectUriReceiverActivity`,
     which keys on the `appAuthRedirectScheme` placeholder.
   - `androidx.browser:browser:1.10.0`: pinned over AppAuth's transitive 1.3.0 for current
     Custom Tabs.
   - `androidx.biometric:biometric:1.1.0`: the latest stable; 1.2/1.4 are still alpha.
   - `androidx.fragment:fragment-ktx:1.9.1`: `BiometricPrompt` needs a `FragmentActivity`, so
     `MainActivity` is one now.
2. **Structure**
   - Pure, JVM-tested rules:
     - `data/auth/AuthLogic.kt`: sign-in mode from `auth/config`, the `GET /me` outcome, refresh
       timing;
     - `data/auth/Envelope.kt`: envelope encryption and `TokenVault` behind a `WrappingKey`
       interface;
     - `data/auth/AuthManager.kt`: in-memory tokens, single-flight refresh and rotation, behind
       an `OidcGateway` interface;
     - `update/UpdateLogic.kt`: `version.json` parsing, the URL allowlist, the decision and APK
       verification.
   - Android adapters:
     - `KeystoreWrappingKey`: RSA-3072 OAEP;
     - `AppAuthGateway`: discovery cache, refresh, RFC 7009 revoke over OkHttp, because AppAuth
       has no revocation API;
     - `ui/auth/AuthUi`: browser sign-in, `end_session`, BiometricPrompt;
     - `update/Updater`: download, PackageManager, PackageInstaller.
3. **Refresh timing** uses `elapsedRealtime` plus `expires_in` at receipt (AppAuth's
   `accessTokenExpirationTime` minus now at receipt). Tokens shorter than 2 min refresh at half
   their remaining life; there's a 5 s floor.
4. **Never wipe on auth trouble.** A 401 after a refresh, `invalid_grant`, an invalidated key or
   a refused socket all go back to sign-in and keep local chats. A `last_user_id` in DataStore
   decides the wipe when a *different* user signs in. Only an explicit logout or a server switch
   wipes. This now also applies to dev tokens.
5. **No secure lock screen → memory-only tokens.** The auth-bound key can't be created, so the
   app works until the process dies and then asks for a browser sign-in. Keycloak's SSO cookie in
   the Custom Tab usually makes that a single tap.
6. **Logout order:** revoke, then `end_session` (browser, fire-and-forget), then delete the key
   pair, then wipe. The key is deleted in the same step as the revoke, before the browser opens;
   `end_session` needs only the ID token, which is already in memory.

## Consequences
- The app can't be fully verified until the Keycloak client exists. Everything except the
  browser hop, BiometricPrompt, the Keystore and PackageInstaller is covered by JVM tests.
- `version.json` field names may change with RisiWork's example; only `VersionJson` changes.
