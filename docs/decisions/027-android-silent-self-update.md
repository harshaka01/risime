# 027 — Android: updater banner and silent self-update on Android 12+

## Context
Decision 016's updater must show the release notes and install after one tap. Harsha asked
whether updates can install **without** the system confirmation dialog.

## Findings (sources below)
1. **API.** `PackageInstaller.SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)` exists
   from API 31 (Android 12). For an installer holding `REQUEST_INSTALL_PACKAGES`, the system skips
   the user action only if **all** of these hold:
   - **The APK's targetSdk is recent enough for the device:**

     | Android | Minimum targetSdk |
     |---|---|
     | 12 (API 31/32) | 29 |
     | 13 (33) | 30 |
     | 14 (34) | 31 |
     | 15 (35) | 33 |
     | 16 (36) | 34 |
     | API 37 | 35 |

     The docs warn that these minimums keep rising. Our targetSdk is 37, so we qualify everywhere
     today.
   - **The installer is** one of: the update owner (when update-ownership enforcement is on), the
     installer of record (when it's off), or **the app updating itself**. Self-update is an
     explicit case, so a RisiMe that was **first installed from a browser or by adb** can still
     update itself silently, as long as no other installer enforced update ownership.
   - **The installer declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`** (a normal permission).
2. **`setRequestUpdateOwnership` (API 34)** only takes effect on the **initial** install (on an
   update it's a no-op) and needs `ENFORCE_UPDATE_OWNERSHIP`. Our first install always comes from
   the browser or adb, so it doesn't help us. It matters only as a risk: if a store installed
   RisiMe with ownership enforcement, our self-update would need confirmation.
3. **Whatever we request, the system may still return `STATUS_PENDING_USER_ACTION`.** The
   confirmation intent must be started from a foreground activity, because background activity
   starts are blocked on Android 10+. So the installer's result receiver passes the intent to the
   Updater, and `MainActivity` launches it while STARTED.
4. **Not in the docs (from AOSP, `SilentUpdatePolicy`; unverified on device):** silent updates of
   the same package by the same installer are throttled (on the order of 30 s). The installer of
   a silent update gets no UI, and the updated app's process is restarted by the system, so the
   app simply closes.
5. **Before Android 12, the confirmation dialog can't be avoided** for an app holding only
   `REQUEST_INSTALL_PACKAGES`. The one-time "install unknown apps" grant is needed on every
   version.

## Decision
- **Opportunistic silent update.** On API 31+ the session requests `USER_ACTION_NOT_REQUIRED`
  when the downloaded APK's targetSdk meets the table above (`requestSilentUpdate`); otherwise it
  requests `USER_ACTION_REQUIRED`. `STATUS_PENDING_USER_ACTION` always falls back to the system
  dialog. The release-only manifest adds `UPDATE_PACKAGES_WITHOUT_USER_ACTION`. No update
  ownership is requested.
- **Banner:**
  - "RisiMe X is available", with expandable, scrollable "What's new" (the `version.json`
    `notes`) and a single "Update" tap: download, verify (decision 016), then install.
  - Working / Failed ("Retry") / needs-permission states.
  - After the user grants "install unknown apps", returning to the app continues the update
    automatically.
  - `required` still uses the blocking screen. Root's policy marks a release `required` only when
    the server drops an older protocol.
- **Checks** run at launch, then every 6 h while in the foreground.

## Sources
- https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)
- https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequestUpdateOwnership(boolean)
- https://developer.android.com/reference/android/Manifest.permission#UPDATE_PACKAGES_WITHOUT_USER_ACTION
- https://developer.android.com/guide/components/activities/background-starts
- AOSP `frameworks/base/services/core/java/com/android/server/pm/SilentUpdatePolicy.java`
  (the throttle).
