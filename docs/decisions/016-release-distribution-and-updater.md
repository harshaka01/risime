# 016 — Distribution through risicloud.ai/app/risime/ and the in-app updater

**Status:** accepted 2026-10-06 (Harsha, amendment E). **Pending:** RisiWork's `version.json`
example, so the format matches exactly. Fields may be renamed to match it before the first
publish.

## Publish (`scripts/nightly-release`, new last step)
- Keep a local mirror, `~/risime-releases/publish/`, holding the **last five** signed APKs
  (`risime-<version>.apk`), `version.json` for the newest, and a static `index.html` ("Get
  RisiMe": version, notes, a download button, the sha256 and certificate fingerprint, and install
  hints). No external assets.
- `rsync -a --delete --delay-updates ~/risime-releases/publish/ spark1:` goes into the rrsync root
  `~/accounts/releases/risime/`, so spark also holds exactly the last five.
  - The APKs upload before `version.json`, so clients never see a `version.json` pointing at a
    missing file.
- It runs only after both gates pass, the APK signer is verified, and the tag is pushed. A
  failed publish fails the release step loudly but leaves the tag and the local release intact.
  Re-run with `scripts/nightly-release --publish-only v<ver>`.
- `version.json` (draft, to align with RisiWork):
  ```json
  {"versionCode": 20002, "versionName": "0.2.0-nightly.2", "date": "2026-10-07T01:30:00Z",
   "notes": "…", "url": "https://risicloud.ai/app/risime/risime-0.2.0-nightly.2.apk",
   "sha256": "<apk sha-256 hex>", "certSha256": "da7b9824…9e2e", "required": false}
  ```
  `required` is set by hand, with `scripts/nightly-release --required`, when a release breaks
  compatibility, for example a contract change that old clients can't follow.

## In-app updater (Android)
- On app start, and at most every 6 h in the foreground: GET `version.json` (no auth) from the
  update base URL `https://risicloud.ai/app/risime/`, configurable in debug.
- If `versionCode` is greater than the installed one: offer the update, or show a **blocking
  screen when `required` is true**. The only action is "Update"; chats are unreachable until the
  update is installed.
- Download the APK to app-private storage, then verify:
  - **sha256** equals `version.json`;
  - the **APK's signing certificate** (`PackageManager.getPackageArchiveInfo(…,
    GET_SIGNING_CERTIFICATES)`) equals the **certificate pinned in the app** (decision 003),
    and equals `certSha256`;
  - the package name equals ours;
  - its `versionCode` equals the advertised one.
- Any mismatch: delete the file, show an error, and don't install.
- Install with the `PackageInstaller` session API, which needs `REQUEST_INSTALL_PACKAGES`. The
  user grants "install unknown apps" once.
