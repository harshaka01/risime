# 044 — nightly.11 released with the upgrade gate overridden by Harsha

- **Status:** accepted (2026-10-06)
- **Decided by:** Harsha (product owner), explicitly: "Release nightly.11 now. I accept the risk and
  am overriding the upgrade gate; record that in a decision."

## Context
- After the nightly.10 data loss, `scripts/nightly-release` requires a PASS from `scripts/upgrade-test`
  (the previous published APK, signed in with chats, upgraded in place on an emulator or device,
  with every message kept and no sign-in asked).
- spark2 can't run an Android emulator (there is no linux-aarch64 build), so the test runs on the
  laptop emulator.
- For nightly.11 (candidate commit `32547e3`, APK sha256 `59561bab1264…`), the laptop runs didn't
  reach the upgrade step:
  1. A SIGPIPE in the script's `badging()` (fixed in `735bb1a`).
  2. The script never focused the Server URL field, so nightly.10 stayed on the OIDC-only pilot and
     the automated dev sign-in never started. The result was `FAIL: old app sign-in (A)` (fixed in
     `e56465c`).
- Neither failure was in the app. The candidate also passed both gates, the full live interop test,
  and the Robolectric upgrade tests from every released database version (1–5) with data.

## Decision
- Release nightly.11 with the upgrade gate **overridden**. This is the explicit
  `UPGRADE_GATE_OVERRIDE=docs/decisions/044-…` input to `scripts/nightly-release`, which prints the
  override and records it in the release notes.
- The binary released is the exact staged candidate. Only release tooling and docs changed since
  `32547e3`.
- Harsha accepts the risk of an untested in-place upgrade on a device.

## Consequences
- The override is per release, never permanent. It needs a committed decision naming the version.
- Follow-up (root): make the emulator upgrade test run without Harsha for future releases.
- If a tester reports lost data after updating to nightly.11, treat it as P0 and stop releases.
