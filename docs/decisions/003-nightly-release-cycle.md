# 003 — Nightly release cycle: versions, signing, scheduling

## Context
Harsha asked for an autonomous nightly cycle (2026-10-06): build the backlog items that don't need
him, run both gates, bump the version, ship a release-signed APK to `~/risime-releases/`, and leave
a morning summary.

## Decision
1. **One version for the whole repo** in the top-level `VERSION` file. `server/mix.exs` and
   `android/app/build.gradle.kts` read it.
   - Nightlies are `X.Y.Z-nightly.N`. After a final `X.Y.Z`, the next nightly is
     `X.(Y+1).0-nightly.1`.
   - Every published build is tagged `v<version>`.
2. **Android versionCode** = `major*1_000_000 + minor*10_000 + patch*100 + (N for nightly.N,
   99 for a final)`.
   - Codes always increase: `0.2.0-nightly.1` = 20001, `0.2.0` = 20099.
   - The v0.1.0 debug build had code 1.
3. **Release signing** with one stable dev key: `~/risime-keys/risime-dev-release.jks` and
   `keystore.properties` on spark2, outside git, mode 600.
   - It is created once (2026-10-05) and never regenerated. Cert SHA-256:
     `DA:7B:98:24:15:E5:D1:1D:D9:0A:EE:89:37:16:C7:A0:4E:D0:84:1E:21:89:0F:FC:C8:E0:75:F4:16:C9:9E:2E`.
   - Gradle reads `$HOME/risime-keys/keystore.properties` when it exists. Otherwise the release
     build is unsigned (on the laptop, for example). No machine paths are committed.
   - **Back it up**: losing it means every phone must uninstall and reinstall the app.
4. **Debug and release builds can be installed side by side.** Debug builds use the
   `applicationIdSuffix ".debug"`. The v0.1.0 debug build (id `lk.codegen.risime`, debug-signed)
   must be uninstalled once before the first release-signed install.
5. **Release builds are dev builds for now** (until the prod environment exists).
   - Cleartext is allowed only to `10.0.2.2` and `127.0.0.1`, as in debug, so the tunnel and USB
     setups keep working. The tailnet is reached over HTTPS through `tailscale serve`.
   - The server URL can be edited in Settings in every build.
   - R8/minify stays off until the prod release.
6. **Scheduling.** A recurring `CronCreate` job in the orchestrator session (cc-root window 0)
   fires nightly at 20:13 UTC (01:43 in Sri Lanka) and runs the runbook in `docs/NIGHTLY.md`.
   - Jobs live only in that session and expire after 7 days, so every nightly run re-creates
     the job to reset the expiry.
   - If the session dies, the cycle stops. The orchestrator says so in the summary; restart it
     by asking the orchestrator.
   - Rejected alternative: system crontab plus headless `claude -p`. It would need a blanket
     permission bypass, and there would be no conversation in which to raise questions.
7. **Releasing:** `scripts/nightly-release` bumps VERSION, runs both gates and `assembleRelease`,
   checks the signer, commits, tags and pushes. It then copies the APK, release notes, SHA256SUMS
   and SIGNER.txt to `~/risime-releases/v<version>/`, writes `~/risime-releases/LATEST`, and
   restarts the dev server from `main` (after migrations).
   - On the laptop, `scripts/fetch-latest-apk [--install]` copies the newest release.

## Consequences
- A failed gate aborts the release: VERSION is restored and nothing is tagged.
- Main may carry several nightly tags per minor version. The final `X.Y.0` is tagged when the
  minor's backlog, including items that need Harsha (FCM, SMTP, prod), is done.
