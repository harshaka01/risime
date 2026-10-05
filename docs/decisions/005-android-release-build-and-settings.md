# 005 — Android: release build wiring and Settings

## Context
Decision 003 asks for VERSION-driven versions, release signing, side-by-side debug/release installs
and a server URL editable in every build. A few client details were left open.

## Decision
1. **versionCode formula lives in `app/build.gradle.kts`** (`risiVersionCode`), not `buildSrc`. A
   configuration-time self-check asserts the decision 003 examples (`0.2.0-nightly.1` = 20001,
   `0.2.0` = 20099). Limits: minor ≤ 99, patch ≤ 99, nightly N ≤ 98; anything else, or an
   unparseable `VERSION`, fails the build. A `buildSrc` module with its own tests was rejected: it
   slows every build and its tests don't run in the normal gate anyway.
2. **Default server URL `http://10.0.2.2:4400` in every build** (release previously had none).
   The login screen shows "Server: … · Change" in every build; Settings has the same field.
   Both use one validator (`checkServerUrl`: http/https, host, no query/fragment/credentials).
3. **Switching servers is one atomic DataStore edit** (`clearLoginAndSetServerUrl`), after a
   best-effort `POST /auth/logout` to the old server and before wiping local chat data, so the
   realtime client never pairs the old token with the new URL. It runs in the app scope, because
   the logout tears down the Settings screen and its ViewModel.
4. **`PROTOCOL_VERSION` constant** (shown in Settings → About) is checked by a unit test against
   the `contract/v1/PROTOCOL.md` header. When root bumps the contract, the Android gate fails
   until the client is updated to the new version. That is intended: the client must not claim a
   version it doesn't implement.
5. **Settings ViewModel depends on a small `SettingsBackend` interface** (implemented by
   `AppSettingsBackend` over `AppContainer`), so it is unit-tested with a fake.

## Consequences
- The debug app is `lk.codegen.risime.debug` ("RisiMe Dev"); release is `lk.codegen.risime`.
- On the laptop (no `~/risime-keys`) `assembleRelease` produces an unsigned APK
  (`app-release-unsigned.apk`).
