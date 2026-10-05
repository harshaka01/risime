# 012 — E2EE (0.3): Rust MLS core, the UniFFI binding to Android, and building on ARM64

Status: **accepted as the plan** for 0.3. The spike (`crypto/risime-mls`) is merged. The FFI crate,
the Android integration and the protocol v2 changes are still to do. Date: 2026-10-05.

## Context
0.3 makes RisiMe end-to-end encrypted with MLS (RFC 9420):
- a Rust core with OpenMLS, connected to Android via UniFFI;
- key packages;
- the server stores only ciphertext;
- the dev banner is removed.

The spike shows that OpenMLS 0.9 does what we need behind a small bytes-in/bytes-out API. Its
tests cover group creation, joining from a Welcome, decryption both ways, removed members locked
out, and tampering detected (`crypto/README.md`).

Three constraints shape the plan:
- The APK is built on **spark2 (aarch64)**. The laptop only `scp`s it and installs it
  (`scripts/install-apk`).
- Google ships the NDK host toolchain (clang/lld) for **x86_64 hosts only**.
- **No sudo.**

## Decisions

### 1. Toolchain: rustup, not mise
On spark2, Rust is installed with rustup into `~/.rustup` / `~/.cargo`:
- profile minimal, plus `rustfmt` and `clippy`;
- the `aarch64-linux-android` and `x86_64-linux-android` targets;
- stable 1.99.0 at the time of writing.

mise can install Rust, but it shells out to rustup anyway. rustup on its own is the canonical way to
add targets and components. Call `~/.cargo/bin/cargo` explicitly, because non-interactive SSH does
not load `.bashrc`.

### 2. Cryptography: OpenMLS 0.9, ciphersuite 0x0001
- **`MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`.** It is the RFC 9420 mandatory-to-implement
  suite, so every MLS implementation interoperates with it (including a future iOS or web client,
  and the Risi agent running server-side). It uses well-reviewed primitives with constant-time
  pure-Rust implementations (`openmls_rust_crypto`). AES-128-GCM is hardware-accelerated on every
  arm64-v8a phone.
  - ChaCha20-Poly1305 (0x0003) would help only on old devices without AES extensions, and our
    `minSdk` is 26.
  - Post-quantum suites (X-Wing) are in OpenMLS but are not yet final. Revisit them when the IETF
    PQ-MLS suites are published. A group's ciphersuite is fixed, so a later switch means
    re-creating the group.
- **Credentials:** `BasicCredential` whose identity is the **RisiMe user UUID plus device id**,
  never the phone number. The server's binding of identity to signature key is an open question
  (see below).
- **Ratchet tree in the Welcome** (`use_ratchet_tree_extension(true)`), so a joiner needs no
  extra round trip.
- **Pure Rust only.** The whole dependency tree has no `cc`/`cmake`/`bindgen` build script (checked
  with `cargo tree -e build`). The core crate must stay that way, because the ARM64 build plan
  (§5) depends on it. A crate that compiles C (for example `rusqlite` with bundled SQLite, which
  `openmls_sqlite_storage` uses) needs an Android C compiler. That would push us onto the fallback
  toolchain.

### 3. Crate layout
```
crypto/
  Cargo.toml            workspace, Cargo.lock committed
  risime-mls/           core: pure Rust, no FFI types, tested with `cargo test` on spark2
  risime-mls-ffi/       (next) cdylib + staticlib, `uniffi` proc-macros, thin wrapper over the core
```
- The core stays FFI-free so it can also be used from the server (Rustler NIF, for the Risi agent
  as an MLS member in 0.5) and from a desktop or iOS client later.
- The FFI crate owns:
  - the `Arc<Mutex<Client>>` object that Kotlin holds;
  - error mapping (`MlsError` → `#[derive(uniffi::Error)]`, which becomes a Kotlin sealed
    exception class);
  - the storage callback interface (§4).

### 4. UniFFI
- **Proc-macros, not UDL.** These are `#[uniffi::export]`, `#[derive(uniffi::Record / Enum /
  Error / Object)]` and `uniffi::setup_scaffolding!()`. UDL is the legacy path, and proc-macros
  keep the interface next to the code. The version is `uniffi` 0.32.x (latest stable, 2026-09),
  pinned exactly, because the bindgen and the runtime must match.
- **Kotlin bindings are generated in library mode at build time, not checked in.** The command is
  `uniffi-bindgen generate --library libuniffi_risime.so --language kotlin`. Library mode reads
  the metadata from the built `.so` without running it, so it works for an Android `.so` on an
  aarch64 host.
  - `uniffi-bindgen` is a small binary target in `risime-mls-ffi`, so its version always matches
    the runtime.
  - Output goes to `android/app/build/generated/uniffi/`, Kotlin package `lk.codegen.risime.crypto`.
  - Not checking it in avoids drift between the bindings and the `.so`. A Rust API change without a
    Kotlin update fails the Android compile instead of crashing at runtime.
- The Kotlin side needs **JNA** (`net.java.dev.jna:jna:<ver>@aar`). That is a new Android
  dependency, to be recorded in the android role's decision when it lands. Add a ProGuard/R8 keep
  rule for `com.sun.jna.**` and the generated package.
- All calls are blocking and CPU-bound (a few milliseconds). Kotlin calls them on
  `Dispatchers.Default` through one serialising wrapper per `Client`. Do not use UniFFI async.
- **Gradle wiring** belongs to the android role and is applied in the 0.3 android chunk:
  - an `Exec` task, `cargoBuildAndroid`, runs `scripts/build-rust-android` (root);
  - that script builds `arm64-v8a` and `x86_64` into `android/app/build/rustJniLibs/<abi>/`;
  - the task is wired before `merge<Variant>JniLibFolders`, then the bindgen task runs;
  - debug builds can use a single ABI (`-Prust.abis=arm64-v8a`).

### 5. Building the Android `.so` on aarch64: options
The spike checked this directly on spark2:
- `rustup target add aarch64-linux-android x86_64-linux-android` works on an aarch64 host.
- `cargo build --release --target aarch64-linux-android` (and `x86_64-linux-android`) **compiles
  the entire OpenMLS tree**, because it is pure Rust and needs no NDK.
- A probe `cdylib` gets **as far as the final link** and then fails with
  `cannot find -llog / -lunwind`. The default linker, the host `cc`, has no Bionic sysroot.
- So the only missing piece is a linker plus the NDK *sysroot*. No NDK is installed in
  `~/Android/Sdk/ndk` (no NDK, as instructed). The link was **not** verified past that point.

| Option | Verdict |
|---|---|
| **A. Laptop (x86_64) + cargo-ndk** (4.1.x) | Works out of the box with the official NDK. But it breaks the flow: the APK is built on spark2, so `.so` files would have to be built on the laptop and copied back, which puts Rust and the NDK on the laptop and creates two sources of truth. **Emergency fallback only.** |
| **B. spark2 native: Rust targets + official NDK sysroot + `rust-lld`** | The NDK's `sysroot/` (Bionic stubs `libc.so`, `liblog.so`, `crtbegin_so.o` …) and the clang runtime (`libunwind.a`, `libclang_rt.builtins-aarch64-android.a`) are **target files that don't depend on the host**. Only the NDK's *executables* are x86_64. `rust-lld` ships with rustup for aarch64 hosts and is a full lld. Rustup ships every executable, and the NDK zip supplies the data files, so we run no community binaries. The cost is a small linker shim, because rustc's Android target expects a clang driver. Works only while the core is pure Rust (§2). **Recommended.** |
| **C. Community arm64-host NDK** (for example `HomuHomu833/android-ndk-custom` r30 `aarch64-linux-gnu`, active, last push 2026-10-05; `lzhiyong/termux-ndk` targets Termux/bionic hosts) + cargo-ndk on spark2 | A full drop-in NDK (clang can also compile C dependencies). It has the same precedent as our `Commit451/android-arm-build-tools`. But here an **unofficial compiler/linker touches the crypto library**, which is a supply-chain risk higher than aapt2's. **Fallback if B fails**, pinned by version and SHA-256, and only for the link step. |
| **D. Docker + qemu x86_64 emulation** on spark2 | spark2 has no `qemu-x86_64` binfmt handler registered (`/proc/sys/fs/binfmt_misc` lists only python3.12). Registering one (`docker run --privileged tonistiigi/binfmt --install amd64`) is a host-wide kernel change made through root-equivalent docker access, so it is really a sudo action for Harsha. Emulated clang is also roughly 5–10× slower. **Rejected.** |
| **E. CI (GitHub Actions x86_64 runner)** + cargo-ndk + official NDK | Clean, reproducible and official. But it takes minutes per change, needs repo secrets or an artifact download step, and Harsha has not set up CI. **Later:** the release-candidate build and a cross-check of the spark2 `.so` (compare exported symbols and run the instrumented test). Not the dev loop. |

**Recommendation: B**, with C as the pinned fallback and E later for release verification.

**How B works** (the `scripts/build-rust-android` plan):
1. Get the official NDK (current stable **r30**, `ndk;30.0.16248370`) without sudo. Use
   `curl` + `unzip` of `https://dl.google.com/android/repository/android-ndk-r30-linux.zip`
   (check the SHA-1 from `repository2-3.xml`) into `~/Android/Sdk/ndk/30.0.16248370`.
   - `sdkmanager` can't be used on spark2: the current cmdline-tools `sdkmanager` delegates to
     an x86_64 `android` binary, which fails with `Exec format error`.
   - Only `toolchains/llvm/prebuilt/linux-x86_64/sysroot` and
     `.../lib/clang/<v>/lib/linux/<arch>/` are used. Their x86_64 executables never run.
2. Per target, `.cargo/config.toml` (generated by the script, not committed, because paths
   differ by machine):
   ```toml
   [target.aarch64-linux-android]
   linker = "<repo>/scripts/android-ld"           # shim, see below
   rustflags = ["-C", "link-arg=-zmax-page-size=16384"]
   ```
3. `scripts/android-ld` takes the clang-style arguments rustc passes (`-Wl,…`, `-l…`, `-shared`,
   `-nodefaultlibs`) and calls `rust-lld -flavor gnu` with:
   - `--sysroot`;
   - `-L sysroot/usr/lib/<triple>/<api>`;
   - `crtbegin_so.o` / `crtend_so.o` (Rust std's TLS destructors reference `__dso_handle`);
   - `libunwind.a`;
   - `--hash-style=both`;
   - `-z max-page-size=16384` (Android 15+ needs 16 KB alignment).
4. Verify with `llvm-readelf` from rustup's `llvm-tools` component: the expected `NEEDED`
   entries, 16 KB `LOAD` alignment, and exported `uniffi_*` symbols. Then do a real run: the
   instrumented test on the laptop emulator (x86_64) and the USB phone (arm64).
5. Time-box B to **half a day**. If the shim gets fragile (unusual rustc arguments, LTO plugin
   flags), switch to C. With C, cargo-ndk drives the community clang, and the core stays the same.

This fits the "build on spark2, install from the laptop" flow unchanged: `./gradlew assembleDebug`
on spark2 builds the `.so`, the bindings and the APK, and `scripts/install-apk` on the laptop stays
as it is.

### 6. Persisting keys and group state on Android
MLS state is security-critical and changes on **every** message: the sender ratchet advances, and
re-using a ratchet secret after a crash breaks forward secrecy, or desynchronises the group.
- **The storage engine is a custom OpenMLS `StorageProvider` in the FFI crate.** It writes opaque
  key/value blobs through a UniFFI **callback interface** (`trait KvStore { get, put, delete,
  begin, commit }`), implemented in Kotlin on top of **Room**: a separate database file,
  `mls.db`, with one table `(namespace, key) → value`.
  - This keeps the Rust side pure Rust (§2): no SQLite C build.
  - It reuses Room, which the app already uses, and puts transactions under Kotlin's control.
  - OpenMLS writes many small entries, and the callback overhead is microseconds, against
    milliseconds for crypto.
- **Encryption at rest:** each value is sealed with AES-256-GCM in the Rust layer. The key is a
  random 32-byte **database key** that is wrapped by a non-exportable **Android Keystore** key
  (`AES/GCM`, StrongBox when available, `setUserAuthenticationRequired(false)`, because messages
  must decrypt in the background). The wrapped database key lives in DataStore.
  - Kotlin unwraps it at startup and hands it to `Client::open(store, db_key)`. It is zeroised on
  drop in Rust.
  - Why not SQLCipher: it is C (build issue §2), and it encrypts the whole Room file while we
    only need the MLS table. The message history database encryption is a separate decision.
- **Atomicity:** each `process`/`encrypt` call runs inside one `KvStore` transaction, so ratchet
  state and the decrypted message are committed together. The app then writes the plaintext to
  its message DB and acks. The rule is never to ack a server event before the MLS transaction
  commits.
- **Backups:** set `android:allowBackup` / data-extraction rules to exclude `mls.db` and the
  DataStore key file. Restoring MLS state on another device is unsafe; device linking is a
  separate 0.3 item.
- The signature private key stays in the Rust store (OpenMLS signs in software). Hardware-backed
  Ed25519 signing is not available on most Keystores, so we accept this.

### 7. Server stays ciphertext-only: sketch of protocol v2
These are proposals only. **PROTOCOL.md is not changed here**; the real text goes through
`contract/proposals/` when 0.3 starts.
- **Devices.** `POST /api/v2/devices` `{device_id, signature_key, credential}` registers a
  device's MLS identity. For each user the server stores device rows (Postgres).
- **Key packages.**
  - `POST /api/v2/key_packages` `{device_id, key_packages: [b64…]}` uploads a batch (for example
    100). The server checks only the size and the count, plus a light TLS parse to extract the
    `KeyPackageRef`; it never trusts content.
  - `POST /api/v2/key_packages/claim` `{user_id}` → `{device_id, key_package}`, one per device of
    that user. It is **consumed atomically** (Postgres `DELETE … RETURNING`), because a key
    package is single-use.
  - Keep one *last-resort* key package per device so that claims never fail. Clients top up when
    `GET /key_packages/count` falls below 20.
- **Group operations on the existing inbox fan-out.** New `Event.kind`s carry opaque bytes:
  - `mls_welcome` `{group_id, from, welcome: b64}` → only to the added device;
  - `mls_commit` `{group_id, epoch, from, commit: b64}` → to all current members' devices;
  - `mls_message` `{group_id, epoch, from, ciphertext: b64, client_msg_id}` → replaces `body`
    for encrypted conversations.

  `msg:ack` and statuses stay as they are, since status metadata stays cleartext for now
  (see the risks).
- **Commit ordering** is the one thing the server must arbitrate. Commits go through `mls:commit`
  `{group_id, epoch, commit}`.
  - The server accepts the commit only if `epoch == current_epoch(group_id)`. That is a
    compare-and-set on a Postgres `mls_groups(group_id, epoch)` row, so there is one winner per
    epoch.
  - The loser gets `{"reason": "stale_epoch"}`, applies the winner's commit and retries.
  - The client merges its own pending commit **only after** the ok reply. This is the spike
    simplification called out in `crypto/README.md`.
- **Storage.** The Cassandra `Store` keeps the same shape with `body` → `ciphertext blob`, plus
  `epoch` and `content_type`. Under the "one table per query" rule, a tiny Postgres table
  `mls_groups` (epoch CAS and membership as a list of device ids, for fan-out) is added. The
  server sees *who talks to whom and when*, never content.
- **The DM is a two-member MLS group.** `group_id` = a server-issued UUID, mapped from
  `conversation_id`.
- **Migration.** v1 plaintext DMs stay readable as history. A new conversation (or the first
  message after the upgrade) creates the MLS group. The dev banner is removed only when every
  send path is MLS.

## Risks
- **The authentication service is the server.** With BasicCredentials, a malicious server could
  substitute key packages (man-in-the-middle at add time). Mitigations:
  - safety numbers / QR verification of signature keys in the UI;
  - later, key transparency.

  This is the same trust model as most messengers before verification.
- **Metadata stays visible** (sender, recipient, timing, sizes, statuses, typing and presence).
  This is acceptable for 0.3. Padding is already supported by OpenMLS (`padding_size`).
- **State loss = group desync.** A crash between ratchet advance and persist, or a restored
  backup, breaks decryption. This is mitigated by the transactional `KvStore`, by excluding the
  state from backups, and by a "re-join via new Welcome" recovery flow (the server can ask a
  member to re-add a device).
- **OpenMLS API churn.** 0.6 → 0.9 changed the processing variants (for example
  `OwnPendingCommit`). Pin exact versions, keep the wrapper thin, and let the tests catch breakage
  on upgrade.
- **Toolchain B is unverified past "compiles; link needs the sysroot".** It needs the NDK zip
  (≈700 MB, user-level, no sudo), and the fallback is C.
- **Out-of-order commits vs messages** across the inbox: the client must process events in
  `event_id` order per group, and keep past-epoch secrets briefly
  (`max_past_epochs`, for example 3) for late application messages.

## Open questions for Harsha
1. **NDK download (≈700 MB to `~/Android/Sdk/ndk/`):** OK to unpack the official
   `android-ndk-r30-linux.zip` on spark2 for option B? There is no sudo involved.
   (`sdkmanager` itself no longer runs on aarch64; see §5.)
2. If B fails: is the **community arm64 NDK** (option C) acceptable for linking the crypto `.so`,
   given we already trust `Commit451/android-arm-build-tools`? Or do you prefer building
   releases on CI (E) and using C only for dev builds?
3. **Credential identity:** user UUID + device id (proposed), not the phone number. Is a server
   **attestation** (the server signs `{user_id, device_id, signature_key}`) wanted in 0.3, or are
   safety-number QR checks enough for the pilot?
4. **Multi-device:** is each device its own MLS leaf (proposed; simplest, and it matches MLS)?
   The UI would show "Harsha (phone)" and "Harsha (tablet)" as one member.
5. **History for newly added members:** none by MLS design. For "Add Risi" (0.5), consented
   history share needs an explicit re-encrypt-and-send flow. Confirm that is the expectation.
6. **Message-history-at-rest encryption** on the device (beyond `mls.db`): in scope for 0.3,
   or later?

## Consequences
- `crypto/` is a new root-owned area with its own gate:
  `cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test`.
- Next chunks for 0.3:
  1. `risime-mls-ffi` with UniFFI plus the `KvStore` storage provider (root/crypto);
  2. `scripts/build-rust-android` with option B, verified on the emulator and the phone (root);
  3. a contract proposal for v2 (root);
  4. server key package, `mls_groups` and the new event kinds (server);
  5. Gradle wiring, Kotlin `CryptoEngine` and `mls.db` (android).
