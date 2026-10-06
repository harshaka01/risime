# 040 — Robolectric for Compose UI tests on the JVM

## Context
The P0 "Update required" gate bug (Update button off-screen, dark-on-dark text, raw Markdown)
shipped because no test rendered the screen. spark2 (ARM64) has no emulator, and the gate is
`testDebugUnitTest`, so instrumented tests can't be the safety net.

## Decision
- Add **Robolectric 4.17** plus `androidx.compose.ui:ui-test-junit4` (test) and
  `ui-test-manifest` (debugImplementation, so `ComponentActivity` is in the debug manifest) to
  the Android app. Compose UI tests run inside `testDebugUnitTest` on spark2 and the laptop.
- Tests pin `@Config(sdk = [34], application = Application::class)`: SDK 35+ sandboxes need
  Java 21 and the build uses Java 17; the plain `Application` keeps `RisiMeApp` start-up (push,
  container) out of UI tests. Screen size and density come from qualifiers (e.g.
  `w320dp-h480dp`); font scale from a `LocalDensity` override.
- Screens under test are split into a stateless `…Content` composable with lambdas (first:
  `RequiredUpdateContent`), so tests don't need an `AppContainer`.
- Robolectric downloads its `android-all-instrumented` jar from Maven Central on first run
  (cached in `~/.m2`).

## Consequences
- Blocking screens (update gate, blocked, locked) can get layout/reachability tests: "the primary
  action is displayed, enabled and within the window on a small screen at large font".
- Release notes are converted to plain text by a small in-house converter
  (`update/MarkdownPlain.kt`, unit-tested); no Markdown library was added.
