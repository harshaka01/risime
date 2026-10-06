# 037 — Android: the real MLS engine, and packaging the core in release builds now

## Context
Phase B plugs the committed `risime-mls-ffi` API (decision 036) into the phase A pipeline
(decision 035). The E2EE rollout comes with a `required` app update (§10.4), so that update must
already carry the native core.

## Decision
1. **Package the MLS core in release builds now, for all four ABIs** (arm64-v8a, x86_64,
   armeabi-v7a, x86), together with JNA.
   - Native libs are **compressed** (`useLegacyPackaging = true`, extracted at install). Every
     update is a sideloaded download, and the core would add about 20 MB stored versus about 7 MB
     compressed.
   - **Clean release APK sizes:**

     | Build | Size (bytes) |
     |---|---|
     | 0.2.0-nightly.6 | 12,733,648 |
     | With the core, uncompressed | 32,979,928 |
     | With the core, compressed | **19,916,208** |

   - **Debug APK:** 25.4 MB.
   - 32-bit phones are covered, so a 32-bit member never blocks a conversation as `no_mls`.
2. **Release behaviour stays identical until rollout.** The engine is opened only when:
   - the build has the core (`CRYPTO_AVAILABLE`); and
   - the session is signed in and phone-verified; and
   - **`GET /mls/attestation_keys` returns at least one key.**

   Today the pilot answers `mls_unavailable`, so the `.so` is never even loaded. If the MLS
   registration then fails, the engine is dropped again and the app stays a v1.6 client.
3. **Trusted attestation keys** = `BuildConfig.MLS_PINNED_KEYS` + the keys served by
   `/mls/attestation_keys`.
   - The pinned keys are public JWK JSON, `;`-separated, set at build time with
     `-Prisime.mlsPinnedKeys`. They are empty until root provides the pilot key at rollout.
   - The served keys are trusted in addition, which covers rotation. Trusting them is a TOFU
     weakness until a pinned key is baked in, and it is accepted for the pilot.
4. **`UniffiMlsEngine`** (`src/crypto/kotlin`, compiled only with the toolchain, found by name via
   `MlsEngineFactory`):
   - The core's `KvStore` is `SealedKvStore` (namespace `mls`).
   - The conversation → generation map is in the same sealed table (namespace `app`), so it rolls
     back with the MLS state.
   - Every call joins the caller's Room transaction, or opens one with `runInTransaction`.
   - Application messages go through `process`, to get the authenticated sender.
5. **Membership executor:**
   - It runs after the named-committer delay.
   - "added" → claim that device's key package (with `X-Device-Id`) → `add_members` → commit with
     the Welcome. "removed" → `remove_members`.
   - Each runs only if still needed. On a `409` it catches up and re-checks once, and our commit
     merges only on `200`.
   - Key packages are topped up after every Welcome.
6. **Real-crypto JVM tests** live in `src/testCrypto/kotlin`. They are compiled only with the
   toolchain, use the host library via JNA, and skip without it. Each device gets its own bundled
   SQLite and the sealed table.

## Consequences
- Every nightly from now on carries the core. Roll-out is a server switch (the attestation key)
  plus pinning the key in the app.
- The laptop still builds without the core (no toolchain), but releases are built only on spark2.
