# 031 — Android: the native MLS core in debug builds (0.3 groundwork)

## Context
Decision 012 §5a builds `crypto/risime-mls-ffi` (`libuniffi_risime.so`, UniFFI 0.32.2) natively
on spark2. Before any E2EE product work, we want proof that it loads and runs inside the app on
real devices, without changing what release users get.

## Decision
1. **Gradle task `cargoBuildAndroid`** (`app/build.gradle.kts`):
   - It runs the unchanged `../scripts/build-rust-android --abis arm64-v8a,x86_64 --out
     build/rustJniLibs --kotlin-out build/generated/uniffi`.
   - It is registered only when `~/.cargo/bin/cargo` is executable, an NDK exists
     (`$ANDROID_NDK_HOME`, or the newest `$ANDROID_HOME/ndk/*` with `toolchains/`), and the script
     and crate are present. Otherwise it logs a skip and the app builds without crypto (the
     laptop, a CI without Rust). `-Prisime.crypto=false` forces the skip.
   - Inputs are `crypto/**` (minus `target/`), `scripts/build-rust-android` and
     `scripts/android-ld`, so the task is up to date unless Rust code changes.
2. **Debug variant only.** Through `androidComponents.onVariants(withBuildType("debug"))`, the
   task's outputs become generated `jniLibs` and Kotlin source directories, plus
   `src/debugCrypto/kotlin` (the bridge). JNA 5.19.1 (`@aar`) is `debugImplementation`.
3. **Rust release profile by default**, even in the debug app:
   - The cargo debug profile produces unstripped 59 MB (arm64) / 61 MB (x86_64) libraries.
     They're stored uncompressed in the APK, so that would add about 120 MB.
   - The release profile gives 4.5 / 4.7 MB.
   - `-Prisime.rustProfile=debug` builds the debug profile when a debuggable core is needed.
   - This deviates from the brief's `--debug`.
4. **No compile-time reference from main.**
   - `CryptoProbe` (interface) and `CryptoProbes` live in main.
   - `UniffiCryptoProbe` (debugCrypto) wraps `lk.codegen.risime.crypto.mlsInfo()` /
     `selfTest()`, and is found by class name only when `BuildConfig.CRYPTO_AVAILABLE` is true.
     That flag is true only in debug builds that have the toolchain.
   - Release has neither the classes, the `.so`, nor JNA.
   - Link errors (`UnsatisfiedLinkError`, for example on a 32-bit device, which has no
     `libuniffi_risime.so`) are reported as text and never crash the app.
5. **Packaging:** JNA's obsolete `armeabi`, `mips` and `mips64` libraries are excluded (minSdk
   26 can't use them). 32-bit `armeabi-v7a`/`x86` devices get JNA but no MLS core until we
   decide on 32-bit support.
6. **Guard:** `CryptoPackagingTest` reads both variants' merged native libs and the release
   `BuildConfig`:
   - release has no `libuniffi_risime.so`, no `libjnidispatch.so`, and `CRYPTO_AVAILABLE = false`;
   - debug has both libraries for arm64-v8a and x86_64 when the toolchain is present.

## Consequences
- **Clean APK sizes:**

  | Build | Before | After |
  |---|---|---|
  | Debug | 17,408,916 | 27,659,146 |
  | Release | 12,733,648 | 12,733,648 (unchanged) |

- A spark2 debug build runs cargo the first time, about 15 s with a warm `crypto/target`.
- The real on-device run is the device check in `docs/status/android.md`.
