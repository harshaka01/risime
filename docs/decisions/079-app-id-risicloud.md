# 079 — New app ai.risicloud.risime, new signing key, no "codegen" (2026-10-11)

Approved by Harsha (P0 after nightly.49).

## What changes
- **App id:** `lk.codegen.risime` → `ai.risicloud.risime` (debug `ai.risicloud.risime.debug`); Kotlin packages,
  namespace, AppAuth redirect scheme, scripts, contract examples and tests move with it.
- **Signing:** a fresh key, subject `CN=Risi, O=Risi, C=LK`, RSA 4096, 30 years, at
  `~/secrets/signing/risi-upload.jks` (+ `keystore.properties`, chmod 600, outside git). The old key
  (`CN=RisiMe Dev Release, O=CodeGen, C=LK`, `~/risime-keys/`) is kept and archived
  (`~/secrets/archive/`): it signs only `lk.codegen.risime` builds. A release gate fails any APK whose
  certificate subject contains "codegen" (except the explicitly marked legacy build).
- **Push:** a second FCM credential (Firebase project `risicloud`, path in `.env` as
  `FCM_CREDENTIALS_RISICLOUD`). The server picks the project per device token by the token's app package:
  `lk.codegen.risime` → old project, `ai.risicloud.risime(.debug)` → new. Both work side by side.
- **Keycloak:** client `risime` gets the new redirect schemes; the old ones stay.
- **Updates:** the new app has its own feed (`risicloud.ai/app/risime/ai.risicloud.risime/version.json`);
  `risicloud.ai/app/risime/version.json` stays the old app's feed and gets one last `lk.codegen.risime`
  update that shows "RisiMe has moved — tap to install the new RisiMe". The download page offers the new app.
- **Ownership:** top-level `LICENSE` (proprietary, © 2026 Risi), an in-app "Open-source licences" screen,
  RisiMe/Risi-only branding; release gate: no "codegen" signer, no hard-coded IP addresses.

## Chats: clean start (Harsha's decision)
No transfer bridge. The new app is a new install on a new device: empty local history; account, friends and
groups come back from the server. Rule 9 holds: nothing deletes the old app or its data; it keeps working
until the user removes it, and the new app never touches it. Users who want old chats export them in the old
app first (§22 backup file).

## Rollback
Stop publishing the new feed and keep the old app's feed; the old app, its key, its Firebase project and the
server's old-package push path are never removed in this change, so old installs are unaffected. Server
changes are additive (per-package FCM choice falls back to the old project).

## Amendment (2026-10-12, v0.3.0-nightly.2)
- **Own sign-in scheme:** the old app claims `ai.risicloud.risime(.debug)://`, so the new app signs in through
  `ai.risicloud.risime.app://callback|logout` (debug `ai.risicloud.risime.app.debug://…`). Keycloak client `risime`
  needs these four URIs; `scripts/check-oidc-redirect` (release gate) checks the two callbacks against the issuer.
- **Push on:** `~/secrets/firebase/google-services.json` lists the new ids; `scripts/check-apk-push` (release gate)
  fails a release APK without its own package's `google_app_id`. The server logs one audit line per FCM send
  (`fcm: send project=… app=… http=…`, no tokens); `scripts/push-proof` summarises it from the pilot log.
- **Old app line:** `scripts/publish-release --legacy <tag>` rewrites only the root feed; the new app's subfolder stays.
