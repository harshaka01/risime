# Android session — Release 0.1
You run on **spark2** (ARM64) as `harsha` in `/home/harsha/development/risime` (shared checkout,
branch `main`), in tmux window 2 of `cc-root`. You own `android/` and `docs/status/android.md`,
and you stage only those paths.

## Start
1. Read `CLAUDE.md` (especially "Android on ARM64"), the Android part of `docs/RELEASE-0.1.md`,
   and `contract/v1/PROTOCOL.md`.
2. Wait until `~/setup.log` shows "Toolchain ready on spark2". Then confirm that `java -version`
   reports 17, that `$ANDROID_HOME/build-tools/*/aapt2 version` runs, and that
   `~/.gradle/gradle.properties` has `android.aapt2FromMavenOverride`.
3. **Never** add the aapt2 override (or any machine-specific path) to `android/gradle.properties`.
   The laptop must be able to build the same repo.

## Build order
After each green step: `git add android/ docs/status/android.md`, commit, `git pull --rebase`, push.
1. **A1:** create the Gradle project in `android/` (Kotlin DSL, version catalog, Gradle wrapper)
   and the debug network security config. Confirm `./gradlew assembleDebug` passes on spark2.
   If AGP or aapt2 fails with "exec format error", fix the toolchain (see CLAUDE.md) rather than
   the project.
2. **A2 (data):** kotlinx.serialization models matching the contract exactly. Add tests that parse
   every `contract/v1/examples/*.json` file, copied into the test resources by a Gradle task, so
   the tests always use the real contract.
3. **REST client** (OkHttp) and token storage in DataStore.
4. **A3:** the `RealtimeClient` interface and implementation: Phoenix join, push/reply, `event`
   handling, heartbeat, reconnect backoff, and the `since`/`sync` loop.
5. **Room:** the outbox state machine, dedupe, cursor, and delivered/read acks, with unit tests.
6. **A4/A5:** screens and theme, including the persistent dev encryption banner.
7. **A6:** the local behaviour event log (local only).
8. For a device test: there is no emulator on spark2. Push, then ask Harsha to run `git pull` and
   `scripts/install-apk` on the laptop and report what he sees. Once the server is READY, the
   emulator uses `http://10.0.2.2:4400` through his `spark2-tunnel` (laptop port 4400 → spark2
   port 4000). That is the debug default server URL.
9. Write `docs/status/android.md`: READY, how to build and install, and known limits.

## Rules
- Never edit `contract/v1`. If you need a change, write a proposal under `contract/proposals/`
  and tell Harsha.
- Never stage `server/` or root files.
- Test gate before every commit: `./gradlew assembleDebug testDebugUnitTest`
- No analytics, no network calls other than to the configured RisiMe server, and the behaviour
  log never leaves the device.
